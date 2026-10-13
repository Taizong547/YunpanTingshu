package com.yunpan.tingshu.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        HistoryEntity::class,
        ListenDayEntity::class,
        Cover::class,
        CoverOverride::class,
        AlbumDay::class,
        BookshelfEntity::class,
        PlaylistEntity::class,
        PlaylistItemEntity::class
    ],
    version = 5,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun historyDao(): HistoryDao
    abstract fun listenDao(): ListenDao
    abstract fun coverDao(): CoverDao
    abstract fun coverOverrideDao(): CoverOverrideDao
    abstract fun albumDayDao(): AlbumDayDao
    abstract fun bookshelfDao(): BookshelfDao
    abstract fun playlistDao(): PlaylistDao

    companion object {
        private const val DB_NAME = "yunpan.db"

        /** v1→v2：新增封面两表 + album_day。均为新表，无历史数据迁移。 */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `cover` (
                        `coverId` TEXT NOT NULL,
                        `fileSize` INTEGER NOT NULL,
                        `source` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        PRIMARY KEY(`coverId`)
                    )"""
                )
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `cover_override` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `scope` TEXT NOT NULL,
                        `target_key` TEXT NOT NULL,
                        `cover_id` TEXT NOT NULL,
                        `updated_at` INTEGER NOT NULL,
                        FOREIGN KEY(`cover_id`) REFERENCES `cover`(`coverId`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )"""
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_cover_override_scope_target_key` ON `cover_override` (`scope`, `target_key`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_cover_override_cover_id` ON `cover_override` (`cover_id`)")
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `album_day` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `date` TEXT NOT NULL,
                        `accountId` TEXT NOT NULL,
                        `albumPath` TEXT NOT NULL,
                        `playedMs` INTEGER NOT NULL,
                        `episodeCount` INTEGER NOT NULL,
                        `completedCount` INTEGER NOT NULL
                    )"""
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_album_day_date_accountId_albumPath` ON `album_day` (`date`, `accountId`, `albumPath`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_album_day_date` ON `album_day` (`date`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_album_day_albumPath` ON `album_day` (`albumPath`)")
            }
        }

        /**
         * v2→v3：
         * 1) history 新增 SHARE 重定位坐标三列（分享直链约 8h 过期，冷启动/历史续播需按坐标重新 openShare）；
         * 2) 新增 bookshelf（本地书单/书架）表。
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `history` ADD COLUMN `shareLinkId` TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE `history` ADD COLUMN `sharePasswd` TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE `history` ADD COLUMN `shareParentToken` TEXT DEFAULT NULL")
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `bookshelf` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `source` TEXT NOT NULL,
                        `accountId` TEXT,
                        `shareLinkId` TEXT,
                        `sharePasswd` TEXT,
                        `folderId` TEXT NOT NULL,
                        `folderName` TEXT NOT NULL,
                        `albumPath` TEXT NOT NULL DEFAULT '',
                        `coverId` TEXT,
                        `positionMs` INTEGER NOT NULL DEFAULT 0,
                        `totalMs` INTEGER NOT NULL DEFAULT 0,
                        `addedAtMs` INTEGER NOT NULL,
                        `updatedAtMs` INTEGER NOT NULL
                    )"""
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_bookshelf_addedAtMs` ON `bookshelf` (`addedAtMs`)")
            }
        }

        /**
         * v3→v4：bookshelf 补 addedAtMs 索引（v1.3 的 MIGRATION_2_3 建了该索引，
         * 但 v1.3 实体未声明 @Index——fresh 安装 v1.3 的库缺此索引；v1.4 实体补上 @Index 后，
         * 用此迁移为旧库补齐，保证所有升级路径 schema 一致）。
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_bookshelf_addedAtMs` ON `bookshelf` (`addedAtMs`)")
            }
        }

        /**
         * v4→v5：新增命名歌单两表（playlist / playlist_item）。纯新表，无数据搬迁。
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `playlist` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `name` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL
                    )"""
                )
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `playlist_item` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `playlistId` INTEGER NOT NULL,
                        `bookshelfId` INTEGER NOT NULL,
                        `addedAt` INTEGER NOT NULL,
                        FOREIGN KEY(`playlistId`) REFERENCES `playlist`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )"""
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_playlist_item_playlistId` ON `playlist_item` (`playlistId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_playlist_item_bookshelfId` ON `playlist_item` (`bookshelfId`)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_playlist_item_playlistId_bookshelfId` ON `playlist_item` (`playlistId`, `bookshelfId`)")
            }
        }

        /** 真机：磁盘库。 */
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, DB_NAME)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                .build()

        /** 测试：内存库（进程存活期间保留），Robolectric / instrumentation 可用。 */
        fun createInMemory(context: Context): AppDatabase =
            Room.inMemoryDatabaseBuilder(context.applicationContext, AppDatabase::class.java)
                .allowMainThreadQueries()
                .build()
    }
}
