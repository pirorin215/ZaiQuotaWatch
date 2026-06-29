package com.yoshi.zaiquota

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * ランチャー Activity。
 * 通知アクセス権限の状態表示と、キャッシュ（直近に ntfy 通知から取得した値）の表示を行う。
 * データ取得自体は QuotaNotificationListener がバックグラウンドで行う。
 */
class MainActivity : Activity() {

    private lateinit var statusText: TextView
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val timeFmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.JAPAN)

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

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(statusText)
            addView(permButton)
            addView(reloadButton)
        }
        setContentView(layout)
        scope.launch {
            QuotaStore.flow.collect { refreshDisplay() }
        }
    }

    override fun onResume() {
        super.onResume()
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
            append("Mac から ntfy 送信で\nWatch が数秒で更新されます")
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
