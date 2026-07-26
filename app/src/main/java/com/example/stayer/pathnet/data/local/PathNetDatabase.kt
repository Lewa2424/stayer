package com.example.stayer.pathnet.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * База данных локальной сети маршрутов.
 * Room database for the local path network.
 */
@Database(
    entities = [
        PathNodeEntity::class,
        PathEdgeEntity::class,
        LoadedAreaEntity::class,
        ImportedPathNodeEntity::class,
        ImportedWayEntity::class,
    ],
    version = 2,
    exportSchema = false,
)
@TypeConverters(PathNetConverters::class)
abstract class PathNetDatabase : RoomDatabase() {
    /**
     * Возвращает DAO базы сети.
     * Returns the database DAO.
     */
    abstract fun pathNetDao(): PathNetDao

    companion object {
        private val migration1To2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `imported_path_nodes` (
                        `id` TEXT NOT NULL,
                        `lat` REAL NOT NULL,
                        `lon` REAL NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `imported_path_ways` (
                        `id` TEXT NOT NULL,
                        `highwayType` TEXT NOT NULL,
                        `nodeIds` TEXT NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent(),
                )
            }
        }

        @Volatile
        private var instance: PathNetDatabase? = null

        /**
         * Возвращает singleton базы данных.
         * Returns a singleton database instance.
         */
        fun getInstance(context: Context): PathNetDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    PathNetDatabase::class.java,
                    "path_net.db",
                ).addMigrations(migration1To2)
                    .build()
                    .also { instance = it }
            }
        }
    }
}
