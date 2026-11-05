package io.github.m0n0t0ne0ne.colophon

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import io.github.m0n0t0ne0ne.colophon.core.*
import io.github.m0n0t0ne0ne.colophon.data.ColophonDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LibraryTest {
    private fun file(name: String) = ScanNode(name, false)
    private fun dir(name: String, vararg kids: ScanNode) = ScanNode(name, true) { kids.toList() }

    private fun described(p: BookPlan) = BookNames.describe(p.files.first().name, p.bookFolder, p.seriesFolder, null)

    /** The folder layout from a real library: one book loose, the rest inside a series folder. */
    private val library = dir(
        "Audiobooks",
        dir("!Smart AudioBook Player Stats", file("stats.txt")),
        dir("2013 - A Memory of Light", file("2013 - A Memory of Light Wheel of Time, Book 14 (Unabridged).m4b")),
        dir(
            "A Wheel of Time",
            dir("06 Lord of Chaos", file("Lord of Chaos Wheel of Time, Book 6 (Unabridged).m4b")),
            dir("1996 - A Crown of Swords", file("A Crown of Swords Wheel of Time, Book 7 (Unabridged).m4b")),
            dir("1998 - The Path of Daggers", file("Path of Daggers Wheel of Time, Book 8 (Unabridged).m4b")),
            dir("2000 - Winter's Heart", file("Winter's Heart Wheel of Time, Book 9 (Unabridged).m4b")),
        ),
    )

    @Test fun bookFoldersAreBooksAndTheirParentIsTheSeries() {
        val plans = LibraryScanner.plan(library)
        assertEquals(5, plans.size)
        val books = plans.map(::described)
        val crown = books.first { it.number == 7.0 }
        assertEquals("A Crown of Swords", crown.title)
        assertEquals("A Wheel of Time", crown.series)
        assertEquals(1996, crown.year)
        val chaos = books.first { it.number == 6.0 }
        assertEquals("Lord of Chaos", chaos.title)
        assertEquals("A Wheel of Time", chaos.series)
        assertEquals("The Path of Daggers", books.first { it.number == 8.0 }.title)
    }

    @Test fun looseBookJoinsTheSeriesItNames() {
        val memory = described(LibraryScanner.plan(library).first { it.bookFolder == "2013 - A Memory of Light" })
        assertEquals("A Memory of Light", memory.title)
        assertNull(memory.series)
        assertEquals(14.0, memory.number!!, 0.0)
        val items = listOf(
            SeriesConsolidator.Item(1, "A Wheel of Time", 6.0, "Lord of Chaos", false),
            SeriesConsolidator.Item(2, "A Wheel of Time", 7.0, "A Crown of Swords", false),
            SeriesConsolidator.Item(3, null, 14.0, "A Memory of Light 2013 - A Memory of Light Wheel of Time, Book 14 (Unabridged).m4b", false),
            SeriesConsolidator.Item(4, null, null, "Dune Dune.m4b", false),
        )
        val r = SeriesConsolidator.assign(items)
        assertEquals("A Wheel of Time", r[3])
        assertNull(r[4])
    }

    @Test fun spellingVariantsBecomeOneSeries() {
        val r = SeriesConsolidator.assign(listOf(
            SeriesConsolidator.Item(1, "The Wheel of Time", 1.0, "", false),
            SeriesConsolidator.Item(2, "Wheel of Time", 2.0, "", false),
            SeriesConsolidator.Item(3, "The Wheel of Time", 3.0, "", false),
            SeriesConsolidator.Item(4, "My Own Name", 4.0, "wheel of time", true),
        ))
        assertEquals(setOf("The Wheel of Time"), setOf(r[1], r[2], r[3]))
        assertEquals("My Own Name", r[4])
    }

    @Test fun pickingTheSeriesFolderItselfStillWorks() {
        val p = LibraryScanner.plan(dir("A Wheel of Time", dir("2000 - Winter's Heart", file("Winter's Heart Wheel of Time, Book 9.m4b"))))
        assertEquals("A Wheel of Time", p.single().seriesFolder)
    }

    @Test fun aLibraryFolderIsNotASeries() {
        val p = LibraryScanner.plan(dir("My Stuff", file("Dune.m4b"), file("Piranesi.m4b"), dir("Hobbit", file("Hobbit.m4b"))))
        assertEquals(3, p.size)
        assertTrue(p.all { it.seriesFolder == null })
    }

    @Test fun numberedFilesAreOneBookAndDiscsMerge() {
        val parts = LibraryScanner.plan(dir("Books", dir("Dune", file("Dune Part 1.m4b"), file("Dune Part 2.m4b"))))
        assertEquals(2, parts.single().files.size)
        val discs = LibraryScanner.plan(dir("Books", dir("Dune", dir("CD 1", file("01.mp3")), dir("CD 2", file("01.mp3")))))
        assertEquals(2, discs.single().files.size)
        val tracks = LibraryScanner.plan(dir("Books", dir("Dune", file("Track 10.mp3"), file("Track 2.mp3"))))
        assertEquals(1, tracks.size)
    }

    @Test fun updatingKeepsExistingBooksAndPositions() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val path = context.getDatabasePath("migrate.db").also { it.parentFile?.mkdirs(); it.delete() }
        SQLiteDatabase.openOrCreateDatabase(path, null).use { db ->
            db.execSQL("CREATE TABLE IF NOT EXISTS `Book` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `sourceKey` TEXT NOT NULL, `title` TEXT NOT NULL, `author` TEXT NOT NULL, `series` TEXT, `seriesNumber` REAL, `year` INTEGER, `coverPath` TEXT, `durationMs` INTEGER NOT NULL, `positionMs` INTEGER NOT NULL, `lastPlayedAt` INTEGER NOT NULL, `finished` INTEGER NOT NULL, `speed` REAL NOT NULL)")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_Book_sourceKey` ON `Book` (`sourceKey`)")
            db.execSQL("CREATE TABLE IF NOT EXISTS `BookFile` (`bookId` INTEGER NOT NULL, `idx` INTEGER NOT NULL, `uri` TEXT NOT NULL, `durationMs` INTEGER NOT NULL, PRIMARY KEY(`bookId`, `idx`))")
            db.execSQL("CREATE TABLE IF NOT EXISTS `Chapter` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `bookId` INTEGER NOT NULL, `chapterSet` TEXT NOT NULL, `startMs` INTEGER NOT NULL, `title` TEXT NOT NULL, `kind` TEXT NOT NULL, `reason` TEXT)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_Chapter_bookId_chapterSet` ON `Chapter` (`bookId`, `chapterSet`)")
            db.execSQL("CREATE TABLE IF NOT EXISTS `Bookmark` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `bookId` INTEGER NOT NULL, `positionMs` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_Bookmark_bookId` ON `Bookmark` (`bookId`)")
            db.execSQL("CREATE TABLE IF NOT EXISTS `PositionHistory` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `bookId` INTEGER NOT NULL, `positionMs` INTEGER NOT NULL, `at` INTEGER NOT NULL)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_PositionHistory_bookId` ON `PositionHistory` (`bookId`)")
            db.execSQL("INSERT INTO Book (sourceKey,title,author,durationMs,positionMs,lastPlayedAt,finished,speed) VALUES ('k','Kept','',1000,500,1,0,1.25)")
            db.version = 1
        }
        val room = Room.databaseBuilder(context, ColophonDatabase::class.java, "migrate.db")
            .addMigrations(ColophonDatabase.MIGRATION_1_2).allowMainThreadQueries().build()
        val book = room.dao().allBooks().single()
        assertEquals(500L, book.positionMs)
        assertFalse(book.userEdited)
        room.close()
    }
}
