package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        ProfileEntity::class,
        SubscriptionEntity::class,
        ProfileGroupEntity::class,
        ApplicationSettingsEntity::class,
        RoutingRuleEntity::class,
        ConnectionHistoryEntity::class
    ],
    version = 4,
    exportSchema = false
)
abstract class MaxNetDatabase : RoomDatabase() {
    abstract fun maxNetDao(): MaxNetDao

    companion object {
        @Volatile
        private var INSTANCE: MaxNetDatabase? = null

        /**
         * Non-destructive migration from version 1 to 2 preserving all user data.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `profile_groups` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `createdAt` INTEGER NOT NULL)"
                )
            }
        }

        /**
         * Non-destructive migration from version 2 to 3 adding diagnostic TCP connect latency,
         * test timestamp, user-customized remarks flag, and conditional HTTP headers (ETag/Last-Modified).
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `profiles` ADD COLUMN `tcpConnectMs` INTEGER DEFAULT NULL")
                db.execSQL("ALTER TABLE `profiles` ADD COLUMN `lastTestedAtMs` INTEGER DEFAULT NULL")
                db.execSQL("ALTER TABLE `profiles` ADD COLUMN `userCustomizedRemarks` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `subscriptions` ADD COLUMN `etag` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `subscriptions` ADD COLUMN `lastModifiedHeader` TEXT NOT NULL DEFAULT ''")
            }
        }

        /**
         * Non-destructive migration from version 3 to 4 adding smart performance metrics
         * (jitterMs, packetLossRate, successRate, healthScore, consecutiveFailures, lastFailureTimestampMs)
         * and connection history analytics (failoverReason, activeMode, averageLatencyMs).
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `profiles` ADD COLUMN `jitterMs` INTEGER DEFAULT NULL")
                db.execSQL("ALTER TABLE `profiles` ADD COLUMN `packetLossRate` REAL DEFAULT NULL")
                db.execSQL("ALTER TABLE `profiles` ADD COLUMN `successRate` REAL DEFAULT NULL")
                db.execSQL("ALTER TABLE `profiles` ADD COLUMN `healthScore` INTEGER DEFAULT NULL")
                db.execSQL("ALTER TABLE `profiles` ADD COLUMN `consecutiveFailures` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `profiles` ADD COLUMN `lastFailureTimestampMs` INTEGER DEFAULT NULL")
                db.execSQL("ALTER TABLE `connection_history` ADD COLUMN `failoverReason` TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE `connection_history` ADD COLUMN `activeMode` TEXT NOT NULL DEFAULT 'STABLE'")
                db.execSQL("ALTER TABLE `connection_history` ADD COLUMN `averageLatencyMs` INTEGER DEFAULT NULL")
            }
        }

        fun getInstance(context: Context): MaxNetDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    MaxNetDatabase::class.java,
                    "maxnet5g_vpn.db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
