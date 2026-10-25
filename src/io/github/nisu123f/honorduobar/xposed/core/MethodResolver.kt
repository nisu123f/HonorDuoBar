package io.github.nisu123f.honorduobar.xposed.core

import android.util.Log
import java.lang.reflect.Method
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap

/**
 * 目标方法解析（直连反射）。
 *
 * 抽取自 HonorHook（AGPL-3.0）的 `MethodResolver`，**去掉了 DexKit 兜底轨**：
 * 本模块只作用于荣耀 SystemUI，全部挂载点都是未混淆的具名类/方法（jadx 实证），
 * 直连反射即可解析；宿主升级导致类名漂移时如实返回 [ResolveOutcome.Unresolved] 并留日志，
 * 不猜类名硬兜。
 */
object MethodResolver {

    private const val TAG = "HonorSystemUI"

    /** 已挂载方法 → 占用它的挂载点 id（按 ClassLoader 分桶；防同一方法被两个拦截器重复包装）。 */
    private val claimed = WeakHashMap<ClassLoader, ConcurrentHashMap<Method, Claim>>()

    private fun bucket(loader: ClassLoader): ConcurrentHashMap<Method, Claim> =
        synchronized(claimed) { claimed.getOrPut(loader) { ConcurrentHashMap() } }

    internal fun resolveOutcome(
        loader: ClassLoader,
        className: String,
        methodName: String,
        paramCount: Int? = null,
        returnType: String? = null,
        paramTypes: List<String>? = null,
        pointId: String? = null,
        pointFeature: String = "",
    ): ResolveOutcome {
        val method = runCatching {
            val byName = loader.loadClass(className).declaredMethods.filter { m ->
                m.name == methodName &&
                    matchesParameters(m, paramCount, paramTypes) &&
                    (returnType == null || canonicalTypeName(m.returnType.name) == canonicalTypeName(returnType))
            }
            val candidates = byName.filter { !it.isSynthetic && !it.isBridge }.ifEmpty { byName }
            when (candidates.size) {
                0 -> null
                1 -> candidates[0]
                else -> {
                    // 同名同参数量且无 paramTypes 锚点：保留 firstOrNull 的既有权衡，但必须留痕
                    val picked = candidates.firstOrNull {
                        returnType == null || it.returnType.name == returnType
                    } ?: candidates[0]
                    XLog.log(
                        Log.WARN,
                        TAG,
                        "直连反射命中 ${candidates.size} 个同名同参数量方法（$className#$methodName），" +
                            "paramTypes 锚点不足，取第一个: ${signatureOf(picked)}",
                    )
                    picked
                }
            }
        }.onFailure { t ->
            XLog.log(Log.WARN, TAG, "直连反射未命中 $className#$methodName（${t::class.java.simpleName}）", t)
        }.getOrNull()

        if (method == null) return ResolveOutcome.Unresolved

        val owner = pointId ?: signatureOf(method)
        val prev = bucket(loader).putIfAbsent(method, Claim(ownerId = owner, feature = pointFeature))
        return if (prev == null) {
            ResolveOutcome.Resolved(method)
        } else {
            ResolveOutcome.Merged(method, prev.ownerId, prev.feature)
        }
    }

    /** 参数匹配：优先按参数类型名序列（消解同 arity 重载），否则按个数。 */
    private fun matchesParameters(m: Method, paramCount: Int?, paramTypes: List<String>?): Boolean {
        if (!paramTypes.isNullOrEmpty()) {
            val actual = m.parameterTypes
            return actual.size == paramTypes.size &&
                actual.indices.all {
                    canonicalTypeName(actual[it].name) == canonicalTypeName(paramTypes[it])
                }
        }
        return paramCount == null || m.parameterCount == paramCount
    }

    /** 基本类型与包装类型归一（`int` ≡ `java.lang.Integer`），只用于放宽匹配、不用于改选。 */
    internal fun canonicalTypeName(name: String): String = when (name) {
        "int" -> "java.lang.Integer"
        "boolean" -> "java.lang.Boolean"
        "long" -> "java.lang.Long"
        "short" -> "java.lang.Short"
        "byte" -> "java.lang.Byte"
        "char" -> "java.lang.Character"
        "float" -> "java.lang.Float"
        "double" -> "java.lang.Double"
        else -> name
    }

    internal fun signatureOf(m: Method): String {
        val params = m.parameterTypes.joinToString(",") { it.name }
        return "${m.declaringClass.name}#${m.name}($params):${m.returnType.name}"
    }
}

/**
 * 解析结论：
 * - [Resolved]：命中唯一方法，可挂载；
 * - [Merged]：与另一挂载点落到同一方法——同一方法只挂一次（不重复包装），该点意图已被覆盖；
 * - [Unresolved]：未命中（可随类加载重试）。
 */
internal sealed interface ResolveOutcome {
    data class Resolved(val method: Method) : ResolveOutcome
    data class Merged(val method: Method, val mergedWith: String?, val mergedFeature: String = "") : ResolveOutcome
    object Unresolved : ResolveOutcome
}

internal data class Claim(val ownerId: String, val feature: String)
