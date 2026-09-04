package com.footprints.app.data

import android.location.Location

class PointRepository(private val dao: PointDao) {

    fun observeStats(todayStart: Long) = dao.observeStats(todayStart)

    suspend fun pointsBetween(from: Long, to: Long) = dao.pointsBetween(from, to)

    /** 清空全部轨迹（不可恢复） */
    suspend fun clearAll() = dao.clearAll()

    /** 记录一个轨迹点，返回计入里程的有效距离（米）。
     *  distPrevOverride：运动模式传入实际步进距离（自有 8m 去重，不走显著性门槛） */
    suspend fun addPoint(
        lat: Double, lng: Double, accuracy: Float, time: Long,
        address: String = "", distPrevOverride: Double? = null
    ): Double {
        val last = dao.lastPoint()
        var effective = 0.0
        if (last != null) {
            val result = FloatArray(1)
            Location.distanceBetween(last.lat, last.lng, lat, lng, result)
            effective = when {
                distPrevOverride != null -> distPrevOverride
                // 显著性门槛：位移小于两点定位精度之和，视为原地未动。
                // 室内漂移（精度几百米、坐标乱跳）不会虚增里程；户外真实移动必然超过该门槛
                result[0] > last.accuracy + accuracy -> result[0].toDouble()
                else -> 0.0
            }
        }
        dao.insert(
            PointEntity(
                lat = lat, lng = lng, accuracy = accuracy, time = time,
                distPrev = effective, address = address
            )
        )
        return effective
    }
}
