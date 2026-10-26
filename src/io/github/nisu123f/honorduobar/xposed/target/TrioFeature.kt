package io.github.nisu123f.honorduobar.xposed.target

import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothProfile
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
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import io.github.nisu123f.honorduobar.FeatureCatalog
import io.github.nisu123f.honorduobar.xposed.core.MountEngine
import io.github.nisu123f.honorduobar.xposed.core.XLog
import io.github.nisu123f.honorduobar.xposed.core.MountSession
import io.github.nisu123f.honorduobar.xposed.data.RemoteConfig
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.util.WeakHashMap
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
 * - 环内上部 = Wi-Fi 弧（2 条弧 + 1 个点，按 RSSI 档位）；飞行模式下改为飞机图标；
 * - 环内下部 = 移动信号点（4 点，单排，只取默认数据卡）；
 * - 环心 = 电量数字（充电时改为闪电，快充琥珀色）；
 * - 无 Wi-Fi 时上部改为 5G/4G 制式文字。
 *
 * 挂载点（本 APK 反编译实证）：
 * - HwSystemIcons.onFinishInflate()：注入时机；
 * - HwSystemIcons.onMeasure(int,int)：前置把原生件压回 GONE（防闪根修，见 assertHidden）；
 * - HwSystemIcons.updateIconsAlpha()：胶囊显隐后把状态/网络图标 alpha 拉回可见（防蓝牙被挤掉）；
 * - HwStatusbarContents.refreshTint()：明暗切换统一入口（HonorDuo 同源链路，本机实测有效），
 *   proceed 后重绘；颜色在绘制时实时采样电池 mTintColor → mBorderView.mDefaultColor →
 *   mIconTint（复刻 HonorDuo resolveInk 的优先级）。
 */
object TrioFeature {

    private const val HOST_PACKAGE = "com.android.systemui"
    private const val CLS_SYSTEM_ICONS = "com.android.systemui.statusbar.HwSystemIcons"
    private const val CLS_STATUSBAR_CONTENTS = "com.android.systemui.statusbar.HwStatusbarContents"
    private const val CLS_BT_POLICY = "com.android.systemui.statusbar.phone.HwPhoneStatusBarPolicy"
    private const val METHOD_FINISH_INFLATE = "onFinishInflate"
    private const val METHOD_REFRESH_TINT = "refreshTint"
    private const val METHOD_LAYOUT = "onLayout"
    private const val METHOD_MEASURE = "onMeasure"
    private const val METHOD_UPDATE_ICONS_ALPHA = "updateIconsAlpha"
    private const val METHOD_UPDATE_BLUETOOTH = "updateBluetooth"

    private const val TAG_VIEW = "hh_trio_view"
    /** 字形样式 1=胶囊（0=圆环，与 FeatureCatalog.KEY_SYSTEMUI_TRIO_STYLE 对应）。 */
    private const val STYLE_CAPSULE = 1
    /** 三合一开启时要隐藏的原生件：整组 + 组内单件兜底（id 不存在会自动跳过）。 */
    private val HIDDEN_IDS = arrayOf(
        "signalIcons",
        "battery",
        "wifi_combo",
        "mobile_combo",
        "mobile_signal",
        "mobile_type",
    )
    /** 胶囊避让会改 alpha 的容器 id 名（statusIcons / network_area_icons）。 */
    private val ALPHA_GROUP_NAMES = arrayOf("statusIcons", "network_area_icons")
    private const val JUDGE_PREFIX = "SystemUiHook·U9"
    private const val ID_INFLATE = "systemui:trio.systemIcons.onFinishInflate"
    private const val ID_REFRESH_TINT = "systemui:trio.hwContents.refreshTint"
    private const val ID_LAYOUT = "systemui:trio.systemIcons.onLayout"
    private const val ID_MEASURE = "systemui:trio.systemIcons.onMeasure"
    private const val ID_UPDATE_ALPHA = "systemui:trio.systemIcons.updateIconsAlpha"
    private const val ID_BT_BATTERY = "systemui:trio.hwPhoneStatusBarPolicy.updateBluetooth"
    private const val ID_CAPSULE_STATE = "systemui:trio.hnAnimController.updateCapsuleState"
    private const val ID_PANEL_EXPAND = "systemui:trio.hwPanelView.setExpandedHeightInternal"
    private const val ID_SHOWING_TYPE = "systemui:trio.hnPanelController.setPanelShowingType"

    // 灵动胶囊不缩放（复用系统恢复动画，不自写动画）：
    // 上岛常驻 → LivelyCapsuleStatusBarHelper.onExpandChanged(1) → updateCapsuleState(1)
    //   → startStatusBarScaleAnim(true) 播缩小动画，mStatusBarScaleAnim 持有该 AnimatorSet；
    // 胶囊关闭 → updateCapsuleState(0) → startStatusBarScaleAnim(false) 播原生回弹动画。
    // 这里 hook updateCapsuleState：i==1 proceed 后给缩小动画挂结束监听，播完立即反射调
    // startStatusBarScaleAnim(false, false)，走与「关闭胶囊恢复」完全相同的系统路径弹回 1.0。
    // （此前 hook HwNotchUtils.getScaleValue 恒 1.0f 的方案实测挂载成功但运行时零调用，已废弃。）
    private const val CLS_ANIM_CONTROLLER = "com.android.systemui.statusbar.phone.HnPhoneStatusBarAnimController"
    private const val METHOD_UPDATE_CAPSULE_STATE = "updateCapsuleState"

    // 下拉面板切原生图标：MagicOS 11 通知中心/控制中心共用同一 PanelView 展开机制，
    // 唯一实体子类 NotificationPanelView 继承 HwPanelView（覆写 setExpandedHeightInternal，
    // super 调用后 updateBlurView），hook HwPanelView 的覆写实现即可覆盖两个面板。
    // 展开判定取 PanelView.mExpandedFraction > 0（proceed 后读，含滑动半展开）；
    // heads-up 横幅也会抬升 mExpandedFraction，用 isPanelVisibleBecauseOfHeadsUp() 剔除。
    private const val CLS_PANEL_VIEW = "com.android.systemui.statusbar.phone.HwPanelView"
    private const val METHOD_SET_EXPANDED_HEIGHT = "setExpandedHeightInternal"
    private const val METHOD_HEADS_UP_VISIBLE = "isPanelVisibleBecauseOfHeadsUp"
    // 收起兜底：MagicOS 收起动画走自家 HnStatusBarPullAnim，末端 fraction 归零那一步可能
    // 不进 setExpandedHeightInternal（或该 final 短方法在 odex 里被内联跳过），导致展开态卡住。
    // setPanelShowingType(0) 是所有收起路径（fling/无动画/锁屏/熄屏）的必经收口，挂上后
    // 轮询 mExpandedFraction 归零再恢复三合一（>0 说明是面板互切/重新展开，不动）。
    private const val CLS_PANEL_CONTROLLER = "com.android.systemui.statusbar.phone.HnPanelViewControllerImpl"
    private const val METHOD_SET_SHOWING_TYPE = "setPanelShowingType"

    /**
     * 蓝牙耳机电量缓存。
     *
     * 数据源：HwBluetoothControllerImpl.mBluetoothBatteryLevel（原生由 mBluetoothBatteryReceiver
     * 监听 ACTION_BATTERY_LEVEL_CHANGED 维护）。
     *
     * 为何 hook HwPhoneStatusBarPolicy.updateBluetooth 而不是 getBluetoothBatteryLevel：
     * 后者是 3 行的 public final 方法，R8 几乎必然内联，且 LSPosed hook final 短方法在
     * ART 上有 deopt 兼容问题，实测会触发 SystemUI 原生崩溃（非 Java 异常，runCatching 拦不住）。
     * updateBluetooth 方法体长（几百行）不会被内联，虽然也是 final，但 hook 稳定。
     * proceed 后从 thisObject.mBluetooth 反射读 mBluetoothBatteryLevel 即可。
     */
    @Volatile private var cachedBtBattery: Int = -1

    /** 主副卡诊断去重（多个 TrioView 实例共享同一状态，变化时只输出一次）。 */
    @Volatile private var lastSlotDiagMsg: String? = null

    /** 缓存反射字段（getDeclaredField 是低频调用但仍要避免每次重建）。 */
    @Volatile private var btPolicyBluetoothField: Field? = null
    @Volatile private var btControllerBatteryLevelField: Field? = null

    /** 蓝牙电量播种进行中（防重入；结果回调/断开时复位）。 */
    @Volatile private var btSeeding = false

    /** 粘滞电量图标清理进行中（防重入；清理后重调 updateBluetooth 会再过一次本钩子）。 */
    @Volatile private var btClearing = false

    /** 原生电量图标使能粘滞位（mIsBluetoothEarBattery）与其 setter 的反射缓存。 */
    @Volatile private var btEarBatteryFlagField: Field? = null
    @Volatile private var btBatteryEnableMethod: java.lang.reflect.Method? = null

    // ---- 原生蓝牙图标单件隐藏（与信号/Wi-Fi 同一 View 显隐体系） ----
    // 蓝牙图标与闹钟等混在 statusIcons 组内，不能整组 GONE；它是 StatusBarIconController
    // 动态 addView 的（无资源 id），但 StatusBarIconView.mSlot 恒为 "bluetooth"，按 slot 名
    // 在组内定位单件后，即可与信号/Wi-Fi 一样走 View visibility，面板切换无需特判。
    /** statusIcons 容器 id（惰性缓存，0=未解析）。 */
    @Volatile private var statusIconsIdCache = 0
    /** StatusBarIconView.mSlot 反射缓存（public final，getField 一次即可）。 */
    @Volatile private var iconSlotField: Field? = null
    /** 各宿主的蓝牙图标 View 缓存（仅主线程读写；BT 状态变化时清空重查）。 */
    private val btIconViews = WeakHashMap<ViewGroup, WeakReference<View>>()

    /** 主线程 Handler：蓝牙 hook 回调可能在 binder 线程，View 操作必须 post 回主线程。 */
    private val mainHandler = Handler(Looper.getMainLooper())

    /** 所有已注入的三合一 View（折叠栏/锁屏/覆盖层各一个实例；仅主线程读写）。 */
    private val trioViews = ArrayList<WeakReference<TrioView>>()

    // ---- 下拉面板切原生图标 ----
    /** 面板展开全局状态（任一 HwPanelView 展开即 true；所有三合一宿主同步切原生）。 */
    @Volatile private var panelExpanded = false
    /** PanelView.mExpandedFraction 反射缓存（字段声明在基类 PanelView 上，findField 沿层级上找）。 */
    @Volatile private var panelFractionField: Field? = null
    /** NotificationPanelView.isPanelVisibleBecauseOfHeadsUp 反射缓存（public final，仅调用不 hook）。 */
    @Volatile private var panelHeadsUpMethod: java.lang.reflect.Method? = null
    /** 已注入三合一的 HwSystemIcons 宿主实例（仅主线程读写；面板状态切换时逐一切换显隐）。 */
    private val injectedHosts = ArrayList<WeakReference<ViewGroup>>()

    /** HnPanelViewControllerImpl.mPanelView 字段缓存（指向 NotificationPanelView）。 */
    @Volatile private var controllerPanelField: Field? = null
    /** 收起归零轮询状态（仅主线程）：收起信号后的目标控制器与已试次数。 */
    private var collapseSettleController: WeakReference<Any>? = null
    private var collapseSettleAttempts = 0

    /**
     * 收起归零轮询：setPanelShowingType(0) 在收起动画开始时就触发，此时 fraction 还没归零；
     * 每 100ms 复读一次，归零才真正恢复三合一（面板互切/中途重新展开时 fraction 恒 >0，自动放弃）。
     * fraction 钩子若在收起末端正常触发会先一步恢复，轮询看到 panelExpanded==false 即退出。
     */
    private val collapseSettleTask = object : Runnable {
        override fun run() {
            val controller = collapseSettleController?.get() ?: return
            val fraction = readPanelFraction(controller)
            if (fraction <= 0f) {
                collapseSettleController = null
                if (panelExpanded) {
                    panelExpanded = false
                    XLog.diag(Log.INFO, MountEngine.TAG, "$JUDGE_PREFIX 面板收起(轮询兜底): fraction=$fraction → 恢复三合一 hosts=${injectedHosts.size}")
                    applyPanelVisibility(false)
                }
                return
            }
            if (++collapseSettleAttempts >= 10) {
                XLog.diag(Log.WARN, MountEngine.TAG, "$JUDGE_PREFIX 面板收起轮询超时放弃: fraction=$fraction panelExpanded=$panelExpanded")
                collapseSettleController = null
                return
            }
            mainHandler.postDelayed(this, 100L)
        }
    }

    private fun scheduleCollapseSettle(controller: Any) {
        // 宿主收起流程会重复发 2-3 次 setPanelShowingType(0)，已有轮询在跑就直接复用，避免重复刷屏与重置计时。
        if (collapseSettleController != null) return
        XLog.diag(Log.INFO, MountEngine.TAG, "$JUDGE_PREFIX 收到收起信号 setPanelShowingType(0)，开始轮询归零 panelExpanded=$panelExpanded")
        collapseSettleAttempts = 0
        collapseSettleController = WeakReference(controller)
        mainHandler.removeCallbacks(collapseSettleTask)
        mainHandler.postDelayed(collapseSettleTask, 100L)
    }

    /** 经控制器 mPanelView 读 PanelView.mExpandedFraction；读不到返回 -1（按已收起处理）。 */
    private fun readPanelFraction(controller: Any): Float {
        val pvField = controllerPanelField
            ?: findField(controller.javaClass, "mPanelView")?.also { controllerPanelField = it }
            ?: return -1f
        val panel = runCatching { pvField.get(controller) }.getOrNull() ?: return -1f
        val fField = panelFractionField
            ?: findField(panel.javaClass, "mExpandedFraction")?.also { panelFractionField = it }
            ?: return -1f
        return runCatching { fField.getFloat(panel) }.getOrDefault(-1f)
    }

    private fun invalidateAllTrio() {
        val it = trioViews.iterator()
        while (it.hasNext()) {
            val v = it.next().get()
            if (v == null) it.remove() else v.invalidate()
        }
    }

    /**
     * 反色采样字段：HwStatusBarBatteryView 的 mTintColor / mBorderView（类级 Field，各实例通用；
     * 电池实例由各 TrioView 的 tintProvider 闭包按实例捕获，避免多状态栏实例串色）。
     */
    @Volatile private var batteryTintField: Field? = null
    @Volatile private var batteryBorderField: Field? = null
    @Volatile private var borderInkField: Field? = null

    /** 沿类层级找字段（mTintColor 可能声明在电池视图的父类上）。 */
    private fun findField(type: Class<*>, name: String): Field? {
        var c: Class<*>? = type
        while (c != null) {
            val current = c
            val f = runCatching { current.getDeclaredField(name) }.getOrNull()
            if (f != null) {
                f.isAccessible = true
                return f
            }
            c = c.superclass
        }
        return null
    }

    /** 采样当前反色：电池 mTintColor → 边框 mDefaultColor，取不到返回 0（对齐 HonorDuo resolveInk）。 */
    private fun sampleBatteryTint(battery: View?): Int {
        if (battery == null) return 0
        val tint = runCatching { batteryTintField?.getInt(battery) }.getOrNull() ?: 0
        if (tint != 0) return tint
        val border = runCatching { batteryBorderField?.get(battery) }.getOrNull() ?: return 0
        val f = borderInkField
            ?: findField(border.javaClass, "mDefaultColor")?.also { borderInkField = it }
            ?: return 0
        return runCatching { f.getInt(border) }.getOrNull() ?: 0
    }

    /** 胶囊避让涉及的两组容器 id（惰性缓存，同 hiddenIds）。 */
    private fun alphaGroupIds(context: Context): IntArray {
        alphaGroupIdCache?.let { return it }
        return resolveIds(context, ALPHA_GROUP_NAMES).also { alphaGroupIdCache = it }
    }

    fun install(module: XposedModule, param: XposedModuleInterface.PackageLoadedParam) {
        if (!RemoteConfig.isEnabled(FeatureCatalog.KEY_SYSTEMUI_TRIO)) {
            logDisabled(FeatureCatalog.KEY_SYSTEMUI_TRIO, JUDGE_PREFIX)
            return
        }
        val loader = param.defaultClassLoader
        val session = MountEngine.open(
            "SystemUiHook·U9",
            HOST_PACKAGE,
            param.applicationInfo?.sourceDir,
            FeatureCatalog.baselineVersionOf(HOST_PACKAGE),
        )
        val pending = buildList {
            add(
                MountEngine.SimpleTask(
                    className = CLS_SYSTEM_ICONS,
                    methodName = METHOD_FINISH_INFLATE,
                    paramCount = 0,
                    returnType = "void",
                    id = ID_INFLATE,
                    description = "U9 三合一注入（HwSystemIcons.onFinishInflate 后插自绘 View，隐藏原生信号组/电池）",
                ),
            )
            add(
                MountEngine.SimpleTask(
                    className = CLS_SYSTEM_ICONS,
                    methodName = METHOD_MEASURE,
                    paramCount = 2,
                    returnType = "void",
                    id = ID_MEASURE,
                    description = "U9 三合一防闪（HwSystemIcons.onMeasure 前把原生件压回 GONE，排版不再含电池宽度）",
                ),
            )
            add(
                MountEngine.SimpleTask(
                    className = CLS_SYSTEM_ICONS,
                    methodName = METHOD_LAYOUT,
                    paramCount = 5,
                    returnType = "void",
                    id = ID_LAYOUT,
                    description = "U9 三合一隐藏复位（HwSystemIcons.onLayout 后重申原生信号组/电池 GONE）",
                ),
            )
            add(
                MountEngine.SimpleTask(
                    className = CLS_SYSTEM_ICONS,
                    methodName = METHOD_UPDATE_ICONS_ALPHA,
                    paramCount = 0,
                    returnType = "void",
                    id = ID_UPDATE_ALPHA,
                    description = "U9 三合一胶囊避让（HwSystemIcons.updateIconsAlpha 后恢复状态图标 alpha，胶囊不再挤掉蓝牙等图标）",
                ),
            )
            add(
                MountEngine.SimpleTask(
                    className = CLS_STATUSBAR_CONTENTS,
                    methodName = METHOD_REFRESH_TINT,
                    paramCount = 0,
                    returnType = "void",
                    id = ID_REFRESH_TINT,
                    description = "U9 三合一反色（HwStatusbarContents.refreshTint 后按电池字段重采样重绘，HonorDuo 同源）",
                ),
            )
            // 蓝牙耳机电量 hook 由模式独立控制挂载：模式=关闭（默认）时不挂，
            // 避免挂载/回调异常穿透 SystemUI 触发 LSPosed 安全模式。
            // 模式切换需重启 SystemUI 生效（与其他 hook 一致，挂载时机是 onPackageLoaded 一次性）。
            if (RemoteConfig.int(FeatureCatalog.KEY_SYSTEMUI_TRIO_BT_BATTERY, 0).coerceIn(0, 2) > 0) {
                add(
                    MountEngine.SimpleTask(
                        className = CLS_BT_POLICY,
                        methodName = METHOD_UPDATE_BLUETOOTH,
                        paramCount = 0,
                        returnType = "void",
                        id = ID_BT_BATTERY,
                        description = "U9 三合一蓝牙耳机电量（hook HwPhoneStatusBarPolicy.updateBluetooth，proceed 后反射读 HwBluetoothControllerImpl.mBluetoothBatteryLevel）",
                    ),
                )
            }
            // 灵动胶囊禁用状态栏缩放：hook 胶囊状态入口，上岛缩小动画播完后触发系统原生回弹。
            if (RemoteConfig.isEnabled(FeatureCatalog.KEY_SYSTEMUI_CAPSULE_NO_SCALE)) {
                add(
                    MountEngine.SimpleTask(
                        className = CLS_ANIM_CONTROLLER,
                        methodName = METHOD_UPDATE_CAPSULE_STATE,
                        paramCount = 1,
                        returnType = "void",
                        id = ID_CAPSULE_STATE,
                        description = "U9 灵动胶囊状态栏不缩放（updateCapsuleState(1) 后复用系统 startStatusBarScaleAnim(false) 回弹）",
                    ),
                )
            }
            // 下拉面板切原生图标：开关关（需重启 SystemUI）时不挂，面板状态恒按未展开处理。
            if (RemoteConfig.isEnabled(FeatureCatalog.KEY_SYSTEMUI_PANEL_NATIVE_ICONS)) {
                add(
                    MountEngine.SimpleTask(
                        className = CLS_PANEL_VIEW,
                        methodName = METHOD_SET_EXPANDED_HEIGHT,
                        paramCount = 1,
                        returnType = "void",
                        id = ID_PANEL_EXPAND,
                        description = "U9 下拉面板切原生图标（HwPanelView.setExpandedHeightInternal 后按 mExpandedFraction 切换三合一/原生显隐）",
                    ),
                )
                add(
                    MountEngine.SimpleTask(
                        className = CLS_PANEL_CONTROLLER,
                        methodName = METHOD_SET_SHOWING_TYPE,
                        paramCount = 1,
                        returnType = "void",
                        id = ID_SHOWING_TYPE,
                        description = "U9 面板收起恢复三合一兜底（HnPanelViewControllerImpl.setPanelShowingType(0) 后轮询 fraction 归零恢复）",
                    ),
                )
            }
        }
        // 诊断：记录条件任务挂载状态（日志确认开关是否生效及挂载了哪些 hook）。
        XLog.diag(
            Log.INFO,
            MountEngine.TAG,
            "$JUDGE_PREFIX 条件任务: btStyle=${RemoteConfig.int(FeatureCatalog.KEY_SYSTEMUI_TRIO_BT_BATTERY, 0)}," +
                " capsuleNoScale=${RemoteConfig.isEnabled(FeatureCatalog.KEY_SYSTEMUI_CAPSULE_NO_SCALE)}," +
                " panelNative=${RemoteConfig.isEnabled(FeatureCatalog.KEY_SYSTEMUI_PANEL_NATIVE_ICONS)}," +
                " 任务数=${pending.size}",
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
            ID_CAPSULE_STATE -> {
                chain.proceed()
                // i==1 = 胶囊上岛常驻（系统刚启动缩小动画）。只有 i==1 才恢复：
                // i==2 是点击展开（状态栏缩到消失属预期），i==0 是关闭（系统自己回弹）。
                val state = chain.args?.firstOrNull() as? Int
                if (state == 1) {
                    runCatching { chain.thisObject?.let { scheduleCapsuleScaleRestore(it) } }
                }
                null
            }
            ID_MEASURE -> {
                // 防闪根修：必须在 measure 之前压回 GONE——宿主电量/信号更新把原生件置回
                // VISIBLE 后，若 measure 已按 VISIBLE 排版，再 GONE 就会触发「含电池宽度→
                // 回跳」的单帧抖动（状态栏闪一下）。这里前置处理，本帧排版即正确。
                // 面板展开时跳过：此时原生件应正常参与排版（切原生图标）。
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
                    // 灵动胶囊不缩放：onLayout 兜底瞬时 snap（主路径是 updateCapsuleState hook
                    // 触发系统原生回弹动画；这里只兜无动画路径的残留缩放）。
                    if (RemoteConfig.isEnabled(FeatureCatalog.KEY_SYSTEMUI_CAPSULE_NO_SCALE)) {
                        runCatching { resetSubtreeScale(host) }
                    }
                }
            }
            ID_UPDATE_ALPHA -> {
                // 原生在这里按胶囊边界把越界的状态/网络图标 alpha 置 0（蓝牙通常是最左那个）。
                // 三合一已经腾出信号与电量空间，这里把原生 alpha 结果再拉回可见。
                chain.proceed()
                (chain.thisObject as? ViewGroup)?.let { host ->
                    runCatching { restoreIconAlpha(host) }
                }
            }
            ID_REFRESH_TINT -> {
                // refreshTint 返回时电池 mTintColor/mBorderView 已写完（invalidate 只是预约
                // 下一 vsync 重绘，onDraw 实际发生在本调用栈之后，采到的就是新值）。
                chain.proceed()
                invalidateAllTrio()
            }
            ID_PANEL_EXPAND -> {
                // 面板滑动/动画期间逐帧回调：仅读字段比对状态，翻转时才动 View，开销可忽略。
                chain.proceed()
                (chain.thisObject as? View)?.let { panel ->
                    runCatching { updatePanelState(panel) }
                }
                null
            }
            ID_SHOWING_TYPE -> {
                // 收起兜底：byte 0 = 面板收起（fling/无动画/锁屏等所有路径的收口）。
                // 触发时收起动画刚开始，fraction 未归零，交给轮询等归零后恢复三合一。
                chain.proceed()
                if ((chain.args?.firstOrNull() as? Byte)?.toInt() == 0) {
                    runCatching { chain.thisObject?.let { scheduleCollapseSettle(it) } }
                }
                null
            }
            ID_BT_BATTERY -> {
                // proceed 后 thisObject.mBluetooth 已是当前 HwBluetoothControllerImpl 实例，
                // 反射读 mBluetoothBatteryLevel 缓存；整条链路 runCatching，任何异常不穿透 SystemUI。
                chain.proceed()
                runCatching {
                    val policy = chain.thisObject ?: return@runCatching
                    val policyField = btPolicyBluetoothField ?: findField(policy.javaClass, "mBluetooth")
                        ?.also { btPolicyBluetoothField = it } ?: return@runCatching
                    val controller = policyField.get(policy) ?: return@runCatching
                    val levelField = btControllerBatteryLevelField
                        ?: findField(controller.javaClass, "mBluetoothBatteryLevel")
                            ?.also { btControllerBatteryLevelField = it } ?: return@runCatching
                    val v = runCatching { levelField.getInt(controller) }.getOrDefault(-1)
                    if (v != cachedBtBattery) {
                        cachedBtBattery = v
                        mainHandler.post { invalidateAllTrio() }
                    }
                    // BT 状态/电量变化可能增删图标 View：回主线程清缓存并按融合状态重设
                    // 蓝牙单件显隐（与信号/Wi-Fi 同 View 显隐体系）。断开/电量未知时原生
                    // proceed 已重设图标显隐，helper 内部按融合状态判定，不会误动。
                    mainHandler.post {
                        btIconViews.clear()
                        applyBluetoothIconVisibilityAll()
                    }
                    // 电量未知（典型：SystemUI 重启后 BATTERY_LEVEL_CHANGED 尚未再上报）：
                    // 主动经 HEADSET 代理查缓存电量并写回原生状态，让三合一与原生图标
                    // 立即显示电量，无需断开重连耳机。
                    if (v !in 0..100) {
                        clearStickyBtBattery(policy, controller)
                        seedBluetoothBattery(policy, controller)
                    }
                }
            }
            else -> chain.proceed()
        }
    }

    /** id 解析缓存：onMeasure 是高频回调，getIdentifier 字符串查表不能每次都跑。 */
    @Volatile private var hiddenIdCache: IntArray? = null

    /** 胶囊避让会动 alpha 的两组容器（statusIcons 放蓝牙等状态图标，network_area_icons 放网络图标）。 */
    @Volatile private var alphaGroupIdCache: IntArray? = null

    /** 资源名 → id 惰性解析（id 按包名解析，各宿主实例通用；调用方负责缓存结果）。 */
    private fun resolveIds(context: Context, names: Array<String>): IntArray {
        val res = context.resources
        val pkg = context.packageName
        return names.mapNotNull { name ->
            res.getIdentifier(name, "id", pkg).takeIf { it != 0 }
        }.toIntArray()
    }

    /** 原生按胶囊边界把越界图标 alpha 置 0，这里把它们拉回可见（仅在三合一生效时）。 */
    private fun restoreIconAlpha(host: ViewGroup) {
        if (!RemoteConfig.isEnabled(FeatureCatalog.KEY_SYSTEMUI_TRIO_KEEP_ICONS)) return
        if (host.findViewWithTag<View>(TAG_VIEW) == null) return
        for (id in alphaGroupIds(host.context)) {
            val group = host.findViewById<ViewGroup>(id) ?: continue
            for (i in 0 until group.childCount) {
                val child = group.getChildAt(i) ?: continue
                if (child.visibility == View.VISIBLE && child.alpha != 1f) child.alpha = 1f
            }
        }
    }

    /** 宿主偶尔会把电池/信号重新显示（电量/信号更新、AOD/胶囊切换），测量/布局时重申一次隐藏。 */
    private fun assertHidden(host: ViewGroup) {
        // 面板展开（切原生图标）期间不再压 GONE，否则与切换意图打架，原生件永远显示不出来。
        if (panelExpanded) return
        if (host.findViewWithTag<View>(TAG_VIEW) == null) return
        for (id in hiddenIds(host)) {
            host.findViewById<View>(id)?.let { v ->
                if (v.visibility != View.GONE) v.visibility = View.GONE
            }
        }
        // 蓝牙单件同理重申（BT 图标刷新可能绕过 layout，这里与 preDraw 双保险）。
        applyBluetoothIconVisibility(host, expanded = false)
    }

    /** 要隐藏的原生件 id（除整组 signalIcons 与 battery 外，再兜底隐藏组内单件——个别版本会把单件挂到别的容器）。 */
    private fun hiddenIds(host: ViewGroup): IntArray {
        hiddenIdCache?.let { return it }
        return resolveIds(host.context, HIDDEN_IDS).also { hiddenIdCache = it }
    }

    /**
     * 面板展开状态更新（setExpandedHeightInternal proceed 后调用，主线程）。
     *
     * mExpandedFraction > 0 即视为展开（含滑动半展开，与 isFullyCollapsed 口径一致）；
     * heads-up 横幅抬升的展开不算（用户没下拉面板，状态栏行应保持三合一）。
     * 仅在展开态翻转时逐宿主切换显隐，滑动期间的逐帧回调走纯字段比对，零 View 操作。
     */
    private fun updatePanelState(panel: View) {
        val fractionField = panelFractionField
            ?: findField(panel.javaClass, "mExpandedFraction")?.also { panelFractionField = it }
            ?: return
        val fraction = runCatching { fractionField.getFloat(panel) }.getOrDefault(0f)
        var expanded = fraction > 0f
        // heads-up 反射调用只在 0→1 翻转前做一次：展开稳态下不再逐帧 invoke；
        // 同时避免面板已开时恰好有 pinned heads-up 被误判为「仅横幅展开」而错误恢复三合一。
        if (expanded && !panelExpanded) {
            val headsUp = panelHeadsUpMethod ?: runCatching {
                panel.javaClass.getMethod(METHOD_HEADS_UP_VISIBLE)
            }.getOrNull()?.also { panelHeadsUpMethod = it }
            if (headsUp != null &&
                runCatching { headsUp.invoke(panel) as? Boolean }.getOrNull() == true
            ) {
                expanded = false
            }
        }
        if (expanded != panelExpanded) {
            panelExpanded = expanded
            XLog.diag(Log.INFO, MountEngine.TAG, "$JUDGE_PREFIX 面板状态切换(fraction钩子): expanded=$expanded fraction=$fraction hosts=${injectedHosts.size}")
            applyPanelVisibility(expanded)
        }
    }

    /** 面板展开态翻转：所有已注入宿主统一切换（展开→原生件 VISIBLE + 三合一 GONE；收起→反之）。 */
    private fun applyPanelVisibility(expanded: Boolean) {
        val it = injectedHosts.iterator()
        while (it.hasNext()) {
            val host = it.next().get()
            if (host == null) it.remove() else runCatching { applyHostVisibility(host, expanded) }
        }
    }

    /** 蓝牙电量融合当前是否生效（开关开 + 耳机电量可读）。 */
    private fun btMerged(): Boolean = cachedBtBattery in 0..100 &&
        RemoteConfig.int(FeatureCatalog.KEY_SYSTEMUI_TRIO_BT_BATTERY, 0) > 0

    /**
     * 清理原生「B+电量」粘滞态：mIsBluetoothEarBattery 只在蓝牙整体关闭时复位，
     * 设备断开（含改连电动车等无电量设备）时原生 receiver 的清理会被
     * getBatteryLevel 缓存防护跳过（HonorModuleImpl 断开分支 early return），
     * 导致 getSuggestBluetoothIcon 用残留电量图标覆盖普通 B 图标。
     * 条件：电量未知（mBluetoothBatteryLevel ∉ 0..100）且粘滞位仍置位——
     * 置 false 后重调 updateBluetooth 让原生按当前连接态重绘普通图标；
     * 重调会再过一次本钩子，此时粘滞位已清，不再重复清理（另有 btClearing 防重入）。
     * 耳机电量有效（0..100）时不触碰，不影响正常电量显示。
     */
    private fun clearStickyBtBattery(policy: Any, controller: Any) {
        if (btClearing) return
        val flag = btEarBatteryFlagField
            ?: findField(controller.javaClass, "mIsBluetoothEarBattery")
                ?.also { btEarBatteryFlagField = it } ?: return
        if (!runCatching { flag.getBoolean(controller) }.getOrDefault(false)) return
        val setter = btBatteryEnableMethod ?: runCatching {
            controller.javaClass.getMethod("setBluetoothBatteryEnable", Boolean::class.javaPrimitiveType)
        }.getOrNull()?.also { btBatteryEnableMethod = it } ?: return
        btClearing = true
        try {
            setter.invoke(controller, false)
            policy.javaClass.getMethod("updateBluetooth").invoke(policy)
            XLog.diag(Log.INFO, MountEngine.TAG, "$JUDGE_PREFIX 已清理蓝牙电量图标粘滞态（无电量设备/未连接）")
        } finally {
            btClearing = false
        }
    }

    /**
     * 蓝牙电量播种：SystemUI 重启后原生 mBluetoothBatteryLevel 未就绪（原生 HEADSET 代理
     * 播种路径把 0~100 原始值传给只接受 1~10 档位的 updateBluetoothEarBattery，被校验
     * 拒绝，故原生自己也得等下次 BATTERY_LEVEL_CHANGED 上报）。这里自行查已连接设备的
     * 缓存电量（BluetoothDevice.getBatteryLevel 读系统服务侧缓存，无需耳机重新上报），
     * 按原生 receiver 口径写回字段 + 重算图标档位 + 重调 updateBluetooth 刷新原生图标；
     * 该调用会再过一次本钩子，届时电量已知，不会再触发播种。
     */
    private fun seedBluetoothBattery(policy: Any, controller: Any) {
        if (btSeeding) return
        val adapter = runCatching { BluetoothAdapter.getDefaultAdapter() }.getOrNull() ?: return
        if (!runCatching { adapter.isEnabled }.getOrDefault(false)) return
        val ctxField = findField(policy.javaClass, "mContext") ?: return
        val ctx = runCatching { ctxField.get(policy) as? Context }.getOrNull() ?: return
        val levelField = btControllerBatteryLevelField ?: return
        btSeeding = true
        runCatching {
            adapter.getProfileProxy(ctx, object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                    if (profile == BluetoothProfile.HEADSET) {
                        val dev = runCatching { proxy.connectedDevices?.firstOrNull() }.getOrNull()
                        // getBatteryLevel 不在编译用 android.jar 公开 API 内（运行时存在，原生直连），反射读取。
                        val level = dev?.let {
                            runCatching {
                                it.javaClass.getMethod("getBatteryLevel").invoke(it) as Int
                            }.getOrDefault(-1)
                        } ?: -1
                        val address = dev?.address
                        if (level in 1..100 && !address.isNullOrEmpty()) {
                            mainHandler.post {
                                runCatching {
                                    levelField.setInt(controller, level)
                                    val rounded = if (level > 10) Math.round(level / 10f) else 1
                                    controller.javaClass
                                        .getMethod(
                                            "updateBluetoothEarBattery",
                                            Int::class.javaPrimitiveType,
                                            String::class.java,
                                        )
                                        .invoke(controller, rounded, address)
                                    policy.javaClass.getMethod("updateBluetooth").invoke(policy)
                                    XLog.diag(Log.INFO, MountEngine.TAG, "$JUDGE_PREFIX 蓝牙电量播种成功: level=$level")
                                }
                            }
                        }
                    }
                    runCatching { adapter.closeProfileProxy(profile, proxy) }
                    btSeeding = false
                }

                override fun onServiceDisconnected(profile: Int) {
                    btSeeding = false
                }
            }, BluetoothProfile.HEADSET)
        }.onFailure { btSeeding = false }
    }

    /** 对所有已注入宿主按融合状态与面板状态重设蓝牙图标单件显隐（仅主线程）。 */
    private fun applyBluetoothIconVisibilityAll() {
        val it = injectedHosts.iterator()
        while (it.hasNext()) {
            val host = it.next().get()
            if (host == null) it.remove() else runCatching { applyBluetoothIconVisibility(host, panelExpanded) }
        }
    }

    /**
     * 蓝牙图标显隐与信号/Wi-Fi 同一 View 体系：融合生效且面板收起时 GONE，展开时恢复
     * VISIBLE；融合未生效（断开/电量未知/开关关）时不触碰——原生 updateBluetooth 的
     * proceed 已按连接状态重设过图标显隐，避免把已关闭的蓝牙图标误点亮。
     */
    private fun applyBluetoothIconVisibility(host: ViewGroup, expanded: Boolean) {
        if (!btMerged()) return
        val v = findBluetoothIconView(host) ?: return
        val want = if (expanded) View.VISIBLE else View.GONE
        if (v.visibility != want) v.visibility = want
    }

    /**
     * 定位宿主 statusIcons 组内的蓝牙图标单件（StatusBarIconView.mSlot == "bluetooth"）。
     * 结果按宿主缓存；BT 状态变化时由 updateBluetooth 钩子清空重查。
     */
    private fun findBluetoothIconView(host: ViewGroup): View? {
        btIconViews[host]?.get()?.let { if (it.parent != null) return it }
        val groupId = statusIconsIdCache.takeIf { it != 0 } ?: run {
            val id = host.context.resources.getIdentifier("statusIcons", "id", host.context.packageName)
            statusIconsIdCache = id
            id
        }
        if (groupId == 0) return null
        val group = host.findViewById<ViewGroup>(groupId) ?: return null
        for (i in 0 until group.childCount) {
            val child = group.getChildAt(i) ?: continue
            val f = iconSlotField ?: runCatching {
                child.javaClass.getField("mSlot")
            }.getOrNull()?.also { iconSlotField = it } ?: continue
            if (runCatching { f.get(child) as? String }.getOrNull() == "bluetooth") {
                btIconViews[host] = WeakReference(child)
                return child
            }
        }
        return null
    }

    private fun applyHostVisibility(host: ViewGroup, expanded: Boolean) {
        val trio = host.findViewWithTag<View>(TAG_VIEW) ?: return
        if (expanded) {
            trio.visibility = View.GONE
            for (id in hiddenIds(host)) {
                host.findViewById<View>(id)?.let { v ->
                    if (v.visibility != View.VISIBLE) v.visibility = View.VISIBLE
                }
            }
        } else {
            for (id in hiddenIds(host)) {
                host.findViewById<View>(id)?.let { v ->
                    if (v.visibility != View.GONE) v.visibility = View.GONE
                }
            }
            trio.visibility = View.VISIBLE
        }
        // 蓝牙单件与上面同体系，随面板状态一并翻转。
        applyBluetoothIconVisibility(host, expanded)
    }

    /**
     * 上岛缩小动画播完 → 触发系统原生回弹（startStatusBarScaleAnim(false)）。
     *
     * mStatusBarScaleAnim 非空 = 缩小动画在跑：挂结束监听，播完走系统恢复路径（原生 spring，零自写动画）。
     * 为空 = forbid 模式（卡片置顶等，无动画直接置状态）：立即翻回状态，视觉上此模式图标本就隐藏。
     */
    private fun scheduleCapsuleScaleRestore(controller: Any) {
        val animField = scaleAnimField
            ?: findField(controller.javaClass, "mStatusBarScaleAnim")?.also { scaleAnimField = it }
            ?: return
        val anim = animField.get(controller) as? AnimatorSet
        if (anim != null) {
            capsuleRestoreRunning = true
            anim.addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    // post 出当前调用栈：避免与外层 updateCapsuleState(0)（关胶囊）的 end() 重入。
                    mainHandler.post { runCatching { invokeNativeScaleRestore(controller) } }
                }
            })
        } else {
            invokeNativeScaleRestore(controller)
        }
    }

    /** 反射调 startStatusBarScaleAnim(false, false)（胶囊关闭时系统恢复大小的同一入口）。 */
    private fun invokeNativeScaleRestore(controller: Any) {
        // 仅胶囊仍常驻（mCapsuleExpandState==1）才恢复：若用户已关胶囊（==0），系统自己会回弹，跳过防重复。
        val stateField = capsuleExpandStateField
            ?: findField(controller.javaClass, "mCapsuleExpandState")?.also { capsuleExpandStateField = it }
        if (stateField != null && runCatching { stateField.getInt(controller) }.getOrDefault(-1) != 1) {
            capsuleRestoreRunning = false
            return
        }
        val method = startScaleAnimMethod ?: runCatching {
            controller.javaClass.getDeclaredMethod(
                "startStatusBarScaleAnim",
                Boolean::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType,
            ).also { it.isAccessible = true }
        }.getOrNull()?.also { startScaleAnimMethod = it } ?: run {
            capsuleRestoreRunning = false
            return
        }
        runCatching { method.invoke(controller, false, false) }
        // 回弹动画启动则挂监听清标志（期间 onLayout 兜底不打断 spring）；未启动（early return）立即清。
        val upAnim = scaleAnimField?.get(controller) as? AnimatorSet
        if (upAnim != null) {
            upAnim.addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    capsuleRestoreRunning = false
                }
            })
        } else {
            capsuleRestoreRunning = false
        }
    }

    @Volatile private var scaleAnimField: Field? = null
    @Volatile private var capsuleExpandStateField: Field? = null
    @Volatile private var startScaleAnimMethod: java.lang.reflect.Method? = null
    /** 系统恢复动画在跑标志：true 期间 onLayout 兜底不瞬时 snap，避免打断原生 spring。 */
    @Volatile private var capsuleRestoreRunning = false

    /**
     * onLayout 兜底：瞬时重置子树 scaleX/scaleY = 1.0f（非动画路径残留，如 forbid 模式的直接
     * setScaleXY(0.88)）。系统恢复动画在跑（capsuleRestoreRunning）时跳过，不打断原生回弹。
     */
    private fun resetSubtreeScale(view: View) {
        if (capsuleRestoreRunning) return
        if (view.scaleX != 1.0f || view.scaleY != 1.0f) {
            view.scaleX = 1.0f
            view.scaleY = 1.0f
            return  // 子 View 跟随父容器缩放，无需递归
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                view.getChildAt(i)?.let { resetSubtreeScale(it) }
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
        // 电池在布局里是 system_icons 的直接子件；从 HwSystemIcons 根查，不依赖隐藏清单命中。
        val battery = host.findViewById<View>(id("battery"))

        // 反色取色复刻 HonorDuo resolveInk：电池 mTintColor（refreshTint 无条件写入，GONE 也是
        // 新值）→ 电池边框 mBorderView.mDefaultColor → SignalClusterView.mIconTint 兜底。
        // 电池实例由下方 tintProvider 闭包捕获（与 TrioView 同属一个状态栏实例、同生命周期）。
        if (battery != null) {
            batteryTintField = findField(battery.javaClass, "mTintColor")
            batteryBorderField = findField(battery.javaClass, "mBorderView")
        }
        val cluster = host.rootView.findViewById<View>(id("signal_cluster"))
        val tintField = runCatching {
            cluster?.javaClass?.getDeclaredField("mIconTint")?.apply { isAccessible = true }
        }.getOrNull()
        // Wi-Fi 弧 / 信号点 / 电量数字 / 无 Wi-Fi 显示制式：恒开（原子开关已精简，无场景需要关闭）
        val config = TrioConfig(
            showWifi = true,
            showDots = true,
            showPercent = true,
            showType = true,
            levelColor = RemoteConfig.isEnabled(FeatureCatalog.KEY_SYSTEMUI_TRIO_LEVEL_COLOR),
            antiBurnIn = RemoteConfig.isEnabled(FeatureCatalog.KEY_SYSTEMUI_TRIO_ANTI_BURNIN),
            btBatteryMode = RemoteConfig.int(FeatureCatalog.KEY_SYSTEMUI_TRIO_BT_BATTERY, 0).coerceIn(0, 2),
            style = FeatureCatalog.resolveTrioStyle(
                RemoteConfig.int(FeatureCatalog.KEY_SYSTEMUI_TRIO_STYLE, FeatureCatalog.defaultTrioStyle()),
            ),
            capsuleLength = FeatureCatalog.resolveCapsuleLength(
                RemoteConfig.int(
                    FeatureCatalog.KEY_SYSTEMUI_TRIO_CAPSULE_LENGTH,
                    FeatureCatalog.defaultCapsuleLength(),
                ),
            ),
            capsuleHeight = FeatureCatalog.resolveCapsuleHeight(
                RemoteConfig.int(
                    FeatureCatalog.KEY_SYSTEMUI_TRIO_CAPSULE_HEIGHT,
                    FeatureCatalog.defaultCapsuleHeight(),
                ),
            ),
            capsuleStrokePct = FeatureCatalog.resolveCapsuleStroke(
                RemoteConfig.int(
                    FeatureCatalog.KEY_SYSTEMUI_TRIO_CAPSULE_STROKE,
                    FeatureCatalog.defaultCapsuleStroke(),
                ),
            ),
            capsuleMarginHPct = FeatureCatalog.resolveCapsuleMarginH(
                RemoteConfig.int(
                    FeatureCatalog.KEY_SYSTEMUI_TRIO_CAPSULE_MARGIN_H,
                    FeatureCatalog.defaultCapsuleMarginH(),
                ),
            ),
            capsuleMarginVPct = FeatureCatalog.resolveCapsuleMarginV(
                RemoteConfig.int(
                    FeatureCatalog.KEY_SYSTEMUI_TRIO_CAPSULE_MARGIN_V,
                    FeatureCatalog.defaultCapsuleMarginV(),
                ),
            ),
        )
        val sizeDp = FeatureCatalog.resolveTrioSize(
            RemoteConfig.int(
                FeatureCatalog.KEY_SYSTEMUI_TRIO_SIZE,
                FeatureCatalog.defaultTrioSize(),
            ),
        )
        val view = TrioView(ctx, config)
        view.tintProvider = {
            val sampled = sampleBatteryTint(battery)
            if (sampled != 0) sampled else runCatching { tintField?.getInt(cluster) }.getOrNull() ?: 0
        }
        view.tag = TAG_VIEW
        // 固定 dp 尺寸，不跟父容器高度拉伸：横屏/锁屏的状态栏行高更大时字形也不会放大。
        // 胶囊按「独立高度 × 宽高比」放宽；圆环保持正方形、用三合一尺寸。
        val side = dp(ctx, if (config.style == STYLE_CAPSULE) config.capsuleHeight else sizeDp)
        val widthPx = if (config.style == STYLE_CAPSULE) {
            (side * FeatureCatalog.capsuleAspect(config.capsuleLength, config.capsuleStrokePct))
                .toInt().coerceAtLeast(side)
        } else {
            side
        }
        val lp: ViewGroup.LayoutParams = if (systemIcons is LinearLayout) {
            LinearLayout.LayoutParams(widthPx, side).apply {
                gravity = Gravity.CENTER_VERTICAL
            }
        } else {
            ViewGroup.LayoutParams(widthPx, side)
        }
        systemIcons.addView(view, lp)

        // 宿主在电量/信号更新时可能把子视图重新设为 VISIBLE（且不一定触发 onLayout），
        // 这里挂 pre-draw 每帧重申；GONE→GONE 是空操作，开销可忽略。
        // 面板展开（切原生图标）期间跳过重申，让原生件保持可见。
        hiddenTargets.forEach { it.visibility = View.GONE }
        applyBluetoothIconVisibility(host, panelExpanded)
        host.viewTreeObserver.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (!panelExpanded) {
                    for (target in hiddenTargets) {
                        if (target.visibility != View.GONE) target.visibility = View.GONE
                    }
                    applyBluetoothIconVisibility(host, expanded = false)
                }
                return true
            }
        })
        injectedHosts.add(WeakReference(host))
        // 注入时面板已展开（如面板开着时 SystemUI 重启）：立即按当前面板状态切原生显隐。
        if (panelExpanded) runCatching { applyHostVisibility(host, true) }
        trioViews.add(WeakReference(view))
        XLog.log(
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
    private class TrioView(
        context: Context,
        private val config: TrioConfig,
    ) : View(context) {

        /** 反色采样回调：注入后由 TrioFeature 赋值，返回 ARGB 或 0（取不到）。 */
        var tintProvider: (() -> Int)? = null

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
            // cachedBtBattery）；模式=关闭时保持 -1 走原信号点分支。原生只在蓝牙状态变化时刷新，
            // 连接/断开/电量上报都会触发，无需自己轮询 BluetoothManager。
            bluetoothBattery = if (config.btBatteryMode > 0) cachedBtBattery else -1

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
                val fallback = runCatching { tm?.signalStrength?.level }.getOrNull()
                if (fallback != null && fallback >= 0) slots[0] = fallback.coerceIn(0, 4)
            }
            // 信号以下开口为主：单卡（无论插哪个槽）归位到下开口，顶部闭合；
            // 双卡时上开口 = 卡1、下开口 = 卡2。
            val dual = slots[0] >= 0 && slots[1] >= 0
            if (!dual && (slots[0] >= 0 || slots[1] >= 0)) {
                slots[1] = if (slots[0] >= 0) slots[0] else slots[1]
                slots[0] = -1
            }
            val diagMsg = "sim主副卡: slots=${slots[0]},${slots[1]} subs=${subs?.size ?: -1}$diagSb"
            if (diagMsg != lastSlotDiagMsg) {
                lastSlotDiagMsg = diagMsg
                XLog.diag(Log.INFO, MountEngine.TAG, "$JUDGE_PREFIX $diagMsg")
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
                    "$JUDGE_PREFIX TrioView.onDraw 异常（已防护，跳过本帧绘制）: ${t.javaClass.simpleName}: ${t.message}",
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
            drawTextAt(
                canvas,
                level.toString(),
                capRight - capStroke * 0.5f - capTextInset,
                capInteriorCy,
            )
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
}
