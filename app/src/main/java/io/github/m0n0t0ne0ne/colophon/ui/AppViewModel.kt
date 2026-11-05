package io.github.m0n0t0ne0ne.colophon.ui

import android.app.Application
import android.content.Context
import android.graphics.BitmapFactory
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
    val reviews: StateFlow<List<Long>> = dao.booksWithSuggestions().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val message = MutableStateFlow<String?>(null)
    val importing = MutableStateFlow(false)

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

    fun importFolder(uri: Uri) = runImport { Importer(context, dao).importFolder(uri) }
    fun importFiles(uris: List<Uri>) = runImport { Importer(context, dao).importFiles(uris) }

    private fun runImport(block: suspend () -> ImportResult) {
        viewModelScope.launch {
            importing.value = true
            val result = withContext(Dispatchers.IO) { block() }
            importing.value = false
            message.value = buildString {
                append(if (result.added == 1) "Added 1 book." else "Added ${result.added} books.")
                if (result.skippedProtected > 0) append(" Skipped ${result.skippedProtected} DRM-protected file(s).")
                if (result.failed > 0) append(" ${result.failed} couldn't be read.")
            }
        }
    }

    fun play(bookId: Long, autoplay: Boolean = true, at: Long? = null) {
        viewModelScope.launch { hub.open(bookId, autoplay, at) }
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

    fun resetChapters(bookId: Long) {
        viewModelScope.launch { dao.replaceSet(bookId, ChapterSet.CURRENT, dao.chapters(bookId, ChapterSet.ORIGINAL)) }
    }

    fun acceptSuggestions(bookId: Long) {
        viewModelScope.launch {
            val list = dao.chapters(bookId, ChapterSet.SUGGESTED).map { it.copy(reason = null) }
            dao.replaceSet(bookId, ChapterSet.CURRENT, list)
            dao.clearSet(bookId, ChapterSet.SUGGESTED)
            message.value = "Chapters saved. Your original file is unchanged."
        }
    }

    fun discardSuggestions(bookId: Long) { viewModelScope.launch { dao.clearSet(bookId, ChapterSet.SUGGESTED) } }

    fun saveBook(book: Book, coverForSeries: Boolean) {
        viewModelScope.launch {
            dao.update(book)
            if (coverForSeries && book.series != null) {
                dao.booksInSeries(book.series).forEach { if (it.id != book.id) dao.update(it.copy(coverPath = book.coverPath)) }
            }
        }
    }

    fun copyCover(uri: Uri, bookId: Long, done: (String?) -> Unit) {
        viewModelScope.launch { done(withContext(Dispatchers.IO) { Covers.copy(context, uri, bookId) }) }
    }

    fun removeBook(bookId: Long) {
        viewModelScope.launch {
            hub.forget(bookId)
            cancelAnalysis(bookId)
            dao.deleteChapters(bookId)
            dao.deleteFiles(bookId)
            dao.deleteBook(bookId)
        }
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
