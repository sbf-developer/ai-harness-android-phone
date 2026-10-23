package dev.pocketharness.review

import dev.pocketharness.data.*

object ReviewPolicy {
    const val MAX_FILES = 6
    const val MAX_CONTEXT_BYTES = 120 * 1024
    fun validateContext(files: List<ContextFile>) {
        if (files.isEmpty()) throw UserFacingException("Select at least one text file in Repositories.")
        if (files.size > MAX_FILES || files.sumOf { it.content.toByteArray().size } > MAX_CONTEXT_BYTES)
            throw UserFacingException("Select up to 6 files, totaling at most 120 KiB.")
        if (files.map { it.path }.distinct().size != files.size || files.any { it.snapshot != files.first().snapshot })
            throw UserFacingException("Select files from the same repository and base commit.")
        files.forEach {
            if (it.mode !in setOf("100644", "100755")) throw UserFacingException("Only regular text files can be edited.")
        }
    }
    fun parse(text: String, files: List<ContextFile>): Proposal {
        validateContext(files)
        val clean = text.trim().let { if (it.startsWith("```json\n") && it.endsWith("```")) it.removePrefix("```json\n").removeSuffix("```").trim() else it }
        val proposal = try { AppJson.decodeFromString<Proposal>(clean) }
        catch (_: Exception) { throw UserFacingException("The model did not return a valid edit proposal. Try again with a smaller request.") }
        if (proposal.edits.isEmpty()) throw UserFacingException("The model proposed no changes.")
        if (proposal.summary.isBlank() || proposal.summary.length > 4000) throw UserFacingException("The proposal has an invalid summary.")
        if (proposal.edits.size > MAX_FILES || proposal.edits.map { it.path }.distinct().size != proposal.edits.size)
            throw UserFacingException("The proposal contains duplicate or too many files.")
        val allowed = files.associateBy { it.path }
        proposal.edits.forEach { edit ->
            val original = allowed[edit.path] ?: throw UserFacingException("The proposal tried to change an unselected file.")
            if (edit.content.contains('\u0000') || edit.content.toByteArray().size > MAX_CONTEXT_BYTES)
                throw UserFacingException("A proposed file is binary or too large.")
            if (edit.content == original.content) throw UserFacingException("The proposal contains an unchanged file. Ask the model to return only changed files.")
        }
        if (proposal.edits.sumOf { it.content.toByteArray().size } > MAX_CONTEXT_BYTES)
            throw UserFacingException("The proposed changes exceed 120 KiB.")
        return proposal
    }
    fun branch(name: String, base: String) {
        if (name == base || name == "HEAD" || name.startsWith('-') || name.length !in 1..160 || !name.matches(Regex("[A-Za-z0-9_./-]+")) ||
            name.startsWith('/') || name.endsWith('/') || name.endsWith('.') ||
            name.contains("..") || name.contains("//") || name.split('/').any { it.startsWith('.') || it.endsWith(".lock") })
            throw UserFacingException("Enter a valid, new Git branch name different from the base.")
    }
}

/** A single exact replacement hunk with shared prefix/suffix folded to three context lines. */
fun unifiedDiff(path: String, before: String, after: String): String {
    val a = before.split('\n'); val b = after.split('\n')
    var prefix = 0
    while (prefix < minOf(a.size, b.size) && a[prefix] == b[prefix]) prefix++
    var suffix = 0
    while (suffix < minOf(a.size, b.size) - prefix && a[a.lastIndex - suffix] == b[b.lastIndex - suffix]) suffix++
    val start = maxOf(0, prefix - 3)
    val endA = minOf(a.size, a.size - suffix + 3); val endB = minOf(b.size, b.size - suffix + 3)
    return buildString {
        append("--- a/$path\n+++ b/$path\n@@ -${start + 1},${endA - start} +${start + 1},${endB - start} @@\n")
        for (i in start until prefix) append(" ${a[i]}\n")
        for (i in prefix until a.size - suffix) append("-${a[i]}\n")
        for (i in prefix until b.size - suffix) append("+${b[i]}\n")
        for (i in a.size - suffix until endA) append(" ${a[i]}\n")
        if (!before.endsWith('\n') || !after.endsWith('\n')) append("\\ No newline at end of file (see Before / After)\n")
    }
}
