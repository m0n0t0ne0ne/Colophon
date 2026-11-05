package io.github.m0n0t0ne0ne.colophon.data

import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import io.github.m0n0t0ne0ne.colophon.core.*
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer

data class ImportResult(val added: Int, val skippedProtected: Int, val failed: Int)

class Importer(private val context: Context, private val dao: LibraryDao) {
    private val audioTypes = setOf("m4b", "m4a", "mp4", "aac", "mp3", "flac", "ogg", "oga", "opus", "wav")
    private val protectedTypes = setOf("aa", "aax", "aaxc")
    private var added = 0
    private var protectedCount = 0
    private var failed = 0

    private fun ext(name: String?) = name?.substringAfterLast('.', "")?.lowercase().orEmpty()

    suspend fun importFolder(tree: Uri): ImportResult {
        keepAccess(tree)
        DocumentFile.fromTreeUri(context, tree)?.let { scan(it, null) }
        return ImportResult(added, protectedCount, failed)
    }

    suspend fun importFiles(uris: List<Uri>): ImportResult {
        uris.forEach { uri ->
            keepAccess(uri)
            val doc = DocumentFile.fromSingleUri(context, uri) ?: return@forEach
            when {
                ext(doc.name) in protectedTypes -> protectedCount++
                else -> addBook(listOf(doc), doc.name.orEmpty(), null, null, null)
            }
        }
        return ImportResult(added, protectedCount, failed)
    }

    private fun keepAccess(uri: Uri) {
        runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }

    private suspend fun scan(dir: DocumentFile, parent: String?) {
        val children = dir.listFiles().filter { it.name?.startsWith(".") != true }
        protectedCount += children.count { it.isFile && ext(it.name) in protectedTypes }
        val audio = children.filter { it.isFile && ext(it.name) in audioTypes }
        val playlist = children.firstOrNull { it.isFile && ext(it.name) in setOf("m3u", "m3u8") }
        val cues = children.filter { it.isFile && ext(it.name) == "cue" }
        val dirName = dir.name

        if (audio.any { ext(it.name) == "m4b" } || (cues.isNotEmpty() && audio.size == cues.size)) {
            audio.forEach { file ->
                val cue = cues.firstOrNull { it.name?.substringBeforeLast('.') == file.name?.substringBeforeLast('.') }
                addBook(listOf(file), file.name.orEmpty(), dirName, cue, null)
            }
        } else if (audio.isNotEmpty()) {
            addBook(audio, dirName.orEmpty(), parent, null, playlist)
        }
        children.filter { it.isDirectory }.forEach { scan(it, dirName) }
    }

    private class Probe(
        val doc: DocumentFile, val durationMs: Long, val title: String?, val artist: String?, val album: String?,
        val year: Int?, val track: Int?, val picture: ByteArray?,
    )

    private fun probe(doc: DocumentFile): Probe? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(context, doc.uri)
            fun tag(k: Int) = r.extractMetadata(k)?.takeIf { it.isNotBlank() }
            Probe(
                doc,
                tag(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: return null,
                tag(MediaMetadataRetriever.METADATA_KEY_TITLE),
                tag(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST) ?: tag(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                    ?: tag(MediaMetadataRetriever.METADATA_KEY_AUTHOR),
                tag(MediaMetadataRetriever.METADATA_KEY_ALBUM),
                tag(MediaMetadataRetriever.METADATA_KEY_YEAR)?.take(4)?.toIntOrNull()
                    ?: tag(MediaMetadataRetriever.METADATA_KEY_DATE)?.take(4)?.toIntOrNull(),
                tag(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)?.substringBefore('/')?.trim()?.toIntOrNull(),
                r.embeddedPicture,
            )
        } catch (_: Exception) {
            null
        } finally {
            runCatching { r.release() }
        }
    }

    private suspend fun addBook(docs: List<DocumentFile>, name: String, folder: String?, cue: DocumentFile?, playlist: DocumentFile?) {
        val key = docs.first().uri.toString()
        if (dao.bookByKey(key) != null) return
        val probes = docs.mapNotNull { probe(it) }
        if (probes.isEmpty()) { failed++; return }
        val ordered = order(probes, playlist)

        val isMulti = ordered.size > 1
        val tagTitle = if (isMulti) ordered.first().album else ordered.first().title
        val guess = MetadataGuess.guess(name, folder, tagTitle)
        val duration = ordered.sumOf { it.durationMs }
        val id = dao.insert(
            Book(
                sourceKey = key,
                title = guess.title.ifBlank { name },
                author = ordered.first().artist.orEmpty(),
                series = guess.series,
                seriesNumber = guess.number,
                year = guess.year ?: ordered.first().year,
                durationMs = duration,
            ),
        )
        dao.insertFiles(ordered.mapIndexed { i, p -> BookFile(id, i, p.doc.uri.toString(), p.durationMs) })

        val cover = ordered.firstNotNullOfOrNull { it.picture }?.let { saveCover(id, it) }
        if (cover != null) dao.book(id)?.let { dao.update(it.copy(coverPath = cover)) }

        val chapters = readChapters(ordered, cue)
        dao.replaceSet(id, ChapterSet.ORIGINAL, chapters)
        dao.replaceSet(id, ChapterSet.CURRENT, chapters)
        added++
    }

    private fun order(probes: List<Probe>, playlist: DocumentFile?): List<Probe> {
        if (playlist != null) {
            val names = runCatching {
                context.contentResolver.openInputStream(playlist.uri)!!.bufferedReader().use { M3u.parse(it.readText()) }
                    .map { it.substringAfterLast('/').substringAfterLast('\\') }
            }.getOrDefault(emptyList())
            val byName = probes.associateBy { it.doc.name }
            val listed = names.mapNotNull { byName[it] }
            if (listed.size == probes.size) return listed
        }
        val tracks = probes.map { it.track }
        if (tracks.all { it != null } && tracks.toSet().size == tracks.size) return probes.sortedBy { it.track }
        return probes.sortedWith(compareBy(NaturalOrder) { it.doc.name.orEmpty() })
    }

    private fun readChapters(ordered: List<Probe>, cue: DocumentFile?): List<Chapter> {
        val out = mutableListOf<Chapter>()
        var offset = 0L
        ordered.forEach { p ->
            val parsed = cue?.let { c ->
                runCatching {
                    context.contentResolver.openInputStream(c.uri)!!.bufferedReader().use { CueSheet.parse(it.readText()) }
                }.getOrNull()
            }?.takeIf { it.isNotEmpty() } ?: readEmbedded(p.doc.uri)
            if (parsed.isEmpty()) {
                val label = p.title ?: p.doc.name?.substringBeforeLast('.').orEmpty()
                out += Chapter(bookId = 0, chapterSet = "", startMs = offset, title = label)
            } else {
                parsed.forEachIndexed { i, c ->
                    out += Chapter(bookId = 0, chapterSet = "", startMs = offset + c.startMs, title = c.title.ifBlank { "Section ${i + 1}" })
                }
            }
            offset += p.durationMs
        }
        return out
    }

    private fun readEmbedded(uri: Uri): List<ParsedChapter> = runCatching {
        context.contentResolver.openFileDescriptor(uri, "r")!!.use { pfd ->
            FileInputStream(pfd.fileDescriptor).use { stream ->
                val channel = stream.channel
                ChapterParser.parse(object : ByteSource {
                    override val size: Long = channel.size()
                    override fun read(position: Long, length: Int): ByteArray {
                        val b = ByteBuffer.allocate(length)
                        var p = position
                        while (b.hasRemaining()) {
                            val n = channel.read(b, p)
                            if (n <= 0) break
                            p += n
                        }
                        return b.array().copyOf(b.position())
                    }
                })
            }
        }
    }.getOrDefault(emptyList())

    private fun saveCover(id: Long, bytes: ByteArray): String? = runCatching {
        Covers.dir(context).resolve("book_${id}_${System.currentTimeMillis()}.img").also { it.writeBytes(bytes) }.absolutePath
    }.getOrNull()
}

object Covers {
    fun dir(context: Context): File = File(context.filesDir, "covers").also { it.mkdirs() }

    fun copy(context: Context, uri: Uri, id: Long): String? = runCatching {
        val target = dir(context).resolve("custom_${id}_${System.currentTimeMillis()}.img")
        context.contentResolver.openInputStream(uri)!!.use { input -> target.outputStream().use { input.copyTo(it) } }
        target.absolutePath
    }.getOrNull()
}
