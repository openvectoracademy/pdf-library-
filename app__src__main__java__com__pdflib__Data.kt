package com.pdflib

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "folders", indices = [Index("parentId")])
data class FolderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val parentId: Long? = null,
    val color: Int = 0,
    val created: Long = System.currentTimeMillis(),
    /** Pinned folders always stay above the others (they can still be reordered among themselves). */
    @ColumnInfo(defaultValue = "0") val pinned: Boolean = false,
    /** Manual position inside its group (pinned / not pinned). 0 = never ordered by hand, falls back to A-Z. */
    @ColumnInfo(defaultValue = "0") val sortIndex: Int = 0,
    /** Saved folders also show up in the Saved tab, like starred PDFs. */
    @ColumnInfo(defaultValue = "0") val favorite: Boolean = false,
    /** "Removed from Shelf": the folder and everything inside it stay hidden until restored from Settings. */
    @ColumnInfo(defaultValue = "0") val removed: Boolean = false,
    /** Set on folders Shelf created while mirroring a linked phone folder: "<link key>|<path on the phone>". Lets rescans find them again. */
    val link: String? = null
)

/** A phone folder that feeds a Shelf folder (target null = the Library itself). */
@Entity(tableName = "links", indices = [Index(value = ["uri"], unique = true)])
data class LinkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uri: String,
    val target: Long? = null,
    /** LINK_MIRROR: phone subfolders become Shelf folders. LINK_FLAT: every PDF goes straight into the target. */
    val mode: Int = 0,
    val name: String,
    val created: Long = System.currentTimeMillis()
)

@Entity(tableName = "pdfs", indices = [Index(value = ["uri"], unique = true), Index("folderId")])
data class PdfEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uri: String,
    val name: String,
    val size: Long,
    val modified: Long,
    val folderId: Long? = null,
    val favorite: Boolean = false,
    val lastPage: Int = 0,
    val pageCount: Int = 0,
    val lastOpened: Long = 0,
    /** A name chosen inside Shelf. Used when the file on the phone can't be renamed (read-only access). */
    val label: String? = null,
    /** "Removed from Shelf": hidden everywhere but kept, so a rescan never brings it back and Undo/Restore is lossless. */
    @ColumnInfo(defaultValue = "0") val hidden: Boolean = false,
    /** The file wasn't found on the last scan. The row (folder, stars, bookmarks, progress) is kept, never auto-deleted. */
    @ColumnInfo(defaultValue = "0") val missing: Boolean = false,
    @ColumnInfo(defaultValue = "0") val pinned: Boolean = false,
    @ColumnInfo(defaultValue = "0") val sortIndex: Int = 0,
    /** A fingerprint of the file's content ("-" = couldn't be read). Survives renames, so a renamed file is always recognised. */
    val fp: String? = null,
    /** When the file was moved to Shelf's Trash (0 = not in the trash). The file waits in a hidden folder on the phone. */
    @ColumnInfo(defaultValue = "0") val trashed: Long = 0,
    /** Where the file lived before it went to the Trash (a document id), so Restore can put it back. */
    val origDir: String? = null
)

@Entity(
    tableName = "bookmarks",
    foreignKeys = [ForeignKey(entity = PdfEntity::class, parentColumns = ["id"], childColumns = ["pdfId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("pdfId")]
)
data class BookmarkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val pdfId: Long,
    val page: Int,
    val created: Long = System.currentTimeMillis()
)

@Dao
interface LibDao {
    @Query("SELECT * FROM folders ORDER BY name COLLATE NOCASE") fun folders(): Flow<List<FolderEntity>>
    @Query("SELECT * FROM pdfs ORDER BY name COLLATE NOCASE") fun pdfs(): Flow<List<PdfEntity>>
    @Query("SELECT * FROM bookmarks ORDER BY page") fun bookmarks(): Flow<List<BookmarkEntity>>

    @Query("SELECT * FROM pdfs") suspend fun pdfsOnce(): List<PdfEntity>
    @Query("SELECT * FROM folders") suspend fun foldersOnce(): List<FolderEntity>
    @Query("SELECT * FROM links ORDER BY created") fun links(): Flow<List<LinkEntity>>
    @Query("SELECT * FROM links ORDER BY created") suspend fun linksOnce(): List<LinkEntity>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertLink(l: LinkEntity): Long
    @Query("DELETE FROM links WHERE id=:id") suspend fun deleteLink(id: Long)
    @Query("SELECT * FROM bookmarks") suspend fun bookmarksOnce(): List<BookmarkEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertPdfs(list: List<PdfEntity>)
    @Query("DELETE FROM pdfs WHERE uri IN (:uris)") suspend fun deleteByUris(uris: List<String>)

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertFolder(f: FolderEntity): Long
    @Query("UPDATE folders SET name=:name, color=:color WHERE id=:id") suspend fun updateFolder(id: Long, name: String, color: Int)
    @Query("UPDATE folders SET parentId=:parent, sortIndex=0 WHERE id=:id") suspend fun moveFolder(id: Long, parent: Long?)
    @Query("UPDATE folders SET parentId=:newParent WHERE parentId=:old") suspend fun reparentChildren(old: Long, newParent: Long?)
    @Query("UPDATE pdfs SET folderId=:newF WHERE folderId=:old") suspend fun reparentPdfs(old: Long, newF: Long?)
    @Query("DELETE FROM folders WHERE id=:id") suspend fun deleteFolder(id: Long)
    @Query("DELETE FROM folders WHERE id IN (:ids)") suspend fun deleteFoldersByIds(ids: List<Long>)
    @Query("UPDATE folders SET favorite=:fav WHERE id IN (:ids)") suspend fun setFolderFavorite(ids: List<Long>, fav: Boolean)
    @Query("UPDATE folders SET removed=:r WHERE id IN (:ids)") suspend fun setFoldersRemoved(ids: List<Long>, r: Boolean)
    @Query("UPDATE folders SET parentId=:parent, sortIndex=0 WHERE id IN (:ids)") suspend fun moveFolders(ids: List<Long>, parent: Long?)

    @Query("UPDATE pdfs SET folderId=:folder, sortIndex=0 WHERE id IN (:ids)") suspend fun movePdfs(ids: List<Long>, folder: Long?)
    @Query("UPDATE pdfs SET favorite=:fav WHERE id IN (:ids)") suspend fun setFavorite(ids: List<Long>, fav: Boolean)
    @Query("UPDATE pdfs SET pinned=:pin, sortIndex=:idx WHERE id=:id") suspend fun setPdfPin(id: Long, pin: Boolean, idx: Int)
    @Query("UPDATE pdfs SET sortIndex=:idx WHERE id=:id") suspend fun setPdfOrder(id: Long, idx: Int)
    @Query("UPDATE folders SET pinned=:pin, sortIndex=:idx WHERE id=:id") suspend fun setFolderPin(id: Long, pin: Boolean, idx: Int)
    @Query("UPDATE folders SET sortIndex=:idx WHERE id=:id") suspend fun setFolderOrder(id: Long, idx: Int)
    @Query("UPDATE pdfs SET lastPage=:page, pageCount=:count WHERE id=:id") suspend fun progress(id: Long, page: Int, count: Int)
    @Query("UPDATE pdfs SET lastOpened=:t WHERE id=:id") suspend fun touch(id: Long, t: Long)

    @Query("UPDATE pdfs SET hidden=:h WHERE id IN (:ids)") suspend fun setHidden(ids: List<Long>, h: Boolean)
    @Query("UPDATE pdfs SET missing=:m WHERE id IN (:ids)") suspend fun setMissing(ids: List<Long>, m: Boolean)
    @Query("UPDATE pdfs SET label=:label WHERE id=:id") suspend fun setLabel(id: Long, label: String?)
    @Query("UPDATE pdfs SET uri=:uri, name=:name, size=:size, modified=:modified, missing=0 WHERE id=:id")
    suspend fun relink(id: Long, uri: String, name: String, size: Long, modified: Long)
    @Query("DELETE FROM pdfs WHERE id IN (:ids)") suspend fun deleteByIds(ids: List<Long>)
    @Query("UPDATE pdfs SET fp=:fp WHERE id=:id") suspend fun setFp(id: Long, fp: String?)
    @Query("UPDATE pdfs SET uri=:uri, trashed=:t, origDir=:orig WHERE id=:id") suspend fun setTrash(id: Long, uri: String, t: Long, orig: String?)
    @Query("UPDATE pdfs SET uri=:uri, trashed=0, origDir=NULL, missing=0 WHERE id=:id") suspend fun untrash(id: Long, uri: String)
    @Query("UPDATE folders SET link=:link WHERE id=:id") suspend fun setFolderLink(id: Long, link: String?)
    @Query("UPDATE folders SET name=:name, link=:link, parentId=:parent WHERE id=:id") suspend fun renameMirror(id: Long, name: String, link: String, parent: Long?)

    @Insert suspend fun addBookmark(b: BookmarkEntity)
    @Query("DELETE FROM bookmarks WHERE id=:id") suspend fun deleteBookmark(id: Long)

    @Query("UPDATE pdfs SET folderId=NULL") suspend fun resetPdfFolders()
    @Query("DELETE FROM bookmarks") suspend fun clearBookmarks()
    @Query("DELETE FROM folders") suspend fun clearFolders()
    @Query("UPDATE pdfs SET folderId=:folder, favorite=:fav, lastPage=:page, pageCount=:count, label=:label, hidden=:hidden, pinned=:pinned, sortIndex=:sortIndex WHERE id=:id")
    suspend fun restorePdf(id: Long, folder: Long?, fav: Boolean, page: Int, count: Int, label: String?, hidden: Boolean, pinned: Boolean, sortIndex: Int)
}

@Database(entities = [FolderEntity::class, PdfEntity::class, BookmarkEntity::class, LinkEntity::class], version = 7, exportSchema = false)
abstract class AppDb : RoomDatabase() {
    abstract fun dao(): LibDao

    companion object {
        @Volatile private var inst: AppDb? = null
        fun get(c: Context): AppDb = inst ?: synchronized(this) {
            inst ?: Room.databaseBuilder(c.applicationContext, AppDb::class.java, "library.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7) // never a destructive fallback: folders, stars and bookmarks must survive updates
                .build().also { inst = it }
        }
    }
}

private val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE pdfs ADD COLUMN label TEXT")
        db.execSQL("ALTER TABLE pdfs ADD COLUMN hidden INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE pdfs ADD COLUMN missing INTEGER NOT NULL DEFAULT 0")
    }
}

private val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE folders ADD COLUMN pinned INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE folders ADD COLUMN sortIndex INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE pdfs ADD COLUMN pinned INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE pdfs ADD COLUMN sortIndex INTEGER NOT NULL DEFAULT 0")
    }
}

private val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE folders ADD COLUMN favorite INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE folders ADD COLUMN removed INTEGER NOT NULL DEFAULT 0")
    }
}

private val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `links` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `uri` TEXT NOT NULL, `target` INTEGER, `mode` INTEGER NOT NULL, `name` TEXT NOT NULL, `created` INTEGER NOT NULL)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_links_uri` ON `links` (`uri`)")
        db.execSQL("ALTER TABLE folders ADD COLUMN link TEXT")
    }
}

private val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE pdfs ADD COLUMN fp TEXT")
    }
}

private val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE pdfs ADD COLUMN trashed INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE pdfs ADD COLUMN origDir TEXT")
    }
}
