package io.github.nisu123f.honorduobar.xposed.target

import android.util.Log
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
 * 隐藏荣耀控制中心（右侧下滑面板）右上角的关机按钮。
 *
 * 反编译 SystemUI 17.0.0.1 实证（HnControlCenterHeader）：
 * - 关机按钮是 `mPowerButton`（HnBlurFrameLayout，id = 0x7f0a0ba7 / id/power_button），
 *   在 onFinishInflate() 里 findViewById 拿到；
 * - 原生构造器读了 Settings.Global `hw_hide_statusbar_power` 到 mIsHidePowerButton，
 *   但 onFinishInflate 根本没用它（死代码），所以只能自己把 View 置 GONE；
 * - updateHeaderPadding 只调 settings/title 边距，不依赖 power 按钮可见性；
 *   按钮在 LinearLayout 里，置 GONE 后设置按钮自动右移补齐，无需补布局。
 *
 * 防线（与 ClockFeature 同款思路）：
 *   1. hook HnControlCenterHeader.onFinishInflate() —— proceed 后反射 mPowerButton 置 GONE 并挂每帧重申；
 *   2. OnPreDrawListener 每帧重申 GONE —— 防宿主在面板展开/刷新流程里把按钮重新置回可见。
 *
 * 隐藏后关机入口只剩长按电源键（HwGlobalActions 菜单），不受影响。
 */
object ControlCenterFeature {

    private const val HOST_PACKAGE = "com.android.systemui"
    private const val CLS_HEADER = "com.android.systemui.controllercenter.HnControlCenterHeader"
    private const val ID_ON_FINISH_INFLATE = "systemui:ccpower.header.onFinishInflate"
    private const val FIELD_POWER_BUTTON = "mPowerButton"

    private const val JUDGE_PREFIX = "SystemUiHook·CCPower"

    /** 已挂重申的按钮实例（按 identityHashCode 去重，防重复挂监听）。 */
    private val tracked = ConcurrentHashMap<Int, WeakReference<View>>()

    fun install(module: XposedModule, param: XposedModuleInterface.PackageLoadedParam) {
        if (!RemoteConfig.isEnabled(FeatureCatalog.KEY_SYSTEMUI_HIDE_CC_POWER)) {
            logDisabled(module, FeatureCatalog.KEY_SYSTEMUI_HIDE_CC_POWER, JUDGE_PREFIX)
            return
        }
        val loader = param.defaultClassLoader
        val session = MountEngine.open(
            "SystemUiHook·CCPower",
            HOST_PACKAGE,
            param.applicationInfo?.sourceDir,
            FeatureCatalog.baselineVersionOf(HOST_PACKAGE),
        )

        val pending = listOf(
            MountEngine.SimpleTask(
                className = CLS_HEADER,
                methodName = "onFinishInflate",
                paramCount = 0,
                returnType = "void",
                id = ID_ON_FINISH_INFLATE,
                description = "CCPower 隐藏控制中心关机按钮（HnControlCenterHeader.onFinishInflate 后置 GONE mPowerButton + 每帧重申）",
                feature = FeatureCatalog.KEY_SYSTEMUI_HIDE_CC_POWER,
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
        chain.proceed()
        val header = chain.thisObject as? View ?: return@Hooker null
        val btn = runCatching {
            val field = header.javaClass.getDeclaredField(FIELD_POWER_BUTTON)
            field.isAccessible = true
            field.get(header) as? View
        }.getOrElse {
            module.log(Log.WARN, MountEngine.TAG, "$JUDGE_PREFIX 反射 $FIELD_POWER_BUTTON 失败: ${it.message}")
            null
        } ?: return@Hooker null
        hideAndTrack(btn, module)
        null
    })

    /** 立即置 GONE，并挂 OnPreDrawListener 每帧重申（防宿主回显）。 */
    private fun hideAndTrack(btn: View, module: XposedModule) {
        if (btn.visibility != View.GONE) {
            btn.visibility = View.GONE
            module.log(Log.INFO, MountEngine.TAG, "$JUDGE_PREFIX 已隐藏控制中心关机按钮")
        }
        val key = System.identityHashCode(btn)
        if (tracked.putIfAbsent(key, WeakReference(btn)) != null) return
        btn.viewTreeObserver.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (btn.visibility != View.GONE) btn.visibility = View.GONE
                return true
            }
        })
    }

    private fun logDisabled(module: XposedModule, featureKey: String, prefix: String) {
        module.log(Log.INFO, MountEngine.TAG, "$prefix 功能已关闭，跳过 hook: $featureKey")
    }
}
