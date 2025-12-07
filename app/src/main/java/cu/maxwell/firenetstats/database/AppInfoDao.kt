package cu.maxwell.firenetstats.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
interface AppInfoDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(apps: List<AppInfoEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(app: AppInfoEntity)

    @Update
    suspend fun update(app: AppInfoEntity)

    @Query("SELECT * FROM app_info ORDER BY appName ASC")
    suspend fun getAllApps(): List<AppInfoEntity>

    @Query("SELECT * FROM app_info WHERE packageName = :packageName")
    suspend fun getAppByPackage(packageName: String): AppInfoEntity?

    @Query("DELETE FROM app_info")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM app_info")
    suspend fun getAppCount(): Int

    @Query("SELECT * FROM app_info WHERE isSystemApp = :isSystemApp ORDER BY appName ASC")
    suspend fun getAppsByType(isSystemApp: Boolean): List<AppInfoEntity>

    @Query("SELECT * FROM app_info WHERE hasInternetPermission = :hasInternet ORDER BY appName ASC")
    suspend fun getAppsByInternet(hasInternet: Boolean): List<AppInfoEntity>
}
