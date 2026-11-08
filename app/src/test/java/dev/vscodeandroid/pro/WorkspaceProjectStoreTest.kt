package dev.vscodeandroid.pro

import dev.vscodeandroid.pro.workspace.WorkspaceProjectStore
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files\nimport java.io.File

class WorkspaceProjectStoreTest {
    @Test fun createsReadsAndListsProjectFiles() {
        val root = Files.createTempDirectory("workspace-store-test").toFile()
        try {
            val store = WorkspaceProjectStore(root)
            store.defaultWorkspace()
            store.createFile("default", "src/main.py")
            store.write("default", "src/main.py", "print('hello')")
            assertEquals("print('hello')", store.read("default", "src/main.py"))
            assertTrue(store.listFiles().any { it.relativePath == "src/main.py" && !it.directory })
        } finally { root.deleteRecursively() }
    }

    @Test fun rejectsTraversalAndInternalGitMetadataPaths() {
        val root = Files.createTempDirectory("workspace-store-security").toFile()
        try {
            val store = WorkspaceProjectStore(root)
            store.defaultWorkspace()
            assertThrows(IllegalArgumentException::class.java) { store.write("default", "../secret", "x") }
            assertThrows(IllegalArgumentException::class.java) { store.write("default", ".git/config", "x") }
            assertThrows(IllegalArgumentException::class.java) { store.createFile("../../outside", "x.txt") }
        } finally { root.deleteRecursively() }
    }
}
