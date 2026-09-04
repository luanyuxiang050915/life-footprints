# 我的足迹（Footprints）技术文档

一款**被动式记录一生足迹**的 Android 应用：装好后无需任何操作，每 15 分钟自动在地图上记录一个
精确经纬度点，全部存放在手机本地，并把点连成线——代表你走过的路。另有「运动模式」可连续
记录完整行进路线。

- **当前版本**：v4.6（versionCode 22）
- **包名**：`com.footprints.app`，应用名「我的足迹」
- **本文档面向**：想了解或继续开发此项目的人，逐一说明每个功能是怎么实现的、为什么这样实现

---

## 目录

1. [技术栈](#1-技术栈)
2. [整体架构与数据流](#2-整体架构与数据流)
3. [核心功能实现](#3-核心功能实现)
   - 3.1 [自动记录：WorkManager 每 15 分钟记一个点](#31-自动记录workmanager-每-15-分钟记一个点)
   - 3.2 [定位质量：开窗择优 + 差精度快速补测](#32-定位质量开窗择优--差精度快速补测)
   - 3.3 [数据存储：Room 与 distPrev / address 设计](#33-数据存储room-与-distprev--address-设计)
   - 3.4 [主界面：地图绘制规则（点与线）](#34-主界面地图绘制规则点与线)
   - 3.5 [五个时间页签与「今日」首屏](#35-五个时间页签与今日首屏)
   - 3.6 [轨迹日志列表（v3.0）](#36-轨迹日志列表v30)
   - 3.7 [运动模式：连续记录完整行进路线（v4.0）](#37-运动模式连续记录完整行进路线v40)
   - 3.8 [暗黑主题（黑色地图）](#38-暗黑主题黑色地图)
   - 3.9 [权限申请与后台保活](#39-权限申请与后台保活)
   - 3.10 [隐私合规（高德 SDK 要求）](#310-隐私合规高德-sdk-要求)
4. [设计决策与踩坑记录](#4-设计决策与踩坑记录)
5. [构建、签名与发布](#5-构建签名与发布)
6. [目录结构](#6-目录结构)

---

## 1. 技术栈

| 项 | 选型 | 版本 | 说明 |
|----|------|------|------|
| 语言 | Kotlin | 2.0.21 | 协程 + Flow |
| 界面 | XML + ViewBinding + Material 3 | material 1.12.0 | 单 Activity（无 Compose，地图 View 互操作简单可靠） |
| 地图/定位 | 高德 Android SDK | 3dmap 10.0.600 | **内置定位模块与逆地理编码**，勿再引 location/search 包 |
| 后台调度 | WorkManager | 2.9.1 | 15 分钟周期任务 + 补测一次性任务，Doze 兼容、重启恢复 |
| 连续记录 | 前台服务 | — | `foregroundServiceType="location"`，运动模式专用 |
| 数据存储 | Room（SQLite） | 2.6.1 | 单表 `points`，KSP 注解处理，正规 Migration |
| 构建工具链 | AGP 8.7.3 / Gradle 8.9 / JDK 21 | — | minSdk 26 / targetSdk 34 / compileSdk 34 |

---

## 2. 整体架构与数据流

```
┌──────────────────────────── 两条记录通道 ────────────────────────────┐
│                                                                      │
│  日常模式（默认，省电）                                               │
│  WorkManager 15 分钟周期 → LocationWorker.doWork()                   │
│      │ 无权限/运动中 → 跳过                                          │
│      │ 主线程：连续定位 15 秒取精度最高一次（开窗择优）                │
│      │ 精度 >1km 丢弃；≤150m 直接落库；                    │
│      │ >150m 落库后另排 3 分钟后补测（最多 4 次，补测仍差不落库）      │
│                                                                      │
│  运动模式（手动开启，完整路线）                                        │
│  TrackingService（前台服务，type=location）                           │
│      │ 连续定位 2 秒一次，距上一落库点 ≥8 米才入库                     │
│      │ 通知栏常驻（实时距离 + 停止按钮）                               │
│                                                                      │
│  共同落库：PointRepository.addPoint()                                 │
│      │ distPrev = 与上一点地面距离（显著性门槛 / 运动步进）            │
│      ▼                                                               │
│  Room「points」表（lat/lng/accuracy/time/distPrev/address，仅本地）   │
└──────────────────────────────────────────────────────────────────────┘
                    │ Flow<LifeStats>        │ pointsBetween(from,to)
                    ▼                        ▼
┌──────────────────────────────────────────────────────────────────────┐
│                        MainActivity（唯一界面）                        │
│  全屏高德地图                                                          │
│  ├─ 底部页签：一生 / 今日 / 昨日 / 七日 / 此月（冷启动固定落在「今日」） │
│  │    · 一生页：只显示红色圆点（一生到过的地方一览）                    │
│  │    · 其他页：红点 + 按时间顺序连成的红色细线（行进轨迹）             │
│  ├─ 右下角信息卡：日常=今日点数/总里程；运动中=实时距离/点数/均速       │
│  ├─ 左上角 ☰ 轨迹日志：时间+地址列表，点击条目地图居中并橙色高亮        │
│  │    └─ 标题行「清空全部」：双重确认后清空重来                         │
│  └─ 右上角：运动模式开关 ／ 暗黑⇄亮色主题切换                          │
└──────────────────────────────────────────────────────────────────────┘
```

没有服务器、没有账号体系：**所有数据只存在手机本地**（地图瓦片需联网加载）。

---

## 3. 核心功能实现

### 3.1 自动记录：WorkManager 每 15 分钟记一个点

**需求**：被动记录，用户零操作，每 15 分钟一个点。

**方案对比**（本项目最关键的设计决策）：

| 方案 | 问题 |
|------|------|
| 前台服务常驻定位（v1 用过） | 7×24 常驻通知、持续耗电、国产 ROM 杀后台严重 |
| `AlarmManager` 精确闹钟 | Doze 下触发受限，部分 ROM 静默拦截 |
| **WorkManager 周期任务（采用）** | 系统级调度、Doze 兼容、**重启自动恢复**、耗电极低 |

**实现**（`App.kt`）：

```kotlin
/** 每 15 分钟自动记录一个点（KEEP：已调度则不重复，任意时机调用都幂等） */
fun ensureTrackingScheduled(context: Context) {
    val request = PeriodicWorkRequestBuilder<LocationWorker>(15, TimeUnit.MINUTES).build()
    WorkManager.getInstance(context).enqueueUniquePeriodicWork(
        WORK_PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request
    )
}

/** 打开 App 时立即补记一个点（运动模式进行中则跳过） */
fun recordNow(context: Context) { ... }
```

- 调度入口三处：`App.onCreate`（进程启动）、隐私协议同意后、权限刚拿齐时——保证任何路径下任务都在
- WorkManager 周期任务最小间隔就是 15 分钟（系统限制），与需求恰好一致；手机重启后自动恢复
- 「约 15 分钟」在 Doze 下会有几分钟级漂移，对「一生足迹」粒度完全可接受

### 3.2 定位质量：开窗择优 + 差精度快速补测

**文件**：`worker/LocationWorker.kt`。定位质量是本应用的核心体验，经历三轮迭代：

**① 开窗择优（替代单发定位）**：单发定位拿到的常是缓存的网络粗定位（室内实测 550m）。
改为连续定位 15 秒（每 2 秒一次），取精度数值最小的一次落库——同场景实测拿到 75m，
对刚出建筑物、信号恢复的场景收益最大：

```kotlin
// doWork() 中：
val location = withTimeoutOrNull(LOCATE_TIMEOUT_MS) {
    withContext(Dispatchers.Main) { locateBest(context) }   // 必须切主线程，见踩坑 #2
}

private suspend fun locateBest(context: Context): AMapLocation? =
    suspendCancellableCoroutine { cont ->
        val client = AMapLocationClient(context)
        val option = AMapLocationClientOption().apply {
            locationMode = Hight_Accuracy
            isOnceLocation = false          // 连续模式
            interval = LOCATE_INTERVAL_MS   // 2 秒一次
            isSensorEnable = true           // 传感器辅助，提升室内质量
            isNeedAddress = true            // 结果自带逆地理编码地址
        }
        client.setLocationOption(option)
        var best: AMapLocation? = null
        client.setLocationListener { loc ->
            if (loc.errorCode == 0 && loc.accuracy > 0f &&
                (best == null || loc.accuracy < best!!.accuracy)) best = loc
        }
        // 15 秒后收窗：stopLocation + onDestroy + cont.resume(best)
        ...
        client.startLocation()
        handler.postDelayed(finish, LOCATE_WINDOW_MS)
    }
```

**② 精度过滤与快速补测**：

```kotlin
if (location.accuracy > MAX_ACCURACY) return Result.success()   // 只拦 >1km 的异常粗定位

if (location.accuracy > GOOD_ACCURACY) {          // 150m：够准就不补测
    if (attempt < MAX_RETRY) scheduleRetry(context, attempt + 1)   // 3 分钟后补测
    if (attempt > 0) return Result.success()      // 补测仍不准：不落库，避免差点刷屏
}
repo.addPoint(...)                                // 正式周期：差点照记（宁滥勿缺，不留空档）
```

- 补测用一次性 Work（`ExistingWorkPolicy.REPLACE`，同时只有一个待补测），attempt 计数走
  `inputData`；实测室内 550m 级点之后 3~10 分钟内能补到 11~75m 的准点
- **宁可缺一个点，也不记脏数据**：超时/错误码/精度异常一律静默放弃，下个周期自然重试
- **宁滥勿缺的另一面**：正式周期的差点照记——「一生足迹」不能有时间空档

**③ 逆地理编码不引搜索 SDK**：`isNeedAddress = true` 让 `location.address` 直接返回完整地址
（v3.0 起随点落库，存的是「记录那一刻」的地址），见踩坑 #15。

### 3.3 数据存储：Room 与 distPrev / address 设计

**表结构**（`data/PointEntity.kt`，数据库 v3）：

```kotlin
@Entity(tableName = "points")
data class PointEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val lat: Double,           // GCJ-02 经纬度（高德坐标系，与地图一致）
    val lng: Double,
    val accuracy: Float,       // 该点定位精度（米）
    val time: Long,            // GPS 时间戳（非系统时间，补录不失真）
    val distPrev: Double = 0.0,// 与上一点地面距离（米），O(1) 汇总总里程
    @ColumnInfo(defaultValue = "") val address: String = "",  // 记录时刻的地址（v3 迁移新增）
)
```

**distPrev 的两个语义**（`PointRepository.addPoint`）：

- 日常模式（15 分钟点）：**显著性门槛**——位移 ≤ 两点精度之和视为原地未动，`distPrev = 0`。
  室内漂移（精度几百米、坐标乱跳）不会虚增里程；户外真实移动必然超过门槛
- 运动模式：自有 8 米去重，传入 `distPrevOverride` 直接累加实际步进

于是总里程是一条 O(1) SQL（`SUM(distPrev)`），与点数无关。信息卡的「今日点数 + 总距离」
也是一条聚合查询（`observeStats`），返回 `Flow`，任何插入自动推流刷新 UI。

**迁移纪律**：v2→v3 用正规 `Migration`（`ALTER TABLE ... ADD COLUMN address TEXT NOT NULL
DEFAULT ''`），**绝不删库重建**——那是用户的一生数据。实体侧用 `@ColumnInfo(defaultValue="")`
保证 Room 校验两侧 schema 一致（见踩坑 #16）。

### 3.4 主界面：地图绘制规则（点与线）

**文件**：`ui/MainActivity.kt`。所有元素在 `drawTrack(points)` 中统一重绘（页签切换、
回到前台、清空数据后触发）：

| 元素 | 规则 |
|------|------|
| 红色圆点 | **所有页签都画**。8dp 实心红圆 + 白描边（`BitmapDescriptorFactory.fromBitmap`，屏幕尺寸恒定）；同一位置的多条记录按**簇内序号**沿黄金角螺旋逐个外扩 20m（仅显示层，不改坐标）；最多画最近 500 个 |
| 轨迹线 | **仅「今日/昨日/七日/此月」页画**（一生页只有点）；红色 5dp 细线（比圆点略细），**按时间顺序连接全部点、永不断线**；超过 1 万点均匀抽稀（统计数字仍走 SQL 全量） |
| 高亮标记 | 日志列表点选时：橙色 14dp 圆点 + `newLatLngZoom(17f)` 居中 |
| 我的位置 | `MyLocationStyle.LOCATION_TYPE_LOCATE`，精度圈诚实显示定位质量 |

视野自适应：所有点的包络盒 `newLatLngBounds(bounds, 120)`；**所有点重合时（零面积包围盒）
会把视野缩放到最大级**，需判 `minLat==maxLat && minLng==maxLng` 改用 `newLatLngZoom(16f)`
（见踩坑 #11）。

### 3.5 五个时间页签与「今日」首屏

**文件**：`util/TimeRanges.kt`。每个页签对应查询区间 `[from, to]`：

| 页签 | 区间 |
|------|------|
| 一生 | `[0, +∞)` |
| 今日 | `[今天 0 点, +∞)` |
| 昨日 | `[昨天 0 点, 今天 0 点)` |
| 七日 | `[6 天前 0 点, +∞)` |
| 此月 | `[本月 1 号 0 点, +∞)` |

- **v4.2 起：每次冷启动固定落在「今日」**（需求方要求首屏即今日）；会话内用 companion 的
  `sessionTabId` 记住当前页签，切主题等 Activity 重建不跳页
- `setSelectedItemId` 传相同 id **不会**触发监听器——初始化不能只依赖监听器建状态，
  要显式补一次 `refreshTrack()`

### 3.6 轨迹日志列表（v3.0）

左上角 ☰ 按钮 → 底部弹层（`BottomSheetDialog`）：当前页签范围、最新在前、最多 300 条，
每行显示「几点几分 + 记录时刻的地址」（`Format.pointTime` + 库内 `address` 列，旧数据回退
显示坐标精度）。点某一条 → 地图居中该点并放橙色高亮标记。

标题行右侧红字「**清空全部**」（v4.1）：双重确认后 `DELETE FROM points`，地图与统计自动
归零，随后即从头记录。权限/偏好不受影响。

### 3.7 运动模式：连续记录完整行进路线（v4.0）

15 分钟一个点在物理上不可能还原街道级行走路线。参考同类 App 的「运动模式」，双模式并存：

| 模式 | 机制 | 适用 |
|------|------|------|
| 日常（默认） | WorkManager 15 分钟一个点，省电无感 | 一生足迹的底图 |
| **运动模式** | 前台服务连续定位 2 秒一次，移动 ≥8 米才落库 | 徒步/骑行/遛弯，街道级完整路线 |

- **实现**：`TrackingService`（`foregroundServiceType="location"`，START_STICKY，系统杀进程后
  以 null intent 重启并继续记录）+ `TrackingManager`（StateFlow 会话状态：点序列/累计距离/
  最速），MainActivity 订阅实时画红色路线；通知栏常驻「已记录 X 千米 · N 个点」+ 停止按钮
- **入口**：顶部「运动模式」按钮；Android 13+ 先请求 `POST_NOTIFICATIONS`；停止需二次确认
- **互斥**：运动进行中 `LocationWorker` 与 `recordNow` 直接跳过，防止重复记点
- **信息卡双写冲突**：运动中统计 Flow 让位（collect 里判 `isTracking`），由 `renderSport`
  独占写入，否则两者互相覆盖（v4.0 踩坑 #19）
- **代价（诚实版）**：连续 GPS 约增加 5~10%/小时耗电，故设计为手动开关而非默认

### 3.8 暗黑主题（黑色地图）

三层联动：

**① 应用层**：偏好持久化 + `AppCompatDelegate.setDefaultNightMode`，切换后 Activity 自动
重建；**默认暗黑**。

**② 资源层**：`values-night/` 自动替换同名资源：

| 资源 | 亮色 | 暗黑 |
|------|------|------|
| `track_line`（已弃用，线现用 track_dot） | — | — |
| `track_dot` 轨迹点/线 | `#E53935` | `#FF6E62` |
| `highlight_dot` 日志高亮点 | `#FF8F00` | `#FFB300` |
| `info_card_bg/text` 信息卡 | 白底深青字 | 深灰底浅青字 |
| 状态栏 | 深青 | 纯黑 |

底部导航用 Material3 `Theme.Material3.DayNight`，颜色属性自动跟随，零代码。

**③ 地图层**：暗黑下切高德官方夜间样式 `AMAP.MAP_TYPE_NIGHT`（深灰黑色调瓦片）：

```kotlin
map.setMapType(if (isNightMode()) AMap.MAP_TYPE_NIGHT else AMap.MAP_TYPE_NORMAL)
```

`isNightMode()` 读 `Configuration.UI_MODE_NIGHT_MASK`。切主题会重建 Activity，重建后的
`setupUi()` 按新模式重设——三层始终同步。

> 想要**纯黑**（高德「幻影黑」效果）需在高德控制台「自定义地图」制作样式并导出样式数据文件，
> 通过 `CustomMapStyleOptions.setStyleData()` 加载，属后续可扩展项。

### 3.9 权限申请与后台保活

**权限清单**：`FINE/COARSE_LOCATION`、`BACKGROUND_LOCATION`、`FOREGROUND_SERVICE(_LOCATION)`、
`POST_NOTIFICATIONS`、`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`。

**分步申请**（Android 10+ 硬性要求：后台定位必须在前台定位授予后单独发起）：

```
启动 → 弹前台定位（FINE+COARSE，选「精确」）→ 授予
     → Android 10+：单独弹后台定位「始终允许」
     → 无论结果：ensureTrackingScheduled() + recordNow()
     → 电池优化白名单（只询问一次，prefs 记录）
运动模式首次开启时：请求 POST_NOTIFICATIONS（无论结果都开始记录）
```

即使后台定位被拒也不中断：前台权限下打开 App 时 Worker 仍能记点。

**保活三板斧**：① 后台定位「始终允许」；② 电池优化白名单；③ 国产 ROM（小米/华为/OPPO 等）
设置里开「自启动」+ 省电策略「无限制」。WorkManager 的系统级持久化是地基。

### 3.10 隐私合规（高德 SDK 要求）

高德官方要求：调用任何地图/定位能力前必须完成隐私合规接口，否则 SDK 拒绝工作（地图空白、
定位回调错误）。实现（`App.kt`）：

```kotlin
fun initAmapPrivacy(context: Context) {
    MapsInitializer.updatePrivacyShow(context, true, true)
    MapsInitializer.updatePrivacyAgree(context, true)
    AMapLocationClient.updatePrivacyShow(context, true, true)
    AMapLocationClient.updatePrivacyAgree(context, true)
}
```

首次启动弹隐私说明对话框，同意后立即调用并落盘 `privacy_agreed`；之后每次进程启动在
`App.onCreate` 里补调——**必须早于任何 MapView/LocationClient 的创建**。

---

## 4. 设计决策与踩坑记录

真实开发中实际遇到并解决的问题（对应版本号 = 修复版本），继续开发前建议先读一遍：

1. **3dmap 10.x 内置了定位模块**：引入 `com.amap.api:location` 报上百个 Duplicate class。
   只用 `com.amap.api:3dmap`，定位类都在里面。
2. **高德定位 Client 必须在带 Looper 的线程（主线程）创建/启动**（v2.2）：SDK 内部建 Handler，
   在 CoroutineWorker 默认线程直接跑抛 `Can't create handler inside thread...`，Worker 静默
   失败——症状就是「权限全对但点数永远为 0」。解法 `withContext(Dispatchers.Main) { locate() }`。
3. **室内别把定位精度门槛卡太死**（v2.3）：室内 WiFi/基站定位普遍 300~600m，300m 门槛 = 室内
   永远 0 点。放宽到 1km 保证记点 + 开窗择优 + 里程显著性门槛兜底。
4. **`newLatLngBounds` 传零面积包围盒（所有点重合）会把视野缩放到最大级**（v2.8）：室内原地
   场景下屏幕只有几十米宽。判 `minLat==maxLat && minLng==maxLng` 改用 `newLatLngZoom(16f)`。
5. **坐标偏移别重复换算单位**（v2.7）：`r*sin/111320` 已是度数，再包 `Math.toDegrees()` 放大
   57 倍，50 米错开变 2.8 公里，点直接"飞出"屏幕。
6. **重叠点错开要用「簇内序号」**（v2.9）：按四舍五入坐标分组计数取螺旋位置；不能用
   「数附近已放置点」——错开后的点逃出判定半径，序号永远相同，全部叠在同一位置。
7. **`Math`/回调转协程防重入**：高德定位回调可能多次触发，`suspendCancellableCoroutine` 需要
   `AtomicBoolean` 守卫 + 回调里 `stopLocation()/onDestroy()`，`invokeOnCancellation` 清理。
8. **`setSelectedItemId` 等值不触发监听**：初始化要显式补一次刷新。
9. **搜索 SDK（search）与 3dmap 类冲突**（v3.0）：两者都内置 `com.amap.apis.utils.core.api.*`。
   逆地理编码用定位 SDK 的 `isNeedAddress=true` 免依赖解决。
10. **加数据库列用正规 Migration**（v3.0）：`@ColumnInfo(defaultValue="")` +
    `ALTER TABLE ... ADD COLUMN ... NOT NULL DEFAULT ''`，两侧 schema 含 DEFAULT 完全一致才
    能过 Room 校验。
11. **开窗择优定位大幅提升精度**（v2.11）：连续 15 秒取 accuracy 最小一次，室内实测
    550m → 75m → 11m。另：`isWifiActiveScans` 属性在内置定位类中不存在（`isSensorEnable` 存在）。
12. **补测与信息卡双写**（v4.0）：两个 Flow 都写同一 TextView 会互相覆盖——统计收集里判
    `isTracking` 让位给运动卡片。
13. **MIUI/HyperOS force-stop 后全部冻结**（后台任务/前台服务都不跑），直到用户再打开 App；
    真机验证要在启动 App 后观察。
14. **中文项目路径**触发 AGP 路径检查：`gradle.properties` 加 `android.overridePathCheck=true`。
15. **Room+KSP（Kotlin 2.0.21）对同文件混合声明敏感**：实体 + DAO 写一个文件报
    `[MissingType]` 且 clean 无效；拆文件 + 聚合查询返回 POJO 解决。
16. **高德 `MapView` 没有 `getMapAsync`**（那是 Google Maps 的 API）：同步 `mapView.map`。
17. **真机验证渲染的高效组合拳**：`adb exec-out screencap -p` 截图 + PowerShell `GetPixel`
    扫特征色像素聚类 + `logcat` 打 Marker 坐标，三层证据相互印证，比肉眼看截图快得多。
18. **Git Bash 的 `tar` 解不了 zip**：用 `unzip` / `powershell Expand-Archive`。

---

## 5. 构建、签名与发布

### 环境要求

JDK 21、Android SDK（`local.properties` 的 `sdk.dir` 已指定）、Gradle 8.9（wrapper 已配置）。
`gradle.properties` 已含 `android.overridePathCheck=true`（中文路径）。

### 高德 Key

按「包名 + 签名 SHA1」绑定，已配置在 `local.properties` 并经 manifest 占位符注入：

```kotlin
manifestPlaceholders["AMAP_KEY"] = localProps.getProperty("AMAP_KEY", "")
```

换 Key/换签名到 [高德控制台](https://console.amap.com/dev/key/app) 重新绑定，SHA1 查看：

```bash
keytool -list -v -keystore signing/footprints.jks -storepass footprints2026 -alias footprints
```

### 打包

```bash
gradlew.bat assembleRelease
# 产物：app/build/outputs/apk/release/app-release.apk（已签名，可直接安装）
```

- debug 与 release **共用同一 keystore**，保证高德 SHA1 一致、调试包也能显示地图
- 发布前递增 `versionCode`（覆盖安装要求新版本号更大）

### 签名信息（务必妥善保管）

| 项 | 值 |
|----|----|
| keystore | `signing/footprints.jks` |
| 别名 | `footprints` |
| 密码（store/key） | `footprints2026` |
| 发布版 SHA1 | `5B:2B:26:D5:51:DD:E8:8D:61:80:18:1B:E9:4A:2A:30:BD:DA:8C:CE` |

> **升级安装必须使用同一个 keystore**，否则系统拒绝覆盖安装。请备份 `signing/footprints.jks`。

---

## 6. 目录结构

```
app/src/main/
├── AndroidManifest.xml              # 权限、AMAP_KEY 占位符、TrackingService 声明
├── java/com/footprints/app/
│   ├── App.kt                       # Application：隐私合规、WorkManager 调度、暗黑默认
│   ├── data/
│   │   ├── PointEntity.kt           # Room 实体（经纬度/精度/时间/distPrev/address）
│   │   ├── PointDao.kt              # DAO + LifeStats 聚合查询 + clearAll
│   │   ├── AppDatabase.kt           # 数据库 v3 + MIGRATION_2_3
│   │   └── PointRepository.kt       # addPoint（显著性门槛/步进覆盖）、区间查询、清空
│   ├── worker/
│   │   └── LocationWorker.kt        # 15 分钟周期：开窗择优定位→过滤→补测调度→落库
│   ├── service/
│   │   ├── TrackingService.kt       # 运动模式前台服务：2 秒连续定位、8m 去重、常驻通知
│   │   └── TrackingManager.kt       # 运动会话状态（StateFlow）
│   ├── ui/
│   │   ├── MainActivity.kt          # 全屏地图+页签+信息卡+日志+运动开关+主题切换
│   │   └── LogPointAdapter.kt       # 轨迹日志列表适配器
│   └── util/
│       ├── TimeRanges.kt            # 五个页签 → 查询区间
│       └── Format.kt                # 千米格式化、点时间格式化
└── res/
    ├── layout/activity_main.xml     # 地图 + 日志/运动/主题按钮 + 信息卡 + 底部导航
    ├── layout/bottom_sheet_log.xml  # 轨迹日志弹层（含清空全部入口）
    ├── layout/item_log_point.xml    # 日志行（时间 + 地址）
    ├── menu/menu_main.xml           # 一生/今日/昨日/七日/此月
    ├── values/ + values-night/      # 亮色/暗黑：颜色、主题自动替换
    └── drawable/                    # ic_walk/ic_log/ic_sun/ic_moon、信息卡背景等
```

---

## 附：后续可扩展方向

- GPX 导出/导入（标准轨迹格式，方便备份迁移）
- 自定义纯黑地图样式（高德自定义地图 + `CustomMapStyleOptions`）
- 停留点识别（在同一位置停留超过阈值自动标注，参考竞品的「停留点 3 个」）
- 云端备份（端到端加密后上传自己的网盘/服务器）
- 桌面小组件（今日点数速览）
