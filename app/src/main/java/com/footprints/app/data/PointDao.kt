package com.footprints.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

data class LifeStats(
    val todayCount: Int,
    val totalMeters: Double,
)

@Dao
interface PointDao {

    @Insert
    suspend fun insert(point: PointEntity)

    @Query("SELECT * FROM points ORDER BY time DESC LIMIT 1")
    suspend fun lastPoint(): PointEntity?

    @Query("SELECT * FROM points WHERE time BETWEEN :from AND :to ORDER BY time ASC")
    suspend fun pointsBetween(from: Long, to: Long): List<PointEntity>

    @Query("DELETE FROM points")
    suspend fun clearAll()

    @Query(
        "SELECT COALESCE(SUM(CASE WHEN time >= :from THEN 1 ELSE 0 END), 0) AS todayCount, " +
                "COALESCE(SUM(distPrev), 0) AS totalMeters FROM points"
    )
    fun observeStats(from: Long): Flow<LifeStats>
}
