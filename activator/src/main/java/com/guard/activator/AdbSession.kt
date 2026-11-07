package com.guard.activator

import android.content.Context
import io.github.muntashirakon.adb.AdbConnection
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * ADB 会话（带超时）。
 *
 * 之前的问题：
 *  - 读流遇到 CLSE 会抛 IOException → 已按 EOF 处理；
 *  - 但某些命令（如 pm disable-user 触发的系统繁忙）流可能永远不结束 →
 *    exec 无限阻塞，界面看起来"卡住"。现在每条命令 15 秒超时，
 *    超时后主动关闭连接解除阻塞并重连重试。
 */
class AdbSession(
    private val ctx: Context,
    private val host: String,
    private val port: Int
) : AutoCloseable {

    private var conn: AdbConnection? = null
    private val identity = Identity.get(ctx)

    private fun ensure(): AdbConnection {
        conn?.takeIf { it.isConnected }?.let { return it }
        runCatching { conn?.close() }
        val c = AdbConnection.create(host, port, identity.first, identity.second)
        c.connect(20, TimeUnit.SECONDS, false)
        conn = c
        return c
    }

    private fun execOnce(c: AdbConnection, cmd: String): String {
        val stream = c.open("shell:$cmd")
        val out = ByteArrayOutputStream()
        try {
            val ins = stream.openInputStream()
            val buf = ByteArray(4096)
            while (true) {
                val n = try {
                    ins.read(buf)
                } catch (e: IOException) {
                    -1
                }
                if (n <= 0) break
                out.write(buf, 0, n)
            }
        } finally {
            runCatching { stream.close() }
        }
        return out.toString(Charsets.UTF_8.name())
    }

    /** 执行命令：每条 15 秒超时，失败重连重试（最多 3 次） */
    fun exec(cmd: String, timeoutSec: Long = 15): String {
        var last: Throwable? = null
        for (attempt in 1..3) {
            val c = try {
                ensure()
            } catch (t: Throwable) {
                last = t
                android.util.Log.i("Act", "connect fail($attempt): ${t.message}")
                Thread.sleep(500L * attempt)
                continue
            }
            val fut = pool.submit(Callable { execOnce(c, cmd) })
            try {
                return fut.get(timeoutSec, TimeUnit.SECONDS)
            } catch (e: TimeoutException) {
                last = e
                android.util.Log.i("Act", "timeout($attempt): $cmd")
                runCatching { c.close() }      // 关连接让阻塞的 read 抛异常退出
                fut.cancel(true)
                conn = null
            } catch (t: Throwable) {
                last = t
                android.util.Log.i("Act", "exec fail($attempt): ${t.javaClass.simpleName}: ${t.message}")
                conn = null
                runCatching { c.close() }
            }
            Thread.sleep(600L * attempt)
        }
        throw last ?: IllegalStateException("exec failed: $cmd")
    }

    override fun close() {
        runCatching { conn?.close() }
        conn = null
    }

    companion object {
        private val pool = Executors.newCachedThreadPool { r ->
            Thread(r, "adb-exec").apply { isDaemon = true }
        }
    }
}
