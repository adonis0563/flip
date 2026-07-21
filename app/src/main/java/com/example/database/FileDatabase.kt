package com.example.database

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "indexed_files")
data class IndexedFile(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uriString: String,
    val path: String? = null,
    val name: String,
    val size: Long,
    val mimeType: String,
    val category: String, // "Images", "Videos", "Audio", "Documents", "Apps"
    val dateAdded: Long,
    val hash: String? = null
)

@Dao
interface IndexedFileDao {
    @Query("SELECT * FROM indexed_files WHERE category = :category ORDER BY dateAdded DESC")
    suspend fun getFilesByCategory(category: String): List<IndexedFile>

    @Query("SELECT * FROM indexed_files WHERE category = :category ORDER BY dateAdded DESC")
    fun getFilesByCategoryFlow(category: String): Flow<List<IndexedFile>>

    @Query("SELECT * FROM indexed_files ORDER BY dateAdded DESC")
    suspend fun getAllFiles(): List<IndexedFile>

    @Query("SELECT * FROM indexed_files ORDER BY dateAdded DESC")
    fun getAllFilesFlow(): Flow<List<IndexedFile>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFiles(files: List<IndexedFile>)

    @Query("DELETE FROM indexed_files")
    suspend fun clearAll()

    @Query("DELETE FROM indexed_files WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    @Query("SELECT EXISTS(SELECT 1 FROM indexed_files WHERE uriString = :uriStr OR (name = :name AND size = :size))")
    suspend fun exists(uriStr: String, name: String, size: Long): Boolean

    @Query("SELECT * FROM indexed_files WHERE name = :name AND size = :size AND abs(dateAdded - :dateAdded) <= 2 LIMIT 1")
    suspend fun findMatch(name: String, size: Long, dateAdded: Long): IndexedFile?
}

@Database(entities = [IndexedFile::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun indexedFileDao(): IndexedFileDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "flip_file_database"
                )
                .fallbackToDestructiveMigration()
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
