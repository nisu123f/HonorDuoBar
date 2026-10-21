package io.github.nisu123f.honorduobar.xposed.core

import android.os.Handler
import android.os.HandlerThread

/** 挂载调度器：共享单 HandlerThread（"HonorHook-mount"）后台线程。 */
object MountScheduler {

    private val thread = HandlerThread("HonorSystemUI-mount").apply { start() }

    /** 后台挂载线程的 Handler（挂载/重试任务按投递顺序串行执行）。 */
    val handler: Handler = Handler(thread.looper)

    /** 在挂载后台线程延后 [delayMillis] 毫秒执行 [action]。 */
    fun postDelayed(delayMillis: Long, action: () -> Unit) {
        handler.postDelayed(action, delayMillis)
    }
}