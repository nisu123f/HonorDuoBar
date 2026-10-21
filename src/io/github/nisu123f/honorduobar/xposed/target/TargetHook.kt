package io.github.nisu123f.honorduobar.xposed.target

import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface

/**
 * hook 目标接口（照 Rain oa 适配器接口；`.claude/skills/lsposed-hook-expert/references/rain-dexkit-and-mount-patterns.md`）。
 *
 * 每个荣耀目标应用/系统进程一个实现：
 * - [packageName] 是注册表查键（Rain oa.getPackageName 的等价物）；
 * - [install] 由 HookEntry 在 isFirstPackage 门控 + 注册表命中后调用，同一包只调用一次
 *   （防重装由 [TargetRegistry] 的 installed 状态集合保证，HC xxxInstalled 布尔模式）。
 */
/**
 * 功能被远端开关关闭时的留痕日志（[install] 开头就 return 的目标用）。
 *
 * 这类目标不会走到 `installed N/M` 汇总行——没有这行的话，真机上分不清「用户关掉了功能」
 * 与「模块根本没注入」，排查时会白跑一轮。
 */
internal fun logDisabled(module: XposedModule, key: String, hookName: String) {
    module.log(Log.INFO, "HonorSystemUI", "$hookName: 功能已由远端开关关闭（$key），本次注入跳过挂载")
}

interface TargetHook {

    /** hook 目标包名（注册表键，如 com.hihonor.android.thememanager） */
    val packageName: String

    /** 安装本目标的全部 hook；幂等性由调用方（TargetRegistry.installed）保证，实现内不需要自加 installed 标志 */
    fun install(module: XposedModule, param: XposedModuleInterface.PackageLoadedParam)
}