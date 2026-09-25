package com.github.gbandszxc.obt.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 应用 Room 数据库：煲机会话表 + 本地音轨表；schema 导出到 app/schemas（ksp room.schemaLocation 已配）。
 *
 * 版本历史：
 * - v1：burn_in_sessions；
 * - v2：新增 local_tracks（本地音乐自定义音源），见 [MIGRATION_1_2]。
 */
@Database(
    entities = [BurnInSession::class, LocalTrack::class],
    version = 2,
    exportSchema = true,
)
@TypeConverters(SessionStatusConverter::class)
abstract class BurnInDatabase : RoomDatabase() {

    abstract fun burnInSessionDao(): BurnInSessionDao

    abstract fun localTrackDao(): LocalTrackDao

    companion object {
        const val DATABASE_NAME: String = "burn_in.db"

        /**
         * v1 → v2：新建 local_tracks 表。
         *
         * CREATE TABLE 语句与 Room 为 [LocalTrack] 生成的默认对象逐列一致
         * （app/schemas/...BurnInDatabase/2.json 可比对）：
         * 主键自增 INTEGER NOT NULL；可空 Long 字段为无 NOT NULL 的 INTEGER；
         * 字符串为 TEXT NOT NULL。对既有 burn_in_sessions 无任何改动。
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `local_tracks` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`displayName` TEXT NOT NULL, " +
                        "`fileName` TEXT NOT NULL, " +
                        "`durationMs` INTEGER, " +
                        "`sizeBytes` INTEGER, " +
                        "`addedAt` INTEGER NOT NULL)",
                )
            }
        }

        /** 创建数据库实例（Application 级单例，见 [com.github.gbandszxc.obt.data.AppContainer]）。 */
        fun create(context: Context): BurnInDatabase =
            Room.databaseBuilder(context.applicationContext, BurnInDatabase::class.java, DATABASE_NAME)
                .addMigrations(MIGRATION_1_2)
                .build()
    }
}
