package com.guard.ownerguard

import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 开机自检：确认占位还在。
 *
 * 不申请通知权限（保持零权限），只把结果写进 SharedPreferences；
 * 用户下次打开应用时会看到醒目提示。同时尽力尝试拉起界面。
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != "android.intent.action.LOCKED_BOOT_COMPLETED" &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return

        val dpm = ctx.getSystemService(DevicePolicyManager::class.java)
        val pkg = ctx.packageName
        val isDo = runCatching { dpm.isDeviceOwnerApp(pkg) }.getOrDefault(false)
        val isPo = runCatching { dpm.isProfileOwnerApp(pkg) }.getOrDefault(false)
        val expected = ctx.getSharedPreferences("owner_guard", Context.MODE_PRIVATE)
            .getBoolean("owner_expected", false)

        Log.i("OwnerGuard", "boot check: DO=$isDo PO=$isPo expected=$expected")

        if (expected && !isDo && !isPo) {
            ctx.getSharedPreferences("owner_guard", Context.MODE_PRIVATE)
                .edit().putBoolean("owner_lost", true).apply()
            // 尽力拉起界面（可能被后台启动限制拦，拦了也无所谓，下次打开会提示）
            runCatching {
                ctx.startActivity(
                    Intent(ctx, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
    }
}
