package com.footprints.app.worker

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.amap.api.location.AMapLocation
import com.amap.api.location.AMapLocationClient
import com.amap.api.location.AMapLocationClientOption
import com.amap.api.location.AMapLocationListener
import com.footprints.app.App
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * 每 15 分钟由 WorkManager 唤醒：连续定位一个窗口期，取其中最准的一次落库。
 */
class LocationWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "LocationWorker"

        /** 只拦截明显异常的粗定位（>1km）。室内 WiFi/基站定位（几百米级）必须保留：
         *  点记不上比位置略偏更伤「一生足迹」；里程准确性由 PointRepository 的
         *  显著性门槛（位移 < 精度之和 → 视为原地）兜底，漂移不会虚增里程 */
        private const val MAX_ACCURACY = 1000f

        /** 外层总超时（窗口 15s + 余量） */
        private const val LOCATE_TIMEOUT_MS = 30_000L

        /** 连续定位窗口：窗口内取精度数值最小（最准）的一次落库。
         *  单发定位拿到的常是缓存的网络粗定位；刚出建筑物等信号恢复场景下，
         *  多等十几秒 GPS 就能给出好得多的结果 */
        private const val LOCATE_WINDOW_MS = 15_000L
        private const val LOCATE_INTERVAL_MS = 2_000L

        /** 优于该值（米）即认为够准；否则 3 分钟后补测一次（最多 MAX_RETRY 次） */
        private const val GOOD_ACCURACY = 150f
        private const val MAX_RETRY = 4
        private const val RETRY_WORK_NAME = "life_location_retry"
        const val KEY_ATTEMPT = "attempt"
    }

    override suspend fun doWork(): Result {
        val context = applicationContext
        // 运动模式前台连续记录中：周期任务让路，避免重复记点
        if (com.footprints.app.service.TrackingManager.state.value.isTracking) {
            Log.i(TAG, "运动模式进行中，跳过本周期")
            return Result.success()
        }
        if (!App.hasLocationPermission(context)) return Result.success()
        val attempt = inputData.getInt(KEY_ATTEMPT, 0)

        // 高德定位 SDK 只能在带 Looper 的线程（主线程）初始化与启动；
        // CoroutineWorker 默认跑在无 Looper 的后台线程，直接调用会抛
        // "Can't create handler inside thread that has not called Looper.prepare()"
        // 并导致任务静默失败 —— 必须切到主线程执行（挂起不阻塞主线程）
        val location = withTimeoutOrNull(LOCATE_TIMEOUT_MS) {
            withContext(Dispatchers.Main) { locateBest(context) }
        }
        if (location == null) {
            Log.w(TAG, "定位超时或失败，本周期跳过")
            return Result.success()
        }
        if (location.errorCode != 0) {
            Log.w(TAG, "定位失败 errorCode=${location.errorCode} info=${location.locationDetail}")
            return Result.success()
        }
        if (location.accuracy > MAX_ACCURACY) {
            Log.w(TAG, "精度异常(${location.accuracy}m)，丢弃本点")
            return Result.success()
        }

        val repo = (context as App).repository
        val address = location.address?.removePrefix("中国").orEmpty()

        // 补测逻辑：正式周期（attempt=0）差点照记（一生足迹宁缺毋滥的反面——宁滥勿缺），
        // 但随后 3 分钟快速补测抢好点；补测（attempt>0）若仍不准则不落库，避免刷屏差点
        if (location.accuracy > GOOD_ACCURACY) {
            if (attempt < MAX_RETRY) {
                scheduleRetry(context, attempt + 1)
            }
            if (attempt > 0) {
                Log.w(TAG, "补测第 $attempt 次精度仍差(${location.accuracy}m)，不落库")
                return Result.success()
            }
        }

        repo.addPoint(
            lat = location.latitude,
            lng = location.longitude,
            accuracy = location.accuracy,
            time = if (location.time > 0) location.time else System.currentTimeMillis(),
            address = address
        )
        Log.i(
            TAG,
            "已记录轨迹点 (${location.latitude},${location.longitude}) 精度=${location.accuracy}m 地址=$address"
        )
        return Result.success()
    }

    /** 3 分钟后自动补测一次：抢在用户出门、信号恢复的瞬间拿到准点 */
    private fun scheduleRetry(context: Context, attempt: Int) {
        val request = OneTimeWorkRequestBuilder<LocationWorker>()
            .setInitialDelay(3, TimeUnit.MINUTES)
            .setInputData(workDataOf(KEY_ATTEMPT to attempt))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            RETRY_WORK_NAME, ExistingWorkPolicy.REPLACE, request
        )
        Log.i(TAG, "精度不足，已排定 $attempt/4 次补测（3 分钟后）")
    }

    /**
     * 连续定位 [LOCATE_WINDOW_MS] 毫秒，返回窗口内精度最高（数值最小）的一次。
     * 必须在主线程调用；失败/超时返回 null，等下个周期再记。
     */
    private suspend fun locateBest(context: Context): AMapLocation? =
        suspendCancellableCoroutine { cont ->
            val client = AMapLocationClient(context)
            val option = AMapLocationClientOption().apply {
                locationMode = AMapLocationClientOption.AMapLocationMode.Hight_Accuracy
                isOnceLocation = false
                interval = LOCATE_INTERVAL_MS
                // 传感器（指南针/加速度计）辅助定位，提升室内定位质量
                isSensorEnable = true
                // 逆地理编码：定位结果自带地址（存库供轨迹日志展示）
                isNeedAddress = true
            }
            client.setLocationOption(option)

            val done = AtomicBoolean(false)
            var best: AMapLocation? = null
            val listener = AMapLocationListener { loc ->
                if (loc.errorCode == 0 && loc.accuracy > 0f &&
                    (best == null || loc.accuracy < best!!.accuracy)
                ) {
                    best = loc
                }
            }
            val handler = Handler(Looper.getMainLooper())
            val finish = Runnable {
                if (done.compareAndSet(false, true)) {
                    client.stopLocation()
                    client.onDestroy()
                    cont.resume(best)
                }
            }
            client.setLocationListener(listener)
            // 协程被取消（总超时触发）时，清理定位资源防泄漏
            cont.invokeOnCancellation {
                if (done.compareAndSet(false, true)) {
                    handler.removeCallbacks(finish)
                    runCatching {
                        client.stopLocation()
                        client.onDestroy()
                    }
                }
            }
            client.startLocation()
            handler.postDelayed(finish, LOCATE_WINDOW_MS)
        }
}
