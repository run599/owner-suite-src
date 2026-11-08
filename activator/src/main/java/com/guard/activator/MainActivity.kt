package com.guard.activator

import android.content.Context
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import io.github.muntashirakon.adb.android.AdbMdns
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 占位激活器：临时禁用账号提供者 → 设置 owner → 立即恢复。
 * 禁用清单会持久化；即使进程被杀，下次打开也会自动补恢复。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var etPairPort: EditText
    private lateinit var etPairCode: EditText
    private lateinit var etConnPort: EditText
    private lateinit var swDo: Switch
    private lateinit var tvLog: TextView

    private val scope = CoroutineScope(Dispatchers.IO)
    private val sb = StringBuilder()

    private var mdnsConnect: AdbMdns? = null
    private var mdnsPairing: AdbMdns? = null
    private var pairHost: String = "127.0.0.1"
    private var connHost: String = "127.0.0.1"

    private val target = "com.guard.ownerguard/.DeviceOwnerAdminReceiver"

    private val prefs by lazy { getSharedPreferences("activator", Context.MODE_PRIVATE) }

    private fun pending(): MutableSet<String> =
        (prefs.getStringSet("disabled_pending", emptySet()) ?: emptySet()).toMutableSet()

    private fun markDisabled(pkg: String) {
        val s = pending()
        s.add(pkg)
        prefs.edit().putStringSet("disabled_pending", s).apply()
    }

    private fun markRestored(pkg: String) {
        val s = pending()
        s.remove(pkg)
        prefs.edit().putStringSet("disabled_pending", s).apply()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        etPairPort = findViewById(R.id.etPairPort)
        etPairCode = findViewById(R.id.etPairCode)
        etConnPort = findViewById(R.id.etConnPort)
        swDo = findViewById(R.id.swDo)
        tvLog = findViewById(R.id.tvLog)

        findViewById<Button>(R.id.btnActivate).setOnClickListener { startActivation() }
        findViewById<Button>(R.id.btnTest).setOnClickListener { testOnly() }
    }

    private fun startDiscovery() {
        if (mdnsConnect == null) {
            mdnsConnect = AdbMdns(this, AdbMdns.SERVICE_TYPE_TLS_CONNECT) { host, port ->
                runOnUiThread {
                    if (port > 0) {
                        host?.hostAddress?.let { connHost = it }
                        etConnPort.setText(port.toString())
                        log("🔎 自动发现连接端口：$connHost:$port")
                    }
                }
            }
        }
        if (mdnsPairing == null) {
            mdnsPairing = AdbMdns(this, AdbMdns.SERVICE_TYPE_TLS_PAIRING) { host, port ->
                runOnUiThread {
                    if (port > 0) {
                        host?.hostAddress?.let { pairHost = it }
                        etPairPort.setText(port.toString())
                        log("🔎 自动发现配对端口：$pairHost:$port（对话框保持打开）")
                    }
                }
            }
        }
        runCatching { mdnsConnect?.start() }
        runCatching { mdnsPairing?.start() }
        log("🔎 正在自动发现 ADB 端口…")
    }

    private fun stopDiscovery() {
        runCatching { mdnsConnect?.stop() }
        runCatching { mdnsPairing?.stop() }
    }

    override fun onResume() {
        super.onResume()
        startDiscovery()
        autoRestorePending()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopDiscovery()
    }

    private fun log(s: String) {
        runOnUiThread {
            sb.appendLine(s)
            tvLog.text = sb.toString()
            android.util.Log.i("Act", s)
        }
    }

    /** 上次若因进程被杀没恢复，这里补恢复 */
    private fun autoRestorePending() {
        val pend = pending()
        if (pend.isEmpty()) return
        val cp = etConnPort.text.toString().trim().toIntOrNull()
        if (cp == null) {
            log("⚠️ 有 ${pend.size} 个应用待恢复，但连接端口未知——请先打开无线调试")
            return
        }
        sb.setLength(0)
        log("⚠️ 检测到上次有 ${pend.size} 个应用未恢复，正在补恢复…")
        scope.launch {
            AdbSession(this@MainActivity, connHost, cp).use { s ->
                for (pkg in pend.toList()) {
                    var ok = false
                    repeat(3) { attempt ->
                        ok = runCatching { s.exec("pm enable $pkg") }
                            .map { it.contains("new state") }
                            .getOrDefault(false)
                        if (ok) return@repeat
                        Thread.sleep(500L * (attempt + 1))
                    }
                    if (ok) {
                        markRestored(pkg)
                        log("   已恢复 $pkg")
                    } else {
                        log("   恢复失败 $pkg")
                    }
                }
            }
            log("—— 补恢复结束 ——")
        }
    }

    private data class Params(val pairPort: Int, val code: String, val connPort: Int)

    private fun params(): Params? {
        val pp = etPairPort.text.toString().trim().toIntOrNull() ?: 0
        val code = etPairCode.text.toString().trim()
        val cp = etConnPort.text.toString().trim().toIntOrNull()
        if (cp == null) {
            log("❗ 连接端口为空：请确认无线调试已开启（端口会自动发现）")
            return null
        }
        return Params(pp, code, cp)
    }

    private fun testOnly() {
        val p = params() ?: return
        sb.setLength(0)
        scope.launch {
            runCatching {
                log("连接 $connHost:${p.connPort} …")
                AdbSession(this@MainActivity, connHost, p.connPort).use { s ->
                    log("输出：\n" + s.exec("id && getprop ro.product.model"))
                }
                log("✅ 连接成功（未做任何修改）")
            }.onFailure { log("❌ 失败：${it.javaClass.simpleName}: ${it.message}") }
        }
    }

    private fun startActivation() {
        val p = params() ?: return
        if (pending().isNotEmpty()) {
            log("❗ 还有 ${pending().size} 个包待恢复，请先让它自动恢复（或等待补恢复完成）")
            return
        }
        val wantDo = swDo.isChecked
        sb.setLength(0)
        scope.launch {
            val disabled = mutableListOf<String>()
            val session = AdbSession(this@MainActivity, connHost, p.connPort)
            try {
                if (p.code.isNotEmpty() && p.pairPort > 0) {
                    log("① 配对 $pairHost:${p.pairPort} …")
                    runCatching { AdbKit.pair(this@MainActivity, pairHost, p.pairPort, p.code) }
                        .onSuccess { log("   配对成功") }
                        .onFailure { log("   配对失败（若之前已配对可忽略）：${it.message}") }
                } else {
                    log("① 跳过配对（未填配对码）")
                }

                log("② 连接 $connHost:${p.connPort} …")
                log("   " + session.exec("id").lineSequence().firstOrNull { it.startsWith("uid=") }.orEmpty())

                log("③ 分析真实账号 …")
                val raw = session.exec("dumpsys account")
                // 1) 真实存在账号的类型
                val activeTypes = Regex("""Account \{name=[^}]*?type=([^,}]+)\}""")
                    .findAll(raw).map { it.groupValues[1].trim() }.toSet()
                // 2) 类型 → 持有该账号服务的包
                val typeToPkg = Regex("""AuthenticatorDescription \{type=([^,}]+)\},\s*ComponentInfo\{([^/]+)/""")
                    .findAll(raw)
                    .associate { it.groupValues[1].trim() to it.groupValues[2].trim() }
                // 3) 兜底：某些提供者当前处于禁用态时不会出现在映射里，
                //    用已安装包名前缀 / 段名匹配来推断
                val installed = session.exec("pm list packages")
                    .lineSequence()
                    .map { it.trim().removePrefix("package:") }
                    .filter { Regex("^[a-zA-Z0-9_.]+$").matches(it) }
                    .toList()

                // 绝不触碰的系统 / 账号关键包
                fun isBlocked(pkg: String) = pkg == "android" ||
                    pkg.startsWith("com.oplus.") || pkg.startsWith("com.heytap.") ||
                    pkg == "com.google.android.gms" || pkg == "com.android.settings" ||
                    pkg == "com.android.systemui" || pkg == "com.android.phone" ||
                    pkg == "com.android.vending"

                val generic = setOf(
                    "account", "accounts", "sync", "type", "push", "datasync",
                    "service", "auth", "com", "cn", "org", "net", "app", "mobile", "android"
                )

                fun resolve(type: String): String? {
                    typeToPkg[type]?.let { if (!isBlocked(it)) return it }
                    // 1) 包名是类型的点分段前缀
                    installed.filter { !isBlocked(it) && (type == it || type.startsWith("$it.")) }
                        .maxByOrNull { it.length }?.let { return it }
                    // 2) 类型与包名共享一个"非通用"段（最长者优先）
                    val typeSegs = type.split('.').filter { it.isNotBlank() && it.lowercase() !in generic }
                    if (typeSegs.isEmpty()) return null
                    return installed
                        .filter { !isBlocked(it) }
                        .map { pkg -> pkg to pkg.split('.').filter { it.lowercase() !in generic } }
                        .map { (pkg, segs) -> pkg to segs.count { s -> typeSegs.any { it.equals(s, true) } } }
                        .filter { (_, hits) -> hits > 0 }
                        .maxByOrNull { (pkg, _) -> pkg.length }?.first
                }

                val providers = activeTypes.mapNotNull { resolve(it) }
                    .filter { Regex("^[a-zA-Z0-9_.]+$").matches(it) }
                    .distinct()
                log("   有账号的类型 ${activeTypes.size} 个，涉及 ${providers.size} 个包：")
                activeTypes.forEach { t ->
                    log("     $t → ${resolve(t) ?: "（未找到持有者）"}")
                }

                log("④ 临时禁用（窗口开始）…")
                // 已经是禁用状态的包不要碰：否则恢复阶段会把用户自己禁用的应用重新启用
                val alreadyDisabled = session.exec("pm list packages -d")
                    .lineSequence().map { it.trim().removePrefix("package:") }.toSet()
                for (pkg in providers) {
                    if (pkg in alreadyDisabled) {
                        log("   跳过 $pkg（本来就是禁用状态，不碰）")
                        continue
                    }
                    runCatching { session.exec("pm disable-user --user 0 $pkg") }
                        .onSuccess { r ->
                            if (r.contains("new state")) {
                                disabled.add(pkg)
                                markDisabled(pkg)
                                log("   禁用 $pkg")
                            } else {
                                log("   跳过 $pkg")
                            }
                        }
                        .onFailure { log("   禁用 $pkg 失败：${it.message}") }
                    Thread.sleep(200)
                }
                log("   实际禁用 ${disabled.size} 个，等待 6 秒…")
                Thread.sleep(6000)

                log("⑤ 设置所有者（${if (wantDo) "Device Owner" else "Profile Owner"}）…")
                val cmd = if (wantDo) "dpm set-device-owner $target" else "dpm set-profile-owner $target"
                log("   " + session.exec(cmd).trim().take(300))

                log("⑥ 当前 owner：\n" + session.exec("dpm list-owners").trim())
            } catch (t: Throwable) {
                log("❌ 异常：${t.javaClass.simpleName}: ${t.message}")
            } finally {
                runCatching { session.close() }
                if (disabled.isNotEmpty()) {
                    log("⑦ 恢复账号提供者（窗口结束）…")
                    // 用独立新会话恢复，每包最多 3 次
                    runCatching {
                        AdbSession(this@MainActivity, connHost, p.connPort).use { rs ->
                            for (pkg in disabled) {
                                var ok = false
                                repeat(3) { attempt ->
                                    ok = runCatching { rs.exec("pm enable $pkg") }
                                        .map { it.contains("new state") }
                                        .getOrDefault(false)
                                    if (ok) return@repeat
                                    Thread.sleep(500L * (attempt + 1))
                                }
                                if (ok) {
                                    markRestored(pkg)
                                    log("   启用 $pkg")
                                } else {
                                    log("   ⚠️ 恢复失败 $pkg（下次打开会自动补）")
                                }
                            }
                        }
                    }.onFailure { log("   ⚠️ 恢复会话失败：${it.message}（下次打开会自动补）") }
                }
                log("—— 流程结束 ——")
            }
        }
    }
}






