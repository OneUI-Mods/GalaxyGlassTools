package dev.ryuya.galaxyglass

import io.github.libxposed.api.XposedInterface.Chain
import java.util.concurrent.locks.ReentrantReadWriteLock

/** Drains intercepted calls and Compose delegates before retiring a glass generation. */
internal object GlassHookDispatch {
    private val calls = ReentrantReadWriteLock()
    @Volatile private var managed = false
    @Volatile private var retired = false

    fun track() { managed = true }

    fun stop(save: () -> Unit): Boolean {
        val write = calls.writeLock()
        if (!write.tryLock()) return false
        return try {
            save()
            retired = true
            true
        } finally {
            write.unlock()
        }
    }

    fun drawOrStock(stock: () -> Any?, draw: () -> Any?): Any? {
        if (!managed) return draw()
        if (retired) return stock()
        val read = calls.readLock()
        read.lock()
        try {
            return if (retired) stock() else draw()
        } finally {
            read.unlock()
        }
    }

    inline fun invoke(chain: Chain, action: () -> Any?): Any? {
        if (!managed) return action()
        if (retired) return chain.proceed()
        val read = calls.readLock()
        read.lock()
        try {
            return if (retired) chain.proceed() else action()
        } finally {
            read.unlock()
        }
    }
}
