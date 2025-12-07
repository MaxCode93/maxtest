package cu.maxwell.firenetstats.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [AppInfoEntity::class], version = 1, exportSchema = false)
abstract class FireNetStatsDatabase : RoomDatabase() {

    abstract fun appInfoDao(): AppInfoDao

    companion object {
        @Volatile
        private var INSTANCE: FireNetStatsDatabase? = null

        fun getDatabase(context: Context): FireNetStatsDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    FireNetStatsDatabase::class.java,
                    "firenetstats_database"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}
