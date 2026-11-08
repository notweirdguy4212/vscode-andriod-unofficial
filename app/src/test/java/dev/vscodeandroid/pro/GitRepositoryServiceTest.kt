package dev.vscodeandroid.pro

import dev.vscodeandroid.pro.git.GitRepositoryService
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class GitRepositoryServiceTest {
    @Test fun initStageCommitAndStatusWork() {
        val root = Files.createTempDirectory("vscode-git-test").toFile()
        try {
            val service = GitRepositoryService()
            service.init(root)
            java.io.File(root, "hello.txt").writeText("hello")
            assertFalse(service.status(root).clean)
            service.stage(root, listOf("hello.txt"))
            val commit = service.commit(root, "Initial commit", "Test User", "test@example.com")
            assertTrue(commit.isNotBlank())
            assertTrue(service.status(root).clean)
            assertEquals("Initial commit", service.log(root).first().shortMessage)
            val initialBranch = service.status(root).branch
            assertTrue(service.branches(root).isNotEmpty())
            service.createBranch(root, "feature/test")
            service.checkout(root, "feature/test")
            assertEquals("feature/test", service.status(root).branch)
            service.checkout(root, initialBranch)
            service.deleteBranch(root, "feature/test")
        } finally { root.deleteRecursively() }
    }

    @Test fun stageAllAndDiffPatchIncludeTrackedChanges() {
        val root = Files.createTempDirectory("vscode-git-diff").toFile()
        try {
            val service = GitRepositoryService()
            service.init(root)
            val file = java.io.File(root, "code.txt")
            file.writeText("before\n")
            service.stageAll(root)
            service.commit(root, "base", "Test User", "test@example.com")
            file.writeText("after\n")
            assertTrue(service.diffPatch(root).contains("-before"))
            assertTrue(service.diffPatch(root).contains("+after"))
            service.stageAll(root)
            assertEquals(listOf("code.txt"), service.status(root).staged)
        } finally { root.deleteRecursively() }
    }

    @Test fun stashAndTagWorkflowsAreAvailable() {
        val root = Files.createTempDirectory("vscode-git-stash-tag").toFile()
        try {
            val service = GitRepositoryService()
            service.init(root)
            val file = java.io.File(root, "notes.txt")
            file.writeText("base\n")
            service.stageAll(root)
            service.commit(root, "base", "Test User", "test@example.com")
            service.createTag(root, "v1.0.0", "First release")
            assertTrue(service.tags(root).contains("v1.0.0"))
            file.writeText("working change\n")
            service.stash(root, "save work")
            assertTrue(service.stashes(root).isNotEmpty())
            assertEquals("base\n", file.readText())
            service.applyStash(root)
            assertEquals("working change\n", file.readText())
            service.deleteTag(root, "v1.0.0")
            assertFalse(service.tags(root).contains("v1.0.0"))
        } finally { root.deleteRecursively() }
    }

    @Test fun mergeConflictsCanBeMarkedResolvedAfterManualEdit() {
        val root = Files.createTempDirectory("vscode-git-conflict").toFile()
        try {
            val service = GitRepositoryService()
            service.init(root)
            val file = java.io.File(root, "conflict.txt")
            file.writeText("base\n")
            service.stageAll(root)
            service.commit(root, "base", "Test User", "test@example.com")
            val mainBranch = service.status(root).branch
            service.createBranch(root, "feature/conflict")
            service.checkout(root, "feature/conflict")
            file.writeText("feature\n")
            service.stageAll(root)
            service.commit(root, "feature change", "Test User", "test@example.com")
            service.checkout(root, mainBranch)
            file.writeText("main\n")
            service.stageAll(root)
            service.commit(root, "main change", "Test User", "test@example.com")
            service.merge(root, "feature/conflict")
            assertEquals(listOf("conflict.txt"), service.conflicts(root))
            file.writeText("resolved\n")
            service.markConflictResolved(root, "conflict.txt")
            assertTrue(service.conflicts(root).isEmpty())
        } finally { root.deleteRecursively() }
    }

    @Test fun httpsRemoteRejectsEmbeddedCredentials() {
        val root = Files.createTempDirectory("vscode-git-remote").toFile()
        try {
            val service = GitRepositoryService()
            service.init(root)
            service.setHttpsRemote(root, "origin", "https://github.com/example/project.git")
            assertEquals("https://github.com/example/project.git", service.remotes(root).first { it.name == "origin" }.fetchUrl)
            org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
                service.setHttpsRemote(root, "leak", "https://user:token@example.com/project.git")
            }
            org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
                service.setHttpsRemote(root, "query", "https://example.com/project.git?token=secret")
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun rejectsUnsafePathsAndBranchNames() {
        val root = Files.createTempDirectory("vscode-git-validation").toFile()
        try {
            val service = GitRepositoryService()
            service.init(root)
            org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { service.stage(root, listOf("../secret")) }
            org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { service.createBranch(root, "../bad") }
        } finally { root.deleteRecursively() }
    }

    @Test fun localRebaseAndAbortCommandsAreExposed() {
        val root = Files.createTempDirectory("vscode-git-rebase").toFile()
        try {
            val service = GitRepositoryService()
            service.init(root)
            val file = java.io.File(root, "history.txt")
            file.writeText("base\\n")
            service.stageAll(root)
            service.commit(root, "base", "Test User", "test@example.com")
            val main = service.status(root).branch
            service.createBranch(root, "feature/rebase")
            service.checkout(root, "feature/rebase")
            java.io.File(root, "feature.txt").writeText("feature\\n")
            service.stageAll(root)
            service.commit(root, "feature work", "Test User", "test@example.com")
            service.checkout(root, main)
            java.io.File(root, "main.txt").writeText("main\\n")
            service.stageAll(root)
            service.commit(root, "main work", "Test User", "test@example.com")
            service.checkout(root, "feature/rebase")
            val result = service.rebase(root, main)
            assertTrue(result.status.isNotBlank())
            assertTrue(service.status(root).clean)
            assertEquals("feature/rebase", service.status(root).branch)
        } finally { root.deleteRecursively() }
    }
}
