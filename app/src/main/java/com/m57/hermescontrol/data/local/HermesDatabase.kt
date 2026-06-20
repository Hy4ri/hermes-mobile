package com.m57.hermescontrol.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.m57.hermescontrol.BuildConfig

@Database(
    entities = [ChatMessageEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class HermesDatabase : RoomDatabase() {
    abstract fun chatMessageDao(): ChatMessageDao

    companion object {
        @Volatile
        private var instance: HermesDatabase? = null

        fun get(context: Context): HermesDatabase =
            instance ?: synchronized(this) {
                instance ?: Room
                    .databaseBuilder(
                        context.applicationContext,
                        HermesDatabase::class.java,
                        "hermes_control.db",
                    ).apply {
                        if (BuildConfig.DEBUG) {
                            fallbackToDestructiveMigration(false)
                        }
                    }.build()
                    .also { instance = it }
            }

        /** For testing — inject a custom instance. */
        fun setForTest(db: HermesDatabase?) {
            instance = db
        }
    }
}
