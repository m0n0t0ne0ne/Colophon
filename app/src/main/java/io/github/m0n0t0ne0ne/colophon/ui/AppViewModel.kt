package io.github.m0n0t0ne0ne.colophon.ui

import android.app.Application
import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.asFlow
import androidx.lifecycle.viewModelScope
import androidx.palette.graphics.Palette
import androidx.work.WorkInfo
import androidx.work.WorkManager
import io.github.m0n0t0ne0ne.colophon.ColophonApplication
import io.github.m0n0t0ne0ne.colophon.analyzer.AnalyzeWorker
import io.github.m0n0t0ne0ne.colophon.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class BackgroundMode(val label: String) { Art("Book art"), Color("Art color"), Black("Black") }

data class ArtColors(val tone: Color, val accent: Color)

data class AnalysisRun(val bookId: Long, val state: WorkInfo.State, val stage: Int, val percent: Int, val detail: String)

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val context: Context get() = getApplication()
    private val dao = ColophonDatabase.get(app).dao()
    private val prefs = app.getSharedPreferences("settings", Context.MODE_PRIVATE)
    val hub = (app as ColophonApplication).hub

    val books: StateFlow<List<Book>> = dao.books().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    init {
        // One-time cleanup of jump-back entries left at the very start or end of a book.
        viewModelScope.launch { dao.pruneTrivialHistory() }
    }
    val reviews: StateFlow<List<Long>> = dao.booksWithSuggestions().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val message = MutableStateFlow<String?>(null)
    val importing = MutableStateFlow(false)
    val previewing = MutableStateFlow(false)
    private var previewReturn: Long? = null

    val backgroundMode = MutableStateFlow(
        runCatching { BackgroundMode.valueOf(prefs.getString("bg", "Art")!!) }.getOrDefault(BackgroundMode.Art),
    )
    val backgroundStrength = MutableStateFlow(prefs.getFloat("bgStrength", 0.6f))
    val onlyCharging = MutableStateFlow(prefs.getBoolean("charging", true))
    val fullScan = MutableStateFlow(prefs.getBoolean("fullScan", false))

    val analysis: StateFlow<List<AnalysisRun>> = WorkManager.getInstance(app)
        .getWorkInfosByTagLiveData(AnalyzeWorker.TAG).asFlow()
        .map { infos ->
            infos.filter { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.FAILED }.mapNotNull { w ->
                val id = w.tags.firstOrNull { it.startsWith("book-") }?.removePrefix("book-")?.toLongOrNull() ?: return@mapNotNull null
                AnalysisRun(
                    id, w.state,
                    w.progress.getInt(AnalyzeWorker.KEY_STAGE, 1),
                    w.progress.getInt(AnalyzeWorker.KEY_PERCENT, 0),
                    w.progress.getString(AnalyzeWorker.KEY_DETAIL) ?: w.outputData.getString(AnalyzeWorker.KEY_DETAIL) ?: "",
                )
            }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun importFolder(uri: Uri) = runImport { Importer(context, dao, removedFiles()).importFolder(uri) }
    fun importFiles(uris: List<Uri>) = runImport { Importer(context, dao, removedFiles()).importFiles(uris) }
    fun refreshLibrary() = runImport { Importer(context, dao, removedFiles()).refresh() }

    private fun removedFiles(): Set<String> = prefs.getStringSet("removedFiles", emptySet()).orEmpty()

    private fun runImport(block: suspend () -> ImportResult) {
        if (importing.value) return
        viewModelScope.launch {
            importing.value = true
            val result = runCatching { withContext(Dispatchers.IO) { block() } }
            importing.value = false
            message.value = result.fold({ it.summary() }, { "The library couldn't be updated: ${it.message}" })
        }
    }

    /** Set when the media controls ask for the player; ColophonApp opens it once a book is known. */
    val openPlayerRequested = kotlinx.coroutines.flow.MutableStateFlow(false)

    fun play(bookId: Long, autoplay: Boolean = true, at: Long? = null) {
        viewModelScope.launch { hub.open(bookId, autoplay, at) }
    }

    /** Plays a moment for checking, then returns to where you were listening when it stops. */
    fun startPreview(bookId: Long, at: Long) {
        viewModelScope.launch {
            if (!previewing.value) {
                val s = hub.state.value
                previewReturn = if (s.bookId == bookId) s.positionMs else dao.book(bookId)?.positionMs
            }
            previewing.value = true
            hub.open(bookId, true, at)
        }
    }

    fun stopPreview() {
        if (!previewing.value) return
        previewing.value = false
        hub.pause()
        previewReturn?.let { hub.seekTo(it) }
        previewReturn = null
    }

    fun setBackground(mode: BackgroundMode) {
        backgroundMode.value = mode
        prefs.edit().putString("bg", mode.name).apply()
    }

    fun setStrength(value: Float) {
        backgroundStrength.value = value
        prefs.edit().putFloat("bgStrength", value).apply()
    }

    fun setOnlyCharging(value: Boolean) {
        onlyCharging.value = value
        prefs.edit().putBoolean("charging", value).apply()
    }

    fun setFullScan(value: Boolean) {
        fullScan.value = value
        prefs.edit().putBoolean("fullScan", value).apply()
    }

    fun analyze(bookId: Long) = AnalyzeWorker.enqueue(context, bookId, onlyCharging.value, fullScan.value)

    fun cancelAnalysis(bookId: Long) {
        WorkManager.getInstance(context).cancelUniqueWork("analyze-$bookId")
    }

    fun clearFailedRuns() { WorkManager.getInstance(context).pruneWork() }

    fun bookFlow(id: Long) = dao.bookFlow(id)
    fun chapters(id: Long, set: String) = dao.chaptersFlow(id, set)
    fun bookmarks(id: Long) = dao.bookmarks(id)
    fun history(id: Long) = dao.history(id)

    /** Everything the file's own metadata reports, read fresh from each audio file. */
    suspend fun fileTags(bookId: Long): List<FileTags> = withContext(Dispatchers.IO) {
        val keys = listOf(
            "Title" to MediaMetadataRetriever.METADATA_KEY_TITLE,
            "Album" to MediaMetadataRetriever.METADATA_KEY_ALBUM,
            "Album artist" to MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST,
            "Artist" to MediaMetadataRetriever.METADATA_KEY_ARTIST,
            "Author" to MediaMetadataRetriever.METADATA_KEY_AUTHOR,
            "Composer" to MediaMetadataRetriever.METADATA_KEY_COMPOSER,
            "Writer" to MediaMetadataRetriever.METADATA_KEY_WRITER,
            "Genre" to MediaMetadataRetriever.METADATA_KEY_GENRE,
            "Year" to MediaMetadataRetriever.METADATA_KEY_YEAR,
            "Date" to MediaMetadataRetriever.METADATA_KEY_DATE,
            "Track" to MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER,
            "Track count" to MediaMetadataRetriever.METADATA_KEY_NUM_TRACKS,
            "Disc" to MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER,
            "Type" to MediaMetadataRetriever.METADATA_KEY_MIMETYPE,
            "Bitrate" to MediaMetadataRetriever.METADATA_KEY_BITRATE,
        )
        dao.files(bookId).map { file ->
            val retriever = MediaMetadataRetriever()
            val rows = buildList {
                try {
                    retriever.setDataSource(context, Uri.parse(file.uri))
                    keys.forEach { (label, key) ->
                        retriever.extractMetadata(key)?.takeIf { it.isNotBlank() }?.let { value ->
                            add(TagRow(label, prettyTag(label, value)))
                        }
                    }
                } catch (_: Exception) {
                } finally {
                    runCatching { retriever.release() }
                }
                add(TagRow("Length", formatHms(file.durationMs)))
            }
            FileTags(Uri.decode(file.uri).substringAfterLast('/'), rows)
        }
    }

    private fun prettyTag(label: String, value: String): String = when (label) {
        "Bitrate" -> value.toLongOrNull()?.let { "${it / 1000} kbps" } ?: value
        else -> value.trim()
    }

    private fun formatHms(ms: Long): String {
        val t = ms.coerceAtLeast(0) / 1000
        return "%d:%02d:%02d".format(t / 3600, (t % 3600) / 60, t % 60)
    }
    suspend fun chapter(id: Long) = dao.chapter(id)

    fun addBookmark(bookId: Long, positionMs: Long) {
        viewModelScope.launch {
            dao.insertBookmark(Bookmark(bookId = bookId, positionMs = positionMs, createdAt = System.currentTimeMillis()))
            message.value = "Bookmark added."
        }
    }

    fun deleteBookmark(id: Long) { viewModelScope.launch { dao.deleteBookmark(id) } }
    fun saveChapter(chapter: Chapter) { viewModelScope.launch { dao.updateChapter(chapter) } }
    fun deleteChapter(id: Long) { viewModelScope.launch { dao.deleteChapter(id) } }

    fun addChapter(bookId: Long, set: String, startMs: Long) {
        viewModelScope.launch {
            dao.insertChapters(listOf(Chapter(bookId = bookId, chapterSet = set, startMs = startMs, title = "New chapter", kind = "OTHER")))
        }
    }

    fun mergeWithNext(chapter: Chapter) {
        viewModelScope.launch {
            val list = dao.chapters(chapter.bookId, chapter.chapterSet)
            val next = list.getOrNull(list.indexOfFirst { it.id == chapter.id } + 1) ?: return@launch
            dao.deleteChapter(next.id)
        }
    }

    /** Removes this marker, so its audio becomes part of the chapter before it. */
    fun mergeWithPrevious(chapter: Chapter) {
        viewModelScope.launch {
            val list = dao.chapters(chapter.bookId, chapter.chapterSet)
            val previous = list.getOrNull(list.indexOfFirst { it.id == chapter.id } - 1) ?: return@launch
            dao.deleteChapter(chapter.id)
            message.value = "Merged into ${previous.title}."
        }
    }

    fun hideFromContinue(book: Book) {
        viewModelScope.launch {
            if (hub.state.value.bookId == book.id) hub.pause()
            dao.setHidden(book.id, true)
            message.value = "Removed from Continue listening. Your place is saved."
        }
    }

    fun setAnalyzed(bookId: Long, analyzed: Boolean) {
        viewModelScope.launch { dao.setAnalyzedAt(bookId, if (analyzed) System.currentTimeMillis() else 0) }
    }

    fun resetChapters(bookId: Long) {
        viewModelScope.launch { dao.replaceSet(bookId, ChapterSet.CURRENT, dao.chapters(bookId, ChapterSet.ORIGINAL)) }
    }

    fun acceptSuggestions(bookId: Long) {
        viewModelScope.launch {
            val list = dao.chapters(bookId, ChapterSet.SUGGESTED).map { it.copy(reason = null) }
            dao.replaceSet(bookId, ChapterSet.CURRENT, list)
            dao.clearSet(bookId, ChapterSet.SUGGESTED)
            dao.book(bookId)?.let { dao.update(it.copy(analyzedAt = System.currentTimeMillis())) }
            message.value = "Chapters saved. Your original file is unchanged."
        }
    }

    fun discardSuggestions(bookId: Long) { viewModelScope.launch { dao.clearSet(bookId, ChapterSet.SUGGESTED) } }

    fun saveBook(book: Book, coverForSeries: Boolean) {
        viewModelScope.launch {
            dao.update(book.copy(userEdited = true))
            if (coverForSeries && book.series != null) {
                dao.booksInSeries(book.series).forEach { if (it.id != book.id) dao.update(it.copy(coverPath = book.coverPath)) }
            }
        }
    }

    fun copyCover(uri: Uri, bookId: Long, done: (String?) -> Unit) {
        viewModelScope.launch { done(withContext(Dispatchers.IO) { Covers.copy(context, uri, bookId) }) }
    }

    fun removeBook(bookId: Long) { viewModelScope.launch { removeBookNow(bookId) } }

    /** Removes every book in a series, the app's stand-in for removing an imported folder. */
    fun removeSeries(name: String) {
        viewModelScope.launch {
            dao.booksInSeries(name).forEach { removeBookNow(it.id) }
            message.value = "Removed \u201C$name\u201D. Your audio files are untouched."
        }
    }

    private suspend fun removeBookNow(bookId: Long) {
        hub.forget(bookId)
        cancelAnalysis(bookId)
        val cover = dao.book(bookId)?.coverPath
        // Remembered so a refresh doesn't bring back a book you removed on purpose.
        val keys = dao.files(bookId).map { Importer.fileKey(it.uri) }
        prefs.edit().putStringSet("removedFiles", removedFiles() + keys).apply()
        dao.deleteChapters(bookId)
        dao.deleteBookmarks(bookId)
        dao.deleteHistory(bookId)
        dao.deleteFiles(bookId)
        dao.deleteBook(bookId)
        cover?.let { runCatching { java.io.File(it).delete() } }
    }

    suspend fun artColors(path: String?): ArtColors = withContext(Dispatchers.Default) {
        val fallback = ArtColors(Color(0xFF2A2D31), C.Accent)
        if (path == null) return@withContext fallback
        runCatching {
            val options = BitmapFactory.Options().apply { inSampleSize = 4 }
            val bitmap = BitmapFactory.decodeFile(path, options) ?: return@withContext fallback
            val palette = Palette.from(bitmap).generate()
            val tone = palette.getDarkMutedColor(palette.getDominantColor(0xFF2A2D31.toInt()))
            val accent = palette.getLightVibrantColor(palette.getLightMutedColor(C.Accent.value.toLong().let { 0xFF9CC2DE.toInt() }))
            ArtColors(Color(tone), Color(accent))
        }.getOrDefault(fallback)
    }
}

data class TagRow(val label: String, val value: String)
data class FileTags(val name: String, val rows: List<TagRow>)
