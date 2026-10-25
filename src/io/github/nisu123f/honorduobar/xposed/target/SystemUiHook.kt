package io.github.nisu123f.honorduobar.xposed.target

import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface

/**
 * 荣耀 SystemUI（`com.android.systemui`，基线 17.0.0.1）hook 入口。
 *
 * 各功能点各自在 install() 内按开关自检关闭则跳过；本入口只负责把它们挂上。
 */
object SystemUiHook : TargetHook {

    override val packageName = "com.android.systemui"

    override fun install(module: XposedModule, param: XposedModuleInterface.PackageLoadedParam) {
        TrioFeature.install(module, param)
        ClockFeature.install(module, param)
        ControlCenterFeature.install(module, param)
        ShadeLunarFeature.install(module, param)
        NotifBellFeature.install(module, param)
        CcVolumeLongPressFeature.install(module, param)
    }
}
