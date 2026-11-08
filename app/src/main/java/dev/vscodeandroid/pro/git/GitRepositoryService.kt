package dev.vscodeandroid.pro.git

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.diff.DiffFormatter
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import org.eclipse.jgit.api.MergeResult
import java.io.ByteArrayOutputStream
import java.io.File

/** Git operations scoped to a managed workspace repository. */
class GitRepositoryService {
    data class Snapshot(
        val branch: String,
        val staged: List<String>,
        val changed: List<String>,
        val untracked: List<String>,
        val clean: Boolean,
        val conflicts: List<String> = emptyList()
    )
    data class CommitInfo(val id: String, val shortMessage: String, val author: String, val timestamp: Int)

    fun init(root: File): File {
        val workspace = root.canonicalFile
        require(workspace.isDirectory) { "Workspace directory does not exist" }
        return Git.init().setDirectory(workspace).call().repository.directory
    }

    private fun open(root: File): Git {
        val canonical = root.canonicalFile
        require(canonical.isDirectory) { "Workspace directory does not exist" }
        val repository = FileRepositoryBuilder().setWorkTree(canonical).findGitDir(canonical).build()
        val gitDir = repository.directory?.canonicalFile ?: run {
            repository.close(); error("Repository metadata is unavailable")
        }
        require(gitDir.parentFile == canonical && gitDir.name == ".git") {
            repository.close(); "Expected a Git repository rooted at the selected workspace"
        }
        return Git(repository)
    }

    fun status(root: File): Snapshot = open(root).use { git ->
        val s = git.status().call()
        Snapshot(
            runCatching { git.repository.branch }.getOrDefault("No commits yet"),
            (s.added() + s.changed() + s.removed()).distinct().sorted(),
            (s.modified() + s.missing()).distinct().sorted(),
            s.untracked().toList().sorted(),
            s.isClean,
            s.conflicting().sorted()
        )
    }

    fun stage(root: File, paths: List<String>) = open(root).use { git ->
        require(paths.isNotEmpty()) { "Select at least one file to stage" }
        paths.distinct().forEach { validatePath(it); git.add().addFilepattern(it).call() }
    }

    fun stageAll(root: File) = open(root).use { git ->
        git.add().addFilepattern(".").call()
        git.add().setUpdate(true).addFilepattern(".").call()
    }

    fun unstage(root: File, paths: List<String>) = open(root).use { git ->
        require(paths.isNotEmpty()) { "Select at least one file to unstage" }
        paths.distinct().forEach { validatePath(it); git.reset().addPath(it).call() }
    }

    fun commit(root: File, message: String, name: String, email: String): String {
        require(message.isNotBlank() && message.length <= 5000) { "Commit message must contain 1–5000 characters" }
        require(name.isNotBlank() && name.length <= 200 && email.length <= 254 && email.contains('@')) { "Invalid author identity" }
        return open(root).use { git ->
            val status = git.status().call()
            require(status.added().isNotEmpty() || status.changed().isNotEmpty() || status.removed().isNotEmpty()) {
                "Stage at least one change before committing"
            }
            require(status.conflicting().isEmpty()) { "Resolve merge conflicts before committing" }
            git.commit().setMessage(message.trim()).setAuthor(name.trim(), email.trim()).call().name
        }
    }

    fun branches(root: File): List<String> = open(root).use { git ->
        git.branchList().call().map { it.name.removePrefix("refs/heads/") }.sorted()
    }

    fun checkout(root: File, branch: String) {
        validateBranch(branch)
        open(root).use { git ->
            require(git.status().call().isClean) { "Commit or stash changes before switching branches" }
            git.checkout().setName(branch).call()
        }
    }

    fun createBranch(root: File, branch: String): String {
        validateBranch(branch)
        return open(root).use { it.branchCreate().setName(branch).call().name }
    }

    fun deleteBranch(root: File, branch: String) {
        validateBranch(branch)
        open(root).use { git ->
            require(git.repository.branch != branch) { "Cannot delete the currently checked-out branch" }
            git.branchDelete().setBranchNames(branch).call()
        }
    }

    /** Unified patch for unstaged tracked changes. */
    fun diffPatch(root: File, staged: Boolean = false, maxBytes: Int = 512_000): String = open(root).use { git ->
        require(maxBytes in 1024..1_048_576)
        val output = ByteArrayOutputStream()
        DiffFormatter(output).use { formatter ->
            formatter.setRepository(git.repository)
            formatter.setAbbreviationLength(8)
            formatter.format(git.diff().setCached(staged).call())
        }
        val bytes = output.toByteArray()
        String(bytes.copyOf(minOf(bytes.size, maxBytes)), Charsets.UTF_8) +
            if (bytes.size > maxBytes) "\n[diff truncated at $maxBytes bytes]" else ""
    }

    fun diff(root: File): List<String> = open(root).use { git ->
        git.diff().call().map { "${it.changeType}: ${it.oldPath} -> ${it.newPath}" }
    }

    fun log(root: File, maxCount: Int = 50): List<CommitInfo> {
        require(maxCount in 1..200)
        return open(root).use { git ->
            git.log().setMaxCount(maxCount).call().map { commit ->
                CommitInfo(commit.name, commit.shortMessage, commit.authorIdent.name, commit.commitTime)
            }.toList()
        }
    }

    data class StashInfo(val id: String, val message: String)
    data class RemoteInfo(val name: String, val fetchUrl: String, val pushUrl: String)

    fun stash(root: File, message: String): String = open(root).use { git ->
        require(message.length <= 500) { "Stash message is too long" }
        git.stashCreate().setWorkingDirectoryMessage(message.ifBlank { "IDE working changes" }).call()?.name
            ?: error("No local changes to stash")
    }

    fun stashes(root: File): List<StashInfo> = open(root).use { git ->
        git.stashList().call().map { StashInfo(it.id.name, it.shortMessage) }
    }

    fun applyStash(root: File, index: Int = 0, dropAfterApply: Boolean = false) = open(root).use { git ->
        val all = git.stashList().call()
        require(index in all.indices) { "Stash entry does not exist" }
        git.stashApply().setStashRef(all[index].id.name).call()
        if (dropAfterApply) git.stashDrop().setStashRef(index).call()
    }

    fun dropStash(root: File, index: Int = 0) = open(root).use { git ->
        val all = git.stashList().call()
        require(index in all.indices) { "Stash entry does not exist" }
        git.stashDrop().setStashRef(index).call()
    }

    fun tags(root: File): List<String> = open(root).use { git ->
        git.tagList().call().map { it.name.removePrefix("refs/tags/") }.sorted()
    }

    fun createTag(root: File, name: String, message: String? = null): String {
        validateRefName(name)
        return open(root).use { git ->
            val command = git.tag().setName(name)
            if (!message.isNullOrBlank()) command.setMessage(message.take(2_000))
            command.call().name
        }
    }

    fun deleteTag(root: File, name: String) {
        validateRefName(name)
        open(root).use { it.tagDelete().setTags(name).call() }
    }

    fun remotes(root: File): List<RemoteInfo> = open(root).use { git ->
        git.remoteList().call().map { remote ->
            RemoteInfo(remote.name, remote.getURIs().firstOrNull()?.toString().orEmpty(), remote.getPushURIs().firstOrNull()?.toString().orEmpty())
        }
    }

    /** Configure HTTPS remotes only. Credentials are supplied per operation and never written to config. */
    fun setHttpsRemote(root: File, name: String, url: String) = open(root).use { git ->
        require(name.matches(Regex("[A-Za-z0-9._-]{1,64}"))) { "Invalid remote name" }
        val parsed = java.net.URI(url)
        require(parsed.scheme.equals("https", ignoreCase = true) && !parsed.host.isNullOrBlank() && !parsed.rawPath.isNullOrBlank()) {
            "Only HTTPS Git remotes are supported by this credential-safe workflow"
        }
        require(parsed.userInfo == null && parsed.rawQuery == null && parsed.rawFragment == null) {
            "Do not embed credentials, query tokens or fragments in the remote URL; enter credentials only for the operation"
        }
        val config = git.repository.config
        config.setString("remote", name, "url", parsed.toASCIIString())
        config.setString("remote", name, "fetch", "+refs/heads/*:refs/remotes/$name/*")
        config.save()
    }

    fun fetch(root: File, remote: String, username: String? = null, password: CharArray? = null) = open(root).use { git ->
        val command = git.fetch().setRemote(remote).setRemoveDeletedRefs(true)
        val provider = credentials(username, password)
        try { provider?.let(command::setCredentialsProvider); command.call() }
        finally { runCatching { provider?.clear() } }
    }

    fun pull(root: File, remote: String, branch: String, username: String? = null, password: CharArray? = null): String = open(root).use { git ->
        validateBranch(branch)
        require(git.status().call().isClean) { "Commit or stash local changes before pulling" }
        val command = git.pull().setRemote(remote).setRemoteBranchName(branch).setRebase(false)
        val provider = credentials(username, password)
        val result = try { provider?.let(command::setCredentialsProvider); command.call() }
            finally { runCatching { provider?.clear() } }
        when {
            result.isSuccessful -> "Pull completed: ${result.mergeResult?.mergeStatus ?: "up to date"}"
            result.mergeResult?.mergeStatus == MergeResult.MergeStatus.CONFLICTING -> "Pull produced conflicts: ${result.mergeResult.conflicts.keys.joinToString()}"
            else -> "Pull did not complete: ${result.mergeResult?.mergeStatus ?: "unknown result"}"
        }
    }

    fun push(root: File, remote: String, branch: String, username: String? = null, password: CharArray? = null): List<String> = open(root).use { git ->
        validateBranch(branch)
        val command = git.push().setRemote(remote).add("refs/heads/$branch")
        val provider = credentials(username, password)
        try { provider?.let(command::setCredentialsProvider); command.call().flatMap { it.remoteUpdates }.map { "${it.remoteName}: ${it.status}" } }
        finally { runCatching { provider?.clear() } }
    }

    fun merge(root: File, branch: String): String = open(root).use { git ->
        validateBranch(branch)
        require(git.status().call().isClean) { "Commit or stash local changes before merging" }
        val result = git.merge().include(git.repository.findRef(branch) ?: error("Branch not found: $branch")).call()
        "Merge status: ${result.mergeStatus}" + if (result.conflicts.isNotEmpty()) "\nConflicts: ${result.conflicts.keys.joinToString()}" else ""
    }

    data class RebaseInfo(val status: String, val conflicts: List<String> = emptyList(),
                          val currentCommit: String? = null)

    /** Start a rebase onto a local branch or remote-tracking ref. */
    fun rebase(root: File, upstream: String): RebaseInfo = open(root).use { git ->
        validateRefName(upstream)
        require(git.status().call().isClean) { "Commit or stash local changes before rebasing" }
        val ref = git.repository.findRef(upstream) ?: git.repository.findRef("refs/remotes/$upstream")
            ?: error("Rebase upstream not found: $upstream")
        val result = git.rebase().setUpstream(ref.name).call()
        RebaseInfo(result.status.name, result.conflicts ?: emptyList(), result.currentCommit?.name)
    }

    /** Continue only after all conflict paths have been staged as resolved. */
    fun continueRebase(root: File): RebaseInfo = open(root).use { git ->
        require(git.status().call().conflicting().isEmpty()) { "Resolve and stage every conflicted file first" }
        val result = git.rebase().setOperation(org.eclipse.jgit.api.RebaseCommand.Operation.CONTINUE).call()
        RebaseInfo(result.status.name, result.conflicts ?: emptyList(), result.currentCommit?.name)
    }

    fun abortRebase(root: File): RebaseInfo = open(root).use { git ->
        val result = git.rebase().setOperation(org.eclipse.jgit.api.RebaseCommand.Operation.ABORT).call()
        RebaseInfo(result.status.name, result.conflicts ?: emptyList(), result.currentCommit?.name)
    }

    fun conflicts(root: File): List<String> = open(root).use { it.status().call().conflicting().sorted() }

    /** Stages a user-edited conflict file to mark it resolved. */
    fun markConflictResolved(root: File, path: String) = open(root).use { git ->
        validatePath(path)
        val workspace = root.canonicalFile
        val file = File(workspace, path).canonicalFile
        require(file.toPath().startsWith(workspace.toPath()) && file.isFile) { "Conflict file is outside the repository or missing" }
        git.add().addFilepattern(path).call()
        require(path !in git.status().call().conflicting()) { "Git still reports this path as conflicted" }
    }

    private fun credentials(username: String?, password: CharArray?): UsernamePasswordCredentialsProvider? {
        if (username == null && password == null) return null
        require(!username.isNullOrBlank() && password != null && password.size <= 4096) { "Both username and token/password are required" }
        return UsernamePasswordCredentialsProvider(username, password)
    }

    private fun validateRefName(name: String) {
        require(name.length in 1..120 && name.matches(Regex("[A-Za-z0-9._/-]+")) && !name.startsWith('-') &&
            !name.contains("..") && !name.contains("//") && !name.endsWith('/') && !name.endsWith('.') && !name.contains("@{")) {
            "Invalid Git reference name"
        }
    }

    private fun validateBranch(branch: String) {
        require(branch.length in 1..120 && branch.matches(Regex("[A-Za-z0-9._/-]+")) &&
            !branch.startsWith('-') && !branch.contains("..") && !branch.contains("//") &&
            !branch.endsWith('/') && !branch.endsWith('.') && !branch.contains("@{")) { "Invalid branch name" }
    }

    private fun validatePath(path: String) {
        require(path.isNotBlank() && path.length <= 4096 && !path.startsWith('/') && !path.contains('\\') && !path.matches(Regex("^[A-Za-z]:.*")) &&
            '\u0000' !in path && path.split('/').none { it == ".." || it == "." || it == ".git" }) {
            "Invalid repository path"
        }
    }
}
