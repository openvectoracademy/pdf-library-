package com.pdflib

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "folders", indices = [Index("parentId")])
data class FolderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val parentId: Long? = null,
    val color: Int = 0,
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
    val lastOpened: Long = 0
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
    @Query("SELECT * FROM bookmarks") suspend fun bookmarksOnce(): List<BookmarkEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertPdfs(list: List<PdfEntity>)
    @Query("DELETE FROM pdfs WHERE uri IN (:uris)") suspend fun deleteByUris(uris: List<String>)

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertFolder(f: FolderEntity): Long
    @Query("UPDATE folders SET name=:name, color=:color WHERE id=:id") suspend fun updateFolder(id: Long, name: String, color: Int)
    @Query("UPDATE folders SET parentId=:parent WHERE id=:id") suspend fun moveFolder(id: Long, parent: Long?)
    @Query("UPDATE folders SET parentId=:newParent WHERE parentId=:old") suspend fun reparentChildren(old: Long, newParent: Long?)
    @Query("UPDATE pdfs SET folderId=:newF WHERE folderId=:old") suspend fun reparentPdfs(old: Long, newF: Long?)
    @Query("DELETE FROM folders WHERE id=:id") suspend fun deleteFolder(id: Long)

    @Query("UPDATE pdfs SET folderId=:folder WHERE id IN (:ids)") suspend fun movePdfs(ids: List<Long>, folder: Long?)
    @Query("UPDATE pdfs SET favorite=:fav WHERE id IN (:ids)") suspend fun setFavorite(ids: List<Long>, fav: Boolean)
    @Query("UPDATE pdfs SET lastPage=:page, pageCount=:count WHERE id=:id") suspend fun progress(id: Long, page: Int, count: Int)
    @Query("UPDATE pdfs SET lastOpened=:t WHERE id=:id") suspend fun touch(id: Long, t: Long)

    @Insert suspend fun addBookmark(b: BookmarkEntity)
    @Query("DELETE FROM bookmarks WHERE id=:id") suspend fun deleteBookmark(id: Long)

    @Query("UPDATE pdfs SET folderId=NULL") suspend fun resetPdfFolders()
    @Query("DELETE FROM bookmarks") suspend fun clearBookmarks()
    @Query("DELETE FROM folders") suspend fun clearFolders()
    @Query("UPDATE pdfs SET folderId=:folder, favorite=:fav, lastPage=:page, pageCount=:count WHERE id=:id")
    suspend fun restorePdf(id: Long, folder: Long?, fav: Boolean, page: Int, count: Int)
}

@Database(entities = [FolderEntity::class, PdfEntity::class, BookmarkEntity::class], version = 1, exportSchema = false)
abstract class AppDb : RoomDatabase() {
    abstract fun dao(): LibDao

    companion object {
        @Volatile private var inst: AppDb? = null
        fun get(c: Context): AppDb = inst ?: synchronized(this) {
            inst ?: Room.databaseBuilder(c.applicationContext, AppDb::class.java, "library.db").build().also { inst = it }
        }
    }
}
