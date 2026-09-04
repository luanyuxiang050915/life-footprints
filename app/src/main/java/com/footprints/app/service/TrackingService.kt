package com.footprints.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.amap.api.location.AMapLocation
import com.amap.api.location.AMapLocationClient
import com.amap.api.location.AMapLocationClientOption
import com.amap.api.maps.model.LatLng
import com.footprints.app.App
import com.footprints.app.R
import com.footprints.app.ui.MainActivity
import com.footprints.app.util.Format
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 运动模式：前台连续定位服务，2 秒一个定位、移动 ≥8 米才落库，
 * 熄屏也持续记录完整行进路线。通知栏常驻显示距离并提供停止按钮。
 */
class TrackingService : Service() {

    companion object {
        const val ACTION_START = "com.footprints.app.action.SPORT_START"
        const val ACTION_STOP = "com.footprints.app.action.SPORT_STOP"

        private const val CHANNEL_ID = "sport_tracking"
        private const val NOTIF_ID = 1002

        /** 链式去重：距上一个落库点不足该距离视为原地（防抖动），不落库 */
        private const val MIN_STEP_METERS = 8.0
        private const val NOTIF_UPDATE_INTERVAL_MS = 15_000L

        fun start(context: Context) {
            val intent = Intent(context, TrackingService::class.java).setAction(ACTION_START)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, TrackingService::class.java).setAction(ACTION_STOP)
            )
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var client: AMapLocationClient? = null
    private var lastNotifUpdate = 0L

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                TrackingManager.update {
                    it.copy(
                        isTracking = true,
                        startedAt = if (it.isTracking) it.startedAt else System.currentTimeMillis()
                    )
                }
                ServiceCompat.startForeground(
                    this, NOTIF_ID, buildNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                )
                startUpdates()
            }
            ACTION_STOP -> stopTracking()
            // 系统杀进程后 START_STICKY 重启（intent 为 null）：会话统计已丢失，
            // 从零继续记录，保证路线不中断
            null -> {
                TrackingManager.update { it.copy(isTracking = true) }
                ServiceCompat.startForeground(
                    this, NOTIF_ID, buildNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                )
                startUpdates()
            }
        }
        return START_STICKY
    }

    private fun startUpdates() {
        client?.onDestroy()
        val c = AMapLocationClient(applicationContext)
        val option = AMapLocationClientOption().apply {
            locationMode = AMapLocationClientOption.AMapLocationMode.Hight_Accuracy
            interval = 2_000
            isSensorEnable = true
            isNeedAddress = false
        }
        c.setLocationOption(option)
        c.setLocationListener(::onLocationChanged)
        c.startLocation()
        client = c
    }

    private fun onLocationChanged(loc: AMapLocation) {
        if (loc.errorCode != 0) return
        val s = TrackingManager.state.value
        val last = s.lastLatLng
        val result = FloatArray(1)
        if (last != null) {
            Location.distanceBetween(last.latitude, last.longitude, loc.latitude, loc.longitude, result)
            if (result[0] < MIN_STEP_METERS) return
        }
        val step = if (last != null) result[0].toDouble() else 0.0
        val point = LatLng(loc.latitude, loc.longitude)
        val app = applicationContext as App

        scope.launch {
            app.repository.addPoint(
                lat = loc.latitude,
                lng = loc.longitude,
                accuracy = loc.accuracy,
                time = if (loc.time > 0) loc.time else System.currentTimeMillis(),
                address = "",
                distPrevOverride = step
            )
        }
        TrackingManager.update {
            it.copy(
                points = it.points + point,
                distanceMeters = it.distanceMeters + step,
                lastLatLng = point,
                maxSpeed = maxOf(it.maxSpeed, loc.speed)
            )
        }
        maybeUpdateNotification()
    }

    private fun stopTracking() {
        client?.stopLocation()
        client?.onDestroy()
        client = null
        TrackingManager.reset()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun maybeUpdateNotification() {
        val now = System.currentTimeMillis()
        if (now - lastNotifUpdate < NOTIF_UPDATE_INTERVAL_MS) return
        lastNotifUpdate = now
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification())
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "运动模式", NotificationManager.IMPORTANCE_LOW
        ).apply { description = "运动模式记录时显示实时距离" }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val s = TrackingManager.state.value
        val stopPi = PendingIntent.getService(
            this, 1,
            Intent(this, TrackingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val openPi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_walk)
            .setContentTitle("运动模式记录中")
            .setContentText("已记录 ${Format.km(s.distanceMeters)} 千米 · ${s.pointCount} 个点")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openPi)
            .addAction(0, "停止记录", stopPi)
            .build()
    }

    override fun onDestroy() {
        client?.onDestroy()
        client = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
