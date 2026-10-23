package io.github.nisu123f.honorduobar.xposed.target

import android.icu.util.ChineseCalendar
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import io.github.nisu123f.honorduobar.FeatureCatalog
import io.github.nisu123f.honorduobar.xposed.core.MountEngine
import io.github.nisu123f.honorduobar.xposed.core.MountSession
import io.github.nisu123f.honorduobar.xposed.data.RemoteConfig

/**
 * 通知中心日期行追加农历（「10月24日周六九月初四」样式，星期缩为「周X」后直接拼接）。
 *
 * 反编译 SystemUI 17.0.0.1 实证：
 * - 通知中心顶部是纯 Compose（HnNotificationCenterHeader extends BasicComposeView），
 *   没有可追加的经典 TextView；
 * - 大时间旁的日期行文本由 `NotifCenterHeaderInteractorImpl.getCurrentClockDate(DateFormat)`
 *   生产（clockDate StateFlow 的初始值 + 分钟滴答 + 时区/语言变更 combine，非每帧调用）；
 * - 故 hook 该实例方法，在返回串后拼「 · 农历X月X」即可随 Compose 重组渲染。
 *
 * 农历用系统 `android.icu.util.ChineseCalendar`（ICU 自带历法数据，SystemUI 进程可用，
 * 宿主本身就 import android.icu.text.DateFormat），无需自带 1900–2100 位表。
 * 该钩子每分钟至多触发一次，开销可忽略；算法异常时静默返回原串，不影响宿主。
 */
object ShadeLunarFeature {

    private const val HOST_PACKAGE = "com.android.systemui"
    private const val CLS_INTERACTOR =
        "com.android.systemui.statusbar.notification.panelheader.NotifCenterHeaderInteractorImpl"
    private const val METHOD_GET_DATE = "getCurrentClockDate"
    private const val ID_GET_DATE = "systemui:lunar.notifHeader.getCurrentClockDate"
    private const val JUDGE_PREFIX = "SystemUiHook·Lunar"

    private val MONTHS = arrayOf(
        "正月", "二月", "三月", "四月", "五月", "六月",
        "七月", "八月", "九月", "十月", "冬月", "腊月",
    )
    private val DAYS = arrayOf(
        "初一", "初二", "初三", "初四", "初五", "初六", "初七", "初八", "初九", "初十",
        "十一", "十二", "十三", "十四", "十五", "十六", "十七", "十八", "十九", "二十",
        "廿一", "廿二", "廿三", "廿四", "廿五", "廿六", "廿七", "廿八", "廿九", "三十",
    )

    fun install(module: XposedModule, param: XposedModuleInterface.PackageLoadedParam) {
        if (!RemoteConfig.isEnabled(FeatureCatalog.KEY_SYSTEMUI_SHADE_LUNAR)) {
            logDisabled(module, FeatureCatalog.KEY_SYSTEMUI_SHADE_LUNAR, JUDGE_PREFIX)
            return
        }
        // 宽度前提：铃铛隐藏后日期行才够宽（dependsOn KEY_SYSTEMUI_HIDE_NOTIF_BELL）
        if (!RemoteConfig.isEnabled(FeatureCatalog.KEY_SYSTEMUI_HIDE_NOTIF_BELL)) {
            module.log(
                android.util.Log.INFO, MountEngine.TAG,
                "$JUDGE_PREFIX 依赖的「隐藏通知中心铃铛按钮」未开启，跳过 hook: ${FeatureCatalog.KEY_SYSTEMUI_SHADE_LUNAR}",
            )
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
                className = CLS_INTERACTOR,
                methodName = METHOD_GET_DATE,
                paramCount = 1,
                returnType = "java.lang.String",
                id = ID_GET_DATE,
                description = "通知中心农历（getCurrentClockDate 返回串拼「·九月初四」后缀）",
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
        val original = chain.proceed() as? String
        if (original.isNullOrEmpty()) {
            original
        } else {
            val lunar = lunarNow()
            if (lunar.isEmpty()) {
                original
            } else {
                // 「星期X」缩为「周X」后直接拼农历：「10月24日周六九月初四」。
                // 宽度依赖「隐藏通知中心铃铛按钮」腾出（dependsOn，隐藏后 6 字闰月也不截断）。
                "${WEEKDAY_REGEX.replace(original) { m -> "周${m.groupValues[1]}" }}$lunar"
            }
        }
    })

    /** 「星期X」→「周X」（省一个汉字宽）。 */
    private val WEEKDAY_REGEX = Regex("星期([一二三四五六日天])")

    /** 当前农历「九月初四」样式；闰月加「闰」前缀。异常返回空串（拼接入判空回退原串）。 */
    private fun lunarNow(): String = runCatching {
        val cal = ChineseCalendar()
        cal.timeInMillis = System.currentTimeMillis()
        val month = cal.get(ChineseCalendar.MONTH)
        val day = cal.get(ChineseCalendar.DAY_OF_MONTH)
        val isLeap = cal.get(ChineseCalendar.IS_LEAP_MONTH) == 1
        val m = MONTHS.getOrElse(month) { "${month + 1}月" }
        val d = DAYS.getOrElse(day - 1) { "${day}日" }
        (if (isLeap) "闰" else "") + m + d
    }.getOrDefault("")
}
