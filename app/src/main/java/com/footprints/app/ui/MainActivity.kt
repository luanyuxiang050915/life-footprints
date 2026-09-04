package com.footprints.app.ui

import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.amap.api.maps.AMap
import com.amap.api.maps.CameraUpdateFactory
import com.amap.api.maps.model.BitmapDescriptor
import com.amap.api.maps.model.BitmapDescriptorFactory
import com.amap.api.maps.model.LatLng
import com.amap.api.maps.model.LatLngBounds
import com.amap.api.maps.model.Marker
import com.amap.api.maps.model.MarkerOptions
import com.amap.api.maps.model.MyLocationStyle
import com.amap.api.maps.model.Polyline
import com.amap.api.maps.model.PolylineOptions
import com.footprints.app.App
import com.footprints.app.R
import com.footprints.app.data.PointEntity
import com.footprints.app.databinding.ActivityMainBinding
import com.footprints.app.databinding.BottomSheetLogBinding
import com.footprints.app.service.TrackingManager
import com.footprints.app.service.TrackingService
import com.footprints.app.util.Format
import com.footprints.app.util.TimeRanges
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: SharedPreferences
    private val repository get() = (application as App).repository

    private var polyline: Polyline? = null
    private val polylines = mutableListOf<Polyline>()
    private var sportPolyline: Polyline? = null
    private val markers = mutableListOf<Marker>()
    private var dotIcon: BitmapDescriptor? = null
    private var highlightIcon: BitmapDescriptor? = null
    private var highlightMarker: Marker? = null
    private var rangeFrom = 0L
    private var rangeTo = Long.MAX_VALUE
    private var pendingStart = false

    /** 前台定位权限通过后再单独申请后台定位（Android 10+ 必须分步） */
    private val fgPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
            enableMyLocation()
            if (Build.VERSION.SDK_INT >= 29) {
                bgPermissionLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            } else {
                onPermissionsReady()
            }
        } else {
            pendingStart = false
            toast(getString(R.string.no_location_permission))
        }
    }

    private val bgPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        // 即使后台定位被拒也先开始调度：前台权限下打开 App 时仍能补点
        onPermissionsReady()
    }

    /** 运动模式的通知权限（Android 13+），无论结果都开始记录 */
    private val notifPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { TrackingService.start(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("app", MODE_PRIVATE)
        if (!prefs.getBoolean("privacy_agreed", false)) {
            askPrivacy(prefs)
            return
        }
        setupUi()
    }

    private fun askPrivacy(prefs: SharedPreferences) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.privacy_title)
            .setMessage(R.string.privacy_message)
            .setCancelable(false)
            .setPositiveButton(R.string.privacy_agree) { _, _ ->
                prefs.edit().putBoolean("privacy_agreed", true).apply()
                App.initAmapPrivacy(this)
                setupUi()
            }
            .setNegativeButton(R.string.privacy_disagree) { _, _ ->
                Toast.makeText(this, "需同意后才能使用地图与记录功能", Toast.LENGTH_SHORT).show()
                finish()
            }
            .show()
    }

    private fun setupUi() {
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.mapView.onCreate(null)
        val map = binding.mapView.map
        map.uiSettings.isZoomControlsEnabled = false
        // 暗黑模式下使用高德官方夜间地图样式
        map.setMapType(if (isNightMode()) AMap.MAP_TYPE_NIGHT else AMap.MAP_TYPE_NORMAL)
        map.moveCamera(CameraUpdateFactory.newLatLngZoom(DEFAULT_CENTER, 12f))
        if (App.hasLocationPermission(this)) {
            enableMyLocation()
        }

        // 每次冷启动固定落在「今日」；会话内（切主题重建）记住当前页签
        val tabId = if (sessionTabId == 0) R.id.tab_today else sessionTabId
        TimeRanges.forTab(tabId).let { (from, to) ->
            rangeFrom = from
            rangeTo = to
        }
        binding.bottomNav.setOnItemSelectedListener { item ->
            sessionTabId = item.itemId
            TimeRanges.forTab(item.itemId).let { (from, to) ->
                rangeFrom = from
                rangeTo = to
                refreshTrack()
            }
            true
        }
        binding.bottomNav.selectedItemId = tabId
        refreshTrack()

        binding.btnLog.setOnClickListener { showTrackLog() }
        binding.btnSport.setOnClickListener { onSportToggle() }
        binding.btnTheme.setOnClickListener { toggleTheme() }

        observeInfoCard()

        // 运动模式实时状态：按钮/红线/信息卡
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                TrackingManager.state.collect { renderSport(it) }
            }
        }

        if (App.hasLocationPermission(this)) {
            onPermissionsReady()
        } else {
            pendingStart = true
            requestFgPermissions()
        }
    }

    private fun toggleTheme() {
        val newDark = !isNightMode()
        prefs.edit().putBoolean(PREF_DARK, newDark).apply()
        AppCompatDelegate.setDefaultNightMode(
            if (newDark) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
        )
        // setDefaultNightMode 会自动重建 Activity，地图类型在 setupUi 里按新模式设置
    }

    private fun isNightMode(): Boolean =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES

    /** 信息卡：今日点数 + 总距离，随数据库实时刷新。
     *  运动模式进行中让位给实时运动卡片（renderSport 负责写入） */
    private fun observeInfoCard() {
        val todayStart = TimeRanges.todayStart()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                repository.observeStats(todayStart).collect { stats ->
                    if (TrackingManager.state.value.isTracking) return@collect
                    binding.infoCard.text =
                        getString(R.string.info_card, stats.todayCount, Format.km(stats.totalMeters))
                }
            }
        }
    }

    /** 按当前页签时间范围重画轨迹线 */
    private fun refreshTrack() {
        val from = rangeFrom
        val to = rangeTo
        lifecycleScope.launch {
            val points = withContext(Dispatchers.IO) { repository.pointsBetween(from, to) }
            drawTrack(points)
        }
    }

    private fun drawTrack(points: List<PointEntity>) {
        polyline?.remove()
        polyline = null
        polylines.forEach { it.remove() }
        polylines.clear()
        markers.forEach { it.remove() }
        markers.clear()
        if (points.isEmpty()) return
        val map = binding.mapView.map

        // 抽稀：点太多时均匀抽样，避免长跨度下画线卡顿（统计数字仍来自 SQL 全量）
        val display = if (points.size > MAX_DRAW_POINTS) {
            val step = points.size / MAX_DRAW_POINTS + 1
            points.filterIndexed { index, _ -> index % step == 0 } + points.last()
        } else {
            points
        }
        val latLngs = display.map { LatLng(it.lat, it.lng) }

        // 线：按时间顺序连接全部点（v4.3：不再做显著性断线）。
        // 防漂移只保留在里程计算（PointRepository 显著性门槛）——公里数不虚增。
        // v4.6：仅「一生」页不画线（只看一生到过的点），其他页 = 点 + 顺序连线。
        // 红色细线（比圆点略细），与点视觉统一
        if (rangeFrom != 0L) {
            val lineColor = ContextCompat.getColor(this, R.color.track_dot)
            val lineWidthPx = (5 * resources.displayMetrics.density).toInt().coerceAtLeast(4)
            polylines += map.addPolyline(
                PolylineOptions().addAll(latLngs).width(lineWidthPx.toFloat()).color(lineColor)
            )
        }

        // 点标记：所有页签都显示红点（室内同位置多点按簇内序号沿黄金角螺旋外扩）。
        // 区别只在：一生页不画线，其他页 = 点 + 线
        if (dotIcon == null) dotIcon = makeDotIcon(
            ContextCompat.getColor(this, R.color.track_dot)
        )
        val shown = points.takeLast(MAX_MARKERS)
        val clusterIndex = HashMap<String, Int>(shown.size)
        shown.forEachIndexed { idx, p ->
            val base = LatLng(p.lat, p.lng)
            val key = String.format(Locale.US, "%.4f,%.4f", p.lat, p.lng)
            val k = clusterIndex.getOrDefault(key, 0)
            clusterIndex[key] = k + 1
            val pos = if (k == 0) base else {
                val angle = k * GOLDEN_ANGLE
                val r = MARKER_SEP_M * kotlin.math.sqrt(k.toDouble())
                // 偏移量直接以度表示（1 纬度 ≈ 111320 米），不要再做弧度换算
                LatLng(
                    base.latitude + r * kotlin.math.sin(angle) / 111_320.0,
                    base.longitude + r * kotlin.math.cos(angle) /
                            (111_320.0 * kotlin.math.cos(Math.toRadians(base.latitude)))
                )
            }
            Log.d(TAG, "marker[$idx] k=$k pos=(${pos.latitude},${pos.longitude})")
            markers += map.addMarker(
                MarkerOptions().position(pos).icon(dotIcon).anchor(0.5f, 0.5f)
            )
        }

        // 视野自适应：计算所有点的经纬度包络盒，让整条线刚好铺满屏幕
        var minLat = 90.0; var maxLat = -90.0
        var minLng = 180.0; var maxLng = -180.0
        latLngs.forEach {
            if (it.latitude < minLat) minLat = it.latitude
            if (it.latitude > maxLat) maxLat = it.latitude
            if (it.longitude < minLng) minLng = it.longitude
            if (it.longitude > maxLng) maxLng = it.longitude
        }
        if (latLngs.size >= 2 &&
            (minLat != maxLat || minLng != maxLng)
        ) {
            val bounds = LatLngBounds(LatLng(minLat, minLng), LatLng(maxLat, maxLng))
            try {
                binding.mapView.map.moveCamera(
                    CameraUpdateFactory.newLatLngBounds(bounds, 120)
                )
            } catch (e: Exception) {
                binding.mapView.map.moveCamera(
                    CameraUpdateFactory.newLatLngZoom(latLngs.first(), 14f)
                )
            }
        } else {
            // 所有点重合（如一直在室内）：零面积包围盒会把视野放到最大级，
            // 固定 16 级让周边的点群和道路可见
            binding.mapView.map.moveCamera(
                CameraUpdateFactory.newLatLngZoom(latLngs.first(), 16f)
            )
        }
    }

    /** 运动模式开关：开始 = 前台连续定位服务；停止 = 二次确认 */
    private fun onSportToggle() {
        if (TrackingManager.state.value.isTracking) {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.sport_confirm_stop_title)
                .setMessage(R.string.sport_confirm_stop_msg)
                .setPositiveButton("结束") { _, _ -> TrackingService.stop(this) }
                .setNegativeButton("继续记录", null)
                .show()
        } else {
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(
                    this, Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                TrackingService.start(this)
            }
        }
    }

    /** 运动模式实时渲染：红线 + 信息卡 + 按钮态 */
    private fun renderSport(s: TrackingManager.SportState) {
        val map = binding.mapView.map
        if (s.isTracking) {
            binding.btnSport.text = getString(R.string.sport_stop)
            binding.btnSport.setIconResource(R.drawable.ic_stop)
            if (s.points.size >= 2) {
            val opts = PolylineOptions()
                .addAll(s.points)
                .width((5 * resources.displayMetrics.density).toFloat().coerceAtLeast(4f))
                .color(ContextCompat.getColor(this@MainActivity, R.color.track_dot))
                if (sportPolyline == null) {
                    sportPolyline = map.addPolyline(opts)
                } else {
                    sportPolyline?.points = s.points
                }
            }
            val elapsedMin = ((System.currentTimeMillis() - s.startedAt) / 60000L).coerceAtLeast(1)
            val avgKmh = s.distanceMeters / (elapsedMin * 60.0) * 3.6
            binding.infoCard.text = getString(
                R.string.sport_card,
                Format.km(s.distanceMeters), s.pointCount,
                String.format(java.util.Locale.CHINA, "%.1f", avgKmh)
            )
        } else {
            binding.btnSport.text = getString(R.string.sport_start)
            binding.btnSport.setIconResource(R.drawable.ic_walk)
            sportPolyline?.remove()
            sportPolyline = null
            // 停止后数据库已更新，统计流会自动把信息卡恢复为每日统计
        }
    }

    /** 轨迹日志：当前页签时间范围内的点，倒序列出（时间 + 地址），点击在地图上定位 */
    private fun showTrackLog() {
        lifecycleScope.launch {
            val pts = withContext(Dispatchers.IO) {
                repository.pointsBetween(rangeFrom, rangeTo).takeLast(MAX_LOG_ITEMS).reversed()
            }
            if (pts.isEmpty()) {
                toast("当前时间段还没有轨迹点")
                return@launch
            }
            val sheet = BottomSheetDialog(this@MainActivity)
            val sb = BottomSheetLogBinding.inflate(layoutInflater)
            sb.tvTitle.text = getString(R.string.log_title_count, pts.size)
            sb.recycler.layoutManager = LinearLayoutManager(this@MainActivity)
            sb.recycler.adapter = LogPointAdapter(pts) { p ->
                sheet.dismiss()
                focusPoint(p)
            }
            sb.btnClear.setOnClickListener { confirmClearAll(sheet) }
            sheet.setContentView(sb.root)
            sheet.show()
        }
    }

    /** 清空全部轨迹数据：双重确认，清完立即刷新地图与统计 */
    private fun confirmClearAll(sheet: BottomSheetDialog) {
        MaterialAlertDialogBuilder(this)
            .setTitle("清空全部数据")
            .setMessage("将永久删除所有日期的全部轨迹记录，不可恢复。确定清空吗？")
            .setPositiveButton("继续") { _, _ ->
                MaterialAlertDialogBuilder(this)
                    .setTitle("再次确认")
                    .setMessage("清空后无法找回，你的足迹将从零开始重新记录。真的要清空吗？")
                    .setPositiveButton("清空") { _, _ ->
                        lifecycleScope.launch {
                            withContext(Dispatchers.IO) { repository.clearAll() }
                            Toast.makeText(this@MainActivity, "已清空，从现在开始重新记录", Toast.LENGTH_LONG).show()
                            sheet.dismiss()
                            refreshTrack()
                        }
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 地图居中放大到指定轨迹点，并放一颗橙色高亮标记 */
    private fun focusPoint(p: PointEntity) {
        val pos = LatLng(p.lat, p.lng)
        binding.mapView.map.moveCamera(CameraUpdateFactory.newLatLngZoom(pos, 17f))
        if (highlightIcon == null) {
            highlightIcon = makeDotIcon(
                ContextCompat.getColor(this, R.color.highlight_dot), sizeDp = 14
            )
        }
        highlightMarker?.remove()
        highlightMarker = binding.mapView.map.addMarker(
            MarkerOptions().position(pos).icon(highlightIcon).anchor(0.5f, 0.5f)
        )
    }

    /** 生成小圆点图标：实心圆 + 细白描边 */
    private fun makeDotIcon(color: Int, sizeDp: Int = 8): BitmapDescriptor {
        val size = (sizeDp * resources.displayMetrics.density).toInt().coerceAtLeast(6)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val r = size / 2f - 1f
        paint.color = color
        canvas.drawCircle(size / 2f, size / 2f, r, paint)
        paint.color = Color.WHITE
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.5f
        canvas.drawCircle(size / 2f, size / 2f, r - 1f, paint)
        return BitmapDescriptorFactory.fromBitmap(bmp)
    }

    private fun onPermissionsReady() {
        App.ensureTrackingScheduled(this)
        App.recordNow(this)
        pendingStart = false
        askIgnoreBatteryOptimizations()
    }

    private fun enableMyLocation() {
        val style = MyLocationStyle()
        style.myLocationType(MyLocationStyle.LOCATION_TYPE_LOCATE)
        binding.mapView.map.myLocationStyle = style
        binding.mapView.map.isMyLocationEnabled = true
    }

    private fun requestFgPermissions() {
        fgPermissionLauncher.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        )
    }

    /** 请求加入电池优化白名单（仅询问一次），降低后台 15 分钟任务被系统冻结的概率 */
    private fun askIgnoreBatteryOptimizations() {
        val pm = getSystemService(PowerManager::class.java)
        if (pm.isIgnoringBatteryOptimizations(packageName)) return
        if (prefs.getBoolean("battery_asked", false)) return
        prefs.edit().putBoolean("battery_asked", true).apply()
        try {
            startActivity(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName")
                )
            )
        } catch (e: Exception) {
            // 部分ROM不支持该页面，忽略即可
        }
    }

    override fun onResume() {
        super.onResume()
        binding.mapView.onResume()
        // 回到前台时重画轨迹：补上 App 不在前台期间新记录的点
        refreshTrack()
    }

    override fun onPause() {
        binding.mapView.onPause()
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        binding.mapView.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        binding.mapView.onDestroy()
        super.onDestroy()
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    companion object {
        private const val TAG = "FootprintsMap"
        private const val PREF_DARK = "dark_mode"
        /** 进程内记住页签：冷启动清零（回到「今日」），切主题重建时保留 */
        var sessionTabId: Int = 0
        private val DEFAULT_CENTER = LatLng(39.90923, 116.397428)
        private const val MAX_DRAW_POINTS = 10_000
        private const val MAX_MARKERS = 500
        private const val MAX_LOG_ITEMS = 300
        /** 重叠点错开显示的间距（米）与黄金角。间距需明显大于点的屏幕尺寸，
         *  否则原地多次记录的点仍会糊成一团；也不宜过大，避免看起来像散落的假位置 */
        private const val MARKER_SEP_M = 20f
        private const val GOLDEN_ANGLE = 2.399963
    }
}
