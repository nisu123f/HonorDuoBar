package io.github.nisu123f.honorduobar.xposed.target

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import io.github.nisu123f.honorduobar.FeatureCatalog
import io.github.nisu123f.honorduobar.xposed.core.MountEngine
import io.github.nisu123f.honorduobar.xposed.core.MountSession
import io.github.nisu123f.honorduobar.xposed.data.RemoteConfig
import java.lang.ref.WeakReference
import kotlin.math.abs
import kotlin.math.min

/**
 * 状态栏三合一（Wi-Fi + 移动信号 + 电量 → 一个 Duo 风格字形）。
 *
 * 思路与 HyperDuo（小米 HyperOS 专用）一致，但实现完全按荣耀 SystemUI 17.0.0.1 的
 * 视图树重写：不碰 battery drawable 槽，而是把自绘 View 插进 system_icons 行，
 * 同时把原生 signalIcons（Wi-Fi/信号组）和 battery 置 GONE。
 *
 * 字形（120×120 设计坐标，按 View 尺寸等比缩放）：
 * - 外圈 = 电量环：底部缺口，亮弧长度按电量百分比；
 * - 环内上部 = Wi-Fi 弧（2 条弧 + 1 个点，按 RSSI 档位）；
 * - 环内下部 = 移动信号点（4 点，单排，只取默认数据卡）；
 * - 环心 = 电量数字（充电时改为闪电，快充琥珀色）；
 * - 无 Wi-Fi 时上部改为 5G/4G 制式文字。
 *
 * 挂载点（本 APK 反编译实证）：
 * - HwSystemIcons.onFinishInflate()：注入时机；
 * - HwSystemIcons.onMeasure(int,int)：前置把原生件压回 GONE（防闪根修，见 assertHidden）；
 * - SignalClusterView.onDarkChanged(Rect,float,int)：跟随状态栏反色（第三参即 tint 色）。
 */
object TrioFeature {

    private const val HOST_PACKAGE = "com.android.systemui"
    private const val CLS_SYSTEM_ICONS = "com.android.systemui.statusbar.HwSystemIcons"
    private const val CLS_SIGNAL_CLUSTER = "com.android.systemui.statusbar.SignalClusterView"
    private const val METHOD_FINISH_INFLATE = "onFinishInflate"
    private const val METHOD_DARK_CHANGED = "onDarkChanged"
    private const val METHOD_LAYOUT = "onLayout"
    private const val METHOD_MEASURE = "onMeasure"

    private const val TAG_VIEW = "hh_trio_view"
    /** 三合一开启时要隐藏的原生件：整组 + 组内单件兜底（id 不存在会自动跳过）。 */
    private val HIDDEN_IDS = arrayOf(
        "signalIcons",
        "battery",
        "wifi_combo",
        "mobile_combo",
        "mobile_signal",
        "mobile_type",
    )
    private const val JUDGE_PREFIX = "SystemUiHook·U9"
    private const val ID_INFLATE = "systemui:trio.systemIcons.onFinishInflate"
    private const val ID_TINT = "systemui:trio.signalCluster.onDarkChanged"
    private const val ID_LAYOUT = "systemui:trio.systemIcons.onLayout"
    private const val ID_MEASURE = "systemui:trio.systemIcons.onMeasure"

    /** 状态栏 tint 色（onDarkChanged 第三参），0 = 尚未收到。 */
    @Volatile private var tintColor = 0
    @Volatile private var currentView: WeakReference<TrioView>? = null

    fun install(module: XposedModule, param: XposedModuleInterface.PackageLoadedParam) {
        if (!RemoteConfig.isEnabled(FeatureCatalog.KEY_SYSTEMUI_TRIO)) {
            logDisabled(module, FeatureCatalog.KEY_SYSTEMUI_TRIO, "SystemUiHook·U9")
            return
        }
        val loader = param.defaultClassLoader
        val session = MountEngine.open(
            "SystemUiHook·U9",
            HOST_PACKAGE,
            param.applicationInfo?.sourceDir,
            FeatureCatalog.baselineVersionOf(HOST_PACKAGE),
        )
        val pending = listOf(
            MountEngine.SimpleTask(
                className = CLS_SYSTEM_ICONS,
                methodName = METHOD_FINISH_INFLATE,
                paramCount = 0,
                returnType = "void",
                id = ID_INFLATE,
                description = "U9 三合一注入（HwSystemIcons.onFinishInflate 后插自绘 View，隐藏原生信号组/电池）",
            ),
            MountEngine.SimpleTask(
                className = CLS_SYSTEM_ICONS,
                methodName = METHOD_MEASURE,
                paramCount = 2,
                returnType = "void",
                id = ID_MEASURE,
                description = "U9 三合一防闪（HwSystemIcons.onMeasure 前把原生件压回 GONE，排版不再含电池宽度）",
            ),
            MountEngine.SimpleTask(
                className = CLS_SYSTEM_ICONS,
                methodName = METHOD_LAYOUT,
                paramCount = 5,
                returnType = "void",
                id = ID_LAYOUT,
                description = "U9 三合一隐藏复位（HwSystemIcons.onLayout 后重申原生信号组/电池 GONE）",
            ),
            MountEngine.SimpleTask(
                className = CLS_SIGNAL_CLUSTER,
                methodName = METHOD_DARK_CHANGED,
                paramCount = 3,
                returnType = "void",
                id = ID_TINT,
                description = "U9 三合一反色（SignalClusterView.onDarkChanged 第三参同步自绘颜色）",
            ),
        )
        session.attemptMount(pending, module, loader) { task -> mountOne(session, task, module, loader) }
    }

    private fun mountOne(
        session: MountSession,
        task: MountEngine.SimpleTask,
        module: XposedModule,
        loader: ClassLoader,
    ): MountEngine.Result = session.mount(task, module, loader) { chain ->
        when (task.id) {
            ID_MEASURE -> {
                // 防闪根修：必须在 measure 之前压回 GONE——宿主电量/信号更新把原生件置回
                // VISIBLE 后，若 measure 已按 VISIBLE 排版，再 GONE 就会触发「含电池宽度→
                // 回跳」的单帧抖动（状态栏闪一下）。这里前置处理，本帧排版即正确。
                (chain.thisObject as? ViewGroup)?.let { host ->
                    runCatching { assertHidden(host) }
                }
                chain.proceed()
            }
            ID_INFLATE -> {
                chain.proceed()
                (chain.thisObject as? ViewGroup)?.let { host ->
                    host.post { runCatching { inject(host, module) } }
                }
            }
            ID_LAYOUT -> {
                chain.proceed()
                (chain.thisObject as? ViewGroup)?.let { host ->
                    runCatching { assertHidden(host) }
                }
            }
            else -> {
                chain.proceed()
                val tint = runCatching { chain.getArg(2) as? Int }.getOrNull()
                if (tint != null && tint != 0) {
                    tintColor = tint
                    currentView?.get()?.invalidate()
                }
            }
        }
        null
    }

    /** id 解析缓存：onMeasure 是高频回调，getIdentifier 字符串查表不能每次都跑。 */
    @Volatile private var hiddenIdCache: IntArray? = null

    /** 宿主偶尔会把电池/信号重新显示（电量/信号更新、AOD/胶囊切换），测量/布局时重申一次隐藏。 */
    private fun assertHidden(host: ViewGroup) {
        if (host.findViewWithTag<View>(TAG_VIEW) == null) return
        // 除整组 signalIcons 与 battery 外，再兜底隐藏组内单件（个别版本会把单件挂到别的容器）
        val ids = hiddenIdCache ?: run {
            val res = host.context.resources
            val pkg = host.context.packageName
            HIDDEN_IDS.mapNotNull { name ->
                res.getIdentifier(name, "id", pkg).takeIf { it != 0 }
            }.toIntArray().also { hiddenIdCache = it }
        }
        for (id in ids) {
            host.findViewById<View>(id)?.let { v ->
                if (v.visibility != View.GONE) v.visibility = View.GONE
            }
        }
    }

    /** 在 system_icons 行尾插入字形，并把原生 Wi-Fi/信号组与电池隐藏。 */
    private fun inject(host: ViewGroup, module: XposedModule) {
        if (host.findViewWithTag<View>(TAG_VIEW) != null) return
        val ctx = host.context
        val pkg = ctx.packageName
        fun id(name: String): Int = ctx.resources.getIdentifier(name, "id", pkg)

        val systemIcons = host.findViewById<ViewGroup>(id("system_icons")) ?: host
        val hiddenTargets = ArrayList<View>(HIDDEN_IDS.size)
        for (name in HIDDEN_IDS) {
            val viewId = id(name)
            if (viewId == 0) continue
            systemIcons.findViewById<View>(viewId)?.let { hiddenTargets += it }
        }
        val signalIcons = hiddenTargets.firstOrNull { it.id == id("signalIcons") }
        val battery = hiddenTargets.firstOrNull { it.id == id("battery") }

        // 反色的双保险：onDarkChanged 实时同步；万一宿主隐藏信号组后不再派发，
        // 就直接读 SignalClusterView.mIconTint（该字段由宿主反色链路持续更新）。
        val cluster = host.rootView.findViewById<View>(id("signal_cluster"))
        val tintField = runCatching {
            cluster?.javaClass?.getDeclaredField("mIconTint")?.apply { isAccessible = true }
        }.getOrNull()
        val config = TrioConfig(
            showWifi = RemoteConfig.isEnabled(FeatureCatalog.KEY_SYSTEMUI_TRIO_WIFI),
            showDots = RemoteConfig.isEnabled(FeatureCatalog.KEY_SYSTEMUI_TRIO_DOTS),
            showPercent = RemoteConfig.isEnabled(FeatureCatalog.KEY_SYSTEMUI_TRIO_PERCENT),
            showType = RemoteConfig.isEnabled(FeatureCatalog.KEY_SYSTEMUI_TRIO_TYPE),
            accent = RemoteConfig.isEnabled(FeatureCatalog.KEY_SYSTEMUI_TRIO_ACCENT),
            levelColor = RemoteConfig.isEnabled(FeatureCatalog.KEY_SYSTEMUI_TRIO_LEVEL_COLOR),
            antiBurnIn = RemoteConfig.isEnabled(FeatureCatalog.KEY_SYSTEMUI_TRIO_ANTI_BURNIN),
        )
        val sizeDp = FeatureCatalog.resolveTrioSize(
            RemoteConfig.int(
                FeatureCatalog.KEY_SYSTEMUI_TRIO_SIZE,
                FeatureCatalog.defaultTrioSize(),
            ),
        )
        val view = TrioView(ctx, config) {
            if (tintColor != 0) {
                tintColor
            } else {
                runCatching { tintField?.getInt(cluster) }.getOrNull() ?: 0
            }
        }
        view.tag = TAG_VIEW
        // 固定 dp 尺寸，不跟父容器高度拉伸：横屏/锁屏的状态栏行高更大时字形也不会放大。
        val side = dp(ctx, sizeDp)
        val lp: ViewGroup.LayoutParams = if (systemIcons is LinearLayout) {
            LinearLayout.LayoutParams(side, side).apply {
                gravity = Gravity.CENTER_VERTICAL
            }
        } else {
            ViewGroup.LayoutParams(side, side)
        }
        systemIcons.addView(view, lp)

        // 宿主在电量/信号更新时可能把子视图重新设为 VISIBLE（且不一定触发 onLayout），
        // 这里挂 pre-draw 每帧重申；GONE→GONE 是空操作，开销可忽略。
        hiddenTargets.forEach { it.visibility = View.GONE }
        host.viewTreeObserver.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                for (target in hiddenTargets) {
                    if (target.visibility != View.GONE) target.visibility = View.GONE
                }
                return true
            }
        })
        currentView = WeakReference(view)
        module.log(
            Log.INFO,
            MountEngine.TAG,
            "$JUDGE_PREFIX 三合一已注入（system_icons=${systemIcons !== host}, " +
                "signalIcons=${signalIcons != null}, battery=${battery != null}, 隐藏件=${hiddenTargets.size}）",
        )
    }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    /** 三合一外观配置（远端开关，注入时读取一次；改动需重启 SystemUI）。 */
    private data class TrioConfig(
        val showWifi: Boolean,
        val showDots: Boolean,
        val showPercent: Boolean,
        val showType: Boolean,
        val accent: Boolean,
        val levelColor: Boolean,
        val antiBurnIn: Boolean,
    )

    /** 自绘字形。所有几何量都在 120×120 设计空间内，绘制前统一缩放。 */
    private class TrioView(
        context: Context,
        private val config: TrioConfig,
        private val tintProvider: () -> Int,
    ) : View(context) {

        private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 8f
            strokeCap = Paint.Cap.ROUND
        }
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        private val arcRect = RectF()
        private val textBounds = Rect()
        private val bolt = Path().apply {
            moveTo(65f, 48f)
            lineTo(52f, 66f)
            lineTo(60f, 66f)
            lineTo(56f, 82f)
            lineTo(69f, 63f)
            lineTo(61f, 63f)
            close()
        }

        private val handler = Handler(Looper.getMainLooper())
        private val refreshTask = object : Runnable {
            override fun run() {
                refresh()
                handler.postDelayed(this, 2000L)
            }
        }

        /** 防烧屏：30s 沿 3×3 网格步进一格（±1dp）。translation 只触发重绘、不触发排版。 */
        private val burnInStepPx = resources.displayMetrics.density
        private var burnInIndex = 0
        private val burnInTask = object : Runnable {
            override fun run() {
                val cell = BURN_IN_CELLS[burnInIndex % BURN_IN_CELLS.size]
                burnInIndex++
                translationX = cell.first * burnInStepPx
                translationY = cell.second * burnInStepPx
                handler.postDelayed(this, BURN_IN_INTERVAL_MS)
            }
        }

        private var level = -1
        private var charging = false
        private var fastCharging = false
        private var fullCharging = false
        private var wifiLevel = -1
        private var mobileLevels = IntArray(0)
        private var netType = ""

        init {
            isClickable = false
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            refresh()
            handler.removeCallbacks(refreshTask)
            handler.postDelayed(refreshTask, 2000L)
            if (config.antiBurnIn) {
                handler.removeCallbacks(burnInTask)
                handler.postDelayed(burnInTask, BURN_IN_INTERVAL_MS)
            }
        }

        override fun onDetachedFromWindow() {
            handler.removeCallbacks(refreshTask)
            handler.removeCallbacks(burnInTask)
            super.onDetachedFromWindow()
        }

        private data class PowerState(val level: Int, val charging: Boolean, val fast: Boolean, val full: Boolean)

        private fun readPowerState(battery: BatteryManager?): PowerState {
            val capacity = runCatching {
                battery?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            }.getOrNull() ?: -1
            val sticky = runCatching {
                context.registerReceiver(
                    null,
                    android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED),
                )
            }.getOrNull()
            val level = if (capacity in 0..100) {
                capacity
            } else {
                val raw = sticky?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
                val scale = sticky?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
                if (raw >= 0 && scale > 0) (raw * 100 / scale).coerceIn(0, 100) else -1
            }
            // 充电判据以 sticky 广播为主：部分机型 isCharging 在 SystemUI 进程里滞后。
            val status = sticky?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val plugged = sticky?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL ||
                plugged != 0 ||
                runCatching { battery?.isCharging == true }.getOrDefault(false)
            // 充满判据：status=FULL 为准；个别机型 FULL 回报滞后，用 charging && 100% 兜底。
            val full = status == BatteryManager.BATTERY_STATUS_FULL || (charging && level >= 100)
            val current = abs(
                runCatching {
                    battery?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) ?: 0
                }.getOrDefault(0),
            )
            return PowerState(level, charging, charging && !full && current >= 1_500_000, full)
        }

        private fun refresh() {
            val power = readPowerState(context.getSystemService(BatteryManager::class.java))
            level = power.level
            charging = power.charging
            fastCharging = power.fast
            fullCharging = power.full

            val wifi = context.getSystemService(android.net.wifi.WifiManager::class.java)
            val info = runCatching { wifi?.connectionInfo }.getOrNull()
            wifiLevel = if (info != null && info.networkId != -1 && info.rssi in -126..-1) {
                (android.net.wifi.WifiManager.calculateSignalLevel(info.rssi, 4) + 1).coerceIn(1, 3)
            } else {
                -1
            }

            val sm = context.getSystemService(SubscriptionManager::class.java)
            val tm = context.getSystemService(TelephonyManager::class.java)
            val subs = runCatching { sm?.activeSubscriptionInfoList }.getOrNull()
            // 单卡口径：优先默认数据卡，取不到再退回第一张活动卡；副卡不参与绘制。
            val dataSubId = runCatching { SubscriptionManager.getDefaultDataSubscriptionId() }.getOrNull() ?: -1
            val chosen = subs?.firstOrNull { it.subscriptionId == dataSubId } ?: subs?.firstOrNull()
            val level = if (chosen != null) {
                val subTm = runCatching { tm?.createForSubscriptionId(chosen.subscriptionId) }.getOrNull()
                runCatching { subTm?.signalStrength?.level }.getOrNull()
            } else {
                runCatching { tm?.signalStrength?.level }.getOrNull()
            }
            mobileLevels = if (level != null && level >= 0) intArrayOf(level.coerceIn(0, 4)) else IntArray(0)

            netType = if (wifiLevel < 0) {
                mapNetworkType(runCatching { tm?.dataNetworkType }.getOrNull() ?: -1)
            } else {
                ""
            }
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val size = min(width, height).toFloat()
            if (size <= 0f) return
            val scale = size / 120f
            val save = canvas.save()
            canvas.translate((width - size) / 2f, (height - size) / 2f)
            canvas.scale(scale, scale)

            val fg = resolveForeground()
            drawRing(canvas, fg)
            drawTop(canvas, fg)
            drawMobile(canvas, fg)
            drawCentre(canvas, fg)

            canvas.restoreToCount(save)
        }

        private fun resolveForeground(): Int {
            val tint = tintProvider()
            if (tint != 0) return tint or 0xFF000000.toInt()
            val attrs = context.obtainStyledAttributes(intArrayOf(android.R.attr.textColorPrimary))
            return try {
                attrs.getColor(0, Color.WHITE)
            } finally {
                attrs.recycle()
            }
        }

        private fun drawRing(canvas: Canvas, fg: Int) {
            ringPaint.strokeWidth = RING_STROKE
            ringPaint.color = withAlpha(fg, TRACK_ALPHA)
            // 原项目几何：起始 148.69°、总扫 242.62°；顶部留 0.325–0.675 的缺口给数字/Wi-Fi。
            // 常态是左右各 78.85° 两段弧，底部缺口 117.4° 给信号点。
            val twoArcs = hasTopContent()
            if (twoArcs) {
                ringArc(canvas, RING_START, ARC_LEFT_SWEEP)
                ringArc(canvas, ARC_RIGHT_START, ARC_RIGHT_SWEEP)
            } else {
                ringArc(canvas, RING_START, RING_SWEEP)
            }
            if (level <= 0) {
                // 电量取不到时仍要表达充电状态：画满可见弧（绿/琥珀）。
                if (charging) {
                    ringPaint.color = if (config.accent && fastCharging) COLOR_AMBER
                    else if (config.accent) COLOR_CHARGING else fg
                    if (twoArcs) {
                        ringArc(canvas, RING_START, ARC_LEFT_SWEEP)
                        ringArc(canvas, ARC_RIGHT_START, ARC_RIGHT_SWEEP)
                    } else {
                        ringArc(canvas, RING_START, RING_SWEEP)
                    }
                }
                return
            }
            // 充电态由彩色强调接管（绿/琥珀）；否则按电量分级变色（对齐原生行为：≤10% 红、11–20% 橙）。
            ringPaint.color = when {
                config.accent && fastCharging -> COLOR_AMBER
                config.accent && charging -> COLOR_CHARGING
                config.levelColor && level <= 10 -> COLOR_DANGER
                config.levelColor && level <= 20 -> COLOR_LOW
                else -> fg
            }
            // 进度只在「可见弧段」上均分：两段弧合计 0.65×总扫角，顶部/底部缺口不占进度。
            // 若按总扫角（含顶部缺口）映射，32.5%–67.5% 会整段落进缺口，电量看起来卡住不动。
            if (twoArcs) {
                val filled = VISIBLE_SWEEP * level / 100f
                val leftFill = min(filled, ARC_LEFT_SWEEP)
                if (leftFill > 0f) ringArc(canvas, RING_START, leftFill)
                val rightFill = (filled - ARC_LEFT_SWEEP).coerceIn(0f, ARC_RIGHT_SWEEP)
                if (rightFill > 0f) ringArc(canvas, ARC_RIGHT_START, rightFill)
            } else {
                ringArc(canvas, RING_START, RING_SWEEP * level / 100f)
            }
        }

        private fun ringArc(canvas: Canvas, startDeg: Float, sweepDeg: Float) {
            arcRect.set(B_CX - B_R, B_CY - B_R, B_CX + B_R, B_CY + B_R)
            canvas.drawArc(arcRect, startDeg, sweepDeg, false, ringPaint)
        }

        private fun hasTopContent(): Boolean =
            (config.showWifi && wifiLevel > 0) ||
                (!(config.showWifi && wifiLevel > 0) && config.showType && netType.isNotEmpty())

        private fun drawTop(canvas: Canvas, fg: Int) {
            if (config.showWifi && wifiLevel > 0) {
                // 直接沿用原项目 Wi-Fi 的圆心/半径/起止角，整体上移进顶部缺口。
                ringPaint.color = fg
                ringPaint.strokeWidth = 10f
                val save = canvas.save()
                canvas.translate(0f, W_LIFT)
                canvas.scale(W_SCALE, W_SCALE, W_CX, W1_CY)
                if (wifiLevel >= 3) {
                    arc(canvas, W_CX, W1_CY, W1_R, W1_START, W1_SWEEP)
                }
                if (wifiLevel >= 2) {
                    arc(canvas, W_CX, W2_CY, W2_R, W2_START, W2_SWEEP)
                }
                fillPaint.color = fg
                canvas.drawCircle(W_CX, W_DOT_Y, W_DOT_R, fillPaint)
                canvas.restoreToCount(save)
                return
            }
            if (!config.showType || netType.isEmpty()) return
            textPaint.color = fg
            textPaint.textSize = 26f
            textPaint.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            drawTextCentred(canvas, netType, 60f, 26f)
        }

        private fun drawMobile(canvas: Canvas, fg: Int) {
            val levels = mobileLevels
            if (!config.showDots || levels.isEmpty()) return
            // 只画一排：默认数据卡。双卡同时画两排在荣耀状态栏高度里会挤到电量数字，
            // 且副卡档位与数据卡更新节奏不同，实测视觉不稳定，固定单卡口径。
            // 点位沿用原项目：沿环底缺口排布，中间两点略低，是「穿在环上的珠子」。
            val lit = levels[0].coerceIn(0, 4)
            for (i in DOT_X.indices) {
                fillPaint.color = if (i < lit) fg else withAlpha(fg, TRACK_ALPHA)
                canvas.drawCircle(DOT_X[i], DOT_Y[i], DOT_R, fillPaint)
            }
        }

        private fun drawCentre(canvas: Canvas, fg: Int) {
            if (charging) {
                fillPaint.color = if (config.accent && fastCharging) COLOR_AMBER else fg
                if (fullCharging) {
                    // 充满：闪电换成「～」（对齐原生充满图标的波浪语义）。
                    textPaint.color = fillPaint.color
                    textPaint.textSize = 44f
                    textPaint.typeface = Typeface.create("sans-serif", Typeface.BOLD)
                    drawTextCentred(canvas, "~", 60f, 66f)
                } else {
                    canvas.drawPath(bolt, fillPaint)
                }
                return
            }
            if (!config.showPercent || level < 0) return
            textPaint.color = when {
                config.levelColor && level <= 10 -> COLOR_DANGER
                config.levelColor && level <= 20 -> COLOR_LOW
                else -> fg
            }
            // 环内可视区域（内径 44.5）横向只有 88 单位，48 号粗体「100」约占 80，刚好留边；
            // 字高 34，上沿约 y=51，与上移后的 Wi-Fi 点（底 40）不打架。
            textPaint.textSize = 48f
            textPaint.typeface = Typeface.create("sans-serif", Typeface.BOLD)
            drawTextCentred(canvas, level.toString(), 60f, 68f)
        }

        private fun drawTextCentred(canvas: Canvas, text: String, cx: Float, cy: Float) {
            textPaint.getTextBounds(text, 0, text.length, textBounds)
            val baseline = cy - (textBounds.top + textBounds.bottom) / 2f
            canvas.drawText(text, cx, baseline, textPaint)
        }

        private fun arc(canvas: Canvas, cx: Float, cy: Float, r: Float, start: Float, sweep: Float) {
            arcRect.set(cx - r, cy - r, cx + r, cy + r)
            canvas.drawArc(arcRect, start, sweep, false, ringPaint)
        }

        private fun withAlpha(color: Int, alpha: Int): Int =
            (color and 0x00FFFFFF) or (alpha shl 24)

        private fun mapNetworkType(type: Int): String = when (type) {
            TelephonyManager.NETWORK_TYPE_NR -> "5G"
            TelephonyManager.NETWORK_TYPE_LTE -> "4G"
            TelephonyManager.NETWORK_TYPE_HSPAP,
            TelephonyManager.NETWORK_TYPE_HSDPA,
            TelephonyManager.NETWORK_TYPE_HSUPA,
            TelephonyManager.NETWORK_TYPE_HSPA,
            TelephonyManager.NETWORK_TYPE_UMTS,
            TelephonyManager.NETWORK_TYPE_EVDO_0,
            TelephonyManager.NETWORK_TYPE_EVDO_A,
            TelephonyManager.NETWORK_TYPE_EVDO_B,
            TelephonyManager.NETWORK_TYPE_TD_SCDMA,
            -> "3G"
            TelephonyManager.NETWORK_TYPE_EDGE,
            TelephonyManager.NETWORK_TYPE_GPRS,
            TelephonyManager.NETWORK_TYPE_GSM,
            TelephonyManager.NETWORK_TYPE_CDMA,
            TelephonyManager.NETWORK_TYPE_1xRTT,
            -> "2G"
            else -> ""
        }

        private companion object {
            /** 原项目 TrioGeometry：环心、半径、描边、轨道透明度与缺口角度。 */
            const val RING_START = 148.69f
            const val RING_SWEEP = 242.62f
            /** 顶部缺口占 0.325–0.675（原项目口径），左右各一段可见弧。 */
            const val ARC_LEFT_SWEEP = RING_SWEEP * 0.325f
            const val ARC_RIGHT_START = RING_START + RING_SWEEP * 0.675f
            const val ARC_RIGHT_SWEEP = RING_START + RING_SWEEP - ARC_RIGHT_START
            /** 可见弧总长（不含顶部与底部缺口），电量进度按它均分。 */
            const val VISIBLE_SWEEP = ARC_LEFT_SWEEP + ARC_RIGHT_SWEEP
            const val B_CX = 59.5f
            const val B_CY = 61.487152f
            const val B_R = 51.5f
            const val RING_STROKE = 14f
            const val TRACK_ALPHA = 56
            const val DOT_R = 5.5f
            val DOT_X = floatArrayOf(39.5f, 53f, 67f, 80.5f)
            val DOT_Y = floatArrayOf(104.8f, 110.5f, 110.5f, 104.8f)
            /** 原项目 Wi-Fi 弧几何（W1 外弧 / W2 内弧）与圆点。 */
            const val W_CX = 59.5f
            const val W1_CY = 78.30351f
            const val W1_R = 31f
            const val W1_START = 227.3423f
            const val W1_SWEEP = 85.3154f
            const val W2_CY = 78.88818f
            const val W2_R = 18.5f
            const val W2_START = 227.5048f
            const val W2_SWEEP = 84.9904f
            const val W_DOT_Y = 75.45f
            const val W_DOT_R = 5.5f
            /** 把 Wi-Fi 组从环心位置整体上移进顶部缺口，并略微收小与数字拉开间距。 */
            const val W_LIFT = -40.5f
            const val W_SCALE = 0.92f
            const val COLOR_DANGER = 0xFFFF3B30.toInt()
            const val COLOR_LOW = 0xFFF2B900.toInt()
            const val COLOR_AMBER = 0xFFFFB300.toInt()
            const val COLOR_CHARGING = 0xFF34C759.toInt()
            /** 防烧屏微移周期与网格（±1 格 × burnInStepPx）。 */
            const val BURN_IN_INTERVAL_MS = 30_000L
            val BURN_IN_CELLS = arrayOf(
                0 to 0, 1 to 0, 1 to 1, 0 to 1,
                -1 to 1, -1 to 0, -1 to -1, 0 to -1, 1 to -1,
            )
        }
    }
}
