package io.github.m0n0t0ne0ne.colophon.data

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(indices = [Index("sourceKey", unique = true)])
data class Book(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sourceKey: String,
    val title: String,
    val author: String = "",
    val series: String? = null,
    val seriesNumber: Double? = null,
    val year: Int? = null,
    val coverPath: String? = null,
    val durationMs: Long = 0,
    val positionMs: Long = 0,
    val lastPlayedAt: Long = 0,
    val finished: Boolean = false,
    val speed: Float = 1f,
    /** Set once you edit the details, so a library refresh never overwrites your changes. */
    @ColumnInfo(defaultValue = "0") val userEdited: Boolean = false,
    /** When analyzed chapters were last saved, or 0 if they never were. */
    @ColumnInfo(defaultValue = "0") val analyzedAt: Long = 0,
    /** Hidden from Continue listening until the book is played again. Its place is kept. */
    @ColumnInfo(defaultValue = "0") val hiddenFromContinue: Boolean = false,
)

@Entity(primaryKeys = ["bookId", "idx"])
data class BookFile(val bookId: Long, val idx: Int, val uri: String, val durationMs: Long)

object ChapterSet {
    const val ORIGINAL = "original"
    const val CURRENT = "current"
    const val SUGGESTED = "suggested"
}

@Entity(indices = [Index("bookId", "chapterSet")])
data class Chapter(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    val chapterSet: String,
    val startMs: Long,
    val title: String,
    val kind: String = "CHAPTER",
    val reason: String? = null,
)

@Entity(indices = [Index("bookId")])
data class Bookmark(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    val positionMs: Long,
    val createdAt: Long,
)

/** Recent places in a book, kept so a mistaken tap on the timeline can be undone. */
@Entity(indices = [Index("bookId")])
data class PositionHistory(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    val positionMs: Long,
    val at: Long,
)

@Dao
interface LibraryDao {
    @Query("SELECT * FROM Book ORDER BY title COLLATE NOCASE") fun books(): Flow<List<Book>>
    @Query("SELECT * FROM Book") suspend fun allBooks(): List<Book>
    @Query("SELECT * FROM BookFile ORDER BY bookId, idx") suspend fun allFiles(): List<BookFile>
    @Query("DELETE FROM Bookmark WHERE bookId = :id") suspend fun deleteBookmarks(id: Long)
    @Query("DELETE FROM PositionHistory WHERE bookId = :id") suspend fun deleteHistory(id: Long)
    @Query("SELECT * FROM Book WHERE id = :id") fun bookFlow(id: Long): Flow<Book?>
    @Query("SELECT * FROM Book WHERE id = :id") suspend fun book(id: Long): Book?
    @Query("SELECT * FROM Book WHERE sourceKey = :key") suspend fun bookByKey(key: String): Book?
    @Query("SELECT * FROM Book WHERE series = :series") suspend fun booksInSeries(series: String): List<Book>
    @Insert suspend fun insert(book: Book): Long
    @Update suspend fun update(book: Book)
    @Query("DELETE FROM Book WHERE id = :id") suspend fun deleteBook(id: Long)

    @Query("UPDATE Book SET positionMs = :pos, lastPlayedAt = :at, finished = :finished WHERE id = :id")
    suspend fun savePosition(id: Long, pos: Long, at: Long, finished: Boolean)
    @Query("UPDATE Book SET hiddenFromContinue = :hidden WHERE id = :id") suspend fun setHidden(id: Long, hidden: Boolean)
    @Query("UPDATE Book SET analyzedAt = :at WHERE id = :id") suspend fun setAnalyzedAt(id: Long, at: Long)
    @Query("UPDATE Book SET speed = :speed WHERE id = :id") suspend fun saveSpeed(id: Long, speed: Float)

    @Insert suspend fun insertFiles(files: List<BookFile>)
    @Query("SELECT * FROM BookFile WHERE bookId = :id ORDER BY idx") suspend fun files(id: Long): List<BookFile>
    @Query("DELETE FROM BookFile WHERE bookId = :id") suspend fun deleteFiles(id: Long)

    @Query("SELECT * FROM Chapter WHERE bookId = :id AND chapterSet = :set ORDER BY startMs")
    fun chaptersFlow(id: Long, set: String): Flow<List<Chapter>>
    @Query("SELECT * FROM Chapter WHERE bookId = :id AND chapterSet = :set ORDER BY startMs")
    suspend fun chapters(id: Long, set: String): List<Chapter>
    @Query("SELECT * FROM Chapter WHERE id = :id") suspend fun chapter(id: Long): Chapter?
    @Query("SELECT DISTINCT bookId FROM Chapter WHERE chapterSet = 'suggested'") fun booksWithSuggestions(): Flow<List<Long>>
    @Insert suspend fun insertChapters(chapters: List<Chapter>)
    @Update suspend fun updateChapter(chapter: Chapter)
    @Query("DELETE FROM Chapter WHERE id = :id") suspend fun deleteChapter(id: Long)
    @Query("DELETE FROM Chapter WHERE bookId = :id AND chapterSet = :set") suspend fun clearSet(id: Long, set: String)
    @Query("DELETE FROM Chapter WHERE bookId = :id") suspend fun deleteChapters(id: Long)

    @Transaction
    suspend fun replaceSet(id: Long, set: String, chapters: List<Chapter>) {
        clearSet(id, set)
        insertChapters(chapters.map { it.copy(id = 0, bookId = id, chapterSet = set) })
    }

    @Insert suspend fun insertBookmark(bookmark: Bookmark)
    @Query("SELECT * FROM Bookmark WHERE bookId = :id ORDER BY positionMs") fun bookmarks(id: Long): Flow<List<Bookmark>>
    @Query("DELETE FROM Bookmark WHERE id = :id") suspend fun deleteBookmark(id: Long)

    @Insert suspend fun insertHistory(entry: PositionHistory)
    @Query("SELECT * FROM PositionHistory WHERE bookId = :id ORDER BY at DESC LIMIT 10")
    fun history(id: Long): Flow<List<PositionHistory>>
    @Query("DELETE FROM PositionHistory WHERE bookId = :id AND id NOT IN (SELECT id FROM PositionHistory WHERE bookId = :id ORDER BY at DESC LIMIT 10)")
    suspend fun trimHistory(id: Long)
}

@Database(entities = [Book::class, BookFile::class, Chapter::class, Bookmark::class, PositionHistory::class], version = 4, exportSchema = false)
abstract class ColophonDatabase : RoomDatabase() {
    abstract fun dao(): LibraryDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE Book ADD COLUMN userEdited INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE Book ADD COLUMN analyzedAt INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE Book ADD COLUMN hiddenFromContinue INTEGER NOT NULL DEFAULT 0")
            }
        }

        @Volatile private var instance: ColophonDatabase? = null
        fun get(context: Context): ColophonDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, ColophonDatabase::class.java, "colophon.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                .build().also { instance = it }
        }
    }
}
