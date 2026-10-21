package io.github.nisu123f.honorduobar.xposed.core

/**
 * 功能开关闸门（hook 侧读侧接口）。
 *
 * 存在理由：功能关闭时，挂载点必须**不挂**——挂了也白挂，还污染判据行（installed N/M 里
 * 出现一个功能已关的点，挂没挂都说不清）。但过滤口径必须唯一：过去散落在 theme / installer /
 * launcher 三处本地 `.filter { it.feature.isEmpty() || RemoteConfig.isEnabled(it.feature) }`，
 * 现下沉到 [MountEngine]：挂载点自己声明 feature，引擎按闸门统一过滤。
 *
 * 依赖方向：core 不依赖 data（[FeatureGate] 的实现方在 data 侧，data → core 单向）。
 */
interface FeatureGate {

    /** 功能 key 是否开启。实现方语义：读不到开关时按目录默认（默认全开）。 */
    fun isEnabled(key: String): Boolean

    companion object {
        /**
         * 恒开闸门：未显式传闸门的目标行为与改造前完全一致（表级过滤是唯一被下沉的过滤口径，
         * 其余目标沿用各自的整目标 gate）。
         */
        val AlwaysOn: FeatureGate = object : FeatureGate {
            override fun isEnabled(key: String): Boolean = true
        }
    }
}

/**
 * 按功能开关过滤挂载点。
 *
 * 口径：
 * - feature 为空 = 该点不属于任何开关（常挂），永远保留；
 * - feature 非空 = 按闸门判定，关闭则剔除。
 *
 * 剔除发生在 [MountEngine] 统计 M **之前** ⇒ 判据行的 M 仍是「本次实际要挂的点数」，
 * 关功能不改变 M 的含义（也不把功能已关的点算成未挂载的失败）。
 */
internal fun <T : MountEngine.Task> filterByFeature(pending: List<T>, gate: FeatureGate): List<T> =
    pending.filter { it.feature.isEmpty() || gate.isEnabled(it.feature) }
