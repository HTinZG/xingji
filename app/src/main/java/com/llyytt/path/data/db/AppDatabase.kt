package com.llyytt.path.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        TripEntity::class,
        TrackPointEntity::class,
        TripSegmentEntity::class,
        StayPointEntity::class,
        TripPauseEntity::class,
        AiChatMessageEntity::class,
    ],
    version = 3,
    exportSchema = true,
)
@TypeConverters(Converters::class, ChatRoleConverter::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun tripDao(): TripDao
    abstract fun trackPointDao(): TrackPointDao
    abstract fun tripSegmentDao(): TripSegmentDao
    abstract fun stayPointDao(): StayPointDao
    abstract fun tripPauseDao(): TripPauseDao
    abstract fun aiChatDao(): AiChatDao

    companion object {
        private const val DB_NAME = "xingji.db"

        /**
         * 1 -> 2 的开发期迁移：丢表重建（v1 从未对外发布，只有测试机有数据）。
         * 第一次正式发版后必须删掉，换成真正保留数据的迁移。
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS track_point")
                db.execSQL("DROP TABLE IF EXISTS trip_segment")
                db.execSQL("DROP TABLE IF EXISTS stay_point")
                db.execSQL("DROP TABLE IF EXISTS trip")
                db.execSQL("DROP TABLE IF EXISTS trip_pause")
            }
        }

        /** 2 -> 3：新增 AI 聊天表。不动已有数据。 */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `ai_chat_message` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `role` TEXT NOT NULL,
                        `content` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `tripId` INTEGER
                    )
                    """.trimIndent(),
                )
            }
        }

        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    DB_NAME,
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .build()
                    .also { instance = it }
            }
    }
}
