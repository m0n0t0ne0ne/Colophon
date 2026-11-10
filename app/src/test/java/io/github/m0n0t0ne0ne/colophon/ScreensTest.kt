package io.github.m0n0t0ne0ne.colophon

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.m0n0t0ne0ne.colophon.data.Book
import io.github.m0n0t0ne0ne.colophon.data.Chapter
import io.github.m0n0t0ne0ne.colophon.playback.PlayerState
import io.github.m0n0t0ne0ne.colophon.ui.*
import androidx.compose.ui.graphics.Color
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Draws each main screen with sample data. Images land in app/build/screens. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xxhdpi")
class ScreensTest {
    @get:Rule val rule = createComposeRule()

    private fun art(name: String): String {
        val out = File.createTempFile(name, ".jpg")
        out.writeBytes(javaClass.getResourceAsStream("/fixtures/$name.jpg")!!.readBytes())
        return out.absolutePath
    }

    private val hours = 3_600_000L
    private fun sampleBooks(): List<Book> {
        val ember = art("ember")
        val titles = listOf("The Salt Road", "A Harbor of Ash", "The Glass Meridian", "Winter Cartography", "Last Light on the Fell")
        val series = titles.mapIndexed { i, t ->
            Book(id = i + 1L, sourceKey = "s$i", title = t, author = "A. N. Author", series = "The Long Tide", seriesNumber = i + 1.0, year = 2010 + i * 2,
                durationMs = 30 * hours, positionMs = if (i < 2) 30 * hours else if (i == 2) 23 * hours else 0, finished = i < 2, lastPlayedAt = i + 1L,
                coverPath = if (i == 2) ember else null)
        }
        return series + listOf(
            Book(id = 10, sourceKey = "a", title = "Tomorrow's Orchard", author = "R. Example", durationMs = 12 * hours),
            Book(id = 11, sourceKey = "b", title = "Field Notes", author = "J. Sample", durationMs = 9 * hours, positionMs = 2 * hours, lastPlayedAt = 1, coverPath = art("tide")),
            Book(id = 12, sourceKey = "c", title = "An Inland Sea", author = "M. Placeholder", durationMs = 15 * hours),
        )
    }

    private val chapters = listOf(
        "Opening credits", "Prologue: The Quiet Tide", "Chapter 1: Salt", "Chapter 2: Mapmakers", "Chapter 3: A Knot of Rope",
        "Chapter 4: Harbor", "Chapter 5: Winter Road", "Chapter 6: Lanterns", "Chapter 7", "Chapter 8: The Fell", "Chapter 9: Letters", "Epilogue: Home",
    ).mapIndexed { i, t -> Chapter(id = i + 1L, bookId = 3, chapterSet = "current", startMs = i * 2 * hours + i * 900_000L, title = t) }

    private fun shoot(name: String, wait: Long = 1500) {
        rule.waitForIdle()
        Thread.sleep(wait)
        rule.mainClock.advanceTimeBy(500)
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("build/screens/$name.png")
    }

    @Test fun library() {
        rule.setContent { ColophonTheme { LibraryContent(sampleBooks(), onOpenBook = {}, onOpenSeries = {}, onAddFolder = {}, onAddFiles = {}) } }
        shoot("library")
    }

    @Test fun emptyLibrary() {
        rule.setContent { ColophonTheme { LibraryContent(emptyList(), onOpenBook = {}, onOpenSeries = {}, onAddFolder = {}, onAddFiles = {}) } }
        shoot("library-empty")
    }

    @Test fun series() {
        rule.setContent { ColophonTheme { SeriesContent("The Long Tide", sampleBooks().filter { it.series != null }, {}, {}, {}) } }
        shoot("series")
    }

    private fun player(mode: BackgroundMode, name: String) {
        val book = sampleBooks()[2]
        val swatches = listOf(Color(0xFFF0A868), Color(0xFFE8604C), Color(0xFF9CC2DE), Color(0xFFBFA98C))
        val colors = ArtColors(Color(0xFF6B3A28), Color(0xFFF0A868), swatches, swatches)
        rule.setContent {
            ColophonTheme {
                PlayerContent(book, PlayerState(bookId = 3, positionMs = 23 * hours, durationMs = 30 * hours, playing = true), chapters, mode, 0.6f, colors, null, emptyList(), PlayerActions())
            }
        }
        shoot(name)
    }

    @Test fun playerArt() = player(BackgroundMode.Art, "player-art")
    @Test fun playerColor() = player(BackgroundMode.Color, "player-color")
    @Test fun playerBlack() = player(BackgroundMode.Black, "player-black")

    @Test fun chaptersScreen() {
        rule.setContent { ColophonTheme { ChaptersContent(chapters, chapters, emptyList(), 13 * hours, false, {}, {}, {}, {}, {}, {}, {}, {}, {}) } }
        shoot("chapters")
    }

    @Test fun reviewScreen() {
        val list = chapters.mapIndexed { i, c -> if (i in setOf(0, 4, 8)) c.copy(reason = listOf("Likely opening credits", "", "", "", "Spelling looks unusual", "", "", "", "Chapter 7 title was not heard")[i]) else c }
        rule.setContent { ColophonTheme { ReviewContent(list, {}, {}, {}, {}, {}, {}) } }
        shoot("review")
    }

    @Test fun editorScreen() {
        rule.setContent { ColophonTheme { EditorContent(chapters[6], 30 * hours, {}, {}, {}, {}, {}) } }
        shoot("editor")
    }

    @Test fun analyzerScreen() {
        val books = sampleBooks()
        rule.setContent {
            ColophonTheme {
                AnalyzerContent(books.take(5), listOf(AnalysisRun(3, androidx.work.WorkInfo.State.RUNNING, 3, 62, "Transcribing 44 of 71")), listOf(2L), true, {}, {}, {}, {}, {})
            }
        }
        shoot("analyzer")
    }
}
