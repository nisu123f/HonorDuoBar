package io.github.nisu123f.honorduobar.xposed.target

import android.util.Log
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import io.github.nisu123f.honorduobar.FeatureCatalog
import io.github.nisu123f.honorduobar.xposed.core.MountEngine
import io.github.nisu123f.honorduobar.xposed.core.MountSession
import io.github.nisu123f.honorduobar.xposed.data.RemoteConfig
import java.lang.ref.WeakReference

/**
 * 状态栏时钟三态：不修改 / 隐藏 / 显秒。
 *
 * 反编译 SystemUI 17.0.0.1 实证：
 * - clock 是自定义 View `com.android.systemui.time.HwStatusBarClock`，XML 里**没有 android:id**，
 *   所以靠 `findViewById("clock")` 根本找不到；
 * - 它挂在 `HwStatusbarContents` 容器里（通过 `<include>` 或 LayoutInflater inflate）；
 * - 宿主在显示流程中会显式 setVisibility(VISIBLE) 或在初始化阶段 showClock。
 *
 * 显秒链路（实证）：
 * - 存储：Settings.Secure["clock_seconds"]，1=显秒，0=不显秒；
 * - HwStatusBarClock.onTimeDisplayPrecisionChange(1) 会置 mShowSeconds=true 并把分钟 ticker 切成秒级 ticker；
 * - 系统侧 TimeManagerImpl 对该设置注册了观察者，写入后会回调所有注册的时钟。
 *
 * 隐藏三层防线：
 *   1. hook HwStatusBarClock.setVisibility(int) —— 所有非 GONE 的设置都强制为 GONE（拦截宿主主动显示）
 *   2. hook HwStatusBarClock.onAttachedToWindow() —— attach 时机强制 GONE（兜底，防止宿主未显式 setVisibility）
 *   3. hook HwStatusbarContents.onFinishInflate() —— inflate 完后递归扫视图树，把所有 HwStatusBarClock 抓出来挂 OnPreDrawListener 每帧重申
 *
 * 显秒：onAttachedToWindow 时写 clock_seconds=1，并反射直调 onTimeDisplayPrecisionChange(1) 兜底
 * （防止系统观察者注册晚于写入导致本次不生效）；非显秒模式在 attach 时把残留值还原为 0。
 */
object ClockFeature {

    private const val HOST_PACKAGE = "com.android.systemui"
    private const val CLS_CLOCK = "com.android.systemui.time.HwStatusBarClock"
    private const val CLS_STATUSBAR_CONTENTS = "com.android.systemui.statusbar.HwStatusbarContents"

    private const val ID_SET_VISIBILITY = "systemui:clock.setVisibility"
    private const val ID_ON_ATTACHED = "systemui:clock.onAttachedToWindow"
    private const val ID_CONTENTS_INFLATE = "systemui:clock.statusbarContents.onFinishInflate"

    private const val JUDGE_PREFIX = "SystemUiHook·Clock"

    /** 时钟模式：不修改 / 隐藏 / 显秒（对应 systemui.clock_mode 的 0/1/2）。 */
    private const val MODE_UNTOUCHED = 0
    private const val MODE_HIDE = 1
    private const val MODE_SECONDS = 2

    @Volatile
    private var mode: Int = MODE_UNTOUCHED

    /** Clock 类实例的弱引用集合（用于 OnPreDrawListener 每帧重申）。 */
    private val tracked = java.util.concurrent.ConcurrentHashMap<Int, WeakReference<View>>()

    fun install(module: XposedModule, param: XposedModuleInterface.PackageLoadedParam) {
        mode = readMode()
        val loader = param.defaultClassLoader
        val session = MountEngine.open(
            "SystemUiHook·Clock",
            HOST_PACKAGE,
            param.applicationInfo?.sourceDir,
            FeatureCatalog.baselineVersionOf(HOST_PACKAGE),
        )
        module.log(Log.INFO, MountEngine.TAG, "$JUDGE_PREFIX 时钟模式=$mode（0=不修改 1=隐藏 2=显秒）")

        // onAttachedToWindow 三种模式都要挂：不修改→还原残留显秒；隐藏→GONE；显秒→写设置
        val pending = ArrayList<MountEngine.SimpleTask>()
        pending += MountEngine.SimpleTask(
            className = CLS_CLOCK,
            methodName = "onAttachedToWindow",
            paramCount = 0,
            returnType = "void",
            id = ID_ON_ATTACHED,
            description = "Clock attach 时机应用时钟模式",
            feature = FeatureCatalog.KEY_SYSTEMUI_CLOCK_MODE,
        )
        if (mode == MODE_HIDE) {
            pending += MountEngine.SimpleTask(
                className = CLS_CLOCK,
                methodName = "setVisibility",
                paramCount = 1,
                returnType = "void",
                id = ID_SET_VISIBILITY,
                description = "Clock 拦截 HwStatusBarClock.setVisibility：非 GONE → GONE",
                feature = FeatureCatalog.KEY_SYSTEMUI_CLOCK_MODE,
            )
            pending += MountEngine.SimpleTask(
                className = CLS_STATUSBAR_CONTENTS,
                methodName = "onFinishInflate",
                paramCount = 0,
                returnType = "void",
                id = ID_CONTENTS_INFLATE,
                description = "Clock 终极兜底：HwStatusbarContents inflate 后递归扫描挂 OnPreDrawListener",
                feature = FeatureCatalog.KEY_SYSTEMUI_CLOCK_MODE,
            )
        }
        session.attemptMount(pending, module, loader) { task -> mountOne(session, task, module, loader) }
    }

    /** 读模式；远端只有旧布尔 hide_clock 键（老版本宿主写入）时折算为 隐藏/不修改。 */
    private fun readMode(): Int {
        val raw = RemoteConfig.int(FeatureCatalog.KEY_SYSTEMUI_CLOCK_MODE, Int.MIN_VALUE)
        if (raw != Int.MIN_VALUE) return raw.coerceIn(MODE_UNTOUCHED, MODE_SECONDS)
        return if (RemoteConfig.isEnabled(FeatureCatalog.KEY_SYSTEMUI_HIDE_CLOCK)) MODE_HIDE else MODE_UNTOUCHED
    }

    private fun mountOne(
        session: MountSession,
        task: MountEngine.SimpleTask,
        module: XposedModule,
        loader: ClassLoader,
    ): MountEngine.Result = session.mount(task, module, loader, XposedInterface.Hooker { chain ->
        when (task.id) {
            ID_SET_VISIBILITY -> {
                chain.proceed()
                val visibility = runCatching { chain.getArg(0) as? Int }.getOrNull() ?: return@Hooker null
                if (visibility != View.GONE) {
                    (chain.thisObject as? View)?.visibility = View.GONE
                }
                null
            }
            ID_ON_ATTACHED -> {
                chain.proceed()
                val v = chain.thisObject as? View ?: return@Hooker null
                when (mode) {
                    MODE_HIDE -> {
                        v.visibility = View.GONE
                        track(v, module)
                    }
                    MODE_SECONDS -> applySeconds(v, module, true)
                    else -> applySeconds(v, module, false)
                }
                null
            }
            ID_CONTENTS_INFLATE -> {
                chain.proceed()
                val container = chain.thisObject as? ViewGroup ?: return@Hooker null
                container.post { runCatching { sweepAndTrack(container.rootView, module) } }
                null
            }
            else -> {
                chain.proceed()
                null
            }
        }
    })

    /**
     * 应用/还原显秒。
     *
     * 主路径：写 Settings.Secure["clock_seconds"]，系统观察者会回调所有注册时钟切精度；
     * 兜底：反射直调该实例的 onTimeDisplayPrecisionChange，防止观察者注册晚于写入导致本次不生效。
     *
     * 开销控制：还原方向（enable=false）且当前已无残留时直接返回，一次 attach 仅付出一次 Settings 读；
     * 显秒方向不做此短路——后创建的时钟实例（如锁屏钟）在值已为 1 时也需要反射直调才会立即切秒级。
     */
    private fun applySeconds(clock: View, module: XposedModule, enable: Boolean) {
        val target = if (enable) 1 else 0
        val resolver = clock.context.contentResolver
        val current = runCatching { Settings.Secure.getInt(resolver, "clock_seconds", 0) }.getOrDefault(0)
        if (!enable && current == 0) return
        runCatching {
            if (current != target) {
                Settings.Secure.putInt(resolver, "clock_seconds", target)
                module.log(Log.INFO, MountEngine.TAG, "$JUDGE_PREFIX clock_seconds $current → $target")
            }
        }.onFailure {
            module.log(Log.WARN, MountEngine.TAG, "$JUDGE_PREFIX 写 clock_seconds=$target 失败: ${it.message}")
        }
        runCatching {
            val m = clock.javaClass.getDeclaredMethod(
                "onTimeDisplayPrecisionChange",
                Int::class.javaPrimitiveType,
            )
            m.isAccessible = true
            m.invoke(clock, target)
        }.onFailure {
            // 方法缺失或反射被拒：仅靠 Settings 链路，最迟下次 SystemUI 重启生效
            module.log(Log.WARN, MountEngine.TAG, "$JUDGE_PREFIX 反射调 onTimeDisplayPrecisionChange($target) 失败: ${it.message}")
        }
    }

    /** 把 clock View 加入跟踪池，并挂 OnPreDrawListener 每帧重申 GONE。 */
    private fun track(clock: View, module: XposedModule) {
        if (tracked.containsKey(System.identityHashCode(clock))) return
        tracked[System.identityHashCode(clock)] = java.lang.ref.WeakReference(clock)
        // 第一次立即压一次，后续每帧重申
        if (clock.visibility != View.GONE) clock.visibility = View.GONE
        clock.viewTreeObserver.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (clock.visibility != View.GONE) clock.visibility = View.GONE
                return true
            }
        })
    }

    /** 兜底：在 rootView 下递归扫出所有 HwStatusBarClock 实例。 */
    private fun sweepAndTrack(root: View, module: XposedModule) {
        val hits = ArrayList<View>()
        findClocks(root, hits)
        hits.forEach { track(it, module) }
        if (hits.isNotEmpty()) {
            module.log(Log.INFO, MountEngine.TAG, "$JUDGE_PREFIX sweep 命中 ${hits.size} 个 HwStatusBarClock（OnPreDraw 已挂）")
        } else {
            module.log(Log.INFO, MountEngine.TAG, "$JUDGE_PREFIX sweep 未命中 HwStatusBarClock（宿主可能用的是其他 Clock 变体，当前只 hook HwStatusBarClock 本类）")
        }
    }

    private fun findClocks(root: View, out: MutableList<View>) {
        if (root.javaClass.name == CLS_CLOCK) out += root
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) findClocks(root.getChildAt(i), out)
        }
    }
}
