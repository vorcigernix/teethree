package com.example.teethree.data

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset
import java.util.zip.ZipFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal data class PreviewText(val text: String, val truncated: Boolean)

internal data class DelimitedPreview(val rows: List<List<String>>, val truncated: Boolean)

internal data class ArchivePreviewEntry(
    val name: String,
    val directory: Boolean,
    val size: Long,
    val compressedSize: Long,
)

internal fun readAttachmentText(file: File, maxBytes: Int = 1024 * 1024): PreviewText {
    val output = ByteArrayOutputStream()
    file.inputStream().use { input ->
        val buffer = ByteArray(8192)
        while (output.size() <= maxBytes) {
            val count = input.read(buffer, 0, minOf(buffer.size, maxBytes + 1 - output.size()))
            if (count < 0) break
            output.write(buffer, 0, count)
        }
    }
    val raw = output.toByteArray()
    val bytes = raw.copyOf(minOf(raw.size, maxBytes))
    val text =
        when {
            bytes.size >= 2 && bytes[0] == 0xff.toByte() && bytes[1] == 0xfe.toByte() ->
                bytes.copyOfRange(2, bytes.size - bytes.size % 2).toString(Charsets.UTF_16LE)
            bytes.size >= 2 && bytes[0] == 0xfe.toByte() && bytes[1] == 0xff.toByte() ->
                bytes.copyOfRange(2, bytes.size - bytes.size % 2).toString(Charsets.UTF_16BE)
            else -> bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF")
        }
    val utf16 =
        bytes.size >= 2 &&
            ((bytes[0] == 0xff.toByte() && bytes[1] == 0xfe.toByte()) ||
                (bytes[0] == 0xfe.toByte() && bytes[1] == 0xff.toByte()))
    return PreviewText(text, raw.size > maxBytes || (utf16 && bytes.size % 2 != 0))
}

internal fun parseDelimitedAttachment(text: String, delimiter: Char): DelimitedPreview {
    require(delimiter == ',' || delimiter == '\t')
    val rows = mutableListOf<List<String>>()
    var row = mutableListOf<String>()
    var cell = StringBuilder()
    var quoted = false
    var truncated = false
    var rowStart = if (text.startsWith('\uFEFF')) 1 else 0
    fun endCell() {
        if (cell.length > 2000) {
            val end =
                if (cell[1999].isHighSurrogate() && cell[2000].isLowSurrogate()) 1999 else 2000
            cell.setLength(end)
            truncated = true
        }
        if (row.size < 30) row.add(cell.toString()) else truncated = true
        cell = StringBuilder()
    }
    var index = rowStart
    while (index < text.length) {
        val char = text[index]
        if (char == '"') {
            if (quoted && text.getOrNull(index + 1) == '"') {
                if (cell.length <= 2000) cell.append('"') else truncated = true
                index += 2
                continue
            }
            if (quoted || cell.isEmpty()) {
                quoted = !quoted
                index++
                continue
            }
        }
        if (!quoted && (char == delimiter || char == '\n' || char == '\r')) {
            endCell()
            if (char != delimiter) {
                rows.add(row)
                row = mutableListOf()
                if (char == '\r' && text.getOrNull(index + 1) == '\n') index++
                rowStart = index + 1
                if (rows.size == 100)
                    return DelimitedPreview(rows, truncated || index < text.lastIndex)
            }
        } else if (cell.length <= 2000) cell.append(char) else truncated = true
        index++
    }
    if (rowStart < text.length) {
        endCell()
        rows.add(row)
    }
    return DelimitedPreview(rows, truncated || quoted)
}

internal fun safeArchiveEntryPath(name: String): Boolean {
    if (
        name.isEmpty() ||
            name.length > 1024 ||
            '\u0000' in name ||
            name.startsWith('/') ||
            name.startsWith('\\') ||
            Regex("^[A-Za-z]:").containsMatchIn(name)
    )
        return false
    return name.replace('\\', '/').split('/').none { it == ".." || it == "." }
}

internal fun checkAttachmentZipDirectory(file: File) {
    require(file.length() in 22L..50L * 1024 * 1024) {
        "ZIP browsing supports archives up to 50 MiB."
    }
    RandomAccessFile(file, "r").use { input ->
        fun read(position: Long, count: Int): ByteBuffer {
            require(position >= 0 && position + count <= input.length()) {
                "The ZIP directory is damaged."
            }
            val bytes = ByteArray(count)
            input.seek(position)
            input.readFully(bytes)
            return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        }
        val tailOffset = (input.length() - 65557).coerceAtLeast(0)
        val tail = read(tailOffset, (input.length() - tailOffset).toInt())
        var found = -1
        for (offset in tail.limit() - 22 downTo 0) {
            if (
                tail.getInt(offset) == 0x06054b50 &&
                    offset + 22 + (tail.getShort(offset + 20).toInt() and 0xffff) == tail.limit()
            ) {
                found = offset
                break
            }
        }
        require(found >= 0) { "This is not a readable ZIP archive." }
        fun short(offset: Int) = tail.getShort(found + offset).toInt() and 0xffff
        fun int(offset: Int) = tail.getInt(found + offset).toLong() and 0xffffffffL
        var count = short(10).toLong()
        var size = int(12)
        var start = int(16)
        var end = tailOffset + found
        if (
            listOf(short(4), short(6), short(8), short(10)).any { it == 0xffff } ||
                size == 0xffffffffL ||
                start == 0xffffffffL
        ) {
            val locator = read(end - 20, 20)
            require(
                locator.getInt(0) == 0x07064b50 && locator.getInt(4) == 0 && locator.getInt(16) == 1
            ) {
                "Unsupported multi-disk ZIP64 archive."
            }
            val recordOffset = locator.getLong(8)
            val record = read(recordOffset, 56)
            require(
                record.getInt(0) == 0x06064b50 &&
                    record.getLong(4) == 44L &&
                    record.getInt(16) == 0 &&
                    record.getInt(20) == 0
            ) {
                "The ZIP64 directory is unsupported or damaged."
            }
            count = record.getLong(32)
            size = record.getLong(40)
            start = record.getLong(48)
            end = recordOffset
            require(
                record.getLong(24) == count &&
                    start >= 0 &&
                    size >= 0 &&
                    start <= end &&
                    size == end - start
            ) {
                "The ZIP64 directory is damaged."
            }
        } else
            require(short(4) == 0 && short(6) == 0 && short(8).toLong() == count) {
                "Multi-disk ZIP archives are unsupported."
            }
        require(
            count in 0L..2048L && size >= 0 && size <= end && start >= 0 && start <= end - size
        ) {
            "The ZIP directory exceeds the 2048-entry limit or is damaged."
        }
        var cursor = end - size
        var records = 0L
        while (cursor < end) {
            val header = read(cursor, 46)
            require(header.getInt(0) == 0x02014b50 && ++records <= 2048) {
                "The ZIP directory is damaged or has too many entries."
            }
            val nameLength = header.getShort(28).toInt() and 0xffff
            require(nameLength <= 1024) { "An archive path exceeds 1024 bytes." }
            cursor +=
                46 +
                    nameLength +
                    (header.getShort(30).toInt() and 0xffff) +
                    (header.getShort(32).toInt() and 0xffff)
            require(cursor <= end) { "The ZIP directory is damaged." }
        }
        require(cursor == end && records == count) {
            "The ZIP entry count does not match its directory."
        }
    }
}

internal suspend fun listAttachmentZip(file: File): List<ArchivePreviewEntry> =
    withContext(Dispatchers.IO) {
        checkAttachmentZipDirectory(file)
        ZipFile(file, Charset.forName("CP437")).use { zip ->
            var total = 0L
            val names = mutableSetOf<String>()
            zip.entries()
                .asSequence()
                .map { entry ->
                    require(safeArchiveEntryPath(entry.name) && names.add(entry.name)) {
                        "The archive contains an unsafe or duplicate path."
                    }
                    require(
                        entry.size >= 0 &&
                            entry.compressedSize >= 0 &&
                            entry.size <= 50L * 1024 * 1024 - total
                    ) {
                        "The expanded archive exceeds 50 MiB."
                    }
                    total += entry.size
                    require(total <= file.length().coerceAtLeast(1) * 200) {
                        "The archive exceeds the safe compression ratio."
                    }
                    ArchivePreviewEntry(
                        entry.name,
                        entry.isDirectory,
                        entry.size,
                        entry.compressedSize,
                    )
                }
                .toList()
        }
    }

internal suspend fun extractAttachmentZipEntry(
    archive: File,
    entry: ArchivePreviewEntry,
    cache: File,
    maxBytes: Long = 10L * 1024 * 1024,
): File {
    var acquired: File? = null
    return try {
        withContext(Dispatchers.IO) {
            require(
                !entry.directory && safeArchiveEntryPath(entry.name) && entry.size <= maxBytes
            ) {
                "The entry exceeds the preview limit or has an unsafe path."
            }
            checkAttachmentZipDirectory(archive)
            check(cache.isDirectory || cache.mkdirs())
            val extension =
                entry.name
                    .substringAfterLast('.', "")
                    .takeIf { it.matches(Regex("[A-Za-z0-9]{1,12}")) }
                    ?.let { ".$it" }
                    .orEmpty()
            val file = File.createTempFile("zip-entry-", extension, cache).also { acquired = it }
            try {
                ZipFile(archive, Charset.forName("CP437")).use { zip ->
                    val source =
                        checkNotNull(zip.getEntry(entry.name)) {
                            "The archive entry no longer exists."
                        }
                    zip.getInputStream(source).use { input ->
                        file.outputStream().use { output ->
                            val buffer = ByteArray(8192)
                            var size = 0L
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val count = input.read(buffer)
                                if (count < 0) break
                                size += count
                                require(
                                    size <= maxBytes &&
                                        size <= archive.length().coerceAtLeast(1) * 200
                                ) {
                                    "The expanded entry exceeds its preview or compression-ratio limit."
                                }
                                output.write(buffer, 0, count)
                            }
                        }
                    }
                }
                file
            } catch (failure: Throwable) {
                file.delete()
                throw failure
            }
        }
    } catch (failure: Throwable) {
        withContext(NonCancellable + Dispatchers.IO) { acquired?.delete() }
        throw failure
    }
}
