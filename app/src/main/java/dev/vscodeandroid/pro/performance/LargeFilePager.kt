package dev.vscodeandroid.pro.performance

import java.io.File
import java.io.RandomAccessFile

/**
 * Bounded, line-aware reader for large workspace files. Offsets are byte offsets,
 * so callers can request the next page without loading the entire file into memory.
 */
class LargeFilePager(private val workspace: File, private val maxPageBytes: Int = 256 * 1024) {
    data class Page(val text: String, val startOffset: Long, val nextOffset: Long, val eof: Boolean)

    init {
        require(maxPageBytes in 4 * 1024..2 * 1024 * 1024) { "Page size must be 4 KiB–2 MiB" }
    }

    fun read(relativePath: String, offset: Long = 0L, requestedBytes: Int = maxPageBytes): Page {
        require(relativePath.isNotBlank() && !relativePath.startsWith('/') && !relativePath.contains('\\'))
        require(relativePath.split('/').none { it.isBlank() || it == "." || it == ".." || it == ".git" })
        require(offset >= 0 && requestedBytes in 1..maxPageBytes)
        val root = workspace.canonicalFile
        val file = File(root, relativePath).canonicalFile
        require(file.toPath().startsWith(root.toPath()) && file.isFile) { "File is outside the workspace or unavailable" }
        RandomAccessFile(file, "r").use { input ->
            require(offset <= input.length()) { "Page offset is past end of file" }
            input.seek(offset)
            val buffer = ByteArray(requestedBytes)
            val count = input.read(buffer)
            if (count <= 0) return Page("", offset, offset, true)
            var end = count
            // Extend to the next newline, but never beyond one extra bounded page.
            if (offset + count < input.length()) {
                var extra = 0
                while (extra < maxPageBytes && input.filePointer < input.length()) {
                    val b = input.read()
                    if (b < 0) break
                    extra++
                    if (b == 10) break
                }
                if (extra > 0) {
                    val expanded = buffer.copyOf(count + extra)
                    input.seek(offset + count)
                    input.readFully(expanded, count, extra)
                    return Page(String(expanded, Charsets.UTF_8), offset, offset + count + extra,
                        offset + count + extra >= input.length())
                }
            }
            return Page(String(buffer, 0, end, Charsets.UTF_8), offset, offset + count,
                offset + count >= input.length())
        }
    }
}
