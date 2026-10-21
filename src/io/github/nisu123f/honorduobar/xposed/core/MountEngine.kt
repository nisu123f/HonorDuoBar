package io.github.nisu123f.honorduobar.xposed.core

/**
 * 各目标 hook 共享的挂载基建：**共享类型 + 会话工厂**。
 *
 * 抽取自 HonorHook（AGPL-3.0）。本独立版只保留直连反射解析（无 DexKit 兜底轨）。
 */
object MountEngine {

    const val TAG = "HonorSystemUI"
    const val MAX_RETRY_ROUNDS = 2
    const val RETRY_DELAY_MS = 500L
    const val RETRY_FINAL_DELAY_MS = 2000L

    /** 判据日志每任务打印上限（超出的命中不再打，防高频读点刷屏）。 */
    const val JUDGE_LOG_PER_TASK = 2

    /** 回调异常 WARN 每任务打印上限（同 JUDGE_LOG_PER_TASK 口径；高频死点只告警前 3 次防刷屏）。 */
    const val CALLBACK_WARN_PER_TASK = 3

    /** 挂载点漂移 WARN 每任务打印上限（漂移是每 task 一次的定性事实 ⇒ 只打 1 行，重试轮不重复打）。 */
    const val DRIFT_WARN_PER_TASK = 1

    /** "挂到 static 实现" WARN 每任务打印上限（同 DRIFT_WARN_PER_TASK 口径：每 task 一行）。 */
    const val STATIC_WARN_PER_TASK = 1

    /** 挂载细节日志门控（默认关；汇总与判据日志不受门控）。 */
    const val DEBUG_LOGGING = false

    /**
     * 成功口径（"installed N/M" 的 N）：[Result.OK] 与 [Result.MERGED] 都算已挂载。
     *
     * MERGED = 与另一挂载点落到同一方法（同一方法只挂一次）⇒ 该挂载点的意图已由另一挂载点覆盖，
     * 记失败会凭空扣 N 并把点写进失败清单（复审缺陷④）。
     */
    fun isInstalled(result: Result): Boolean = result.installed

    /**
     * 失败分级：[FAILED] hook 挂载异常（重试无意义）；[NOT_RESOLVED] 类/方法（或依赖值）未解析（可随类加载重试）。
     *
     * [MERGED]：与另一挂载点落到**同一方法**——同一方法只挂一次（不重复包装），但该挂载点的意图
     * 已由另一个挂载点覆盖 ⇒ **计成功**（不扣 N、不进失败清单），日志留一行 WARN 说明谁与谁合并
     * （复审缺陷④：原先记 NOT_RESOLVED ⇒ 扣 N + 进失败清单，与"方法确实已挂上"的事实不符）。
     */
    enum class Result {
        OK, FAILED, NOT_RESOLVED, MERGED, MERGED_CONFLICT;

        /**
         * 成功口径（"installed N/M" 的 N）：[OK] 与 [MERGED] 都算已挂载。
         *
         * 口径的单一事实源（单测钉住：N 的定义与失败清单不许悄悄变）。MERGED = 与另一挂载点合并到
         * 同一方法 ⇒ 该点的意图已由另一挂载点覆盖，记失败会凭空扣 N 并把点写进失败清单。
         *
         * [MERGED_CONFLICT]：合并到的却是**别的功能**的挂载点 ⇒ 方法确实挂上了，但挂的是别人的意图，
         * 本点的拦截语义没有生效 ⇒ 不能算 installed（否则 N 虚高，功能实际是死的）。
         */
        val installed: Boolean get() = this == OK || this == MERGED
    }

    /** DexKit 兜底特征声明 —— 用于**混淆名目标**（类名/方法名随时可能被 R8 重映射）。 */
    class DexHint(
        val strings: List<String>? = null,
        val paramTypes: List<String>? = null,
        val classStrings: List<String>? = null,
        val classFieldTypes: List<String>? = null,
        /**
         * 期望**实例**方法：拦截器依赖 `thisObject`（如 installer 的 CHECK_BYPASS / CHECK_BYPASS_JOB）。
         * static 实现挂上去 `thisObject` 恒 null ⇒ 拦截器静默空转（判据行照样打 N/M，功能不生效）。
         * 置 true 后 static 候选一律不采纳，并留 WARN 痕迹（复审缺陷③）。
         */
        val instanceOnly: Boolean = false,
    )

    /** 各目标挂载任务须实现的最小协议（kind 枚举 / 路由条件等目标私有字段留在目标侧）。 */
    interface Task {
        /** hook 稳定标识（`setId` 用；`域:类.方法` 命名）。 */
        val id: String
        val className: String
        val methodName: String
        val paramCount: Int
        val returnType: String
        /** 人类可读描述（判据日志与失败清单用；含功能分组标注如 "F2 A1"）。 */
        val description: String
        /** 功能点 id（如 "A1"）；"" = 探针/载体类，不参与通路汇总。 */
        val group: String get() = ""
        /** 所属功能开关 key（FeatureCatalog）；空 = 常挂，不受开关控制。过滤由引擎按闸门统一执行。 */
        val feature: String get() = ""
        /** DexKit 兜底特征（混淆名目标用；null = 仅直连轨）。 */
        val dex: DexHint? get() = null
        /** 挂载前惰性解析强制值（依赖目标类加载，如枚举常量）；返回 null = 未就绪 → [Result.NOT_RESOLVED] 重试。 */
        val resolveValue: ((ClassLoader) -> Any?)? get() = null
    }

    /** 共享零逻辑任务（替代各 target 手写 private MountTask）。 */
    class SimpleTask(
        override val id: String,
        override val className: String,
        override val methodName: String,
        override val paramCount: Int,
        override val returnType: String,
        override val description: String,
        override val group: String = "",
        override val dex: DexHint? = null,
        override val resolveValue: ((ClassLoader) -> Any?)? = null,
        // feature 放最后：各目标大量用位置传参构造 SimpleTask，插前会错位
        override val feature: String = "",
    ) : Task

    /**
     * 打开一次挂载会话：[apkPath]（DexKit 兜底轨要读的宿主 APK）与 [expectedHostVersion]
     * （宿主版本基线，自检用）在构造期一次性确定——没有"忘记 begin"这回事。
     */
    fun open(
        hookName: String,
        hostPackage: String,
        apkPath: String?,
        expectedHostVersion: String? = null,
        gate: FeatureGate = FeatureGate.AlwaysOn,
    ): MountSession = MountSession(hookName, hostPackage, apkPath, expectedHostVersion, gate)
}
