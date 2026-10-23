package io.github.nisu123f.honorduobar.xposed.target

import android.view.View
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import io.github.nisu123f.honorduobar.FeatureCatalog
import io.github.nisu123f.honorduobar.xposed.core.MountEngine
import io.github.nisu123f.honorduobar.xposed.core.MountSession
import io.github.nisu123f.honorduobar.xposed.data.RemoteConfig
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap

/**
 * 隐藏通知中心（左侧下滑面板）大时间右侧的铃铛按钮（通知管理入口）。
 *
 * 反编译 SystemUI 17.0.0.1 实证：
 * - 铃铛是 `NotifMgeBtn extends HnBlurImageView`（经典 View，经 AndroidView 嵌进纯 Compose 头部）；
 * - Compose 的 AndroidView 对 GONE 的 View 测成 0×0，故置 GONE 即消失且不占宽
 *   （日期行随之获得更多宽度，与农历功能互补）。
 *
 * 防线（ControlCenterFeature 同款思路）：
 *   1. hook NotifMgeBtn.setVisibility(int) —— 宿主所有非 GONE 的显示尝试都压回 GONE；
 *   2. hook NotifMgeBtn.onAttachedToWindow() —— attach 时机置 GONE 并挂 OnPreDrawListener 每帧重申，
 *      防宿主在面板刷新流程里把按钮重新置回可见。
 */
object NotifBellFeature {

    private const val HOST_PACKAGE = "com.android.systemui"
    private const val CLS_BTN =
        "com.android.systemui.statusbar.notification.panelheader.NotifMgeBtn"
    private const val ID_SET_VISIBILITY = "systemui:notifbell.setVisibility"
    private const val ID_ON_ATTACHED = "systemui:notifbell.onAttachedToWindow"
    private const val JUDGE_PREFIX = "SystemUiHook·NotifBell"

    /** 已挂重申的按钮实例（按 identityHashCode 去重，防重复挂监听）。 */
    private val tracked = ConcurrentHashMap<Int, WeakReference<View>>()

    fun install(module: XposedModule, param: XposedModuleInterface.PackageLoadedParam) {
        if (!RemoteConfig.isEnabled(FeatureCatalog.KEY_SYSTEMUI_HIDE_NOTIF_BELL)) {
            logDisabled(module, FeatureCatalog.KEY_SYSTEMUI_HIDE_NOTIF_BELL, JUDGE_PREFIX)
            return
        }
        val loader = param.defaultClassLoader
        val session = MountEngine.open(
            JUDGE_PREFIX,
            HOST_PACKAGE,
            param.applicationInfo?.sourceDir,
            FeatureCatalog.baselineVersionOf(HOST_PACKAGE),
        )
        val pending = listOf(
            MountEngine.SimpleTask(
                className = CLS_BTN,
                methodName = "setVisibility",
                paramCount = 1,
                returnType = "void",
                id = ID_SET_VISIBILITY,
                description = "NotifBell 拦截 NotifMgeBtn.setVisibility：非 GONE → GONE",
                feature = FeatureCatalog.KEY_SYSTEMUI_HIDE_NOTIF_BELL,
            ),
            MountEngine.SimpleTask(
                className = CLS_BTN,
                methodName = "onAttachedToWindow",
                paramCount = 0,
                returnType = "void",
                id = ID_ON_ATTACHED,
                description = "NotifBell attach 时机置 GONE + OnPreDraw 每帧重申",
                feature = FeatureCatalog.KEY_SYSTEMUI_HIDE_NOTIF_BELL,
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
                (chain.thisObject as? View)?.let { v ->
                    if (v.visibility != View.GONE) v.visibility = View.GONE
                }
                null
            }
            ID_ON_ATTACHED -> {
                chain.proceed()
                (chain.thisObject as? View)?.let { hideAndTrack(it, module) }
                null
            }
            else -> {
                chain.proceed()
                null
            }
        }
    })

    /** 立即置 GONE，并挂 OnPreDrawListener 每帧重申（防宿主回显）。 */
    private fun hideAndTrack(btn: View, module: XposedModule) {
        if (btn.visibility != View.GONE) btn.visibility = View.GONE
        val key = System.identityHashCode(btn)
        if (tracked.putIfAbsent(key, WeakReference(btn)) != null) return
        btn.viewTreeObserver.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (btn.visibility != View.GONE) btn.visibility = View.GONE
                return true
            }
        })
    }
}
