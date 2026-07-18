package com.yoshi.zaiquota

import android.content.ComponentName
import android.service.notification.NotificationListenerService
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/**
 * NotificationListenerService の休眠を検知し requestRebind で再活性化する WorkManager。
 *
 * 背景: Android 14+ で NotificationListenerService が App Standby により
 * バックグラウンドでランダムに停止する既知の問題がある。ログから34分間の停止を観測済み。
 *
 * 15分周期で起動し、最後の「③ntfy受信」から20分以上経過していたら
 * （＝Macは5分周期で送るので受信途絶＝リスナー休眠確実）
 * requestRebind() でシステムへ再バインドを要求する。
 */
class ListenerHeartbeatWorker(
    appContext: android.content.Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val lastReceived = DebugLog.getLastTime(applicationContext, TAG_NTFY_RECEIVED)
        val now = System.currentTimeMillis()
        val silentForMin = if (lastReceived > 0) (now - lastReceived) / 60_000 else Long.MAX_VALUE

        return if (silentForMin >= STALE_THRESHOLD_MIN) {
            // リスナー休眠濃厚: requestRebind で再バインド要求
            val cn = ComponentName(applicationContext, QuotaNotificationListener::class.java)
            NotificationListenerService.requestRebind(cn)
            val reason = if (lastReceived == 0L) "受信履歴なし" else "${silentForMin}分間受信なし"
            Log.d(TAG, "requestRebind called ($reason)")
            DebugLog.append(applicationContext, TAG_HEARTBEAT, "🔄 rebind ($reason)")
            Result.success()
        } else {
            // 正常稼働中: 何もしない（無駄な再バインド回避）
            Log.d(TAG, "Listener active (last received ${silentForMin}min ago), skip")
            DebugLog.append(applicationContext, TAG_HEARTBEAT, "⚪ skip (${silentForMin}m)")
            Result.success()
        }
    }

    companion object {
        private const val TAG = "HeartbeatWorker"
        private const val TAG_NTFY_RECEIVED = "③ntfy受信"
        private const val TAG_HEARTBEAT = "⑤ハートビート"
        // Macは5分周期で送信するため、20分以上受信がない＝リスナー休眠確実
        private const val STALE_THRESHOLD_MIN = 20L
    }
}
