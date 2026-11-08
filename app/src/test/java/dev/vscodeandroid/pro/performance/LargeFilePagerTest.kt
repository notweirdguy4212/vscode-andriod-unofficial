package dev.vscodeandroid.pro.performance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class LargeFilePagerTest {
    @Test fun pagesStayBoundedAndAdvanceAtLineBoundary() {
        val root = Files.createTempDirectory("pager-test").toFile()
        try {
            File(root, "sample.txt").writeText(("0123456789\\n").repeat(1200))
            val pager = LargeFilePager(root, 4096)
            val first = pager.read("sample.txt", requestedBytes = 4096)
            assertTrue(first.text.length >= 4096)
            assertTrue(first.nextOffset > first.startOffset)
            assertTrue(first.text.endsWith("\\n"))
            val second = pager.read("sample.txt", first.nextOffset, 4096)
            assertEquals(first.nextOffset, second.startOffset)
            assertTrue(second.text.isNotEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun rejectsTraversalAndGitMetadata() {
        val root = Files.createTempDirectory("pager-test").toFile()
        try {
            File(root, "safe.txt").writeText("ok")
            val pager = LargeFilePager(root)
            for (path in listOf("../outside", ".git/config", "a/../safe.txt")) {
                runCatching { pager.read(path) }.onSuccess { throw AssertionError("Accepted unsafe path: $path") }
            }
        } finally {
            root.deleteRecursively()
        }
    }
}
