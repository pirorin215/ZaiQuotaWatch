package com.yoshi.zaiquota

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * ランチャー Activity。
 * 通知アクセス権限の状態表示と、キャッシュ（直近に ntfy 通知から取得した値）の表示を行う。
 * データ取得自体は QuotaNotificationListener がバックグラウンドで行う。
 */
class MainActivity : Activity() {

    private lateinit var statusText: TextView
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val timeFmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.JAPAN)

    private companion object {
        const val HEARTBEAT_WORK_NAME = "listener_heartbeat"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        statusText = TextView(this).apply {
            textSize = 13f
            setPadding(40, 40, 40, 40)
        }

        val permButton = Button(this).apply {
            text = "通知へのアクセス設定を開く"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
        }

        val reloadButton = Button(this).apply {
            text = "キャッシュ再表示"
            setOnClickListener { refreshDisplay() }
        }

        val clearLogButton = Button(this).apply {
            text = "ログを消去"
            setOnClickListener {
                DebugLog.clear(this@MainActivity)
                refreshDisplay()
            }
        }

        val copyLogButton = Button(this).apply {
            text = "ログをコピー"
            setOnClickListener {
                val logs = DebugLog.getRecent(this@MainActivity)
                val text = if (logs.isEmpty()) {
                    "(ログなし)"
                } else {
                    logs.joinToString("\n") { e ->
                        "${timeFmt.format(Date(e.time))} ${e.tag} ${e.message}"
                    }
                }
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("ZaiQuotaWatch ログ", text))
                Toast.makeText(this@MainActivity, "ログをコピーしました（${logs.size}件）", Toast.LENGTH_SHORT).show()
            }
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(statusText)
            addView(permButton)
            addView(reloadButton)
            addView(clearLogButton)
            addView(copyLogButton)
        }
        setContentView(layout)
        scope.launch {
            QuotaStore.flow.collect { refreshDisplay() }
        }
        scheduleHeartbeat()
    }

    /**
     * NotificationListenerService の休眠対策。
     * 15分周期の WorkManager で requestRebind を呼びリスナーを再活性化する。
     * Unique Periodic Work で多重登録を防ぐ（既存があれば保持）。
     */
    private fun scheduleHeartbeat() {
        val request = PeriodicWorkRequestBuilder<ListenerHeartbeatWorker>(
            15, TimeUnit.MINUTES
        ).build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            HEARTBEAT_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }

    override fun onResume() {
        super.onResume()
        // フォアグラウンド化で保留キューが解除されるケースを後から追えるよう、
        // 起動マーカーを記録。この直後に③④が続いていればバックグラウンド遅延の証拠。
        DebugLog.append(this, "📱アプリ起動", "onResume")
        refreshDisplay()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
    private fun refreshDisplay() {
        val enabled = isNotificationListenerEnabled()
        val reset = QuotaStore.getReset(this)
        val pct = QuotaStore.getPct(this)
        val updated = QuotaStore.getUpdatedTime(this)
        val updatedStr = if (updated > 0) timeFmt.format(Date(updated)) else "未取得"
        val logs = DebugLog.getRecent(this)

        statusText.text = buildString {
            append("ZAI Quota Relay\n\n")
            append("【通知アクセス】\n")
            append("  ${if (enabled) "✅ 許可済み" else "❌ 未許可（下のボタンで許可）"}\n\n")
            append("【キャッシュ】\n")
            append("  reset: ${if (reset.isEmpty()) "(空)" else reset}\n")
            append("  pct  : $pct%\n")
            append("  更新 : $updatedStr\n\n")
            append("【経路】\n")
            append("  ntfyアプリ通知 →\n")
            append("  NotificationListener →\n")
            append("  DataLayer → Watch\n\n")
            append("【ログ】\n")
            if (logs.isEmpty()) {
                append("  (なし)\n")
            } else {
                logs.forEach { e ->
                    append("  ${timeFmt.format(Date(e.time))} ${e.tag} ${e.message}\n")
                }
            }
            append("\nMac から ntfy 送信で\nWatch が数秒で更新されます")
        }
    }

    /** 自アプリの NotificationListenerService が有効化されているか。 */
    private fun isNotificationListenerEnabled(): Boolean {
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        if (flat.isNullOrEmpty()) return false
        val cn = ComponentName(this, QuotaNotificationListener::class.java)
        return TextUtils.split(flat, ":").any { ComponentName.unflattenFromString(it) == cn }
    }
}
