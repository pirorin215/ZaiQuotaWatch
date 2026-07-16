package com.yoshi.zaiquotawatch

import android.app.Activity
import android.content.ComponentName
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import com.google.android.gms.wearable.Wearable
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * コンプリケーションをタップすると起動する Activity。
 * 現在のキャッシュ（QuotaStore）を表示し、「更新」ボタンで requestUpdate を呼ぶ。
 * 起動時にも1回 requestUpdate する（タップ＝更新の意図）。
 *
 * 「更新」は既存キャッシュの再描画だけでなく、Phone → Mac mini へ取得要求を送る Pull トリガー。
 * MessageClient で /trigger_refresh を Phone へ送り、Phone が ntfy コマンドトピックへ転送する。
 * 実際のデータは既存 DataLayer /zai_quota 経路で数秒〜最大5分後に非同期到着し Complication が更新される。
 * （Mac 側は常駐 daemon を持たず、SwiftBar の定期周期で要求を取り込むため最大5分の遅延が生じる）
 */
class QuotaRefreshActivity : Activity() {

    private companion object {
        const val TAG = "QuotaRefreshActivity"
        const val TRIGGER_PATH = "/trigger_refresh"
    }

    private lateinit var infoText: TextView
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.JAPAN)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        infoText = TextView(this).apply {
            textSize = 13f
            setPadding(30, 30, 30, 30)
            gravity = Gravity.CENTER_HORIZONTAL
        }

        val refreshButton = Button(this).apply {
            text = "🔄 更新"
            setOnClickListener { refresh() }
        }

        val closeButton = Button(this).apply {
            text = "閉じる"
            setOnClickListener { finish() }
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(infoText)
            addView(refreshButton)
            addView(closeButton)
        }
        setContentView(layout)

        // タップ＝更新の意図なので起動時に即 requestUpdate
        refresh()
    }

    private fun refresh() {
        val component = ComponentName(this, ZaiQuotaComplicationService::class.java)
        ComplicationDataSourceUpdateRequester.create(this, component).requestUpdateAll()

        val reset = QuotaStore.getReset(this)
        val pct = QuotaStore.getPct(this)
        val updated = QuotaStore.getUpdatedTime(this)
        val updatedStr = if (updated > 0) timeFmt.format(Date(updated)) else "未取得"

        // Phone → Mac mini へ取得要求を送る Pull トリガー（既存キャッシュも再表示）。
        // データは既存 DataLayer /zai_quota 経路で数秒〜最大5分後に非同期到着する。
        sendRefreshTrigger()

        infoText.text = buildString {
            append("ZAI Quota\n\n")
            append("reset: ${if (reset.isEmpty()) "(空)" else reset}\n")
            append("pct: $pct%\n")
            append("受信: $updatedStr\n\n")
            append("🔄 Macへ取得要求を送信\n")
            append("（最大5分で反映）")
        }
    }

    /**
     * 接続済みノード（Phone）へ /trigger_refresh メッセージを送信。
     * 送信結果は UI に反映せずログのみ（データは別経路で非同期到着するため）。
     */
    private fun sendRefreshTrigger() {
        val messageClient = Wearable.getMessageClient(this)
        // 接続先ノード一覧は NodeClient から取得する
        Wearable.getNodeClient(this).connectedNodes.addOnCompleteListener { task ->
            if (!task.isSuccessful) {
                Log.w(TAG, "connectedNodes failed", task.exception)
                return@addOnCompleteListener
            }
            val nodes = task.result
            if (nodes.isNullOrEmpty()) {
                Log.w(TAG, "No connected nodes; trigger not sent")
                return@addOnCompleteListener
            }
            nodes.forEach { node ->
                messageClient.sendMessage(node.id, TRIGGER_PATH, ByteArray(0))
                    .addOnSuccessListener {
                        Log.d(TAG, "Trigger sent to ${node.displayName} (${node.id})")
                    }
                    .addOnFailureListener { e ->
                        Log.w(TAG, "Trigger failed for ${node.id}", e)
                    }
            }
        }
    }
}
