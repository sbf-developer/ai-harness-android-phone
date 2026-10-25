"""Portable worker tests. Real Termux transport/process-group checks require an Android device."""
import importlib.util
import base64
import json
import pathlib
import subprocess
import sys
import tempfile
import unittest
import uuid

SOURCE = pathlib.Path(__file__).resolve().parents[1] / "app/src/main/assets/harness_runner.py"
spec = importlib.util.spec_from_file_location("harness_runner", SOURCE)
runner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runner)


class RunnerTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = pathlib.Path(self.temporary.name).resolve()
        self.old_state = runner.STATE
        runner.STATE = self.root / "jobs"
        self.workspace = self.root / "workspace"
        self.workspace.mkdir()
        self.payload = dict(id=str(uuid.uuid4()), nonce=str(uuid.uuid4()), workspace=str(self.workspace))

    def tearDown(self):
        runner.STATE = self.old_state
        self.temporary.cleanup()

    def run_op(self, tool, **args):
        return runner.execute(dict(self.payload, op=tool, args=args))

    def git(self, *args):
        return subprocess.run(["git", "-C", str(self.workspace), *args], check=True,
                              stdout=subprocess.PIPE, stderr=subprocess.PIPE).stdout.decode().strip()

    def init_git(self):
        self.git("init", "--initial-branch=main")
        self.git("config", "user.name", "Local Test")
        self.git("config", "user.email", "test@example.invalid")

    def test_create_read_edit_and_preserve_exact_bytes(self):
        text = "hello\r\nworld 🌍\r\n"
        result = self.run_op("write_file", path="src/a.txt", content=text, expected_sha="")
        read = self.run_op("read_file", path="src/a.txt")["data"]
        self.assertEqual(text, read["content"])
        self.assertTrue(read["complete"])
        self.run_op("edit_file", path="src/a.txt", old_text="world", new_text="phone", expected_sha=result["data"]["sha256"])
        self.assertEqual(text.replace("world", "phone").encode(), (self.workspace / "src/a.txt").read_bytes())

    def test_conflict_never_replaces_intervening_change(self):
        created = self.run_op("write_file", path="a", content="original", expected_sha="")
        (self.workspace / "a").write_text("user change")
        with self.assertRaises(runner.Refused):
            self.run_op("write_file", path="a", content="model", expected_sha=created["data"]["sha256"])
        self.assertEqual("user change", (self.workspace / "a").read_text())
        with self.assertRaises(runner.Refused):
            self.run_op("write_file", path="a", content="replace", expected_sha="")

    def test_file_slices_use_full_hash_and_unique_edit(self):
        (self.workspace / "large").write_text("a" * 12000 + "unique" + "b" * 12000)
        first = self.run_op("read_file", path="large")["data"]
        second = self.run_op("read_file", path="large", offset=first["next_offset"])["data"]
        self.assertFalse(first["complete"]); self.assertTrue(first["has_more"])
        self.assertEqual(first["sha256"], second["sha256"])
        self.run_op("edit_file", path="large", old_text="unique", new_text="changed", expected_sha=first["sha256"])
        with self.assertRaises(runner.Refused):
            self.run_op("edit_file", path="large", old_text="a", new_text="x", expected_sha=runner.read_file(str(self.workspace), "large")["sha256"])

    def test_actual_command_line_envelope_and_unicode_slice_fit_transport(self):
        (self.workspace / "unicode").write_bytes(("🌍" * 10000).encode())
        payload = dict(self.payload, op="read_file", args={"path": "unicode"})
        encoded = base64.b64encode(json.dumps(payload).encode()).decode()
        process = subprocess.run([sys.executable, "-c", SOURCE.read_text(encoding="utf-8"), encoded], stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=10)
        self.assertEqual(0, process.returncode, process.stderr.decode("utf-8", errors="replace"))
        result = json.loads(process.stdout)
        self.assertEqual(payload["id"], result["command_id"])
        self.assertNotIn("error", result)
        self.assertLess(len(process.stdout), 40000)
        self.assertTrue(result["data"]["has_more"])
        remaining = self.run_op("read_file", path="unicode", offset=result["data"]["next_offset"])["data"]
        self.assertEqual("🌍" * 10000, result["data"]["content"] + remaining["content"])

    def test_scope_credential_binary_and_size_rejections(self):
        for path in ("../outside", ".git/config", ".ssh/key", ".env", "id_ed25519", "secrets/private.pem"):
            with self.subTest(path=path), self.assertRaises(runner.Refused):
                self.run_op("write_file", path=path, content="data", expected_sha="")
        for content in ("\x00", "sk-" + "x" * 30, "x" * (runner.MAX_FILE + 1)):
            with self.assertRaises(runner.Refused):
                self.run_op("write_file", path="data", content=content, expected_sha="")

    def test_symlink_directory_cannot_bypass_scope_or_credentials(self):
        outside = self.root / "outside"; outside.mkdir()
        link = self.workspace / "link"
        try:
            link.symlink_to(outside, target_is_directory=True)
        except OSError:
            self.skipTest("OS account does not permit creating symlinks")
        with self.assertRaises(runner.Refused):
            self.run_op("write_file", path="link/a", content="x", expected_sha="")
        link.unlink(); (self.workspace / ".ssh").mkdir()
        link.symlink_to(self.workspace / ".ssh", target_is_directory=True)
        with self.assertRaises(runner.Refused):
            self.run_op("list_files", path="link")

    def test_real_process_reports_stdout_stderr_exit_and_no_output_in_metadata(self):
        result = runner.run_process([sys.executable, "-c", "import sys; print('actual stdout'); print('actual stderr',file=sys.stderr); sys.exit(7)"], str(self.workspace), 5, self.payload)
        self.assertEqual(7, result["exit_code"])
        self.assertEqual(str(self.workspace), result["cwd"])
        self.assertIn("actual stdout", result["stdout"]); self.assertIn("actual stderr", result["stderr"])
        metadata = runner.read_state(self.payload["id"])
        self.assertEqual("finished", metadata["state"])
        self.assertNotIn("stdout", metadata); self.assertNotIn("command", metadata)

    def test_output_is_bounded_and_timeout_kills_tracked_child(self):
        result = runner.run_process([sys.executable, "-c", "import sys; sys.stdout.write('x'*100000); sys.stderr.write('y'*100000)"], str(self.workspace), 5, self.payload)
        self.assertTrue(result["truncated"])
        self.assertLess(len(result["stdout"]), 13000); self.assertLess(len(result["stderr"]), 9000)
        self.payload["id"] = str(uuid.uuid4())
        timed = runner.run_process([sys.executable, "-c", "import time; time.sleep(30)"], str(self.workspace), 0.2, self.payload)
        self.assertTrue(timed["timed_out"]); self.assertNotEqual(0, timed["exit_code"])

    def test_process_with_closed_pipes_is_not_prematurely_killed(self):
        result = runner.run_process([sys.executable, "-c", "import os,time; os.close(1); os.close(2); time.sleep(0.3)"], str(self.workspace), 5, self.payload)
        self.assertEqual(0, result["exit_code"])
        self.assertFalse(result["timed_out"])

    def test_prestart_cancellation_and_unknown_job_never_replay(self):
        status = runner.job_status(dict(target=self.payload["id"], target_nonce=self.payload["nonce"]))
        self.assertEqual("unknown", status["data"]["state"])
        runner.cancel_job(dict(target=self.payload["id"], target_nonce=self.payload["nonce"]))
        result = runner.run_process([sys.executable, "-c", "raise Exception('should not run')"], str(self.workspace), 5, self.payload)
        self.assertTrue(result["cancelled"])
        self.assertFalse((runner.STATE / (self.payload["id"] + ".json")).exists())

    def test_actual_git_commit_preserves_other_files_and_requires_hashes(self):
        self.init_git()
        (self.workspace / "a.txt").write_text("first")
        (self.workspace / "unrelated.txt").write_text("user work")
        info = runner.read_file(str(self.workspace), "a.txt")
        with self.assertRaises(runner.Refused):
            self.run_op("git", operation="commit", paths=["a.txt"], expected_hashes={"a.txt": "stale"}, message="test")
        committed = self.run_op("git", operation="commit", paths=["a.txt"], expected_hashes={"a.txt": info["sha256"]}, message="test")
        self.assertEqual(0, committed["exit_code"], committed)
        self.assertEqual("a.txt", self.git("show", "--pretty=format:", "--name-only", "HEAD"))
        self.assertIn("unrelated.txt", self.git("status", "--short"))

    def test_git_rejects_other_staged_files_and_parent_repo(self):
        self.init_git()
        (self.workspace / "a").write_text("a"); (self.workspace / "other").write_text("user")
        self.git("add", "other")
        with self.assertRaises(runner.Refused):
            self.run_op("git", operation="commit", paths=["a"], expected_hashes={"a": runner.read_file(str(self.workspace), "a")["sha256"]}, message="test")
        (self.workspace / "sub").mkdir()
        with self.assertRaises(runner.Refused):
            self.run_op("git", operation="status", repo_path="sub")

    def test_git_remote_change_blocks_network_operation(self):
        self.init_git(); (self.workspace / "a").write_text("a"); self.git("add", "a"); self.git("commit", "-m", "base")
        self.git("remote", "add", "origin", "https://example.invalid/repo.git")
        preflight = self.run_op("git", operation="remote")["data"]
        self.git("remote", "set-url", "origin", "https://other.invalid/repo.git")
        with self.assertRaises(runner.Refused):
            self.run_op("git", operation="push", branch="main", expected_url=preflight["url"], expected_head=preflight["head"])
        for url in ("https://token@host/repo", "https://user:password@host/repo", "file:///tmp/repo", "ext::cmd"):
            with self.subTest(url=url), self.assertRaises(runner.Refused):
                runner.validate_url(url)

    def test_empty_repo_diff_and_fetch_preflight_are_supported(self):
        self.init_git(); self.git("remote", "add", "origin", "https://example.invalid/repo.git")
        self.assertEqual(0, self.run_op("git", operation="diff")["exit_code"])
        remote = self.run_op("git", operation="remote", for_fetch=True)
        self.assertTrue(remote["data"]["head"].startswith("unborn:"))


if __name__ == "__main__":
    unittest.main(verbosity=2)
