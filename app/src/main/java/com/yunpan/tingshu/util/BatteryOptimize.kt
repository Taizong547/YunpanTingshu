package com.yunpan.tingshu.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

/**
 * 电池优化 / 厂商自启动引导（平台 API，UI 层接线）。全部 resolveActivity 守卫，失败给中文说明。
 */
object BatteryOptimize {

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /** 申请忽略电池优化；API 23+。 */
    fun requestIgnoreIntent(context: Context): Intent = Intent(
        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
    ).setData(Uri.parse("package:${context.packageName}"))

    /**
     * 分厂商自启动/后台管理设置页。返回可跳转的 Intent；无可解析页面返回 null。
     */
    fun autostartIntents(): List<Pair<String, Intent>> = listOf(
        "小米/红米" to Intent().setComponent(
            android.content.ComponentName(
                "com.miui.securitycenter",
                "com.miui.permcenter.autostart.AutoStartManagementActivity"
            )
        ),
        "华为" to Intent().setComponent(
            android.content.ComponentName(
                "com.huawei.systemmanager",
                "com.huawei.systemmanager.optimize.process.ProtectActivity"
            )
        ),
        "OPPO" to Intent().setComponent(
            android.content.ComponentName(
                "com.coloros.safecenter",
                "com.coloros.safecenter.permission.startup.StartupAppListActivity"
            )
        ),
        "vivo" to Intent().setComponent(
            android.content.ComponentName(
                "com.vivo.permissionmanager",
                "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"
            )
        ),
        "魅族" to Intent().setComponent(
            android.content.ComponentName(
                "com.meizu.safe",
                "com.meizu.safe.security.SHOW_APPSEC"
            )
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra("packageName", "com.yunpan.tingshu"),
        "三星" to Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.parse("package:com.yunpan.tingshu"))
    )

    /** 返回可在本机跳转的 (说明, Intent) 列表（已 resolveActivity 守卫）。 */
    fun availableAutostartIntents(context: Context): List<Pair<String, Intent>> =
        autostartIntents().filter { (_, i) ->
            runCatching {
                i.resolveActivity(context.packageManager) != null
            }.getOrDefault(false)
        }
}
