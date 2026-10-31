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
    version = 2,
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

        fun getInstance(context: Context): MaxNetDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    MaxNetDatabase::class.java,
                    "maxnet5g_vpn.db"
                )
                    .addMigrations(MIGRATION_1_2)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
