package com.guard.ownerguard

import android.app.admin.DevicePolicyManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.UserManager
import android.text.InputType
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.security.MessageDigest

/**
 * 所有者占位器。
 *
 * 设计约束：
 *  - 无 INTERNET / 存储 / 定位 / 联系人等敏感权限；
 *  - 不采集任何数据，不联网；
 *  - 只占 owner 槽位 + 可选的"禁止安装新应用"策略；
 *  - 自带 PIN 移除通道，且 PIN 强制设置。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var dpm: DevicePolicyManager
    private lateinit var admin: ComponentName
    private lateinit var um: UserManager
    private lateinit var tvStatus: TextView
    private lateinit var tvInfo: TextView
    private lateinit var btnInstallBlock: Button
    private lateinit var switchDo: Switch

    private val prefs by lazy { getSharedPreferences("owner_guard", Context.MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        dpm = getSystemService(DevicePolicyManager::class.java)
        um = getSystemService(UserManager::class.java)
        admin = ComponentName(this, DeviceOwnerAdminReceiver::class.java)
        tvStatus = findViewById(R.id.tvStatus)
        tvInfo = findViewById(R.id.tvInfo)
        btnInstallBlock = findViewById(R.id.btnInstallBlock)
        switchDo = findViewById(R.id.switchDo)

        findViewById<Button>(R.id.btnCopy).setOnClickListener { copyCommands() }
        findViewById<Button>(R.id.btnSetPin).setOnClickListener { setPin() }
        findViewById<Button>(R.id.btnRelease).setOnClickListener { release() }
        findViewById<Button>(R.id.btnDebug).setOnClickListener { toggleDebug() }
        btnInstallBlock.setOnClickListener { toggleInstallBlock() }
        switchDo.isChecked = prefs.getBoolean("want_do", false)
        switchDo.setOnCheckedChangeListener { _, checked -> onDoSwitch(checked) }

        handleCmd(intent?.getStringExtra("cmd"))
    }

    // ---------------- 关键修复：前台时 intent 走这里 ----------------
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleCmd(intent?.getStringExtra("cmd"))
        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
        promptPinIfNeeded()
    }

    // ---------------- 状态 ----------------

    private fun isDo(): Boolean = runCatching { dpm.isDeviceOwnerApp(packageName) }.getOrDefault(false)
    private fun isPo(): Boolean = runCatching { dpm.isProfileOwnerApp(packageName) }.getOrDefault(false)
    private fun installBlocked(): Boolean =
        runCatching { um.hasUserRestriction(UserManager.DISALLOW_INSTALL_APPS) }.getOrDefault(false)

    private fun refresh() {
        val doOwned = isDo()
        val poOwned = isPo()
        val hasPin = prefs.getString(KEY_PIN_HASH, null) != null
        val lost = prefs.getBoolean("owner_lost", false)
        val last = prefs.getLong(KEY_LAST_SEEN, 0L)
        val gapMin = if (last == 0L) -1 else ((System.currentTimeMillis() - last) / 60000).toInt()

        if (doOwned || poOwned) {
            prefs.edit().putBoolean("owner_expected", true).apply()
        }

        tvStatus.text = buildString {
            if (lost && !doOwned && !poOwned) {
                appendLine("⚠️ 检测到占位曾丢失（开机自检发现）")
                appendLine()
            }
            appendLine("设备所有者（DO）  : ${if (doOwned) "✅ 已被本应用占用" else "❌ 空着"}")
            appendLine("配置文件所有者（PO）: ${if (poOwned) "✅ 已被本应用占用" else "❌ 空着"}")
            appendLine("禁止安装新应用    : ${if (installBlocked()) "🔒 已开启" else "未开启"}")
            appendLine("移除 PIN          : ${if (hasPin) "已设置" else "⚠️ 未设置"}")
            appendLine("调试通道          : ${if (isDebugBlocked()) "已关闭" else "开着"}")
            appendLine("上次打开          : ${if (gapMin < 0) "首次" else "${gapMin} 分钟前"}")
            if (!doOwned && !poOwned) {
                appendLine()
                appendLine("提示：需要先在电脑上执行设置命令，见下方按钮。")
            }
        }

        btnInstallBlock.text = if (installBlocked()) "禁止新应用安装：开 🔒" else "禁止新应用安装：关"

        tvInfo.text = buildString {
            appendLine("它做了什么：占住 owner 槽位；可选地禁止安装新应用。")
            appendLine("它没做什么：不申请敏感权限、不联网、不读数据、不改其它设置、不禁用任何应用。")
            appendLine()
            appendLine("关于卸载：占位生效后系统会拒绝卸载，这是系统对 owner 的固有行为。")
            appendLine("要卸载请先用 PIN 解除占位。")
            appendLine()
            appendLine("⚠️ 若开启「禁止安装」：只有本应用能解除该限制。")
            appendLine("若本应用无法运行，设备将无法安装任何应用，只能恢复出厂。")
            appendLine()
            appendLine("恢复出厂会清除占位，需要重新设置。")
        }

        prefs.edit().putLong(KEY_LAST_SEEN, System.currentTimeMillis()).apply()
    }

    // ---------------- PIN 强制 ----------------

    private fun promptPinIfNeeded() {
        if (prefs.getString(KEY_PIN_HASH, null) != null) return
        if (!isDo() && !isPo()) return
        AlertDialog.Builder(this)
            .setTitle("请先设置移除 PIN")
            .setMessage(
                "占位生效后应用无法卸载，只有 PIN 能解除。\n\n" +
                    "不设置 PIN 的话，将来只能恢复出厂才能清掉它。"
            )
            .setCancelable(false)
            .setPositiveButton("现在设置") { _, _ -> setPin() }
            .setNegativeButton("稍后（有风险）", null)
            .show()
    }

    private fun askPin(title: String, onOk: (String) -> Unit) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = "至少 4 位数字"
            setSingleLine()
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(input)
            .setPositiveButton("确定") { _, _ -> onOk(input.text.toString()) }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun setPin() {
        askPin("设置移除 PIN") { pin ->
            if (pin.length < 4) { toast("PIN 至少 4 位"); return@askPin }
            prefs.edit().putString(KEY_PIN_HASH, sha256(pin)).apply()
            toast("PIN 已设置，请记牢")
            refresh()
        }
    }

    private fun release() {
        if (!isDo() && !isPo()) { toast("当前没有占位，无需解除"); return }
        askPin("输入 PIN 解除占位") { pin ->
            val saved = prefs.getString(KEY_PIN_HASH, null)
            if (saved == null) { toast("还没设置 PIN，请先设置"); return@askPin }
            if (sha256(pin) != saved) { toast("PIN 错误"); return@askPin }
            // 二次确认
            AlertDialog.Builder(this)
                .setTitle("确认解除占位？")
                .setMessage("解除后本应用可被卸载，系统也不再阻止其他应用成为所有者。")
                .setPositiveButton("确认解除") { _, _ -> doRelease() }
                .setNegativeButton("取消", null)
                .show()
        }
    }

    private fun doRelease() {
        runCatching {
            if (isPo()) dpm.clearProfileOwner(admin)
            if (isDo()) dpm.clearDeviceOwnerApp(packageName)
            prefs.edit().putBoolean("owner_expected", false).apply()
        }.onSuccess {
            toast("已解除占位，现在可以卸载了")
            refresh()
        }.onFailure { toast("解除失败：" + (it.message ?: "未知")) }
    }

    // ---------------- 同时占用 DO ----------------

    /**
     * 说明：App 无法自己调用 dpm set-device-owner（那是 shell 命令）。
     * 这里只做三件事：记录意愿、检测状态、给出可执行引导。
     */
    private fun onDoSwitch(checked: Boolean) {
        prefs.edit().putBoolean("want_do", checked).apply()
        if (!checked) { refresh(); return }
        if (isDo()) {
            toast("设备所有者已被本应用占用")
            refresh()
            return
        }
        val cmd = "adb shell dpm set-device-owner $packageName/.DeviceOwnerAdminReceiver"
        AlertDialog.Builder(this)
            .setTitle("要占用设备所有者（DO）")
            .setMessage(
                "前提（AOSP 硬性要求）：\n" +
                    "1. 设备上没有任何账号\n" +
                    "2. 只有一个用户\n" +
                    "3. 没有其他 device owner\n\n" +
                    "步骤：\n" +
                    "① 设置 → 账号 → 删除全部账号\n" +
                    "② 在电脑执行：\n$cmd\n" +
                    "③ 成功后回来把账号登回去\n\n" +
                    "注意：你现在有 11 个账号，必须全部删除，否则会报\n" +
                    "「already some accounts on the device」。\n" +
                    "（PO 已占位时也会阻止 DO，需要先解除 PO）"
            )
            .setPositiveButton("复制命令") { _, _ ->
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("do", cmd))
                toast("命令已复制")
            }
            .setNeutralButton("打开账号设置") { _, _ ->
                runCatching {
                    startActivity(Intent(android.provider.Settings.ACTION_SYNC_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }.onFailure { toast("无法打开：${it.message}") }
            }
            .setNegativeButton("知道了", null)
            .show()
    }

    // ---------------- 禁止安装 ----------------

    private fun toggleInstallBlock() {
        if (!isDo() && !isPo()) { toast("需要先占位（PO 或 DO）才能设置"); return }
        if (installBlocked()) {
            AlertDialog.Builder(this)
                .setTitle("解除「禁止安装」？")
                .setMessage("解除后设备可以正常安装新应用。")
                .setPositiveButton("解除") { _, _ ->
                    val r = runCatching { dpm.clearUserRestriction(admin, UserManager.DISALLOW_INSTALL_APPS) }
                    toast(if (r.isSuccess) "已解除限制" else "失败：${r.exceptionOrNull()?.message}")
                    refresh()
                }
                .setNegativeButton("取消", null)
                .show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("开启「禁止安装新应用」？")
            .setMessage(
                "开启后：\n" +
                    "• 任何新应用都无法安装（包括通过 adb）\n" +
                    "• 只有本应用能解除这个限制\n" +
                    "• 若本应用崩溃或无法运行，设备将无法安装任何应用，\n" +
                    "  只能恢复出厂\n\n" +
                    "建议只在确定需要时开启。"
            )
            .setPositiveButton("我明白，开启") { _, _ ->
                val r = runCatching { dpm.addUserRestriction(admin, UserManager.DISALLOW_INSTALL_APPS) }
                toast(if (r.isSuccess) "已禁止安装新应用" else "失败：${r.exceptionOrNull()?.message}")
                refresh()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ---------------- 调试开关 ----------------

    private fun isDebugBlocked(): Boolean = runCatching {
        um.hasUserRestriction(UserManager.DISALLOW_DEBUGGING_FEATURES)
    }.getOrDefault(false)

    private fun toggleDebug() {
        if (!isDo()) { toast("需要先成为设备所有者；PO 也能试，但可能被拒"); }
        val blocked = isDebugBlocked()
        AlertDialog.Builder(this)
            .setTitle(if (blocked) "恢复调试？" else "关闭 USB 与无线调试？")
            .setMessage(
                if (blocked) "恢复后 adb 可再次使用。"
                else "关闭后所有依赖 adb 的提权链都会断掉。\n代价：你自己也不能用 adb，解除仍需 PIN。"
            )
            .setPositiveButton("确定") { _, _ ->
                val r = runCatching {
                    if (blocked) dpm.clearUserRestriction(admin, UserManager.DISALLOW_DEBUGGING_FEATURES)
                    else dpm.addUserRestriction(admin, UserManager.DISALLOW_DEBUGGING_FEATURES)
                }
                toast(if (r.isSuccess) "操作成功" else "失败：${r.exceptionOrNull()?.message}")
                refresh()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ---------------- adb 命令通道（保留，供自动化/自救） ----------------

    private fun handleCmd(cmd: String?) {
        if (cmd == null) return
        Log.i("OwnerGuard", "handleCmd=$cmd")
        when (cmd) {
            "block_install" -> {
                val r = runCatching { dpm.addUserRestriction(admin, UserManager.DISALLOW_INSTALL_APPS) }
                Log.i("OwnerGuard", if (r.isSuccess) "BLOCK_INSTALL=OK"
                    else "BLOCK_INSTALL=ERR:${r.exceptionOrNull()?.message}")
            }
            "allow_install" -> {
                val r = runCatching { dpm.clearUserRestriction(admin, UserManager.DISALLOW_INSTALL_APPS) }
                Log.i("OwnerGuard", if (r.isSuccess) "ALLOW_INSTALL=OK"
                    else "ALLOW_INSTALL=ERR:${r.exceptionOrNull()?.message}")
            }
            "status" -> Log.i("OwnerGuard",
                "STATUS INSTALL_BLOCKED=${installBlocked()} DEBUG_BLOCKED=${isDebugBlocked()} DO=${isDo()} PO=${isPo()}")
        }
    }

    // ---------------- 设置命令 ----------------

    private fun copyCommands() {
        val pkg = packageName
        val cmds = """
            adb shell dpm set-device-owner  $pkg/.DeviceOwnerAdminReceiver
            adb shell dpm set-profile-owner $pkg/.DeviceOwnerAdminReceiver
            adb shell dpm list-owners
        """.trimIndent()
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("owner-guard", cmds))
        AlertDialog.Builder(this)
            .setTitle("在电脑上执行")
            .setMessage(
                "本机实测：DO 需要设备无账号；PO 需要短暂禁用账号提供者（或退账号）。\n\n" +
                    cmds + "\n\n两行都成功后再恢复，最后关掉无线调试。"
            )
            .setPositiveButton("知道了", null)
            .show()
    }

    // ---------------- 工具 ----------------

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun sha256(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    companion object {
        private const val KEY_PIN_HASH = "pin_hash"
        private const val KEY_LAST_SEEN = "last_seen"
    }
}

