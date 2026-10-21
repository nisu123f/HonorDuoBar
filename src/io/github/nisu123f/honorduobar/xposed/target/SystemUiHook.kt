package io.github.nisu123f.honorduobar.xposed.target

import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface

/**
 * 荣耀 SystemUI（`com.android.systemui`，基线 17.0.0.1）hook 入口。
 *
 * 本模块只保留状态栏三合一，实际挂载点与渲染全部在 [TrioFeature]。
 */
object SystemUiHook : TargetHook {

    override val packageName = "com.android.systemui"

    override fun install(module: XposedModule, param: XposedModuleInterface.PackageLoadedParam) {
        TrioFeature.install(module, param)
    }
}
