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
 * - v2：新增 local_tracks（本地音乐自定义音源），见 [MIGRATION_1_2]；
 * - v3：burn_in_sessions 新增 soundSourceId / soundLabel 两列（自由煲机会话的音效快照，
 *   供历史页回显），见 [MIGRATION_2_3]。
 */
@Database(
    entities = [BurnInSession::class, LocalTrack::class],
    version = 3,
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

        /**
         * v2 → v3：burn_in_sessions 新增音效快照两列（自由煲机记录所用音效，供历史页回显）。
         *
         * ALTER TABLE ADD COLUMN 对既有行是安全的：可空列（无 NOT NULL）既有行回读为 null，
         * 恰与「方案煲机 / 旧数据不展示音效」的语义一致。两列定义与 Room 为 [BurnInSession]
         * 生成的默认对象一致（app/schemas/...BurnInDatabase/3.json 可比对）：
         * soundSourceId 为可空 INTEGER，soundLabel 为可空 TEXT，均无默认值约束之外的要求
         * （Kotlin 默认参数 null 只作用于构造点，不进 DDL）。
         */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `burn_in_sessions` ADD COLUMN `soundSourceId` INTEGER")
                db.execSQL("ALTER TABLE `burn_in_sessions` ADD COLUMN `soundLabel` TEXT")
            }
        }

        /** 创建数据库实例（Application 级单例，见 [com.github.gbandszxc.obt.data.AppContainer]）。 */
        fun create(context: Context): BurnInDatabase =
            Room.databaseBuilder(context.applicationContext, BurnInDatabase::class.java, DATABASE_NAME)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build()
    }
}
