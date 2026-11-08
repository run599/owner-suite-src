package com.guard.ownerguard

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 设备管理员接收器：Device Owner / Profile Owner 必须挂在这个组件上。
 *
 * 这里刻意不做任何事——不监听、不拦截、不修改任何设置。
 * 它的唯一存在意义：给系统的 owner 槽位挂一个"已被占用"的标记。
 */
class DeviceOwnerAdminReceiver : DeviceAdminReceiver() {
    override fun onEnabled(context: Context, intent: Intent) {
        Log.i("OwnerGuard", "admin enabled")
    }

    override fun onDisabled(context: Context, intent: Intent) {
        Log.i("OwnerGuard", "admin disabled")
    }
}
