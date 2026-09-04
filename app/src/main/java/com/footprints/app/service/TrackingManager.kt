package com.footprints.app.service

import com.amap.api.maps.model.LatLng
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 运动模式会话状态：前台服务写入，主界面订阅实时画线与统计。
 */
object TrackingManager {

    data class SportState(
        val isTracking: Boolean = false,
        /** 会话内已落库点的顺序坐标（供实时画线） */
        val points: List<LatLng> = emptyList(),
        /** 会话内累计距离（米），即真实走过的路 */
        val distanceMeters: Double = 0.0,
        /** 链式去重基准：上一个已落库点 */
        val lastLatLng: LatLng? = null,
        val maxSpeed: Float = 0f,
        val startedAt: Long = 0L,
    ) {
        val pointCount: Int get() = points.size
    }

    private val _state = MutableStateFlow(SportState())
    val state: StateFlow<SportState> = _state.asStateFlow()

    fun update(transform: (SportState) -> SportState) = _state.update(transform)

    fun reset() {
        _state.value = SportState()
    }
}
