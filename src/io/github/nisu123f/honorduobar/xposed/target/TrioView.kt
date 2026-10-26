package io.github.nisu123f.honorduobar.xposed.target

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
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
import android.view.View
import io.github.nisu123f.honorduobar.xposed.core.MountEngine
import io.github.nisu123f.honorduobar.xposed.core.XLog
import kotlin.math.min

/**
 * 三合一外观配置（远端开关，注入时读取一次；改动需重启 SystemUI）。
 */
internal data class TrioConfig(
    val showWifi: Boolean,
    val showDots: Boolean,
    val showPercent: Boolean,
    val showType: Boolean,
    val levelColor: Boolean,
    val antiBurnIn: Boolean,
    val btBatteryMode: Int,
    val style: Int,
    val capsuleLength: Int,
    val capsuleHeight: Int,
    val capsuleStrokePct: Int,
    val capsuleMarginHPct: Int,
    val capsuleMarginVPct: Int,
)

/** 自绘字形。所有几何量都在 120×120 设计空间内，绘制前统一缩放。 */
internal class TrioView(
    context: Context,
    private val config: TrioConfig,
    /** 蓝牙电量提供者：返回 0–100 或 -1（无耳机/开关关闭）。由 TrioFeature 注入蓝牙 hook 缓存。 */
    private val btBatteryProvider: () -> Int,
) : View(context) {

    /** 反色采样回调：注入后由 TrioFeature 赋值，返回 ARGB 或 0（取不到）。 */
    var tintProvider: (() -> Int)? = null

    /** 双卡档位诊断去重（变化时才输出日志，受全局诊断开关门控）。 */
    private var lastSlotDiagMsg: String? = null

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 8f
        strokeCap = Paint.Cap.ROUND
    }
    private val signalPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    /** 蓝牙电量弧线画笔：STROKE + ROUND 端点，宽度与 Wi-Fi 单条弧线（ringPaint）一致。 */
    private val btLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 8f
        strokeCap = Paint.Cap.ROUND
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
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
    /**
     * 飞行模式图标：机头朝右的剪影，中心 (60, 26)，纵向压扁 0.72（显瘦），
     * 垂直 11.5–40.6。缩放/位移已烘进坐标，绘制时零矩阵操作。
     */
    private val plane = Path().apply {
        moveTo(79.6f, 26f)    // 机头（右）
        lineTo(65.9f, 28.7f)  // 机身下缘
        lineTo(55.3f, 40.6f)  // 下翼尖
        lineTo(51f, 40.6f)
        lineTo(56.3f, 29.1f)  // 下翼后缘回机身
        lineTo(48.9f, 29.9f)  // 尾段下缘
        lineTo(43.5f, 34.5f)  // 下平尾
        lineTo(40.4f, 34.5f)
        lineTo(45.6f, 26f)    // 尾端
        lineTo(40.4f, 17.6f)  // 上平尾
        lineTo(43.5f, 17.6f)
        lineTo(48.9f, 22.3f)  // 尾段上缘
        lineTo(56.3f, 23f)    // 上翼后缘回机身
        lineTo(51f, 11.5f)    // 上翼尖
        lineTo(55.3f, 11.5f)
        lineTo(65.9f, 23.3f)  // 机身上缘
        close()
    }

    // ---- 胶囊样式几何（轮廓 138×59 设计单位，参考 HyperDuo） ----
    // 所有几何参数都有边界保护，即使极端配置也不会产生 NaN/Infinity/负数坐标。
    private val capStroke = ((CAP_BOTTOM - CAP_TOP) * config.capsuleStrokePct / 100f)
        .coerceIn(2f, 30f)
    /** 信号点半径 = 半个线宽：4 颗点读作「描边串起的珠子」，与线宽同步。 */
    private val capDotR = (capStroke * 0.5f).coerceAtLeast(0.5f)
    /** 单个开口切深：4 点半跨 + 点半径 + 留白 + 半个描边。 */
    private val capDotReach =
        (CAP_DOT_PITCH * 1.5f + capDotR + CAP_DOT_CLEARANCE + capStroke * 0.5f)
            .coerceAtLeast(10f)
    private val capInkLeft = CAP_LEFT - capStroke * 0.5f
    private val capInkTop = CAP_TOP - capStroke * 0.5f
    private val capInkH = ((CAP_BOTTOM - CAP_TOP) + capStroke).coerceAtLeast(20f)
    /** 左右边距（占轮廓高 %）：左格图标/文字与右格数字距内壁的内缩。 */
    private val capTextInset = ((CAP_BOTTOM - CAP_TOP) * config.capsuleMarginHPct / 100f)
        .coerceIn(0f, 30f)
    private val capInteriorLeft = CAP_LEFT + capStroke * 0.5f
    private val capInteriorTop = (CAP_TOP + capStroke * 0.5f + capDotR).coerceAtMost(CAP_BOTTOM - capStroke - capDotR - 8f)
    private val capInteriorBottom = CAP_BOTTOM - capStroke * 0.5f - capDotR
    private val capInteriorCy = (capInteriorTop + capInteriorBottom) * 0.5f
    /** 元素字高 = 内腔高 − 上下边距（边距占轮廓高 %），最小 8 设计单位。 */
    private val capTextCap = (
        (capInteriorBottom - capInteriorTop) -
            2f * (CAP_BOTTOM - CAP_TOP) * config.capsuleMarginVPct / 100f
        ).coerceAtLeast(8f)
    private val capTextSize = (capTextCap / 0.71f).coerceAtLeast(8f)
    /** 胶囊 Wi-Fi：把墨迹高 35.5548 的环内 Wi-Fi 缩到设计字高。 */
    private val capWifiScale = (capTextCap / CAP_WIFI_INK_H).coerceIn(0.3f, 2f)
    private val capLeftIconCx =
        (capInteriorLeft + capTextInset + CAP_WIFI_INK_W * capWifiScale * 0.5f).coerceAtLeast(capInteriorLeft + 4f)
    private val capWifiStroke = (WIFI_STROKE * capWifiScale).coerceAtLeast(2f)
    private val capW1R = (W1_R * W_SCALE * capWifiScale).coerceAtLeast(4f)
    private val capW1Cy = capInteriorCy + (W1_CY + W_LIFT - CAP_WIFI_INK_CY) * capWifiScale
    private val capW2R = (W2_R * W_SCALE * capWifiScale).coerceAtLeast(2f)
    private val capW2Cy = capInteriorCy + (W2_CY_BAKED - CAP_WIFI_INK_CY) * capWifiScale
    private val capWDotY = capInteriorCy + (W_DOT_Y_BAKED - CAP_WIFI_INK_CY) * capWifiScale
    private val capWDotR = (W_DOT_R_BAKED * capWifiScale).coerceAtLeast(1f)
    private val capW1Oval = RectF(
        capLeftIconCx - capW1R, capW1Cy - capW1R,
        capLeftIconCx + capW1R, capW1Cy + capW1R,
    )
    private val capW2Oval = RectF(
        capLeftIconCx - capW2R, capW2Cy - capW2R,
        capLeftIconCx + capW2R, capW2Cy + capW2R,
    )
    /** 胶囊飞机图标：原机 39.2×29.1，按字高缩放。 */
    private val capPlaneScale = (capTextCap / CAP_PLANE_INK_H).coerceIn(0.3f, 2f)
    /** 下开口蓝牙电量：进度线半长随点半径，线宽随描边（0.6 倍）。 */
    private val capBtBarHalf = (CAP_DOT_PITCH * 1.5f + capDotR).coerceAtLeast(5f)
    private val capBtBarStroke = (capStroke * 0.6f).coerceAtLeast(1.5f)

    private val capRight = (CAP_RIGHT_REF + config.capsuleLength).coerceAtLeast(CAP_LEFT + CAP_RADIUS * 2f + 10f)
    private val capCx = (CAP_LEFT + capRight) * 0.5f
    private val capInkW = (capRight - CAP_LEFT + capStroke).coerceAtLeast(capStroke * 2f)
    /** 胶囊过短时开口切深收窄到剩余直段，避免开口越过圆帽。 */
    private val capReach = min(
        capDotReach,
        (capCx - CAP_LEFT - CAP_RADIUS).coerceAtLeast(2f),
    )
    private val capArcRect = RectF()

    /** 胶囊轮廓：上下各一个开口，剩下左、右两段开放弧；长度固定，只构建一次。 */
    private val capsulePath = Path().apply {
        val left = CAP_LEFT
        val right = capRight
        val top = CAP_TOP
        val bottom = CAP_BOTTOM
        val r = CAP_RADIUS
        val cy = (top + bottom) * 0.5f
        val k = 0.55228475f * r
        // 右段：上开口右缘 → 右上直段 → 右圆帽 → 右下直段 → 下开口右缘
        moveTo(capCx + capReach, top)
        lineTo(right - r, top)
        cubicTo(right - r + k, top, right, cy - k, right, cy)
        cubicTo(right, cy + k, right - r + k, bottom, right - r, bottom)
        lineTo(capCx + capReach, bottom)
        // 左段：下开口左缘 → 左下直段 → 左圆帽 → 左上直段 → 上开口左缘
        moveTo(capCx - capReach, bottom)
        lineTo(left + r, bottom)
        cubicTo(left + r - k, bottom, left, cy + k, left, cy)
        cubicTo(left, cy - k, left + r - k, top, left + r, top)
        lineTo(capCx - capReach, top)
    }

    /**
     * 顶部闭合轮廓（单卡/无卡时）：底部仍开口放信号/蓝牙电量，顶部不截断。
     * 电量填充从下开口左缘出发逆时针走一整圈，全程是一条连续线；
     * 长度固定，只构建一次。
     */
    private val capsuleTopClosedPath = Path().apply {
        val left = CAP_LEFT
        val right = capRight
        val top = CAP_TOP
        val bottom = CAP_BOTTOM
        val r = CAP_RADIUS
        val cy = (top + bottom) * 0.5f
        val k = 0.55228475f * r
        moveTo(capCx - capReach, bottom)
        lineTo(left + r, bottom)
        cubicTo(left + r - k, bottom, left, cy + k, left, cy)
        cubicTo(left, cy - k, left + r - k, top, left + r, top)
        lineTo(right - r, top)
        cubicTo(right - r + k, top, right, cy - k, right, cy)
        cubicTo(right, cy + k, right - r + k, bottom, right - r, bottom)
        lineTo(capCx + capReach, bottom)
    }

    /** 底部闭合轮廓（无信号可显示但上开口有蓝牙电量时）：顶部开口，底部不截断。 */
    private val capsuleBottomClosedPath = Path().apply {
        val left = CAP_LEFT
        val right = capRight
        val top = CAP_TOP
        val bottom = CAP_BOTTOM
        val r = CAP_RADIUS
        val cy = (top + bottom) * 0.5f
        val k = 0.55228475f * r
        moveTo(capCx - capReach, top)
        lineTo(left + r, top)
        cubicTo(left + r - k, top, left, cy - k, left, cy)
        cubicTo(left, cy + k, left + r - k, bottom, left + r, bottom)
        lineTo(right - r, bottom)
        cubicTo(right - r + k, bottom, right, cy + k, right, cy)
        cubicTo(right, cy - k, right - r + k, top, right - r, top)
        lineTo(capCx + capReach, top)
    }

    /** 完整闭合胶囊（双卡都无且无蓝牙电量时）：无开口，电量环全程连续。 */
    private val capsuleFullPath = Path().apply {
        val left = CAP_LEFT
        val right = capRight
        val top = CAP_TOP
        val bottom = CAP_BOTTOM
        val r = CAP_RADIUS
        val cy = (top + bottom) * 0.5f
        val k = 0.55228475f * r
        moveTo(left + r, top)
        lineTo(right - r, top)
        cubicTo(right - r + k, top, right, cy - k, right, cy)
        cubicTo(right, cy + k, right - r + k, bottom, right - r, bottom)
        lineTo(left + r, bottom)
        cubicTo(left + r - k, bottom, left, cy + k, left, cy)
        cubicTo(left, cy - k, left + r - k, top, left + r, top)
    }

    /** 胶囊左格飞机图标：把 120 空间的 plane 烘进左格，绘制时零矩阵操作。 */
    private val capsulePlane = Path(plane).apply {
        transform(
            Matrix().apply {
                setScale(capPlaneScale, capPlaneScale)
                postTranslate(
                    capLeftIconCx - CAP_PLANE_CX * capPlaneScale,
                    capInteriorCy - CAP_PLANE_CY * capPlaneScale,
                )
            },
        )
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
            applyBurnInCell(cell.first, cell.second)
            handler.postDelayed(this, BURN_IN_INTERVAL_MS)
        }
    }

    /**
     * 按父容器可用空间钳制位移。
     *
     * 父容器默认裁剪子视图，而字形排在状态栏最右侧、上下也几乎顶满行高；直接 ±1dp
     * 会让绘制区域越界被切掉（显示不全）。这里把位移限制在父容器内边界以内，
     * 贴着边缘时该方向不再移动，其余方向仍保留微移。
     */
    private fun applyBurnInCell(cellX: Int, cellY: Int) {
        var dx = cellX * burnInStepPx
        var dy = cellY * burnInStepPx
        val p = parent as? View
        if (p != null && width > 0 && height > 0) {
            dx = clampToParent(dx, left, right, p.width, p.paddingLeft, p.paddingRight)
            dy = clampToParent(dy, top, bottom, p.height, p.paddingTop, p.paddingBottom)
        }
        translationX = dx
        translationY = dy
    }

    private fun clampToParent(
        delta: Float,
        start: Int,
        end: Int,
        parentSize: Int,
        paddingStart: Int,
        paddingEnd: Int,
    ): Float {
        val min = (paddingStart - start).toFloat()
        val max = (parentSize - paddingEnd - end).toFloat()
        return if (min > max) 0f else delta.coerceIn(min, max)
    }

    private var level = -1
    private var charging = false
    private var fullCharging = false
    private var wifiLevel = -1
    private var airplaneMode = false
    private var mobileLevels = IntArray(0)
    /** 胶囊样式用双卡档位：下标 0/1 = SIM 卡槽 0/1，-1 表示该槽无卡或读不到。 */
    private var slotLevels = IntArray(2) { -1 }
    private var netType = ""
    /** 蓝牙耳机电量（0–100）；开关关 / 无耳机连接 / 设备不上报 → -1。 */
    private var bluetoothBattery = -1

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

    private data class PowerState(val level: Int, val charging: Boolean, val full: Boolean)

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
        return PowerState(level, charging, full)
    }

    private fun refresh() {
        val power = readPowerState(context.getSystemService(BatteryManager::class.java))
        level = power.level
        charging = power.charging
        fullCharging = power.full

        val wifi = context.getSystemService(android.net.wifi.WifiManager::class.java)
        val info = runCatching { wifi?.connectionInfo }.getOrNull()
        wifiLevel = if (info != null && info.networkId != -1 && info.rssi in -126..-1) {
            (android.net.wifi.WifiManager.calculateSignalLevel(info.rssi, 4) + 1).coerceIn(1, 3)
        } else {
            -1
        }

        // 飞行模式：顶部缺口画飞机图标替代 Wi-Fi（2s 轮询与电量/信号同节奏，切换有≤2s 延迟）。
        airplaneMode = runCatching {
            android.provider.Settings.Global.getInt(
                context.contentResolver,
                android.provider.Settings.Global.AIRPLANE_MODE_ON,
                0,
            ) == 1
        }.getOrDefault(false)

        // 蓝牙耳机电量：搭车原生 HwBluetoothControllerImpl 缓存（ID_BT_BATTERY hook 写入
        // 蓝牙缓存）；模式=关闭时保持 -1 走原信号点分支。原生只在蓝牙状态变化时刷新，
        // 连接/断开/电量上报都会触发，无需自己轮询 BluetoothManager。
        bluetoothBattery = if (config.btBatteryMode > 0) btBatteryProvider() else -1

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

        // 胶囊样式需要主卡/副卡两档：按 simSlotIndex 取每个卡槽自己的 signalStrength，
        // 不受「默认数据卡」选择影响；单卡/被关闭的卡槽保持 -1（底部闭合不截断）。
        // 设置里「关闭」的卡仍留在活动订阅列表，以 serviceState == POWER_OFF 识别并跳过。
        // 诊断：组合变化时输出各卡槽证据（开关卡定位用，受全局开关门控）。
        val slots = IntArray(2) { -1 }
        val diagSb = StringBuilder()
        if (subs != null) {
            for (info in subs) {
                val slot = info.simSlotIndex
                if (slot !in 0..1) continue
                val subTm = runCatching { tm?.createForSubscriptionId(info.subscriptionId) }.getOrNull()
                val svcState = runCatching { subTm?.serviceState?.state }.getOrNull()
                val poweredOff = svcState == android.telephony.ServiceState.STATE_POWER_OFF
                val subLevel = if (poweredOff) {
                    null
                } else {
                    runCatching { subTm?.signalStrength?.level }.getOrNull()
                }
                if (subLevel != null && subLevel >= 0) slots[slot] = subLevel.coerceIn(0, 4)
                diagSb.append(" [s$slot id=${info.subscriptionId} svc=$svcState lv=$subLevel]")
            }
        }
        if (slots[0] < 0 && slots[1] < 0) {
            // 拿不到订阅列表时退回全局 signalStrength，至少让主卡有档位。
            val globalLevel = runCatching { tm?.signalStrength?.level }.getOrNull()
            if (globalLevel != null && globalLevel >= 0) slots[0] = globalLevel.coerceIn(0, 4)
        }
        val diagMsg = "sim主副卡: slots=${slots[0]},${slots[1]} subs=${subs?.size ?: -1}$diagSb"
        if (diagMsg != lastSlotDiagMsg) {
            lastSlotDiagMsg = diagMsg
            XLog.diag(Log.INFO, MountEngine.TAG, "$LOG_PREFIX $diagMsg")
        }
        slotLevels = slots

        netType = if (wifiLevel < 0) {
            mapNetworkType(runCatching { tm?.dataNetworkType }.getOrNull() ?: -1)
        } else {
            ""
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        runCatching {
            super.onDraw(canvas)
            if (config.style == STYLE_CAPSULE) {
                drawCapsule(canvas)
            } else {
                val size = min(width, height).toFloat()
                if (size > 0f) {
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
            }
        }.onFailure { t ->
            XLog.log(
                Log.ERROR,
                MountEngine.TAG,
                "$LOG_PREFIX TrioView.onDraw 异常（已防护，跳过本帧绘制）: ${t.javaClass.simpleName}: ${t.message}",
                t,
            )
        }
    }

    private fun resolveForeground(): Int {
        val tint = tintProvider?.invoke() ?: 0
        // 采样值自带 alpha（原生默认白/黑为 0xE6 半透明），原样使用；仅当完全透明时才补不透明。
        if (tint != 0) return if (tint ushr 24 == 0) tint or 0xFF000000.toInt() else tint
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
            // 电量取不到时仍要表达充电状态：画满可见弧（绿）。
            if (charging) {
                ringPaint.color = COLOR_CHARGING
                if (twoArcs) {
                    ringArc(canvas, RING_START, ARC_LEFT_SWEEP)
                    ringArc(canvas, ARC_RIGHT_START, ARC_RIGHT_SWEEP)
                } else {
                    ringArc(canvas, RING_START, RING_SWEEP)
                }
            }
            return
        }
        // 充电态统一绿色（不区分快充）；否则按电量分级变色（对齐原生行为：≤10% 红、11–20% 橙）。
        ringPaint.color = when {
            charging -> COLOR_CHARGING
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
        canvas.drawArc(RING_OVAL, startDeg, sweepDeg, false, ringPaint)
    }

    private fun hasTopContent(): Boolean =
        airplaneMode || (config.showWifi && wifiLevel > 0) || (config.showType && netType.isNotEmpty())

    private fun drawTop(canvas: Canvas, fg: Int) {
        if (airplaneMode) {
            signalPaint.color = fg
            canvas.drawPath(plane, signalPaint)
            return
        }
        if (config.showWifi && wifiLevel > 0) {
            // Wi-Fi 几何已在 companion 烘焙 W_LIFT/W_SCALE，直接画，无 Canvas 矩阵操作。
            ringPaint.color = fg
            ringPaint.strokeWidth = WIFI_STROKE
            if (wifiLevel >= 3) {
                canvas.drawArc(W1_OVAL, W1_START, W1_SWEEP, false, ringPaint)
            }
            if (wifiLevel >= 2) {
                canvas.drawArc(W2_OVAL, W2_START, W2_SWEEP, false, ringPaint)
            }
            signalPaint.color = fg
            canvas.drawCircle(W_CX, W_DOT_Y_BAKED, W_DOT_R_BAKED, signalPaint)
            return
        }
        if (!config.showType || netType.isEmpty()) return
        textPaint.color = fg
        textPaint.textSize = 26f
        textPaint.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        drawTextCentred(canvas, netType, 60f, 26f)
    }

    private fun drawMobile(canvas: Canvas, fg: Int) {
        // 蓝牙耳机电量替换底部信号点，由 config.btBatteryMode 控制：
        // 0=关闭：走下方信号点分支；1=圆弧：沿信号点轨迹画弧线，与电池环同心，半径 48，跨 65°→115°，线宽 8（与 Wi-Fi 单条弧线一致）；
        // 2=数字：底部居中 24 号粗体百分比，与环心电量数字同风格。底部 6 点钟是环缺口不会撞环。
        val bt = bluetoothBattery
        if (config.btBatteryMode > 0 && bt in 0..100) {
            if (config.btBatteryMode == 2) {
                textPaint.color = fg
                textPaint.textSize = 24f
                textPaint.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                drawTextCentred(canvas, bt.toString(), 60f, 108f)
            } else {
                btLinePaint.color = fg
                btLinePaint.alpha = TRACK_ALPHA
                canvas.drawArc(BT_OVAL, BT_ARC_START, BT_ARC_SWEEP, false, btLinePaint)
                if (bt > 0) {
                    val progress = BT_ARC_SWEEP * bt / 100f
                    btLinePaint.alpha = 255
                    canvas.drawArc(BT_OVAL, BT_ARC_START + BT_ARC_SWEEP - progress, progress, false, btLinePaint)
                }
            }
            return
        }
        val levels = mobileLevels
        if (!config.showDots || levels.isEmpty()) return
        // 只画一排：默认数据卡。双卡同时画两排在荣耀状态栏高度里会挤到电量数字，
        // 且副卡档位与数据卡更新节奏不同，实测视觉不稳定，固定单卡口径。
        // 点位沿用原项目：沿环底缺口排布，中间两点略低，是「穿在环上的珠子」。
        val lit = levels[0].coerceIn(0, 4)
        for (i in DOT_X.indices) {
            signalPaint.color = if (i < lit) fg else withAlpha(fg, TRACK_ALPHA)
            canvas.drawCircle(DOT_X[i], DOT_Y[i], DOT_R, signalPaint)
        }
    }

    private fun drawCentre(canvas: Canvas, fg: Int) {
        if (charging) {
            signalPaint.color = COLOR_CHARGING
            if (fullCharging) {
                // 充满：闪电换成「～」（对齐原生充满图标的波浪语义）。
                textPaint.color = signalPaint.color
                textPaint.textSize = 44f
                textPaint.typeface = Typeface.create("sans-serif", Typeface.BOLD)
                drawTextCentred(canvas, "~", 60f, 66f)
            } else {
                canvas.drawPath(bolt, signalPaint)
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

    // ---- 胶囊样式：胶囊轮廓本身就是电量轨道 ----
    // 下开口固定给信号：双卡 = 卡2、单卡 = 唯一的卡（无论插哪个槽）、无卡则闭合；
    // 上开口 = 卡1（双卡），蓝牙耳机电量优先顶替上开口；都无内容则闭合为完整胶囊。
    // 电量从下开口左缘逆时针走一整圈；开口随状态开合，无开口的边电量线连续。
    // 胶囊内左格 Wi-Fi/制式（飞行模式为飞机），右格电量数字。

    private fun drawCapsule(canvas: Canvas) {
        val fg = resolveForeground()
        val scale = height / capInkH
        if (scale <= 0f) return
        val save = canvas.save()
        canvas.translate(
            (width - capInkW * scale) * 0.5f,
            (height - capInkH * scale) * 0.5f,
        )
        canvas.scale(scale, scale)
        canvas.translate(-capInkLeft, -capInkTop)

        // 开口判定：下开口固定给信号（单卡=唯一的卡，双卡=卡2，无卡则闭合）；
        // 上开口 = 卡1（双卡）或蓝牙电量（顶替）；都无内容则闭合。全闭即完整胶囊。
        val bt = bluetoothBattery
        val btShown = config.btBatteryMode > 0 && bt in 0..100
        val topOpen = btShown || slotLevels.getOrElse(0) { -1 } >= 0
        val bottomOpen = slotLevels.getOrElse(1) { -1 } >= 0

        // 轨道：按开口组合选轮廓
        ringPaint.strokeWidth = capStroke
        ringPaint.color = withAlpha(fg, TRACK_ALPHA)
        canvas.drawPath(
            when {
                topOpen && bottomOpen -> capsulePath
                topOpen -> capsuleBottomClosedPath
                bottomOpen -> capsuleTopClosedPath
                else -> capsuleFullPath
            },
            ringPaint,
        )

        // 电量填充：取不到电量时仍用绿色画满，表达充电状态
        val fraction = when {
            level > 0 -> level / 100f
            charging -> 1f
            else -> 0f
        }
        if (fraction > 0f) {
            ringPaint.color = when {
                charging -> COLOR_CHARGING
                config.levelColor && level <= 10 -> COLOR_DANGER
                config.levelColor && level <= 20 -> COLOR_LOW
                else -> fg
            }
            drawCapsuleFill(canvas, fraction, topOpen, bottomOpen)
        }

        drawCapsuleSignals(canvas, fg, bt, btShown)
        drawCapsuleLeft(canvas, fg)
        drawCapsuleRight(canvas, fg)

        canvas.restoreToCount(save)
    }

    private fun drawCapsuleFill(canvas: Canvas, fraction: Float, topOpen: Boolean, bottomOpen: Boolean) {
        val left = CAP_LEFT
        val right = capRight
        val top = CAP_TOP
        val bottom = CAP_BOTTOM
        val r = CAP_RADIUS
        // 底部开口时先走下开口左缘 → 左圆帽的短直段；闭合时直接从左圆帽起点开始
        val bottomFirst = if (bottomOpen) (capCx - capReach) - (left + r) else 0f
        // 顶部开口时左/右两段直段；闭合时合并为一条整段
        val topSeg1 = if (topOpen) bottomFirst else (right - r) - (left + r)
        val topSeg2 = if (topOpen) (right - r) - (capCx + capReach) else 0f
        // 末端底部直段：开口时到下开口右缘，闭合时贯穿整条底边
        val bottomLast = if (bottomOpen) topSeg2 else (right - r) - (left + r)
        val capLen = CAP_PI * r
        val total = bottomFirst + capLen + topSeg1 + topSeg2 + capLen + bottomLast
        if (total <= 0f) return
        var remaining = total * fraction.coerceIn(0f, 1f)

        // 1. 左下直段（下开口左缘 → 左圆帽起点；底部闭合时跳过）
        var used = min(remaining, bottomFirst).coerceAtLeast(0f)
        remaining -= used
        if (used > 0f) {
            val x0 = capCx - capReach
            canvas.drawLine(x0, bottom, x0 - used, bottom, ringPaint)
        }
        // 2. 左圆帽（90° → 270°）
        used = min(remaining, capLen).coerceAtLeast(0f)
        remaining -= used
        if (used > 0f) {
            capArcRect.set(left, top, left + 2f * r, bottom)
            canvas.drawArc(capArcRect, 90f, used / r * RAD_TO_DEG, false, ringPaint)
        }
        // 3. 顶部直段（闭合时为整条：左端 → 右端）
        used = min(remaining, topSeg1).coerceAtLeast(0f)
        remaining -= used
        if (used > 0f) {
            canvas.drawLine(left + r, top, left + r + used, top, ringPaint)
        }
        // 4. 右上直段（仅顶部开口时存在）
        used = min(remaining, topSeg2).coerceAtLeast(0f)
        remaining -= used
        if (used > 0f) {
            canvas.drawLine(capCx + capReach, top, capCx + capReach + used, top, ringPaint)
        }
        // 5. 右圆帽（270° → 450°）
        used = min(remaining, capLen).coerceAtLeast(0f)
        remaining -= used
        if (used > 0f) {
            capArcRect.set(right - 2f * r, top, right, bottom)
            canvas.drawArc(capArcRect, 270f, used / r * RAD_TO_DEG, false, ringPaint)
        }
        // 6. 末端底部直段（右圆帽末端 → 下开口右缘；闭合时贯穿底边回到左圆帽起点）
        used = min(remaining, bottomLast).coerceAtLeast(0f)
        if (used > 0f) {
            canvas.drawLine(right - r, bottom, right - r - used, bottom, ringPaint)
        }
    }

    private fun drawCapsuleSignals(canvas: Canvas, fg: Int, bt: Int, btShown: Boolean) {
        val inactive = withAlpha(fg, TRACK_ALPHA)
        val firstX = capCx - CAP_DOT_PITCH * 1.5f

        // 上开口：蓝牙耳机电量优先顶替；否则 = 卡1 信号点（仅双卡）
        if (btShown) {
            if (config.btBatteryMode == 2) {
                textPaint.color = fg
                textPaint.textSize = CAP_BT_TEXT_SIZE
                textPaint.typeface = Typeface.create("sans-serif", Typeface.BOLD)
                textPaint.textAlign = Paint.Align.CENTER
                drawTextCentred(canvas, bt.toString(), capCx, CAP_TOP + 1f)
            } else {
                ringPaint.strokeWidth = capBtBarStroke
                ringPaint.color = inactive
                canvas.drawLine(
                    capCx - capBtBarHalf, CAP_TOP,
                    capCx + capBtBarHalf, CAP_TOP, ringPaint,
                )
                if (bt > 0) {
                    ringPaint.color = fg
                    canvas.drawLine(
                        capCx - capBtBarHalf, CAP_TOP,
                        capCx - capBtBarHalf + 2f * capBtBarHalf * bt / 100f,
                        CAP_TOP, ringPaint,
                    )
                }
            }
        } else if (slotLevels.getOrElse(0) { -1 } >= 0) {
            val topLit = slotLevels.getOrElse(0) { -1 }
            for (i in 0 until 4) {
                signalPaint.color = if (i < topLit) fg else inactive
                canvas.drawCircle(firstX + i * CAP_DOT_PITCH, CAP_TOP, capDotR, signalPaint)
            }
        }

        // 下开口固定给信号：双卡 = 卡2，单卡 = 唯一的卡；无卡时底部闭合不画
        val bottomLit = slotLevels.getOrElse(1) { -1 }
        if (bottomLit < 0) return
        for (i in 0 until 4) {
            signalPaint.color = if (i < bottomLit) fg else inactive
            canvas.drawCircle(firstX + i * CAP_DOT_PITCH, CAP_BOTTOM, capDotR, signalPaint)
        }
    }

    private fun drawCapsuleLeft(canvas: Canvas, fg: Int) {
        if (airplaneMode) {
            signalPaint.color = fg
            canvas.drawPath(capsulePlane, signalPaint)
            return
        }
        if (config.showWifi && wifiLevel > 0) {
            ringPaint.color = fg
            ringPaint.strokeWidth = capWifiStroke
            if (wifiLevel >= 3) {
                canvas.drawArc(capW1Oval, W1_START, W1_SWEEP, false, ringPaint)
            }
            if (wifiLevel >= 2) {
                canvas.drawArc(capW2Oval, W2_START, W2_SWEEP, false, ringPaint)
            }
            signalPaint.color = fg
            canvas.drawCircle(capLeftIconCx, capWDotY, capWDotR, signalPaint)
            return
        }
        if (!config.showType || netType.isEmpty()) return
        textPaint.color = fg
        // 制式文字比 Wi-Fi 弧占面大：字号缩到 0.82 档，给左右两侧留出与 Wi-Fi 态接近的留白
        textPaint.textSize = capTextSize * 0.82f
        textPaint.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        textPaint.textAlign = Paint.Align.LEFT
        drawTextAt(canvas, netType, capInteriorLeft + capTextInset, capInteriorCy)
    }

    private fun drawCapsuleRight(canvas: Canvas, fg: Int) {
        if (!config.showPercent || level < 0) return
        textPaint.color = when {
            charging -> COLOR_CHARGING
            config.levelColor && level <= 10 -> COLOR_DANGER
            config.levelColor && level <= 20 -> COLOR_LOW
            else -> fg
        }
        textPaint.textSize = capTextSize
        textPaint.typeface = Typeface.create("sans-serif", Typeface.BOLD)
        textPaint.textAlign = Paint.Align.RIGHT
        val text = level.toString()
        // 三位数（仅 100）按比例压缩字宽到不超过两位数（"99"），避免向左延伸撞到左格内容。
        // measureText 开销可忽略（每帧两次，电量值变化才影响分支）。
        if (text.length > 2) {
            val limit = textPaint.measureText("99")
            val actual = textPaint.measureText(text)
            textPaint.textScaleX = (limit / actual).coerceAtMost(1f)
        } else {
            textPaint.textScaleX = 1f
        }
        drawTextAt(
            canvas,
            text,
            capRight - capStroke * 0.5f - capTextInset,
            capInteriorCy,
        )
        textPaint.textScaleX = 1f
    }

    /** 按当前 textAlign 在 (x, cy) 处垂直居中绘制文本。 */
    private fun drawTextAt(canvas: Canvas, text: String, x: Float, cy: Float) {
        textPaint.getTextBounds(text, 0, text.length, textBounds)
        canvas.drawText(text, x, cy - (textBounds.top + textBounds.bottom) / 2f, textPaint)
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

    companion object {
        private const val LOG_PREFIX = "SystemUiHook·U9"
        /** 字形样式 1=胶囊（0=圆环，与 FeatureCatalog.KEY_SYSTEMUI_TRIO_STYLE 对应）。 */
        const val STYLE_CAPSULE = 1

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

        /** 蓝牙电量弧线：与电池环同心，半径 48 贴合原 4 信号点轨迹，扫角跨 65°→115°。 */
        const val BT_R = 48f
        const val BT_ARC_START = 65f
        const val BT_ARC_SWEEP = 50f
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
        /** Wi-Fi 组烘焙 W_LIFT/W_SCALE 后的最终几何（缩放同时作用于描边，10f×W_SCALE），绘制零矩阵操作。 */
        const val WIFI_STROKE = 10f * W_SCALE
        const val W2_CY_BAKED = W1_CY + (W2_CY - W1_CY) * W_SCALE + W_LIFT
        const val W_DOT_Y_BAKED = W1_CY + (W_DOT_Y - W1_CY) * W_SCALE + W_LIFT
        const val W_DOT_R_BAKED = W_DOT_R * W_SCALE
        /** 常量圆环的不可变 Oval（drawArc 只读，可多实例共享，免每帧 RectF.set）。 */
        val RING_OVAL = RectF(B_CX - B_R, B_CY - B_R, B_CX + B_R, B_CY + B_R)
        val BT_OVAL = RectF(B_CX - BT_R, B_CY - BT_R, B_CX + BT_R, B_CY + BT_R)
        val W1_OVAL = RectF(
            W_CX - W1_R * W_SCALE, W1_CY + W_LIFT - W1_R * W_SCALE,
            W_CX + W1_R * W_SCALE, W1_CY + W_LIFT + W1_R * W_SCALE,
        )
        val W2_OVAL = RectF(
            W_CX - W2_R * W_SCALE, W2_CY_BAKED - W2_R * W_SCALE,
            W_CX + W2_R * W_SCALE, W2_CY_BAKED + W2_R * W_SCALE,
        )

        // ---- 胶囊样式 ----
        // 轮廓 138×59、圆角 29.5；线宽与字高占轮廓高的百分比可调，派生几何见 TrioView 实例字段。
        const val CAP_LEFT = 1f
        const val CAP_RIGHT_REF = 139f
        const val CAP_TOP = 1f
        const val CAP_BOTTOM = 60f
        const val CAP_RADIUS = 29.5f
        const val CAP_DOT_PITCH = 10.9f
        const val CAP_DOT_CLEARANCE = 5.31f
        const val CAP_PI = 3.1415927f
        const val RAD_TO_DEG = 57.29578f
        /** 下开口里的蓝牙电量数字模式字号（设计单位，开口高度有限不随字高设置缩放）。 */
        const val CAP_BT_TEXT_SIZE = 12f
        /** 环内 Wi-Fi 原始墨迹盒（胶囊 Wi-Fi 按字高等比缩放的基准）。 */
        const val CAP_WIFI_INK_H = 35.5548f
        const val CAP_WIFI_INK_CY = 22.4609f
        const val CAP_WIFI_INK_W = 47.856f
        /** 胶囊飞机图标：原机 39.2×29.1，中心 (60, 26.05)，按字高缩放。 */
        const val CAP_PLANE_INK_H = 29.1f
        const val CAP_PLANE_CX = 60f
        const val CAP_PLANE_CY = 26.05f

        const val COLOR_DANGER = 0xFFFF3B30.toInt()
        const val COLOR_LOW = 0xFFF2B900.toInt()
        const val COLOR_CHARGING = 0xFF34C759.toInt()
        /** 防烧屏微移周期与网格（±1 格 × burnInStepPx）。 */
        const val BURN_IN_INTERVAL_MS = 30_000L
        val BURN_IN_CELLS = arrayOf(
            0 to 0, 1 to 0, 1 to 1, 0 to 1,
            -1 to 1, -1 to 0, -1 to -1, 0 to -1, 1 to -1,
        )
    }
}
