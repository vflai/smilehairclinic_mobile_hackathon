package com.google.mediapipe.examples.facelandmarker.database

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction

@Entity(tableName = "users")
data class User(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val surname: String,
    val tc: String,
    val birthDate: String,
    val gender: String
)

@Entity(tableName = "sessions")
data class Session(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val userId: Long,
    val date: Long,
    val folderPath: String
)

@Entity(tableName = "photos")
data class Photo(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val stage: Int, // 1-5
    val filePath: String,
    val timestamp: Long
)

data class SessionWithPhotos(
    @androidx.room.Embedded val session: Session,
    @androidx.room.Relation(
        parentColumn = "id",
        entityColumn = "sessionId"
    )
    val photos: List<Photo>
)

data class UserWithSessions(
    @androidx.room.Embedded val user: User,
    @androidx.room.Relation(
        parentColumn = "id",
        entityColumn = "userId"
    )
    val sessions: List<Session>
)

@Dao
interface AppDao {
    @Insert
    suspend fun insertUser(user: User): Long

    @Query("SELECT * FROM users WHERE tc = :tc LIMIT 1")
    suspend fun getUserByTc(tc: String): User?

    @Insert
    suspend fun insertSession(session: Session): Long

    @Insert
    suspend fun insertPhoto(photo: Photo): Long

    @Transaction
    @Query("SELECT * FROM sessions WHERE userId = :userId ORDER BY date DESC")
    suspend fun getSessionsForUser(userId: Long): List<SessionWithPhotos>

    @Query("SELECT * FROM users ORDER BY id DESC LIMIT 1")
    suspend fun getLastUser(): User?
}

@Database(entities = [User::class, Session::class, Photo::class], version = 1)
abstract class AppDatabase : RoomDatabase() {
    abstract fun appDao(): AppDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "hair_clinic_db"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}
