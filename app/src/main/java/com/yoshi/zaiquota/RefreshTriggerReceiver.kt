package com.yoshi.zaiquota

import android.util.Log
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * Watch からの更新要求 (/trigger_refresh) を受け取り、ntfy の要求待機トピックへ転送する。
 *
 * シーケンス:
 *   Watch の「更新」タップ → MessageClient /trigger_refresh
 *   → 本サービスが受信
 *   → ntfy.sh/claude-code-notice215-watchpoll へ POST "refresh"
 *   → （要求はここで待機。Mac の SwiftBar 次回周期起動が poll で取得）
 *   → omp_usage.300s.sh がクォータを取得しデータトピックへ silent 送信
 *   → (既存経路) ntfy 公式アプリ通知 → QuotaNotificationListener → DataLayer → Watch
 *
 * 要求待機トピックは Phone の ntfy アプリが購読していないため通知シェードは汚れない。
 * Mac 側に常駐 daemon は持たず、SwiftBar の定期周期（最大5分）で要求を取り込む設計。
 */
class RefreshTriggerReceiver : WearableListenerService() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onMessageReceived(event: MessageEvent) {
        if (event.path != TRIGGER_PATH) return
        Log.d(TAG, "Received refresh trigger from ${event.sourceNodeId}")
        DebugLog.append(applicationContext, "①Watch要求", "from ${event.sourceNodeId.takeLast(8)}")

        scope.launch {
            postToNtfy()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    /**
     * ntfy の要求待機トピックへ更新要求メッセージを POST する。
     * Mac 側の SwiftBar プラグインが次回周期で poll 取得し omp を取得する。
     */
    private fun postToNtfy() {
        var conn: HttpURLConnection? = null
        try {
            conn = (URL("$WATCHPOLL_TOPIC_URL").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 10_000
                readTimeout = 10_000
                doOutput = true
                setRequestProperty("X-Title", "🔄 ウォッチから更新要求")
                setRequestProperty("Content-Type", "text/plain; charset=UTF-8")
            }
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { writer ->
                writer.write("refresh")
                writer.flush()
            }
            val code = conn.responseCode
            if (code in 200..299) {
                Log.d(TAG, "Posted refresh request to ntfy watchpoll topic (HTTP $code)")
                DebugLog.append(applicationContext, "②ntfy送信", "OK HTTP $code")
            } else {
                Log.w(TAG, "ntfy POST failed: HTTP $code")
                DebugLog.append(applicationContext, "②ntfy送信", "❌ HTTP $code")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to post refresh request to ntfy", e)
            DebugLog.append(applicationContext, "②ntfy送信", "❌ ${e.javaClass.simpleName}: ${e.message}")
        } finally {
            conn?.disconnect()
        }
    }

    companion object {
        private const val TAG = "RefreshTriggerReceiver"
        const val TRIGGER_PATH = "/trigger_refresh"
        private const val WATCHPOLL_TOPIC_URL = "https://ntfy.sh/claude-code-notice215-watchpoll"
    }
}
