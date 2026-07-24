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
 * 15分周期で起動し、最後の「③ntfy受信」or「③poll受信」から20分以上経過していたら
 * （＝Macは5分周期で送るので受信途絶＝リスナー休眠確実）
 * requestRebind() でシステムへ再バインドを要求する。
 *
 * poll受信（QuotaPollWorker のフォールバック）も「データは届いている」証拠なので
 * これを含めて判定する。リスナー経路が死んで poll 経路だけで受けている間も
 * rebind を試み続ける（poll はあくまで補完で、リアルタイム性はリスナー経路が主役）。
 */
class ListenerHeartbeatWorker(
    appContext: android.content.Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        // リスナー経路（③ntfy受信）と poll フォールバック（③poll受信）の新しい方を採用
        val lastListener = DebugLog.getLastTime(applicationContext, TAG_NTFY_RECEIVED)
        val lastPoll = DebugLog.getLastTime(applicationContext, TAG_POLL_RECEIVED)
        val lastReceived = maxOf(lastListener, lastPoll)
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
        // QuotaRelay で定義したログタグと同期
        private const val TAG_NTFY_RECEIVED = QuotaRelay.TAG_NTFY_RECEIVED
        private const val TAG_POLL_RECEIVED = QuotaRelay.TAG_POLL_RECEIVED
        private const val TAG_HEARTBEAT = "⑤ハートビート"
        // Macは5分周期で送信するため、20分以上受信がない＝リスナー休眠確実
        private const val STALE_THRESHOLD_MIN = 20L
    }
}
