package com.footprints.app

import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.amap.api.location.AMapLocationClient
import com.amap.api.maps.MapsInitializer
import com.footprints.app.data.AppDatabase
import com.footprints.app.data.PointRepository
import com.footprints.app.worker.LocationWorker
import java.util.concurrent.TimeUnit

class App : Application() {

    val repository: PointRepository by lazy {
        PointRepository(AppDatabase.getInstance(this).pointDao())
    }

    override fun onCreate() {
        super.onCreate()
        val prefs = getSharedPreferences("app", MODE_PRIVATE)
        // 主题偏好：默认暗黑（AppCompatDelegate 会在 Activity 创建前生效）
        AppCompatDelegate.setDefaultNightMode(
            if (prefs.getBoolean("dark_mode", true)) {
                AppCompatDelegate.MODE_NIGHT_YES
            } else {
                AppCompatDelegate.MODE_NIGHT_NO
            }
        )
        if (prefs.getBoolean("privacy_agreed", false)) {
            initAmapPrivacy(this)
            if (hasLocationPermission(this)) {
                ensureTrackingScheduled(this)
            }
        }
    }

    companion object {
        private const val WORK_PERIODIC = "life_location_periodic"
        private const val WORK_ONCE = "life_location_now"

        /**
         * 高德 SDK 要求在使用地图/定位前完成隐私合规接口调用。
         * 在用户同意隐私说明后调用一次（进程重启时在 Application 里补调）。
         */
        fun initAmapPrivacy(context: Context) {
            MapsInitializer.updatePrivacyShow(context, true, true)
            MapsInitializer.updatePrivacyAgree(context, true)
            AMapLocationClient.updatePrivacyShow(context, true, true)
            AMapLocationClient.updatePrivacyAgree(context, true)
        }

        fun hasLocationPermission(context: Context): Boolean =
            ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

        /** 每 15 分钟自动记录一个点（KEEP：已调度则不重复） */
        fun ensureTrackingScheduled(context: Context) {
            val request = PeriodicWorkRequestBuilder<LocationWorker>(15, TimeUnit.MINUTES).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request
            )
        }

        /** 打开 App 时立即补记一个点，让数据保持新鲜（运动模式进行中则跳过） */
        fun recordNow(context: Context) {
            if (com.footprints.app.service.TrackingManager.state.value.isTracking) return
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_ONCE, ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<LocationWorker>().build()
            )
        }
    }
}
