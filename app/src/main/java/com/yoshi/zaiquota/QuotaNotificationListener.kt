package com.yoshi.zaiquota

import android.app.Notification
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import android.widget.Toast
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * ntfy 公式アプリの通知を検知 → reset/pct をパース → DataLayer で Watch へ転送。
 *
 * シーケンス:
 *   Mac mini が ntfy に POST
 *   → ntfy 公式アプリが通知を受けて表示
 *   → 本サービスがその通知を即時検知 (NotificationListenerService)
 *   → reset/pct をパース
 *   → DataLayer /zai_quota (setUrgent) で Watch へ push
 *
 * 従来の 60分ポーリング (QuotaFetchWorker) に代わるリアルタイム経路。
 * 動作には「通知へのアクセス」権限の許可が必須 (MainActivity で案内)。
 */
class QuotaNotificationListener : NotificationListenerService() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val n = sbn ?: return
        // ntfy 公式アプリの通知だけ処理（F-Droid 版 io.heckel.ntfy / Play 版 io.heckel.ntfy.play 両対応）
        if (!n.packageName.startsWith(NTFY_PKG_PREFIX)) return

        val extras = n.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty()
        val fullText = listOf(title, text, bigText).joinToString(" ")
        Log.d(TAG, "ntfy notification: title='$title' text='$text' big='$bigText'")

        val reset = parseField(fullText, KEY_RESET)
        val pct = parseField(fullText, KEY_PCT).toIntOrNull() ?: -1
        Log.d(TAG, "Parsed: reset='$reset' pct=$pct")

        if (reset.isEmpty()) {
            Log.d(TAG, "No reset field in notification, skip")
            return
        }

        scope.launch {
            try {
                QuotaStore.save(applicationContext, reset, pct)
                val request = PutDataMapRequest.create(DATA_PATH).apply {
                    dataMap.putString(KEY_RESET, reset)
                    dataMap.putInt(KEY_PCT, pct)
                    dataMap.putLong(KEY_TIMESTAMP, System.currentTimeMillis())
                }.asPutDataRequest().setUrgent()
                Wearable.getDataClient(applicationContext).putDataItem(request).await()
                Log.d(TAG, "Pushed to Watch: reset=$reset pct=$pct")
                debugToast("✅ ntfy通知→Watch送信\nreset=$reset pct=${pct}%")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to push to Watch", e)
                debugToast("❌ Watch送信エラー\n${e.message}")
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun parseField(message: String, field: String): String =
        Regex("$field=([^\\s]+)").find(message)?.groupValues?.getOrNull(1) ?: ""

    /** onNotificationPosted はメインスレッド呼出だが、Toast はそのまま出せる。 */
    private fun debugToast(message: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show()
        }
    }

    companion object {
        private const val TAG = "QuotaNtfyListener"
        private const val NTFY_PKG_PREFIX = "io.heckel.ntfy"
        const val DATA_PATH = "/zai_quota"
        const val KEY_RESET = "reset"
        const val KEY_PCT = "pct"
        const val KEY_TIMESTAMP = "timestamp"
    }
}
