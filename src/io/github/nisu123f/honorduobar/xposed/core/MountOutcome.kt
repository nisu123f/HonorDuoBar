package io.github.nisu123f.honorduobar.xposed.core

/**
 * 单个挂载点的挂载结果（[MountSummary] 的输入单元）。
 *
 * 抽成纯数据是为了让判据口径（`installed N/M`、通路存活比、失败清单）能在 JVM 上直接回归——
 * 这些规则是真机唯一验收口径（`docs/hook-inventory-audit.md` §五），之前只散落在
 * [MountEngine] 的可变字段里，改一处口径没有任何测试会拦。
 */
internal data class MountOutcome(
    /** 挂载点稳定 id（`setId` 用；如 `installer:CommonConfigManager.e`）。 */
    val id: String,
    /** 失败清单里打印的文本（现状 = 目标侧的 `task.description`，含功能分组标注）。 */
    val description: String,
    /** 功能分组（"" = 探针/载体类，不参与通路汇总）。 */
    val group: String,
    /** 挂载结果分级。 */
    val result: MountEngine.Result,
)

/** 汇总日志级别（与 `android.util.Log` 级别一一对应，但本层不依赖 Android，可 JVM 回归）。 */
internal enum class MountLogLevel { INFO, WARN, ERROR }

/**
 * 合并到**别的功能**的同名方法上 ⇒ 意图冲突（纯判定，可 JVM 回归）。
 *
 * 背景：宽松轨里两个挂载点可能落到同一方法，只挂一次（[MountEngine.Result.MERGED]）。但若两点的
 * 功能开关不同（一方 feature="A"、另一方 feature="B"），挂上去的是**先到那点**的拦截器 ⇒ 后到那点
 * 的意图压根没生效。判据若照样记 N/M，N 就虚高了（功能实际是死的却显示"已挂载"）。
 *
 * 口径：feature 相同（含双方都为空 = 都是常挂点）⇒ 不冲突，按 MERGED 计成功。
 * 一方为空（常挂点/探针行）、另一方非空 ⇒ **算冲突**：挂上去的是对方（或常挂点）的拦截器，
 * 本功能的意图压根没生效 ⇒ 计失败不计 N（否则 N 虚高，功能实际是死的）。
 */
internal fun mergedFeatureConflict(currentFeature: String, mergedWithFeature: String): Boolean =
    currentFeature != mergedWithFeature

/** 一行汇总日志。 */
internal data class MountLogLine(val level: MountLogLevel, val text: String)

/**
 * 挂载结果聚合（判据口径的单一事实源）。
 *
 * 口径（全部由 `MountSummaryTest` 钉住）：
 * - M 只在**首轮**记一次：重试轮传入的是未解析子列表，逐轮覆盖会让判据行变成"累计成功/本轮待挂"；
 * - N = OK + MERGED（[MountEngine.Result.installed]），NOT_RESOLVED 不计；
 *   MERGED_CONFLICT（合并到别的功能 ⇒ 本点意图没生效）也不计，并进失败清单（判据不许虚高）；
 * - 失败清单只收 FAILED 与"重试耗尽仍未挂载"的点，NOT_RESOLVED 在重试中不进清单；
 * - 通路行按 group **首次出现序**（= 声明序）输出，全灭 group 升级 ERROR 附失败明细。
 */
internal class MountSummary {

    private var began = false
    private var total = 0
    private var ok = 0
    private val failed = mutableListOf<String>()
    /** 通路总数（group → 该组挂载点数；只在首轮记）。 */
    private val groupTotal = LinkedHashMap<String, Int>()
    /** 通路成功数（group → 已挂载点数）。 */
    private val groupOk = HashMap<String, Int>()
    /** 全灭通路的失败明细（group → 失败描述）。 */
    private val groupFailed = HashMap<String, MutableList<String>>()

    /** 首轮记 M 与各通路总数；后续调用（重试轮）忽略。 */
    fun begin(total: Int, groups: Map<String, Int>) {
        if (began) return
        began = true
        this.total = total
        groups.forEach { (group, count) -> groupTotal[group] = count }
    }

    /** 记一次挂载结果。 */
    fun record(outcome: MountOutcome) {
        when {
            outcome.result.installed -> {
                ok++
                if (outcome.group.isNotEmpty()) {
                    groupOk[outcome.group] = (groupOk[outcome.group] ?: 0) + 1
                }
            }
            outcome.result == MountEngine.Result.FAILED ||
                outcome.result == MountEngine.Result.MERGED_CONFLICT -> {
                failed += outcome.description
                if (outcome.group.isNotEmpty()) {
                    groupFailed.getOrPut(outcome.group) { mutableListOf() }.add(outcome.description)
                }
            }
            else -> Unit   // NOT_RESOLVED：重试中，不进失败清单（由引擎在重试耗尽时调 markUnmounted）
        }
    }

    /** 重试轮耗尽：该点仍未挂载 ⇒ 进失败清单（与 FAILED 同列，现状口径一致）。 */
    fun markUnmounted(description: String, group: String) {
        failed += description
        if (group.isNotEmpty()) {
            groupFailed.getOrPut(group) { mutableListOf() }.add(description)
        }
    }

    fun total(): Int = total

    fun ok(): Int = ok

    /** 失败清单（按发生顺序）。 */
    fun failedDescriptions(): List<String> = failed.toList()

    /** 通路存活行片段（顺序 = group 首次出现序）。 */
    fun groupLines(): List<String> =
        groupTotal.entries.map { (group, count) -> "$group: ${groupOk[group] ?: 0}/$count" }

    /** 全灭通路（ok = 0）。 */
    fun deadGroups(): List<String> =
        groupTotal.keys.filter { (groupOk[it] ?: 0) == 0 }

    /** 汇总日志行（顺序：判据行 → 通路行 → 全灭通路 ERROR 行）。 */
    fun logLines(hookName: String, hostPart: String = "", hostPackage: String = ""): List<MountLogLine> {
        val lines = mutableListOf<MountLogLine>()
        // 同一 hook 名按包注册多次（HonorAds 5 个包各注册一次）⇒ 行首带宿主包名，否则判据行分不清归属。
        val tag = if (hostPackage.isEmpty()) hookName else "$hookName[$hostPackage]"
        val failurePart = if (failed.isEmpty()) "" else " — 失败: ${failed.joinToString()}"
        lines += MountLogLine(
            if (failed.isEmpty()) MountLogLevel.INFO else MountLogLevel.WARN,
            "$tag: installed $ok/$total$hostPart$failurePart",
        )
        if (groupTotal.isNotEmpty()) {
            lines += MountLogLine(MountLogLevel.INFO, "$tag 通路: " + groupLines().joinToString("; "))
            deadGroups().forEach { group ->
                lines += MountLogLine(
                    MountLogLevel.ERROR,
                    "$tag 通路 $group 全灭 0/${groupTotal[group]} — 失败: ${groupFailed[group]?.joinToString() ?: "无明细"}",
                )
            }
        }
        return lines
    }
}
