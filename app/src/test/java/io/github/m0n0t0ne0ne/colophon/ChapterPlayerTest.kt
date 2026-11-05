package io.github.m0n0t0ne0ne.colophon

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import io.github.m0n0t0ne0ne.colophon.core.SectionMap
import io.github.m0n0t0ne0ne.colophon.playback.BookSections
import io.github.m0n0t0ne0ne.colophon.playback.ChapterPlayer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ChapterPlayerTest {
    private lateinit var raw: ExoPlayer
    private lateinit var player: ChapterPlayer
    // Two files of 100s; chapters start at 0, 60s and 150s, so the second chapter spans both files.
    private val files = listOf(100_000L, 100_000L)
    private val sections = BookSections(7, files, SectionMap(listOf(0, 60_000, 150_000), 200_000), listOf("One", "Two", "Three"))

    @Before fun setUp() {
        raw = ExoPlayer.Builder(ApplicationProvider.getApplicationContext()).build()
        player = ChapterPlayer(raw)
        val items = files.indices.map { i ->
            MediaItem.Builder().setMediaId("7:$i").setUri(Uri.parse("file:///none$i.mp3"))
                .setMediaMetadata(MediaMetadata.Builder().setTitle("The Book").setArtist("Author").build()).build()
        }
        raw.setMediaItems(items, 1, 20_000) // 120s into the book, 60s into chapter two
        player.setBook(sections)
    }

    @After fun tearDown() = raw.release()

    private fun global() = raw.currentMediaItemIndex * 100_000L + raw.currentPosition

    @Test fun reportsTheChapterNotTheFile() {
        assertEquals(60_000, player.currentPosition)
        assertEquals(90_000, player.duration)
        assertEquals("Two", player.mediaMetadata.title.toString())
        assertEquals("2 of 3 · The Book", player.mediaMetadata.artist.toString())
    }

    @Test fun seekBarIsRelativeToTheChapterAndCrossesFiles() {
        player.seekTo(10_000)
        assertEquals(0, raw.currentMediaItemIndex)
        assertEquals(70_000, global())
    }

    @Test fun nextAndPreviousMoveByChapter() {
        player.seekToNext()
        assertEquals(150_000, global())
        assertEquals("Three", player.mediaMetadata.title.toString())
        player.seekToNext() // already in the last chapter
        assertEquals(150_000, global())
        player.seekToPrevious() // right at the start, so the chapter before
        assertEquals(60_000, global())
    }

    @Test fun jumpsCrossFileBoundaries() {
        player.jump(-30_000)
        assertEquals(90_000, global())
        assertEquals(0, raw.currentMediaItemIndex)
    }

    @Test fun absoluteSeeksStayAbsolute() {
        player.seekTo(0, 5_000)
        assertEquals(5_000, global())
    }

    @Test fun skipButtonsAreAlwaysAvailable() {
        assertTrue(player.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT))
        assertTrue(player.availableCommands.contains(Player.COMMAND_SEEK_TO_PREVIOUS))
    }

    @Test fun passesThroughWithoutSections() {
        player.setBook(null)
        assertEquals(20_000, player.currentPosition)
        assertEquals("The Book", player.mediaMetadata.title.toString())
    }
}
