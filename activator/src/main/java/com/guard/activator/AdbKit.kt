package com.guard.activator

import android.content.Context
import io.github.muntashirakon.adb.AdbConnection
import io.github.muntashirakon.adb.PairingConnectionCtx
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/**
 * ADB 客户端封装。
 *
 * 注意：
 *  - 连接端口（_adb-tls-connect）在回环 127.0.0.1 上通常可用；
 *  - 配对端口（_adb-tls-pairing）只监听无线网卡地址，必须用 mDNS 发现到的 host，
 *    否则连 127.0.0.1 会被 ECONNREFUSED 拒绝。
 */
object AdbKit {

    fun pair(ctx: Context, host: String, pairingPort: Int, pairingCode: String) {
        val (priv, cert) = Identity.get(ctx)
        val pairCtx = PairingConnectionCtx(
            host, pairingPort, pairingCode.toByteArray(Charsets.UTF_8), priv, cert, "owner-activator"
        )
        try {
            pairCtx.start()
        } finally {
            runCatching { pairCtx.close() }
        }
    }

    fun shell(ctx: Context, host: String, connectPort: Int, command: String): String {
        val (priv, cert) = Identity.get(ctx)
        val conn = AdbConnection.create(host, connectPort, priv, cert)
        try {
            conn.connect(20, TimeUnit.SECONDS, false)
            val stream = conn.open("shell:$command")
            val out = ByteArrayOutputStream()
            try {
                stream.openInputStream().use { it.copyTo(out) }
            } finally {
                runCatching { stream.close() }
            }
            return out.toString(Charsets.UTF_8.name())
        } finally {
            runCatching { conn.close() }
        }
    }
}
