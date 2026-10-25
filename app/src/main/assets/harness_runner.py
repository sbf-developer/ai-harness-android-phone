"""Pocket Harness's bounded, non-interactive Termux worker. No server or root required."""
import base64
import fnmatch
import hashlib
import json
import os
import pathlib
import queue
import re
import shutil
import signal
import subprocess
import sys
import tempfile
import threading
import time
import urllib.parse
import uuid

MAX_FILE = 80 * 1024
SECRET = re.compile(rb"gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,}|sk-[A-Za-z0-9_-]{20,}|-----BEGIN (?:[A-Z ]+)?PRIVATE KEY-----")
STATE = pathlib.Path(tempfile.gettempdir()) / "pocket-harness-jobs"


class Refused(Exception):
    pass


def identifier(value):
    if not re.fullmatch(r"[a-f0-9-]{36}", str(value)):
        raise Refused("Invalid command identifier")
    return str(value)


def write_state(cid, value):
    STATE.mkdir(mode=0o700, parents=True, exist_ok=True)
    target = STATE / (identifier(cid) + ".json")
    fd, temporary = tempfile.mkstemp(dir=STATE)
    try:
        with os.fdopen(fd, "w") as out:
            json.dump(value, out)
            out.flush()
            os.fsync(out.fileno())
        os.replace(temporary, target)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def read_state(cid):
    try:
        with open(STATE / (identifier(cid) + ".json")) as source:
            return json.load(source)
    except FileNotFoundError:
        return None


def tick(pid):
    try:
        # The process name can contain spaces or parentheses.
        data = pathlib.Path("/proc", str(pid), "stat").read_text()
        return data[data.rfind(")") + 2:].split()[19]
    except (OSError, IndexError):
        return None


def kill_group(meta):
    pid = meta.get("pid")
    if not pid or pid < 2:
        return False
    if os.name == "posix":
        if tick(pid) != meta.get("start_tick") or not meta.get("start_tick"):
            return False
        try:
            os.killpg(pid, signal.SIGTERM)
            time.sleep(0.15)
            if tick(pid) == meta["start_tick"]:
                os.killpg(pid, signal.SIGKILL)
            return True
        except ProcessLookupError:
            return True
        except OSError:
            return False
    return False


def cancelled(cid, nonce):
    try:
        return (STATE / (identifier(cid) + ".cancel")).read_text() == nonce
    except FileNotFoundError:
        return False


def cancel_job(payload):
    cid = identifier(payload["target"])
    nonce = payload["target_nonce"]
    STATE.mkdir(mode=0o700, parents=True, exist_ok=True)
    meta = read_state(cid)
    if meta and meta.get("nonce") != nonce:
        raise Refused("Command identity mismatch")
    # A marker also covers cancellation arriving before Popen has written its PID.
    (STATE / (cid + ".cancel")).write_text(nonce)
    attempted = kill_group(meta) if meta and meta.get("state") == "running" else False
    return {"stdout": "Cancellation requested for the tracked process group. Escaped background processes are not controlled.",
            "exit_code": 0, "data": {"target": cid, "signal_sent": attempted}}


def job_status(payload):
    meta = read_state(payload["target"])
    if not meta or meta.get("nonce") != payload["target_nonce"]:
        return {"exit_code": 0, "data": {"state": "unknown", "output_available": False}}
    alive = bool(meta.get("pid") and tick(meta["pid"]) == meta.get("start_tick") and meta.get("start_tick"))
    return {"exit_code": 0, "data": {"state": meta.get("state"), "running": alive,
            "exit_code": meta.get("exit_code"), "output_available": False},
            "stdout": "Only execution metadata is retained in Termux. Lost stdout cannot be recovered. Inspect actual files/Git state; do not replay an uncertain write."}


class Capture:
    def __init__(self, limit):
        self.limit = limit
        self.total = 0
        self.head = b""
        self.tail = b""

    def add(self, data):
        self.total += len(data)
        half = self.limit // 2
        if len(self.head) < half:
            count = min(half - len(self.head), len(data))
            self.head += data[:count]
            data = data[count:]
        self.tail = (self.tail + data)[-half:]

    def text(self):
        marker = ("\n...[output truncated; %d bytes produced]...\n" % self.total).encode() if self.total > self.limit else b""
        return (self.head + marker + self.tail).decode("utf-8", errors="replace")


def environment():
    env = dict(os.environ)
    prefix = "/data/data/com.termux/files/usr/bin"
    env.update(GIT_TERMINAL_PROMPT="0", GCM_INTERACTIVE="Never",
               GIT_ASKPASS=prefix + "/false", SSH_ASKPASS=prefix + "/false",
               SSH_ASKPASS_REQUIRE="never", GIT_PAGER="cat", PAGER="cat",
               GIT_SSH_COMMAND="ssh -o BatchMode=yes -o ConnectTimeout=15 -o StrictHostKeyChecking=yes",
               GIT_EDITOR=prefix + "/false", GIT_SEQUENCE_EDITOR=prefix + "/false")
    return env


def run_process(argv, cwd, timeout, payload):
    cid, nonce = identifier(payload["id"]), payload["nonce"]
    if cancelled(cid, nonce):
        return {"exit_code": -15, "stderr": "Cancelled before process start", "cancelled": True}
    process = subprocess.Popen(argv, cwd=cwd, env=environment(), stdin=subprocess.DEVNULL,
                               stdout=subprocess.PIPE, stderr=subprocess.PIPE, bufsize=0,
                               start_new_session=(os.name == "posix"))
    meta = {"nonce": nonce, "pid": process.pid, "start_tick": tick(process.pid),
            "state": "running", "started": time.time()}
    write_state(cid, meta)
    if cancelled(cid, nonce):
        kill_group(meta)
        if os.name != "posix":
            process.kill()
    chunks = queue.Queue(maxsize=64)

    def reader(which, pipe):
        try:
            while True:
                data = os.read(pipe.fileno(), 4096)
                if not data:
                    break
                chunks.put((which, data))
        except OSError:
            pass
        finally:
            chunks.put((which, None))

    for which, pipe in enumerate((process.stdout, process.stderr)):
        threading.Thread(target=reader, args=(which, pipe), daemon=True).start()
    captures = [Capture(12000), Capture(8000)]
    deadline = time.monotonic() + timeout
    timed_out = False
    closed = 0
    drain_deadline = None
    while closed < 2 or process.poll() is None:
        now = time.monotonic()
        if not timed_out and now >= deadline:
            timed_out = True
            kill_group(meta)
            if process.poll() is None:
                process.kill()
            drain_deadline = now + 2
        if process.poll() is not None and drain_deadline is None:
            drain_deadline = now + 2
        if drain_deadline and now > drain_deadline:
            break
        try:
            which, data = chunks.get(timeout=0.05)
            if data is None:
                closed += 1
            else:
                captures[which].add(data)
        except queue.Empty:
            pass
    if process.poll() is None:
        kill_group(meta)
        process.kill()
    code = process.wait(timeout=3)
    process.stdout.close()
    process.stderr.close()
    was_cancelled = cancelled(cid, nonce)
    meta.update(state="cancelled" if was_cancelled else "finished", exit_code=code,
                timed_out=timed_out, finished=time.time())
    write_state(cid, meta)  # No raw command, stdout, stderr or credential is logged to Termux disk.
    return {"stdout": captures[0].text(), "stderr": captures[1].text(), "exit_code": code, "cwd": os.path.realpath(cwd),
            "timed_out": timed_out, "cancelled": was_cancelled,
            "truncated": any(c.total > c.limit for c in captures),
            "stdout_bytes": captures[0].total, "stderr_bytes": captures[1].total}


def workspace_root(value):
    root = os.path.realpath(value)
    if not os.path.isdir(root) or root == os.path.abspath(os.sep):
        raise Refused("Workspace does not exist or is the filesystem root. Create ~/projects in Termux, or select an existing project directory.")
    return root


def sensitive(relative):
    parts = pathlib.PurePosixPath(relative.replace("\\", "/")).parts
    for part in parts:
        lower = part.lower()
        if lower in (".git", ".ssh", ".gnupg", ".aws", ".netrc", ".git-credentials", "credentials", "id_rsa", "id_ed25519"):
            return True
        if lower.startswith(".env") and lower not in (".env.example", ".env.sample", ".env.template"):
            return True
        if any(fnmatch.fnmatch(lower, pattern) for pattern in ("*.pem", "*.key", "*.keystore", "*.jks", "*.p12")):
            return True
    return False


def project_path(root, relative, allow_root=False):
    if not isinstance(relative, str) or len(relative) > 800 or "\x00" in relative or os.path.isabs(relative):
        raise Refused("Use a relative project path")
    if sensitive(relative):
        raise Refused("Credential stores and Git metadata are excluded from structured file tools")
    target = os.path.realpath(os.path.join(root, relative))
    try:
        inside = os.path.commonpath([root, target]) == root
    except ValueError:
        inside = False
    if not inside or (target == root and not allow_root):
        raise Refused("Path leaves the selected workspace")
    cursor = root
    for part in pathlib.PurePath(relative).parts:
        cursor = os.path.join(cursor, part)
        if os.path.islink(cursor):
            raise Refused("Symlinks are not editable/readable through structured file tools")
    if sensitive(os.path.relpath(target, root)):
        raise Refused("Resolved path enters credential or Git metadata")
    return target


def read_file(root, relative, offset=None):
    path = project_path(root, relative)
    if not os.path.isfile(path) or os.path.getsize(path) > MAX_FILE:
        raise Refused("Select a regular UTF-8 file up to 80 KiB")
    with open(path, "rb") as source:
        data = source.read(MAX_FILE + 1)
    if len(data) > MAX_FILE:
        raise Refused("File grew beyond the 80 KiB limit while being read")
    if b"\x00" in data:
        raise Refused("Binary files are not supported")
    if SECRET.search(data):
        raise Refused("Recognizable credential material was found; this file is not sent to the model")
    text = data.decode("utf-8")
    if any(ord(c) < 32 and c not in "\t\n\r" for c in text):
        raise Refused("Non-text control characters are not supported")
    start = max(0, int(offset or 0))
    shown = text if offset is None else text[start:start + 10000]
    if offset is not None:
        while len(json.dumps(shown, ensure_ascii=False).encode("utf-8")) > 30000:
            shown = shown[:max(1, len(shown) * 3 // 4)]
    return {"path": relative, "content": shown, "sha256": hashlib.sha256(data).hexdigest(), "bytes": len(data),
            "offset": start, "next_offset": start + len(shown), "complete": start == 0 and len(shown) == len(text),
            "has_more": start + len(shown) < len(text)}


def write_file(root, args):
    path = project_path(root, args["path"])
    content = args["content"].encode("utf-8")
    if len(content) > MAX_FILE or b"\x00" in content or SECRET.search(content):
        raise Refused("Replacement is too large, binary, or contains recognizable credentials")
    expected = args["expected_sha"]
    existing = os.path.exists(path)
    if existing:
        if not os.path.isfile(path) or not expected or os.path.getsize(path) > MAX_FILE or hashlib.sha256(pathlib.Path(path).read_bytes()).hexdigest() != expected:
            raise Refused("File changed since it was read. Read it again and preserve the user's intervening changes.")
    elif expected:
        raise Refused("The file was removed since it was read")
    parent = os.path.dirname(path)
    os.makedirs(parent, exist_ok=True)
    # Revalidate after directory creation; this is a scope check, not an OS sandbox.
    if project_path(root, args["path"]) != path:
        raise Refused("Path changed during review")
    mode = os.stat(path).st_mode & 0o777 if existing else 0o600
    fd, temporary = tempfile.mkstemp(dir=parent)
    try:
        with os.fdopen(fd, "wb") as target:
            target.write(content)
            target.flush()
            os.fsync(target.fileno())
        os.chmod(temporary, mode)
        if existing and hashlib.sha256(pathlib.Path(path).read_bytes()).hexdigest() != expected:
            raise Refused("File changed during the write. Nothing was replaced.")
        if not existing and os.path.exists(path):
            raise Refused("A file appeared at that path. Nothing was replaced.")
        os.replace(temporary, path)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)
    return {"path": args["path"], "sha256": hashlib.sha256(content).hexdigest(), "bytes": len(content)}


def git_base():
    return ["git", "-c", "core.hooksPath=/dev/null", "-c", "core.fsmonitor=false",
            "-c", "core.pager=cat", "-c", "protocol.ext.allow=never", "-c", "protocol.file.allow=never",
            "-c", "push.followTags=false", "-c", "push.recurseSubmodules=no", "--no-pager"]


def git_quick(repo, args):
    result = subprocess.run(git_base() + args, cwd=repo, env=environment(), stdin=subprocess.DEVNULL,
                            stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=15)
    if result.returncode != 0:
        raise Refused("Git preflight failed. Check the repository, identity and Termux Git setup.")
    return result.stdout.decode("utf-8", errors="replace").strip()


def remote_url(repo, remote, push):
    if not re.fullmatch(r"[A-Za-z0-9_.-]{1,80}", remote) or remote.startswith("-"):
        raise Refused("Invalid remote name")
    urls = git_quick(repo, ["remote", "get-url"] + (["--push"] if push else []) + ["--all", remote]).splitlines()
    if len(urls) != 1:
        raise Refused("A single explicit Git remote URL is required")
    validate_url(urls[0])
    return urls[0]


def validate_url(url):
    if re.fullmatch(r"git@[A-Za-z0-9.-]+:[A-Za-z0-9_./-]+", url):
        return
    parsed = urllib.parse.urlsplit(url)
    if parsed.scheme not in ("https", "ssh") or not parsed.hostname or parsed.password or (parsed.username and not (parsed.scheme == "ssh" and parsed.username == "git")):
        raise Refused("Use an HTTPS or git@host SSH remote without embedded credentials")
    if any(c in url for c in "\r\n\x00") or parsed.query or parsed.fragment:
        raise Refused("Remote URL contains unsupported characters")


def git_operation(root, args, payload):
    operation = args["operation"]
    relative = args.get("repo_path", ".")
    repo = project_path(root, relative, allow_root=True)
    if operation == "clone":
        if os.path.exists(repo):
            raise Refused("Clone destination already exists; choose a new relative directory")
        validate_url(args["url"])
        os.makedirs(os.path.dirname(repo), exist_ok=True)
        return run_process(git_base() + ["clone", "--", args["url"], repo], root, 120, payload)
    if not os.path.isdir(repo):
        raise Refused("Repository directory does not exist")
    if operation == "init":
        existing = subprocess.run(["git", "-C", repo, "rev-parse", "--show-toplevel"], env=environment(),
                                  stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, timeout=10)
        if existing.returncode == 0:
            raise Refused("Directory already belongs to a repository. Select its exact root.")
        return run_process(git_base() + ["init", "--initial-branch=main"], repo, 20, payload)
    top = git_quick(repo, ["rev-parse", "--show-toplevel"])
    if os.path.realpath(top) != repo:
        raise Refused("This directory belongs to a parent repository. Select the exact repository root.")
    if operation == "status":
        return run_process(git_base() + ["status", "--short", "--branch"], repo, 20, payload)
    if operation == "diff":
        paths = args.get("paths", [])
        has_head = subprocess.run(git_base() + ["rev-parse", "--verify", "HEAD"], cwd=repo, env=environment(),
                                  stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=10).returncode == 0
        base = ["HEAD"] if has_head else ["--cached"]
        if not paths:
            paths = git_quick(repo, ["diff", "--name-only", "-z"] + base + ["--"]).split("\x00")
            paths = [path for path in paths if path and not sensitive(path)]
        if not paths:
            return {"exit_code": 0, "stdout": "No eligible tracked text changes. Untracked files appear in git status; read them with read_file."}
        for path in paths:
            read_file(repo, path)  # Reject credential material before it enters a diff.
        return run_process(git_base() + ["diff", "--no-ext-diff", "--no-textconv"] + base + ["--"] + paths, repo, 20, payload)
    if operation in ("remote", "fetch", "push"):
        remote = args.get("remote", "origin")
        url = remote_url(repo, remote, operation == "push" or (operation == "remote" and not args.get("for_fetch", False)))
        try:
            head = git_quick(repo, ["rev-parse", "--verify", "HEAD"])
        except Refused:
            if operation == "push":
                raise
            head = "unborn:" + git_quick(repo, ["symbolic-ref", "--quiet", "HEAD"])
        if operation == "remote":
            return {"exit_code": 0, "data": {"url": url, "head": head, "repo_path": relative}, "stdout": url}
        if args.get("expected_url") != url or args.get("expected_head") != head:
            raise Refused("Git remote or HEAD changed after approval. Inspect again; no network write was attempted.")
        if operation == "fetch":
            return run_process(git_base() + ["fetch", "--no-tags", "--no-recurse-submodules", "--", url,
                               "+refs/heads/*:refs/remotes/" + remote + "/*"], repo, 120, payload)
        branch = args["branch"]
        if not re.fullmatch(r"[A-Za-z0-9_./-]{1,160}", branch) or branch.startswith("-"):
            raise Refused("Invalid destination branch")
        git_quick(repo, ["check-ref-format", "--branch", branch])
        return run_process(git_base() + ["push", "--porcelain", "--no-verify", "--", url, "HEAD:refs/heads/" + branch], repo, 120, payload)
    if operation == "commit":
        paths = args.get("paths", [])
        hashes = args.get("expected_hashes", {})
        if not paths or len(paths) > 30 or len(set(paths)) != len(paths) or not args.get("message", "").strip() or len(args["message"]) > 500:
            raise Refused("Commit requires a message and up to 30 explicit files")
        for path in paths:
            info = read_file(repo, path)
            if hashes.get(path) != info["sha256"]:
                raise Refused("A file changed since credential/conflict preflight. Inspect again.")
        staged = git_quick(repo, ["diff", "--cached", "--name-only", "-z"]).split("\x00")
        if any(path and path not in paths for path in staged):
            raise Refused("Other files are already staged. Review them in Termux before this commit.")
        added = run_process(git_base() + ["add", "--"] + paths, repo, 30, payload)
        if added["exit_code"] != 0:
            return added
        return run_process(git_base() + ["-c", "commit.gpgsign=false", "commit", "--only", "-m", args["message"], "--"] + paths, repo, 60, payload)
    raise Refused("Unsupported Git operation")


def execute(payload):
    operation = payload["op"]
    if operation == "cancel":
        return cancel_job(payload)
    if operation == "job_status":
        return job_status(payload)
    if operation == "diagnostics":
        path = os.path.realpath(payload["workspace"])
        tools = {name: bool(shutil.which(name)) for name in ("python", "git", "rg", "ssh")}
        return {"exit_code": 0, "cwd": path, "data": {"workspace_exists": os.path.isdir(path), "tools": tools, "python": sys.version.split()[0]},
                "stdout": "Workspace: " + path}
    root = workspace_root(payload["workspace"])
    args = payload.get("args", {})
    if operation == "list_files":
        path = project_path(root, args.get("path", "."), allow_root=True)
        entries = []
        for item in sorted(os.scandir(path), key=lambda e: (not e.is_dir(follow_symlinks=False), e.name.lower())):
            relative = os.path.relpath(item.path, root).replace(os.sep, "/")
            if item.is_symlink() or sensitive(relative):
                continue
            entries.append({"name": item.name, "path": relative, "directory": item.is_dir(follow_symlinks=False),
                            "bytes": item.stat(follow_symlinks=False).st_size})
            if len(entries) >= 150:
                break
        return {"exit_code": 0, "cwd": root, "data": {"entries": entries, "limited": len(entries) == 150}}
    if operation == "read_file":
        return {"exit_code": 0, "cwd": root, "data": read_file(root, args["path"], args.get("offset", 0))}
    if operation == "write_file":
        return {"exit_code": 0, "cwd": root, "data": write_file(root, args)}
    if operation == "edit_file":
        original = read_file(root, args["path"])
        old, new = args["old_text"], args["new_text"]
        if not old or original["sha256"] != args["expected_sha"] or original["content"].count(old) != 1:
            raise Refused("Edit conflict: read the latest file and use unique exact old_text")
        replacement = dict(path=args["path"], content=original["content"].replace(old, new, 1), expected_sha=args["expected_sha"])
        return {"exit_code": 0, "cwd": root, "data": write_file(root, replacement)}
    if operation == "search_files":
        path = project_path(root, args.get("path", "."), allow_root=True)
        if not 1 <= len(args.get("query", "")) <= 300:
            raise Refused("Search text must have 1–300 characters")
        cmd = ["rg", "--fixed-strings", "--line-number", "--no-heading", "--max-count", "30", "--max-filesize", "80K",
               "--glob", "!.git/**", "--glob", "!.env*", "--glob", "!*.pem", "--glob", "!*.key",
               "--glob", "!*.keystore", "--glob", "!*.jks", "--glob", "!*.p12", "--glob", "!.ssh/**",
               "--glob", "!credentials", "--glob", "!id_rsa", "--glob", "!id_ed25519",
               "--", args["query"], path]
        return run_process(cmd, root, 20, payload)
    if operation == "terminal":
        command = args.get("command", "")
        if not 1 <= len(command) <= 16000 or "\x00" in command:
            raise Refused("Terminal command must have 1–16000 characters")
        timeout = max(1, min(300, int(args.get("timeout_seconds", 60))))
        shell = "/data/data/com.termux/files/usr/bin/bash"
        if os.name != "posix":
            shell = shutil.which("bash") or "bash"
        return run_process([shell, "--noprofile", "--norc", "-c", command], root, timeout, payload)
    if operation == "git":
        result = git_operation(root, args, payload)
        result["cwd"] = project_path(root, args.get("repo_path", "."), allow_root=True)
        return result
    raise Refused("Unknown operation")


def main():
    os.umask(0o077)
    payload = json.loads(base64.b64decode(sys.argv[1]).decode("utf-8"))
    try:
        result = execute(payload)
    except Exception as error:
        result = {"exit_code": -1, "error": str(error)[:1000], "stderr": "", "stdout": ""}
    result.setdefault("cwd", payload.get("workspace", ""))
    result["command_id"] = payload.get("id", "")
    serialized = json.dumps(result, ensure_ascii=False)
    if len(serialized.encode("utf-8")) > 40000:
        result.update(stdout=result.get("stdout", "")[-2000:], stderr=result.get("stderr", "")[-1000:], truncated=True)
        if "data" in result:
            result["data"] = {}
            result["error"] = "Structured result exceeded the transport limit; choose a smaller file or narrower listing."
        serialized = json.dumps(result, ensure_ascii=False)
    sys.stdout.buffer.write(serialized.encode("utf-8") + b"\n")


if __name__ == "__main__":
    main()
