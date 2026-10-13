package com.yunpan.tingshu.player

import android.content.Context
import android.net.wifi.WifiManager
import android.os.PowerManager

/**
 * 真机锁集合：PARTIAL_WAKE_LOCK + WiFiLock(FULL_HIGH_PERF)。
 * 播放/缓冲时持有，避免息屏后 CPU/WiFi 休眠导致断流；暂停/停止/释放时释放。
 */
class SystemLockSet(context: Context) : LockSet {

    private val app = context.applicationContext
    private val wakeLock: PowerManager.WakeLock =
        (app.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "yunpan:partial")
            .apply { setReferenceCounted(true) }

    private val wifiLock: WifiManager.WifiLock =
        (app.getSystemService(Context.WIFI_SERVICE) as WifiManager)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "yunpan:wifi")
            .apply { setReferenceCounted(true) }

    override fun acquire() {
        try { if (!wakeLock.isHeld) wakeLock.acquire(30 * 60 * 1000L /*30min safety*/) } catch (_: Exception) {}
        try { if (!wifiLock.isHeld) wifiLock.acquire() } catch (_: Exception) {}
    }

    override fun release() {
        try { if (wakeLock.isHeld) wakeLock.release() } catch (_: Exception) {}
        try { if (wifiLock.isHeld) wifiLock.release() } catch (_: Exception) {}
    }

    override fun isHeld(): Boolean =
        try { wakeLock.isHeld || wifiLock.isHeld } catch (_: Exception) { false }
}
