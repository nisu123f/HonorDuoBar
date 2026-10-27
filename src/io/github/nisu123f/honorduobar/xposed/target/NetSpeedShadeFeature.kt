package io.github.nisu123f.honorduobar.xposed.target

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import io.github.nisu123f.honorduobar.FeatureCatalog
import io.github.nisu123f.honorduobar.xposed.core.MountEngine
import io.github.nisu123f.honorduobar.xposed.core.XLog
import io.github.nisu123f.honorduobar.xposed.data.RemoteConfig
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * 实时网速仅下拉面板显示（反编译 SystemUI 17.0.0.1 实证）。
 *
 * 原生链路：`NetworkSpeedManagerEx`（单例 `sNetworkSpeedManagerEx`）的 `checkShowAndUpdate()`
 * 是所有启停决策的唯一收口（设置开关/Wi-Fi/飞行模式/数据开关等观察者都汇到它）：
 * - `start$1$1()`：`notifyUpdateVisibility(true)`（所有 `NetworkSpeedView` 置 VISIBLE）+
 *   开启 `mRunnable` 3 秒轮询（每 tick 经 `INetworkStatsService` binder 查流量统计）；
 * - `stop$1()`：停轮询 + `notifyUpdateVisibility(false)`（全部置 GONE）+ 复位基线。
 *
 * 本功能语义：仅面板展开时允许原生启停逻辑运行；其余场景（状态栏收起、锁屏）一律
 * 「暂停」——停轮询 + 隐藏，但**不复位基线**（自定义暂停替代原生 `stop$1()`，
 * 保留 mIsFirst=false 与 mLastValue/mLastRefreshTime）。这样展开时 `start$1$1()`
 * 立即首拍即按真实时间差算出速度（下滑过程中就显示，无 mIsFirst 强制 0 闪显），
 * 展开后 1.2s 再手动补一拍给出精确瞬时值。
 *
 * 挂载点（与 TrioFeature 零冲突——MethodResolver 全局同方法只挂一次）：
 * - `NetworkSpeedManagerEx.checkShowAndUpdate()`：面板未展开 → 自定义暂停并跳过原逻辑；
 *   展开 → proceed 走原生判定。
 * - `PanelView.setExpandedHeightInternal(float)`：展开状态跟踪（基类 PanelView 的实现，
 *   TrioFeature 挂的是 HwPanelView 覆写版，两个 Method 对象各自独立）。MagicOS 收起动画
 *   末端 fraction 归零不进此方法，故展开期间以 200ms 轮询 fraction 自闭环检测收起，
 *   不占用 trio 的 HnPanelViewControllerImpl.setPanelShowingType 挂载点。
 */
object NetSpeedShadeFeature {

    private const val HOST_PACKAGE = "com.android.systemui"
    private const val CLS_SPEED_MANAGER = "com.android.systemui.networkspeed.NetworkSpeedManagerEx"
    private const val CLS_PANEL_VIEW = "com.android.systemui.statusbar.phone.PanelView"

    private const val METHOD_CHECK = "checkShowAndUpdate"
    private const val METHOD_SET_EXPANDED_HEIGHT = "setExpandedHeightInternal"
    private const val METHOD_HEADS_UP_VISIBLE = "isPanelVisibleBecauseOfHeadsUp"
    private const val METHOD_NOTIFY_VISIBILITY = "notifyUpdateVisibility"

    private const val ID_CHECK = "systemui:netspeed.manager.checkShowAndUpdate"
    private const val ID_PANEL_FRACTION = "systemui:netspeed.panelView.setExpandedHeightInternal"

    private const val JUDGE_PREFIX = "SystemUiHook·NetSpeed"

    /** 收起检测轮询间隔（仅展开期间运行；收起末端 fraction 归零不进 hook，必须轮询兜底）。 */
    private const val COLLAPSE_POLL_MS = 200L

    /**
     * 展开预热延迟：原生首拍只建基线并强制显示 0（mIsFirst），第二拍（+3s）才出真实速度。
     * 展开后 1.2s 手动补一拍（j>0 才计算，须跨过 1s 整秒分辨率），约 1s 内出真实速度。
     */
    private const val WARMUP_TICK_MS = 1200L

    private val mainHandler = Handler(Looper.getMainLooper())

    /** 面板展开全局状态（fraction > 0，heads-up 剔除；仅主线程读写）。 */
    @Volatile private var panelExpanded = false

    /** 宿主 ClassLoader（反射解析网速管理器用，install 时留存）。 */
    @Volatile private var hostLoader: ClassLoader? = null

    // ---- 反射缓存 ----
    @Volatile private var managerClass: Class<*>? = null
    @Volatile private var managerInstanceField: Field? = null
    @Volatile private var managerCheckMethod: Method? = null
    @Volatile private var managerNotifyMethod: Method? = null
    @Volatile private var managerRunnableField: Field? = null
    @Volatile private var managerTimeoutField: Field? = null
    @Volatile private var managerHandlerField: Field? = null
    @Volatile private var managerIsStopField: Field? = null
    @Volatile private var panelFractionField: Field? = null
    @Volatile private var panelHeadsUpMethod: Method? = null

    /** 展开期间收起轮询：当前跟踪的面板实例与运行标记（仅主线程读写）。 */
    private var pollPanel: WeakReference<View>? = null
    private var pollRunning = false

    fun install(module: XposedModule, param: XposedModuleInterface.PackageLoadedParam) {
        if (!RemoteConfig.isEnabled(FeatureCatalog.KEY_SYSTEMUI_NETSPEED_SHADE_ONLY)) {
            XLog.log(Log.INFO, MountEngine.TAG, "$JUDGE_PREFIX 功能已关闭，跳过挂载")
            return
        }
        val loader = param.defaultClassLoader
        hostLoader = loader
        val session = MountEngine.open(
            JUDGE_PREFIX,
            HOST_PACKAGE,
            param.applicationInfo?.sourceDir,
            FeatureCatalog.baselineVersionOf(HOST_PACKAGE),
        )
        val pending = listOf(
            MountEngine.SimpleTask(
                className = CLS_SPEED_MANAGER,
                methodName = METHOD_CHECK,
                paramCount = 0,
                returnType = "void",
                id = ID_CHECK,
                description = "NetSpeed 启停门控（checkShowAndUpdate：面板未展开 → stop\$1 停轮询+隐藏，跳过原生启动）",
            ),
            MountEngine.SimpleTask(
                className = CLS_PANEL_VIEW,
                methodName = METHOD_SET_EXPANDED_HEIGHT,
                paramCount = 1,
                returnType = "void",
                id = ID_PANEL_FRACTION,
                description = "NetSpeed 面板展开跟踪（PanelView.setExpandedHeightInternal 后按 mExpandedFraction 翻转启停网速）",
            ),
        )
        session.attemptMount(pending, module, loader) { task ->
            session.mount(task, module, loader, XposedInterface.Hooker { chain ->
                // 回调异常由 MountSession 记 WARN 并 rethrow，框架 PROTECTIVE 回退原生调用；
                // 这里不再兜底 proceed，避免与已执行的 proceed 重复。
                when (task.id) {
                    ID_CHECK -> interceptCheck(chain)
                    ID_PANEL_FRACTION -> interceptPanelFraction(chain)
                    else -> chain.proceed()
                }
                null
            })
        }
    }

    /**
     * 启停门控：面板展开 → proceed 走原生判定；
     * 未展开（收起/锁屏）→ 自定义暂停（停 3s 轮询 + 全部 GONE，但保留基线），跳过原生启动。
     */
    private fun interceptCheck(chain: XposedInterface.Chain) {
        if (panelExpanded) {
            chain.proceed()
            return
        }
        if (!pauseManager()) chain.proceed()
    }

    /** 展开状态跟踪：proceed 后读 mExpandedFraction；翻转时同步启停网速；展开期间轮询兜收起。 */
    private fun interceptPanelFraction(chain: XposedInterface.Chain) {
        chain.proceed()
        val panel = chain.thisObject as? View ?: return
        val fraction = readFraction(panel) ?: return
        var expanded = fraction > 0f
        // heads-up 横幅也会抬升 fraction：0→1 翻转前剔除（与 TrioFeature 同口径）。
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
            XLog.diag(Log.INFO, MountEngine.TAG, "$JUDGE_PREFIX 面板状态切换: expanded=$expanded fraction=$fraction")
            if (expanded) {
                // 展开：让原生按当前网络条件自行启停（满足条件即显示+开始刷新）。
                invokeManager(managerCheck())
                // 预热：原生首拍强制显示 0（mIsFirst），第二拍要等 3s；1.2s 后手动补一拍。
                mainHandler.removeCallbacks(warmupTickTask)
                mainHandler.postDelayed(warmupTickTask, WARMUP_TICK_MS)
                startCollapsePoll(panel)
            } else {
                mainHandler.removeCallbacks(warmupTickTask)
                onPanelCollapsed()
            }
        }
    }

    /** 收起处理：停轮询 + 隐藏但保留基线（锁屏同样处理——锁屏状态栏默认也不显示）。 */
    private fun onPanelCollapsed() {
        mainHandler.removeCallbacks(warmupTickTask)
        pauseManager()
    }

    /**
     * 自定义暂停：与原生 stop$1() 等价地停轮询 + 隐藏，但刻意不复位
     * mIsFirst / mLastValue / mLastRefreshTime——下次展开时 start$1$1() 立即首拍
     * 即按真实时间差算出速度（下滑过程中直接显示，无强制 0 闪显）。
     * 反射任一环节失败返回 false，调用方回退 proceed（原生行为）。
     */
    private fun pauseManager(): Boolean = runCatching {
        val cls = managerClass() ?: return false
        val mgr = managerInstance(cls) ?: return false
        val fIsStop = managerIsStopField
            ?: cls.getField("mIsStop").also { managerIsStopField = it }
        fIsStop.setBoolean(mgr, true)
        val fHandler = managerHandlerField
            ?: cls.getField("mHandler").also { managerHandlerField = it }
        (fHandler.get(mgr) as? Handler)?.let { h ->
            val fRunnable = managerRunnableField
                ?: cls.getField("mRunnable").also { managerRunnableField = it }
            (fRunnable.get(mgr) as? Runnable)?.let { h.removeCallbacks(it) }
            val fTimeout = managerTimeoutField
                ?: cls.getField("mTimeout").also { managerTimeoutField = it }
            (fTimeout.get(mgr) as? Runnable)?.let { h.removeCallbacks(it) }
        }
        val notify = managerNotifyMethod ?: cls.getDeclaredMethod(
            METHOD_NOTIFY_VISIBILITY,
            Boolean::class.javaPrimitiveType,
        ).apply { isAccessible = true }.also { managerNotifyMethod = it }
        notify.invoke(mgr, false)
        true
    }.getOrDefault(false)

    /**
     * 展开预热拍：摘掉原生 +3s 待执行拍（避免双链），立即手动跑一拍 mRunnable，
     * 以真实时间差（j≈1s）算出速度并自行重排 +3s 链；收起/原生未启动时不补拍。
     */
    private val warmupTickTask = Runnable {
        if (panelExpanded) {
            runCatching {
                val cls = managerClass() ?: return@runCatching
                val mgr = managerInstance(cls) ?: return@runCatching
                val stopped = (managerIsStopField
                    ?: cls.getField("mIsStop").also { managerIsStopField = it }
                    ).getBoolean(mgr)
                if (stopped) return@runCatching
                val r = (managerRunnableField
                    ?: cls.getField("mRunnable").also { managerRunnableField = it }
                    ).get(mgr) as? Runnable ?: return@runCatching
                val h = (managerHandlerField
                    ?: cls.getField("mHandler").also { managerHandlerField = it }
                    ).get(mgr) as? Handler ?: return@runCatching
                h.removeCallbacks(r)
                r.run()
                XLog.diag(Log.INFO, MountEngine.TAG, "$JUDGE_PREFIX 展开预热：手动补拍刷新网速")
            }
        }
    }

    /** 展开期间 200ms 轮询 fraction：收起动画末端归零不进 hook，靠轮询检出；归零即收起。 */
    private val collapsePollTask = object : Runnable {
        override fun run() {
            if (!panelExpanded) {
                pollRunning = false
                return
            }
            val fraction = pollPanel?.get()?.let { readFraction(it) }
            if (fraction == null || fraction <= 0f) {
                // 面板实例被回收按已收起处理（状态栏窗口重建后由新一轮展开回调重新驱动）。
                panelExpanded = false
                pollRunning = false
                XLog.diag(Log.INFO, MountEngine.TAG, "$JUDGE_PREFIX 面板收起(轮询兜底): fraction=$fraction → 停网速刷新")
                onPanelCollapsed()
                return
            }
            mainHandler.postDelayed(this, COLLAPSE_POLL_MS)
        }
    }

    private fun startCollapsePoll(panel: View) {
        pollPanel = WeakReference(panel)
        if (pollRunning) return
        pollRunning = true
        mainHandler.postDelayed(collapsePollTask, COLLAPSE_POLL_MS)
    }

    // ---- 反射辅助（全部惰性 + 缓存；任何失败返回 null，调用方回退原生） ----

    private fun managerClass(): Class<*>? = managerClass ?: runCatching {
        val loader = hostLoader ?: return null
        Class.forName(CLS_SPEED_MANAGER, false, loader)
    }.getOrNull()?.also { managerClass = it }

    private fun managerCheck(): Method? = managerCheckMethod ?: runCatching {
        managerClass()?.getDeclaredMethod(METHOD_CHECK)?.apply { isAccessible = true }
    }.getOrNull()?.also { managerCheckMethod = it }

    private fun managerInstance(cls: Class<*>): Any? = runCatching {
        val f = managerInstanceField
            ?: cls.getDeclaredField("sNetworkSpeedManagerEx")
                .apply { isAccessible = true }
                .also { managerInstanceField = it }
        f.get(null)
    }.getOrNull()

    /** 调单例管理器的 checkShowAndUpdate；返回是否成功调用。 */
    private fun invokeManager(method: Method?): Boolean {
        method ?: return false
        val instance = managerInstance(method.declaringClass) ?: return false
        return runCatching { method.invoke(instance); true }.getOrDefault(false)
    }

    /** 读 PanelView.mExpandedFraction（public 字段，沿层级找一次后缓存）；失败返回 null。 */
    private fun readFraction(panel: View): Float? {
        val f = panelFractionField ?: findField(panel.javaClass, "mExpandedFraction")
            ?.also { panelFractionField = it } ?: return null
        return runCatching { f.getFloat(panel) }.getOrNull()
    }

    /** 沿类层级找字段（字段声明在父类 PanelView 上，hook 实例是 NotificationPanelView）。 */
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
}
