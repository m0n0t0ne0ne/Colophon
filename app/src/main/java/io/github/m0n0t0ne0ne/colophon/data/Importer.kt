package io.github.m0n0t0ne0ne.colophon.data

import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import io.github.m0n0t0ne0ne.colophon.core.*
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import kotlin.math.abs

data class ImportResult(val added: Int, val updated: Int, val removed: Int, val skippedProtected: Int, val failed: Int) {
    fun summary(): String {
        val parts = mutableListOf<String>()
        if (added > 0) parts += if (added == 1) "Added 1 book" else "Added $added books"
        if (updated > 0) parts += if (updated == 1) "updated 1" else "updated $updated"
        if (removed > 0) parts += "removed $removed whose files are gone"
        if (skippedProtected > 0) parts += "skipped $skippedProtected DRM-protected file(s)"
        if (failed > 0) parts += "$failed couldn't be read"
        return if (parts.isEmpty()) "Your library is up to date."
        else parts.joinToString(", ").replaceFirstChar { it.uppercase() } + "."
    }
}

class Importer(private val context: Context, private val dao: LibraryDao, private val removedFiles: Set<String> = emptySet()) {
    private var added = 0
    private var updated = 0
    private var removed = 0
    private var protectedCount = 0
    private var failed = 0

    private fun result() = ImportResult(added, updated, removed, protectedCount, failed)

    suspend fun importFolder(tree: Uri): ImportResult {
        keepAccess(tree)
        return refresh(extraRoot = tree)
    }

    suspend fun importFiles(uris: List<Uri>): ImportResult {
        val known = dao.allFiles().map { fileKey(it.uri) }.toSet()
        uris.forEach { uri ->
            keepAccess(uri)
            val doc = DocumentFile.fromSingleUri(context, uri) ?: return@forEach
            val name = doc.name.orEmpty()
            when {
                LibraryScanner.ext(name) in LibraryScanner.protectedTypes -> protectedCount++
                fileKey(uri.toString()) in known -> Unit
                else -> {
                    val node = ScanNode(name, false, doc)
                    addBook(BookPlan(listOf(node), null, null, null, null))
                }
            }
        }
        consolidate()
        return result()
    }

    /** Re-reads every folder you have added: finds new books and tidies existing ones, keeping your place. */
    suspend fun refresh(extraRoot: Uri? = null): ImportResult {
        removeDuplicates()
        val roots = (context.contentResolver.persistedUriPermissions.filter { it.isReadPermission }.map { it.uri } + listOfNotNull(extraRoot))
            .distinct().filter { DocumentsContract.isTreeUri(it) }.sortedBy { it.toString().length }

        val plans = LinkedHashMap<String, BookPlan>()
        val coveredRoots = mutableListOf<String>()
        for (root in roots) {
            val dir = DocumentFile.fromTreeUri(context, root) ?: continue
            if (!dir.exists() || !dir.canRead()) continue
            val tree = node(dir)
            countProtected(tree)
            val found = LibraryScanner.plan(tree)
            // An empty result can mean storage isn't available, so it never removes anything.
            if (found.isNotEmpty()) coveredRoots += treeKey(root)
            found.forEach { p -> plans.putIfAbsent(fileKey(doc(p.files.first()).uri.toString()), p) }
        }

        val byFile = dao.allFiles().groupBy { fileKey(it.uri) }.mapValues { (_, v) -> v.first().bookId }
        val matched = HashSet<Long>()
        val fresh = mutableListOf<BookPlan>()
        val books = dao.allBooks().associateBy { it.id }

        for (plan in plans.values) {
            val keys = plan.files.map { fileKey(doc(it).uri.toString()) }
            val ids = keys.mapNotNull { byFile[it] }.distinct()
            if (ids.isEmpty()) {
                if (keys.none { it in removedFiles }) fresh += plan
                continue
            }
            val keep = ids.maxWith(compareBy({ books[it]?.positionMs ?: 0 }, { books[it]?.lastPlayedAt ?: 0 }))
            ids.filter { it != keep }.forEach { deleteBook(it) }
            matched += keep
            refreshExisting(keep, plan, keys, ids.size > 1)
            if (applyFolderCover(keep, plan)) updated++
        }

        val missing = dao.allBooks().filter { it.id !in matched }.mapNotNull { book ->
            val files = dao.files(book.id)
            val under = files.isNotEmpty() && files.all { f -> coveredRoots.any { fileKey(f.uri).startsWith("$it/") } }
            if (under) book to files else null
        }.toMutableList()

        for (plan in fresh) {
            // A book that was moved or renamed keeps its place, bookmarks, and chapter edits.
            val probes = plan.files.mapNotNull { probe(it) }
            val moved = missing.firstOrNull { (_, files) ->
                files.size == probes.size && files.zip(probes).all { (f, p) -> abs(f.durationMs - p.durationMs) < 2000 } &&
                    fileNameOf(files.first().uri) == plan.files.first().name
            }
            if (moved != null) {
                missing.remove(moved)
                saveFiles(moved.first.id, probes)
                applyFolderCover(moved.first.id, plan)
                updated++
            } else {
                addBook(plan, probes)
            }
        }
        for ((book, files) in missing) {
            // If the same file was already added from its new location, keep your place on that copy.
            val copy = dao.allBooks().filter { it.id != book.id }.firstOrNull { other ->
                val theirs = dao.files(other.id)
                theirs.size == files.size && theirs.zip(files).all { (a, b) -> fileNameOf(a.uri) == fileNameOf(b.uri) && abs(a.durationMs - b.durationMs) < 2000 }
            }
            if (copy != null) {
                val newer = book.lastPlayedAt > copy.lastPlayedAt
                dao.update(copy.copy(
                    positionMs = if (newer) book.positionMs else copy.positionMs,
                    lastPlayedAt = maxOf(book.lastPlayedAt, copy.lastPlayedAt),
                    finished = if (newer) book.finished else copy.finished,
                    speed = if (newer) book.speed else copy.speed,
                    coverPath = copy.coverPath ?: book.coverPath,
                ))
                updated++
            } else removed++
            deleteBook(book.id)
        }
        consolidate()
        return result()
    }

    private suspend fun refreshExisting(id: Long, plan: BookPlan, keys: List<String>, merged: Boolean) {
        val book = dao.book(id) ?: return
        val current = dao.files(id).map { fileKey(it.uri) }
        if (current != keys) {
            val probes = plan.files.mapNotNull { probe(it) }
            if (probes.size == plan.files.size) saveFiles(id, probes)
        }
        var next = dao.book(id) ?: return
        if (!book.userEdited) {
            val g = BookNames.describe(plan.files.first().name, plan.bookFolder, usableSeries(plan.seriesFolder, book.author), book.title)
            next = next.copy(
                title = g.title,
                series = g.series,
                seriesNumber = g.number ?: next.seriesNumber,
                year = g.year ?: next.year,
            )
        }
        if (next != dao.book(id)) {
            dao.update(next)
            updated++
        } else if (merged) updated++
    }

    /** A picture in the book's folder becomes its cover, unless you've chosen one yourself. */
    private suspend fun applyFolderCover(id: Long, plan: BookPlan): Boolean {
        val book = dao.book(id) ?: return false
        if (book.coverPath?.substringAfterLast('/')?.startsWith("custom_") == true) return false
        val image = plan.images.firstOrNull() ?: return false
        val d = doc(image)
        val stamp = (d.uri.toString() + d.lastModified() + d.length()).hashCode().toUInt()
        val target = Covers.dir(context).resolve("folder_${id}_$stamp.img")
        if (book.coverPath == target.absolutePath && target.exists()) return false
        val copied = runCatching {
            context.contentResolver.openInputStream(d.uri)!!.use { input -> target.outputStream().use { input.copyTo(it) } }
        }.isSuccess
        if (!copied) return false
        book.coverPath?.let { old -> runCatching { File(old).delete() } }
        dao.update(book.copy(coverPath = target.absolutePath))
        return true
    }

    private suspend fun saveFiles(id: Long, probes: List<Probe>) {
        dao.deleteFiles(id)
        dao.insertFiles(probes.mapIndexed { i, p -> BookFile(id, i, doc(p.node).uri.toString(), p.durationMs) })
        dao.book(id)?.let { dao.update(it.copy(durationMs = probes.sumOf { p -> p.durationMs })) }
    }

    private suspend fun removeDuplicates() {
        val groups = dao.allFiles().groupBy { fileKey(it.uri) }.values.filter { g -> g.map { it.bookId }.toSet().size > 1 }
        val books = dao.allBooks().associateBy { it.id }
        val gone = HashSet<Long>()
        groups.forEach { group ->
            val ids = group.map { it.bookId }.distinct().filter { it !in gone }
            if (ids.size < 2) return@forEach
            val keep = ids.maxWith(compareBy({ books[it]?.positionMs ?: 0 }, { books[it]?.lastPlayedAt ?: 0 }))
            ids.filter { it != keep }.forEach { deleteBook(it); gone += it }
            updated++
        }
    }

    private suspend fun deleteBook(id: Long) {
        dao.deleteChapters(id)
        dao.deleteBookmarks(id)
        dao.deleteHistory(id)
        dao.deleteFiles(id)
        dao.deleteBook(id)
    }

    private suspend fun consolidate() {
        val books = dao.allBooks()
        val files = dao.allFiles().groupBy { it.bookId }
        val items = books.map { b ->
            val fileName = Uri.decode(files[b.id]?.firstOrNull()?.uri.orEmpty()).substringAfterLast('/')
            SeriesConsolidator.Item(b.id, b.series, b.seriesNumber, "${b.title} $fileName", b.userEdited)
        }
        val assigned = SeriesConsolidator.assign(items)
        books.forEach { b ->
            if (b.userEdited) return@forEach
            val series = assigned[b.id]
            val next = b.copy(series = series, title = BookNames.withoutSeries(b.title, series))
            if (next != b) dao.update(next)
        }
    }

    private fun usableSeries(folder: String?, author: String): String? =
        folder?.takeIf { author.isBlank() || SeriesNames.core(it) != SeriesNames.core(author) }

    private fun keepAccess(uri: Uri) {
        runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }

    private fun node(d: DocumentFile): ScanNode =
        ScanNode(d.name.orEmpty(), d.isDirectory, d) { d.listFiles().map { node(it) } }

    private fun doc(n: ScanNode) = n.handle as DocumentFile

    private fun countProtected(n: ScanNode) {
        n.children.forEach {
            if (it.isDirectory) countProtected(it)
            else if (LibraryScanner.ext(it.name) in LibraryScanner.protectedTypes) protectedCount++
        }
    }

    private class Probe(
        val node: ScanNode, val durationMs: Long, val title: String?, val artist: String?, val album: String?,
        val year: Int?, val track: Int?, val picture: ByteArray?,
    )

    private fun probe(node: ScanNode): Probe? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(context, doc(node).uri)
            fun tag(k: Int) = r.extractMetadata(k)?.takeIf { it.isNotBlank() }
            Probe(
                node,
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

    private suspend fun addBook(plan: BookPlan, probed: List<Probe>? = null) {
        val probes = probed ?: plan.files.mapNotNull { probe(it) }
        if (probes.isEmpty()) { failed++; return }
        val ordered = order(probes, plan.playlist)
        val key = fileKey(doc(ordered.first().node).uri.toString())
        if (dao.bookByKey(key) != null) return

        val first = ordered.first()
        val tagTitle = if (ordered.size > 1) first.album else first.title
        val g = BookNames.describe(first.node.name, plan.bookFolder, usableSeries(plan.seriesFolder, first.artist.orEmpty()), tagTitle)
        val id = dao.insert(
            Book(
                sourceKey = key,
                title = g.title,
                author = first.artist.orEmpty(),
                series = g.series,
                seriesNumber = g.number,
                year = g.year ?: first.year,
                durationMs = ordered.sumOf { it.durationMs },
            ),
        )
        dao.insertFiles(ordered.mapIndexed { i, p -> BookFile(id, i, doc(p.node).uri.toString(), p.durationMs) })

        val cover = ordered.firstNotNullOfOrNull { it.picture }?.let { saveCover(id, it) }
        if (cover != null) dao.book(id)?.let { dao.update(it.copy(coverPath = cover)) }
        applyFolderCover(id, plan)

        val chapters = readChapters(ordered, plan.cue)
        dao.replaceSet(id, ChapterSet.ORIGINAL, chapters)
        dao.replaceSet(id, ChapterSet.CURRENT, chapters)
        added++
    }

    private fun order(probes: List<Probe>, playlist: ScanNode?): List<Probe> {
        if (playlist != null) {
            val names = runCatching {
                context.contentResolver.openInputStream(doc(playlist).uri)!!.bufferedReader().use { M3u.parse(it.readText()) }
                    .map { it.substringAfterLast('/').substringAfterLast('\\') }
            }.getOrDefault(emptyList())
            val byName = probes.associateBy { it.node.name }
            val listed = names.mapNotNull { byName[it] }
            if (listed.size == probes.size) return listed
        }
        val tracks = probes.map { it.track }
        if (tracks.all { it != null } && tracks.toSet().size == tracks.size) return probes.sortedBy { it.track }
        return probes.sortedWith(compareBy(NaturalOrder) { it.node.name })
    }

    private fun readChapters(ordered: List<Probe>, cue: ScanNode?): List<Chapter> {
        val out = mutableListOf<Chapter>()
        var offset = 0L
        ordered.forEach { p ->
            val parsed = cue?.let { c ->
                runCatching {
                    context.contentResolver.openInputStream(doc(c).uri)!!.bufferedReader().use { CueSheet.parse(it.readText()) }
                }.getOrNull()
            }?.takeIf { it.isNotEmpty() } ?: readEmbedded(doc(p.node).uri)
            if (parsed.isEmpty()) {
                val label = p.title ?: p.node.name.substringBeforeLast('.')
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

    private fun fileNameOf(uri: String) = Uri.decode(uri).substringAfterLast('/')

    private fun treeKey(uri: Uri): String = "${uri.authority}|${DocumentsContract.getTreeDocumentId(uri)}"

    companion object {
        /** The same file reached through different added folders has different URIs but one document id. */
        fun fileKey(uri: String): String = runCatching {
            val u = Uri.parse(uri)
            "${u.authority}|${DocumentsContract.getDocumentId(u)}"
        }.getOrDefault(uri)
    }
}

object Covers {
    fun dir(context: Context): File = File(context.filesDir, "covers").also { it.mkdirs() }

    fun copy(context: Context, uri: Uri, id: Long): String? = runCatching {
        val target = dir(context).resolve("custom_${id}_${System.currentTimeMillis()}.img")
        context.contentResolver.openInputStream(uri)!!.use { input -> target.outputStream().use { input.copyTo(it) } }
        target.absolutePath
    }.getOrNull()
}
