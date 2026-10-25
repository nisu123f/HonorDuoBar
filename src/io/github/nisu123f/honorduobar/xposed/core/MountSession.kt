package io.github.nisu123f.honorduobar.xposed.core

import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule

/**
 * 一次挂载会话：某目标在某宿主进程里的一批挂载。
 *
 * [expectedHostVersion]（宿主版本基线）在**构造期**一次性确定；[apkPath] 仅为与原模块
 * 接口保持一致而保留（本独立版无 DexKit 兜底轨，不读宿主 APK）。
 * 状态（计数 / 节流 map / 判据聚合）全在本会话内，**不存全局**。
 */
class MountSession internal constructor(
    val hookName: String,
    val hostPackage: String,
    @Suppress("unused") private val apkPath: String?,
    private val expectedHostVersion: String?,
    private val gate: FeatureGate,
) {

    private var retryRounds = 0
    /** 判据聚合（`installed N/M`、通路存活、失败清单的口径单一事实源；纯逻辑，可 JVM 回归）。 */
    private var summary = MountSummary()
    /** 判据日志节流计数（task.id → 已打印次数）。 */
    private val judgeCount = java.util.concurrent.ConcurrentHashMap<String, Int>()
    /** 回调异常 WARN 节流计数（task.id → 已打印次数；独立于 [judgeCount]，判据节流是"命中"口径，这里是"异常"口径）。 */
    private val callbackWarnCount = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private var hostVersionChecked = false
    /** 实测宿主版本（延后自检拿到；汇总行用它标注，一眼看出版本错位）。 */
    private var actualVersion: String? = null

    /**
     * 挂载一轮 pending（HC installCustomTileHooks postDelayed 懒加载重试同款）。
     * [mountOne] 由目标侧提供（各 kind 的 intercept 逻辑），返回 [MountEngine.Result] 分级驱动重试。
     * 泛型 [T] 保留目标侧 [MountEngine.Task] 具体类型，免回调强转。
     */
    fun <T : MountEngine.Task> attemptMount(
        pending: List<T>,
        module: XposedModule,
        loader: ClassLoader,
        mountOne: (T) -> MountEngine.Result,
    ) {
        // 首轮挂载一律转投 MountScheduler 后台执行（宿主主线程零同步磁盘 IO）：install() 在宿主主线程
        // 被调用，直连 miss 后 MethodResolver 第二轨要同步做 DexKit 磁盘扫描（秒级），读宿主版本还要
        // 同步 IPC 让 system_server 解析 APK。失效是常态 ⇒ DexKit 兜底是常态路径
        // ⇒ 宿主主线程零同步磁盘 IO 是验收不变量：非挂载线程一律转投 MountScheduler 后台执行。
        // 重试轮本就在 scheduler 线程（递归自然 inline），这里只在首轮命中。
        if (!MountScheduler.handler.looper.isCurrentThread) {
            MountScheduler.postDelayed(0) {
                runCatching { attemptMount(pending, module, loader, mountOne) }
                    .onFailure { t -> XLog.log(Log.ERROR, MountEngine.TAG, "$hookName 挂载调度异常", t) }
            }
            return
        }
        // 自检延后到后台线程：PackageLoadedParam 时机 Application 尚未创建，直查必拿 null；
        // 挂载首轮全成功时无重试轮，故不能依赖重试驱动（hostVersionChecked 未置位但也没有下一轮）。
        MountScheduler.postDelayed(1000) {
            runCatching { checkHostVersionOnce(module) }
        }
        // 功能开关闸门：功能关闭的挂载点不挂（挂了也白挂，还让判据行说不清是"没挂上"还是"功能已关"）。
        // 过滤口径的唯一事实源 = 引擎（过去散落在 theme / installer / launcher 三处本地 .filter）。
        // 过滤发生在统计 M 之前 ⇒ M 仍是"本次实际要挂的点数"，关功能不改变判据行的含义。
        val eligible = filterByFeature(pending, gate)
        // 只在首轮记总数：重试轮传入的是 unresolved 子列表，若逐轮覆盖，判据行会变成
        // "installed 累计成功数/本轮待挂数"（成功数跨轮累计），M 失去意义。
        if (retryRounds == 0) {
            val groupCounts = LinkedHashMap<String, Int>()
            for (task in eligible) {
                if (task.group.isNotEmpty()) groupCounts[task.group] = (groupCounts[task.group] ?: 0) + 1
            }
            summary.begin(eligible.size, groupCounts)
        }
        val unresolved = mutableListOf<T>()
        for (task in eligible) {
            // per-task try/catch 隔离：单任务异常不阻断同批其他任务，汇总行始终输出
            val result = runCatching { mountOne(task) }.getOrElse {
                XLog.log(Log.ERROR, MountEngine.TAG, "$hookName 挂载异常（任务隔离）: ${task.description}", it)
                MountEngine.Result.FAILED
            }
            if (result == MountEngine.Result.NOT_RESOLVED) {
                unresolved += task   // NOT_RESOLVED：可随类加载重试
            } else {
                // 成功口径单一事实源（[MountEngine.Result.installed]）：OK 与 MERGED（与另一挂载点合并）都算已挂载，
                // 不扣 N、不进失败清单（复审缺陷④）；**MERGED_CONFLICT（合并到别的功能）例外**：
                // 挂上去的是别人的拦截器 ⇒ 本点意图未生效 ⇒ 计失败不计 N（[mergedFeatureConflict]）。
                summary.record(MountOutcome(task.id, task.description, task.group, result = result))
            }
        }
        if (unresolved.isEmpty()) {
            logSummary()
            return
        }
        if (retryRounds >= MountEngine.MAX_RETRY_ROUNDS) {
            unresolved.forEach { u -> summary.markUnmounted(u.description, u.group) }
            XLog.log(
                Log.WARN, MountEngine.TAG,
                "$hookName 部分 hook 最终未挂载（类/方法不存在，已重试 ${MountEngine.MAX_RETRY_ROUNDS} 轮，放弃）: " +
                    unresolved.joinToString { it.description },
            )
            logSummary()
            return
        }
        retryRounds++
        val delay = if (retryRounds == 1) MountEngine.RETRY_DELAY_MS else MountEngine.RETRY_FINAL_DELAY_MS
        MountScheduler.postDelayed(delay) {
            runCatching { attemptMount(unresolved, module, loader, mountOne) }
                .onFailure { t -> XLog.log(Log.ERROR, MountEngine.TAG, "$hookName 懒加载重试挂载异常", t) }
        }
    }

    /**
     * 统一方法解析：① [MountEngine.Task.resolveValue] 惰性值（依赖类加载，未就绪 → 重试）；
     * ② [MethodResolver.resolveOutcome] 双轨（直连反射 + DexKit 特征兜底）。
     *
     * 返回 [ResolveOutcome.Unresolved] = [MountEngine.Result.NOT_RESOLVED]（标准 WARN 日志已打）。
     * [ResolveOutcome.Merged] = 与另一挂载点落到同一方法 ⇒ 合并计成功（不重复包装）。
     */
    internal fun resolveTask(task: MountEngine.Task, module: XposedModule, loader: ClassLoader): ResolveOutcome {
        // resolveValue 是目标侧惰性 lambda，可能抛异常（如目标类未加载时枚举反射失败）——那本质是
        // 依赖项未就绪（NOT_RESOLVED，可随类加载重试），不能冒泡成挂载异常 → FAILED（重试无意义）。
        task.resolveValue?.let { resolveValue ->
            val resolved = runCatching { resolveValue(loader) }.getOrElse {
                XLog.log(Log.WARN, MountEngine.TAG, "hook 未挂载（依赖项未解析，将重试）: ${task.description} — ${it.message}")
                return ResolveOutcome.Unresolved
            }
            if (resolved == null) {
                XLog.log(Log.WARN, MountEngine.TAG, "hook 未挂载（依赖项未解析，将重试）: ${task.description}")
                return ResolveOutcome.Unresolved
            }
        }
        val outcome = MethodResolver.resolveOutcome(
            loader = loader,
            className = task.className,
            methodName = task.methodName,
            paramCount = task.paramCount,
            returnType = task.returnType,
            paramTypes = task.dex?.paramTypes,
            pointId = task.id,
            pointFeature = task.feature,
        )
        if (outcome is ResolveOutcome.Unresolved) {
            XLog.log(Log.WARN, MountEngine.TAG, "hook 未挂载（类/方法不存在，版本差异，将重试）: ${task.description}")
        }
        return outcome
    }

    /** 挂载汇总始终输出，不受 [MountEngine.DEBUG_LOGGING] 门控（Rain "installed N/M" 汇总行模式，真机判据）。 */
    fun logSummary() {
        val hostPart = actualVersion?.let { "（宿主 $it）" } ?: ""
        // 通路汇总（存在非空 group 才输出；单行紧凑 "; " 拼接）。判读单位 = 功能点至少一条通路存活，
        // 全灭（ok=0）才升级 ERROR 附失败明细——部分存活是失效常态，不制造噪音。
        summary.logLines(hookName, hostPart, hostPackage).forEach { line ->
            val level = when (line.level) {
                MountLogLevel.INFO -> Log.INFO
                MountLogLevel.WARN -> Log.WARN
                MountLogLevel.ERROR -> Log.ERROR
            }
            XLog.log(level, MountEngine.TAG, line.text)
        }
    }

    /**
     * 单任务挂载统一模板：解析（[resolveTask]）→ 挂 [XposedInterface.ExceptionMode.PROTECTIVE] → 结果分级。
     *
     * 各目标只需提供 [intercept]（kind 分派逻辑），解析、异常兜底与分级由本模板统一负责：
     * 挂载异常记 WARN 并返回 [MountEngine.Result.FAILED]（重试无意义），不阻断同批其他任务。
     */
    fun mount(
        task: MountEngine.Task,
        module: XposedModule,
        loader: ClassLoader,
        intercept: XposedInterface.Hooker,
    ): MountEngine.Result {
        val method = when (val outcome = resolveTask(task, module, loader)) {
            is ResolveOutcome.Resolved -> outcome.method
            is ResolveOutcome.Merged -> {
                // 同一方法已由另一挂载点挂上 ⇒ 不重复包装（两个 CONST 拦截器叠在同一个方法上没意义），
                // 但**合并计成功**（不扣 N、不进失败清单）；这一行 WARN 是判据可审计的唯一现场。
                //
                // 例外：占用方的**功能开关**与本点不同 ⇒ 挂上去的是别人的拦截器，本点的意图压根没生效
                // ⇒ 不能算 installed（否则 N 虚高，功能实际是死的却显示"已挂载"）。
                val conflict = mergedFeatureConflict(task.feature, outcome.mergedFeature)
                val conflictNote = if (conflict) {
                    "，功能不同（本功能 ${task.feature} vs 占用方 ${outcome.mergedFeature}）⇒ 本点意图未生效"
                } else {
                    ""
                }
                XLog.log(
                    Log.WARN,
                    MountEngine.TAG,
                    "$hookName 挂载点合并（与另一挂载点落到同一方法，只挂一次）: ${task.id} → " +
                        "${MethodResolver.signatureOf(outcome.method)}" +
                        "（与 ${outcome.mergedWith ?: "另一挂载点"} 合并$conflictNote）",
                )
                return if (conflict) MountEngine.Result.MERGED_CONFLICT else MountEngine.Result.MERGED
            }
            ResolveOutcome.Unresolved -> return MountEngine.Result.NOT_RESOLVED
        }
        // 回调期留痕（评审 #19）：PROTECTIVE 语义下框架吞 intercept 异常并回退原调用，模块侧零痕迹。
        // 这里只包 try/catch 记 WARN 后**原样 rethrow**——行为零变化；proceed 自身异常恒传播，不吞不包。
        val watched = XposedInterface.Hooker { chain ->
            try {
                intercept.intercept(chain)
            } catch (t: Throwable) {
                val n = callbackWarnCount.merge(task.id, 1) { a, b -> a + b } ?: 1
                if (n <= MountEngine.CALLBACK_WARN_PER_TASK) {
                    XLog.log(Log.WARN, MountEngine.TAG, "$hookName 回调异常（框架按 PROTECTIVE 继续，本行仅模块侧留痕）: ${task.id} — ${t.message}", t)
                }
                throw t
            }
        }
        return runCatching {
            module.hook(method)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .setId(task.id)
                .intercept(watched)
        }.fold(
            onSuccess = { MountEngine.Result.OK },
            onFailure = { t ->
                XLog.log(Log.WARN, MountEngine.TAG, "hook 挂载失败（不阻断其他 hook）: ${task.description} — ${t.message}", t)
                MountEngine.Result.FAILED
            },
        )
    }

    /**
     * 判据日志（每任务前 [MountEngine.JUDGE_LOG_PER_TASK] 次；真机核对"功能已生效"用，不受 [MountEngine.DEBUG_LOGGING] 门控）。
     * @param prefix 目标侧日志前缀（如 `installer-hook`），沿用各历史口径便于按前缀过滤。
     * @param detail 目标侧补充数据（如"剔除副卡后 1/2 卡"），非空时附在行尾。
     */
    fun judge(module: XposedModule, task: MountEngine.Task, prefix: String, detail: String? = null) {
        val n = judgeCount.merge(task.id, 1) { a, b -> a + b } ?: 1
        if (n <= MountEngine.JUDGE_LOG_PER_TASK) {
            val suffix = if (detail.isNullOrEmpty()) "" else "（$detail）"
            XLog.log(Log.INFO, MountEngine.TAG, "$prefix 生效: ${task.id} — ${task.description}$suffix")
        }
    }

    /**
     * 宿主版本自检（每进程一次）：hook 挂载点以特定宿主版本反编译实证，宿主 OTA 后混淆名/签名可能漂移——
     * 版本不一致时打 WARN 提醒"判据以 installed N/M 为准"。拿不到 Context（宿主 Application 未起）则跳过。
     */
    private fun checkHostVersionOnce(module: XposedModule) {
        if (hostVersionChecked || expectedHostVersion == null) return
        runCatching {
            // PackageLoadedParam 时机 Application 往往尚未创建：拿不到就不置位，随下一轮重试再查
            val app = Class.forName("android.app.ActivityThread")
                .getMethod("currentApplication").invoke(null) as? android.content.Context
                ?: return
            val actual = app.packageManager.getPackageInfo(hostPackage, 0)?.versionName ?: return
            hostVersionChecked = true
            actualVersion = actual
            when {
                actual == expectedHostVersion ->
                    XLog.log(Log.INFO, MountEngine.TAG, "$hookName 宿主版本与 hook 基线一致: $actual")
                else -> XLog.log(
                    Log.WARN, MountEngine.TAG,
                    "$hookName 宿主版本 $actual 与 hook 基线 $expectedHostVersion 不一致——挂载点可能漂移，" +
                        "判据以 installed N/M 与失败清单为准",
                )
            }
        }
    }
}
