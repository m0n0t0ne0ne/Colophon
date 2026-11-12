package io.github.m0n0t0ne0ne.colophon.ui

import android.app.Application
import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import io.github.m0n0t0ne0ne.colophon.core.ChapterNumbering
import io.github.m0n0t0ne0ne.colophon.playback.Chime
import android.net.Uri
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.palette.graphics.Palette
import io.github.m0n0t0ne0ne.colophon.ColophonApplication
import io.github.m0n0t0ne0ne.colophon.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class BackgroundMode(val label: String) { Art("Book art"), Color("Art color"), Black("Black") }

data class ArtColors(
    val tone: Color,
    val accent: Color,
    val swatchesSoft: List<Color> = emptyList(),
    val swatchesVivid: List<Color> = emptyList(),
)

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val context: Context get() = getApplication()
    private val dao = ColophonDatabase.get(app).dao()
    private val prefs = app.getSharedPreferences("settings", Context.MODE_PRIVATE)
    val hub = (app as ColophonApplication).hub

    val books: StateFlow<List<Book>> = dao.books().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    init {
        // One-time cleanup of jump-back entries left at the very start or end of a book.
        viewModelScope.launch { dao.pruneTrivialHistory() }
        hub.longAwayRewindSec = prefs.getInt("resumeRewindSec", 15)
        hub.chimeSound = Chime.from(prefs.getString("sleepSound", null))
        hub.chimeVolume = prefs.getInt("sleepVolume", 85)
    }

    fun setSleepSound(sound: Chime) {
        sleepSound.value = sound
        hub.chimeSound = sound
        prefs.edit().putString("sleepSound", sound.name).apply()
    }

    fun setSleepVolume(percent: Int) {
        val v = percent.coerceIn(0, 100)
        sleepVolume.value = v
        hub.chimeVolume = v
        prefs.edit().putInt("sleepVolume", v).apply()
    }

    fun previewSound() = hub.preview()

    fun setResumeRewind(sec: Int) {
        resumeRewindSec.value = sec
        hub.longAwayRewindSec = sec
        prefs.edit().putInt("resumeRewindSec", sec).apply()
    }

    fun setSleepChime(on: Boolean) { sleepChime.value = on; prefs.edit().putBoolean("sleepChime", on).apply() }
    fun setSleepShake(on: Boolean) { sleepShake.value = on; prefs.edit().putBoolean("sleepShake", on).apply() }
    fun setSleepShakeMin(min: Int) { sleepShakeMin.value = min; prefs.edit().putInt("sleepShakeMin", min).apply() }

    /** Starts a sleep timer for a number of minutes, or clears it when null. */
    fun setSleep(minutes: Int?) {
        if (minutes == null) { hub.setSleep(null); return }
        hub.setSleep(minutes * 60_000L, chime = sleepChime.value, shakeExtendMs = shakeExtendMs(), choice = minutes.toString())
    }

    fun setSleepEndOfChapter() {
        hub.setSleep(0L, chime = sleepChime.value, shakeExtendMs = shakeExtendMs(), endOfChapter = true, choice = "chapter")
    }

    private fun shakeExtendMs(): Long = if (sleepShake.value) sleepShakeMin.value * 60_000L else 0L

    val message = MutableStateFlow<String?>(null)
    val importing = MutableStateFlow(false)
    val previewing = MutableStateFlow(false)
    private var previewReturn: Long? = null

    val backgroundMode = MutableStateFlow(
        runCatching { BackgroundMode.valueOf(prefs.getString("bg", "Art")!!) }.getOrDefault(BackgroundMode.Art),
    )
    val backgroundStrength = MutableStateFlow(prefs.getFloat("bgStrength", 0.6f))
    val resumeRewindSec = MutableStateFlow(prefs.getInt("resumeRewindSec", 15))
    val sleepChime = MutableStateFlow(prefs.getBoolean("sleepChime", true))
    val sleepShake = MutableStateFlow(prefs.getBoolean("sleepShake", false))
    val sleepShakeMin = MutableStateFlow(prefs.getInt("sleepShakeMin", 5))
    val sleepSound = MutableStateFlow(Chime.from(prefs.getString("sleepSound", null)))
    val sleepVolume = MutableStateFlow(prefs.getInt("sleepVolume", 85))

    // Picking a folder or files is a deliberate request to add them, so it ignores the "removed"
    // memory (otherwise a book removed earlier can never be added back) and then forgets any of those
    // files that are now in the library. Only the background refresh keeps honouring removals.
    fun importFolder(uri: Uri) = runImport { Importer(context, dao).importFolder(uri).also { forgetReturnedRemovals() } }
    fun importFiles(uris: List<Uri>) = runImport { Importer(context, dao).importFiles(uris).also { forgetReturnedRemovals() } }
    fun refreshLibrary() = runImport { Importer(context, dao, removedFiles()).refresh() }

    private fun removedFiles(): Set<String> = prefs.getStringSet("removedFiles", emptySet()).orEmpty()

    /** Drops files that are back in the library from the removed-memory so a later refresh keeps them. */
    private suspend fun forgetReturnedRemovals() {
        val present = dao.allFiles().map { Importer.fileKey(it.uri) }.toSet()
        val remembered = removedFiles()
        val pruned = remembered - present
        if (pruned.size != remembered.size) prefs.edit().putStringSet("removedFiles", pruned).apply()
    }

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

    /** Zero-pads the numbers in every chapter title to [width] digits, e.g. "2 - Name" to "002 - Name". */
    fun padChapterNumbers(bookId: Long, width: Int) {
        viewModelScope.launch {
            val chapters = dao.chapters(bookId, ChapterSet.CURRENT)
            val padded = ChapterNumbering.padTitles(chapters.map { it.title }, width)
            val changed = chapters.zip(padded).count { (c, t) -> c.title != t && run { dao.updateChapter(c.copy(title = t)); true } }
            message.value = if (changed > 0) "Padded $changed chapter numbers." else "No chapter numbers to pad."
        }
    }
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

    fun resetChapters(bookId: Long) {
        viewModelScope.launch { dao.replaceSet(bookId, ChapterSet.CURRENT, dao.chapters(bookId, ChapterSet.ORIGINAL)) }
    }

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
            val palette = Palette.from(bitmap).maximumColorCount(24).generate()
            val tone = palette.getDarkMutedColor(palette.getDominantColor(0xFF2A2D31.toInt()))
            val accent = palette.getLightVibrantColor(palette.getLightMutedColor(C.Accent.value.toLong().let { 0xFF9CC2DE.toInt() }))
            // Candidate progress colours drawn from the cover, ordered by how much of the cover they
            // occupy and de-duplicated by hue. Each base colour yields two renderings:
            //  - soft:  lifted toward white (gentle, reads easily but loses saturation)
            //  - vivid: brightened by raising value while keeping hue and saturation (truer to the art)
            val base = palette.swatches
                .sortedByDescending { it.population }
                .map { Color(it.rgb) }
                .distinctBy { farHue(it) }
                .take(8)
            ArtColors(Color(tone), Color(accent), base.map { soften(it) }, base.map { vivify(it) })
        }.getOrDefault(fallback)
    }

    // Lift a swatch toward white so even dark cover colours stay visible; gentle but desaturating.
    private fun soften(c: Color): Color {
        val l = c.luminance()
        return when {
            l < 0.18f -> lerp(c, Color.White, 0.45f)
            l < 0.35f -> lerp(c, Color.White, 0.22f)
            else -> c
        }
    }

    // Brighten by raising HSV value (and nudging saturation up) so the colour stays true to the cover.
    private fun vivify(c: Color): Color {
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(c.toArgb(), hsv)
        hsv[2] = hsv[2].coerceAtLeast(0.72f)
        hsv[1] = (hsv[1] * 1.1f).coerceAtMost(1f)
        return Color(android.graphics.Color.HSVToColor(hsv))
    }

    // Bucket hue + a coarse lightness band so near-identical swatches collapse to one choice.
    private fun farHue(c: Color): Int {
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(c.toArgb(), hsv)
        val sat = hsv[1]
        val hueBucket = if (sat < 0.12f) -1 else (hsv[0] / 30f).toInt()  // greys share one bucket
        return hueBucket * 10 + (hsv[2] * 3f).toInt()
    }

    val accentOverride = MutableStateFlow<Color?>(null)

    fun loadAccent(bookId: Long) {
        accentOverride.value = if (prefs.contains("accent_$bookId")) Color(prefs.getInt("accent_$bookId", 0)) else null
    }

    fun setAccent(bookId: Long, color: Color?) {
        accentOverride.value = color
        prefs.edit().apply { if (color == null) remove("accent_$bookId") else putInt("accent_$bookId", color.toArgb()) }.apply()
    }
}

data class TagRow(val label: String, val value: String)
data class FileTags(val name: String, val rows: List<TagRow>)
