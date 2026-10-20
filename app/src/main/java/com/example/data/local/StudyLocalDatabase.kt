package com.example.data.local

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

@Entity(tableName = "cached_study_documents")
data class CachedStudyDocumentEntity(
    @PrimaryKey val contentHash: String,
    val jobId: String,
    val userId: String,
    val title: String,
    val sourceType: String,
    val sourceUri: String,
    val detailLevel: String,
    val videoDurationSeconds: Long,
    val studyDocumentJson: String,
    val localPdfPath: String,
    val pageCount: Int,
    val createdAtEpochMs: Long = System.currentTimeMillis()
)

@Dao
interface CachedStudyDocumentDao {
    @Query("SELECT * FROM cached_study_documents WHERE userId = :userId ORDER BY createdAtEpochMs DESC")
    fun observeUserDocuments(userId: String): Flow<List<CachedStudyDocumentEntity>>

    @Query("SELECT * FROM cached_study_documents WHERE contentHash = :contentHash AND userId = :userId LIMIT 1")
    suspend fun findByContentHash(contentHash: String, userId: String): CachedStudyDocumentEntity?

    @Query("SELECT * FROM cached_study_documents WHERE jobId = :jobId LIMIT 1")
    suspend fun findByJobId(jobId: String): CachedStudyDocumentEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDocument(entity: CachedStudyDocumentEntity)

    @Query("DELETE FROM cached_study_documents WHERE jobId = :jobId AND userId = :userId")
    suspend fun deleteByJobId(jobId: String, userId: String)

    @Query("DELETE FROM cached_study_documents WHERE userId = :userId")
    suspend fun clearAllForUser(userId: String)
}

@Database(
    entities = [CachedStudyDocumentEntity::class],
    version = 1,
    exportSchema = false
)
abstract class StudyLocalDatabase : RoomDatabase() {
    abstract fun cachedStudyDocumentDao(): CachedStudyDocumentDao

    companion object {
        @Volatile
        private var INSTANCE: StudyLocalDatabase? = null

        fun getInstance(context: Context): StudyLocalDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    StudyLocalDatabase::class.java,
                    "video_to_study_pdf.db"
                )
                    .fallbackToDestructiveMigration(true)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
