package io.github.nisu123f.honorduobar.xposed.target

import android.util.Log
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
 * 隐藏荣耀状态栏时钟。
 *
 * 反编译 SystemUI 17.0.0.1 实证：
 * - clock 是自定义 View `com.android.systemui.time.HwStatusBarClock`，XML 里**没有 android:id**，
 *   所以靠 `findViewById("clock")` 根本找不到；
 * - 它挂在 `HwStatusbarContents` 容器里（通过 `<include>` 或 LayoutInflater inflate）；
 * - 宿主在显示流程中会显式 setVisibility(VISIBLE) 或在初始化阶段 showClock。
 *
 * 三层防线：
 *   1. hook HwStatusBarClock.setVisibility(int) —— 所有非 GONE 的设置都强制为 GONE（拦截宿主主动显示）
 *   2. hook HwStatusBarClock.onAttachedToWindow() —— attach 时机强制 GONE（兜底，防止宿主未显式 setVisibility）
 *   3. hook HwStatusbarContents.onFinishInflate() —— inflate 完后递归扫视图树，把所有 HwStatusBarClock 抓出来挂 OnPreDrawListener 每帧重申
 */
object ClockFeature {

    private const val HOST_PACKAGE = "com.android.systemui"
    private const val CLS_CLOCK = "com.android.systemui.time.HwStatusBarClock"
    private const val CLS_STATUSBAR_CONTENTS = "com.android.systemui.statusbar.HwStatusbarContents"

    private const val ID_SET_VISIBILITY = "systemui:clock.setVisibility"
    private const val ID_ON_ATTACHED = "systemui:clock.onAttachedToWindow"
    private const val ID_CONTENTS_INFLATE = "systemui:clock.statusbarContents.onFinishInflate"

    private const val JUDGE_PREFIX = "SystemUiHook·Clock"

    /** Clock 类实例的弱引用集合（用于 OnPreDrawListener 每帧重申）。 */
    private val tracked = java.util.concurrent.ConcurrentHashMap<Int, WeakReference<View>>()

    fun install(module: XposedModule, param: XposedModuleInterface.PackageLoadedParam) {
        if (!RemoteConfig.isEnabled(FeatureCatalog.KEY_SYSTEMUI_HIDE_CLOCK)) {
            logDisabled(module, FeatureCatalog.KEY_SYSTEMUI_HIDE_CLOCK, JUDGE_PREFIX)
            return
        }
        val loader = param.defaultClassLoader
        val session = MountEngine.open(
            "SystemUiHook·Clock",
            HOST_PACKAGE,
            param.applicationInfo?.sourceDir,
            FeatureCatalog.baselineVersionOf(HOST_PACKAGE),
        )

        // 三层独立挂载点：任一命中即生效
        val pending = listOf(
            MountEngine.SimpleTask(
                className = CLS_CLOCK,
                methodName = "setVisibility",
                paramCount = 1,
                returnType = "void",
                id = ID_SET_VISIBILITY,
                description = "Clock 拦截 HwStatusBarClock.setVisibility：非 GONE → GONE",
                feature = FeatureCatalog.KEY_SYSTEMUI_HIDE_CLOCK,
            ),
            MountEngine.SimpleTask(
                className = CLS_CLOCK,
                methodName = "onAttachedToWindow",
                paramCount = 0,
                returnType = "void",
                id = ID_ON_ATTACHED,
                description = "Clock 兜底 onAttachedToWindow 强制 GONE",
                feature = FeatureCatalog.KEY_SYSTEMUI_HIDE_CLOCK,
            ),
            MountEngine.SimpleTask(
                className = CLS_STATUSBAR_CONTENTS,
                methodName = "onFinishInflate",
                paramCount = 0,
                returnType = "void",
                id = ID_CONTENTS_INFLATE,
                description = "Clock 终极兜底：HwStatusbarContents inflate 后递归扫描挂 OnPreDrawListener",
                feature = FeatureCatalog.KEY_SYSTEMUI_HIDE_CLOCK,
            ),
        )
        session.attemptMount(pending, module, loader) { task -> mountOne(session, task, module, loader) }
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
                (chain.thisObject as? View)?.let { v ->
                    v.visibility = View.GONE
                    track(v, module)
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

    private fun logDisabled(module: XposedModule, featureKey: String, prefix: String) {
        module.log(Log.INFO, MountEngine.TAG, "$prefix 功能已关闭，跳过 hook: $featureKey")
    }
}
