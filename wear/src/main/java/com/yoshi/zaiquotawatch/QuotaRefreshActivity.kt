package com.yoshi.zaiquotawatch

import android.app.Activity
import android.content.ComponentName
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
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
        const val POLL_INTERVAL_MS = 2000L
        const val POLL_TIMEOUT_MS = 5 * 60 * 1000L
    }

    private lateinit var infoText: TextView
    private lateinit var statusText: TextView
    private lateinit var refreshButton: Button
    private lateinit var pullIndicator: LinearLayout
    private lateinit var pullText: TextView
    private lateinit var scroll: PullToRefreshScrollView
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.JAPAN)
    private val handler = Handler(Looper.getMainLooper())

    /** 現在リフレッシュ要求を送信済みでデータ到着を待っている状態か。 */
    private var awaitingData = false
    /** リフレッシュ要求送信時点での QuotaStore 更新時刻。これより新しければ「新着あり」。 */
    private var requestedAtStoreTime = 0L
    /** データ到着監視ポーリングの開始時刻。タイムアウト判定に使う。 */
    private var pollStartedAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 円形 Wear 画面での両端の丸まりを考慮し、十分な左右 padding を確保。
        val hPad = 48
        val vPad = 24

        // 主要情報（reset/pct/受信）。更新ボタンの上に来る。
        infoText = TextView(this).apply {
            textSize = 13f
            setPadding(0, vPad, 0, vPad)
            gravity = Gravity.CENTER_HORIZONTAL
            setLineSpacing(4f, 1f)
        }

        refreshButton = Button(this).apply {
            text = "🔄 更新"
            setOnClickListener { refresh() }
        }

        // 補足情報（送信結果・受信状態など）。更新ボタンの下、補助的な扱い。
        statusText = TextView(this).apply {
            textSize = 11f
            setPadding(0, vPad, 0, vPad)
            gravity = Gravity.CENTER_HORIZONTAL
            alpha = 0.7f
            setLineSpacing(3f, 1f)
        }

        // 中央寄せ: gravity=CENTER で縦方向も画面中央に配置されるため、
        // 更新ボタンが概ね画面どまんなかに来る。
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(hPad, 0, hPad, 0)
            addView(infoText)
            addView(refreshButton)
            addView(statusText)
        }

        // プル中に上端に表示されるインジケータ（スピナー＋テキスト）。
        pullText = TextView(this).apply {
            text = "↓ 引っ張って更新"
            textSize = 11f
            gravity = Gravity.CENTER
            setTextColor(Color.LTGRAY)
        }
        val pullSpinner = ProgressBar(this).apply {
            val size = (24 * resources.displayMetrics.density).toInt()
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                gravity = Gravity.CENTER
                marginEnd = (6 * resources.displayMetrics.density).toInt()
            }
        }
        pullIndicator = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            visibility = View.GONE
            setPadding(0, vPad, 0, vPad)
            addView(pullSpinner)
            addView(pullText)
        }

        scroll = PullToRefreshScrollView(this).apply {
            isFillViewport = true
            indicator = pullIndicator
            onRefresh = { refresh() }
            addView(content)
        }

        // 下部に固定の閉じ方ヒント（スクロール領域の外にあるため絶対に見切れない）。
        // Wear 標準の「画面左端からのスワイプで finish()」はテーマ標準挙動で有効。
        val hint = TextView(this).apply {
            text = "← スワイプで閉じる"
            textSize = 11f
            gravity = Gravity.CENTER
            setPadding(0, vPad, 0, vPad)
            alpha = 0.7f
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(pullIndicator)
            addView(
                scroll,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    0,
                    1f
                )
            )
            addView(hint)
        }
        setContentView(root)

        // タップ＝更新の意図なので起動時に即 requestUpdate
        refresh()
    }

    private fun refresh() {
        val component = ComponentName(this, ZaiQuotaComplicationService::class.java)
        ComplicationDataSourceUpdateRequester.create(this, component).requestUpdateAll()

        // 主要情報（キャッシュ）を再描画
        renderInfo()

        // 送信中はボタン・ステータスで「要求中」を示す
        refreshButton.isEnabled = false
        refreshButton.text = "⏳ 送信中…"
        statusText.text = "Macへ取得要求を送信中…"

        // Phone → Mac mini へ取得要求を送る Pull トリガー。
        // 送信結果コールバックで「送信完了/失敗」を即時フィードバックする。
        sendRefreshTrigger { result ->
            when (result) {
                TriggerResult.SENT -> {
                    // 送信成功: データ到着を監視開始
                    requestedAtStoreTime = QuotaStore.getUpdatedTime(this)
                    awaitingData = true
                    pollStartedAt = System.currentTimeMillis()
                    statusText.text = "✓ 要求送信完了\nデータ到着を待っています…"
                    startPollingForData()
                }
                TriggerResult.NO_NODES -> {
                    statusText.text = "⚠ Phone未接続\nペアリングを確認してください"
                }
                TriggerResult.FAILED -> {
                    statusText.text = "⚠ 送信失敗\nもう一度お試しください"
                }
            }
            // プルトゥリフレッシュのインジケータを解除
            scroll.completeRefresh()
            refreshButton.isEnabled = true
            refreshButton.text = "🔄 更新"
        }
    }

    /** 主要情報（reset/pct/受信）をキャッシュから描画。 */
    private fun renderInfo() {
        val reset = QuotaStore.getReset(this)
        val pct = QuotaStore.getPct(this)
        val updated = QuotaStore.getUpdatedTime(this)
        val updatedStr = if (updated > 0) timeFmt.format(Date(updated)) else "未取得"

        infoText.text = buildString {
            append("ZAI Quota\n")
            append("reset: ${if (reset.isEmpty()) "(空)" else reset}\n")
            append("pct: $pct%\n")
            append("受信: $updatedStr")
        }
    }

    /**
     * データ到着を監視するポーリング。
     * QuotaStore の更新時刻が要求時点より新しくなったら「新着あり」として即時反映。
     * タイムアウト（5分）で待機終了。
     */
    private fun startPollingForData() {
        if (!awaitingData) return
        val currentUpdated = QuotaStore.getUpdatedTime(this)
        if (currentUpdated > requestedAtStoreTime) {
            // 新着あり
            awaitingData = false
            renderInfo()
            statusText.text = "✓ 新着データを受信"
            return
        }
        if (System.currentTimeMillis() - pollStartedAt > POLL_TIMEOUT_MS) {
            // タイムアウト: 到着を通知せず待機終了（次回起動時に反映される）
            awaitingData = false
            statusText.text = "（タイムアウト: 後ほど再確認）"
            return
        }
        handler.postDelayed({ startPollingForData() }, POLL_INTERVAL_MS)
    }

    override fun onDestroy() {
        super.onDestroy()
        // Activity 破棄時はポーリングを停止（リーク防止）
        awaitingData = false
        handler.removeCallbacksAndMessages(null)
    }

    /** 送信結果のフィードバック用。 */
    private enum class TriggerResult { SENT, NO_NODES, FAILED }

    /**
     * 接続済みノード（Phone）へ /trigger_refresh メッセージを送信。
     * 送信結果（少なくとも1ノードへの送信成功）を [onResult] で UI に反映する。
     * データ本体は別経路（DataLayer /zai_quota）で非同期到着する。
     */
    private fun sendRefreshTrigger(onResult: (TriggerResult) -> Unit) {
        val messageClient = Wearable.getMessageClient(this)
        // 接続先ノード一覧は NodeClient から取得する
        Wearable.getNodeClient(this).connectedNodes.addOnCompleteListener { task ->
            if (!task.isSuccessful) {
                Log.w(TAG, "connectedNodes failed", task.exception)
                onResult(TriggerResult.FAILED)
                return@addOnCompleteListener
            }
            val nodes = task.result
            if (nodes.isNullOrEmpty()) {
                Log.w(TAG, "No connected nodes; trigger not sent")
                onResult(TriggerResult.NO_NODES)
                return@addOnCompleteListener
            }
            // 各ノードへ送信し、いずれかが成功すれば SENT 扱い
            var anySent = false
            var settled = false
            nodes.forEach { node ->
                messageClient.sendMessage(node.id, TRIGGER_PATH, ByteArray(0))
                    .addOnSuccessListener {
                        Log.d(TAG, "Trigger sent to ${node.displayName} (${node.id})")
                        if (!settled) {
                            settled = true
                            anySent = true
                            onResult(TriggerResult.SENT)
                        }
                    }
                    .addOnFailureListener { e ->
                        Log.w(TAG, "Trigger failed for ${node.id}", e)
                        // 全ノード失敗時のみ FAILED。簡易判定: 最後まで成功がなければ FAILED。
                        // 並び順の都合で後続成功が上書きする可能性があるため、
                        // ここでは失敗を即 FAILED にせず、短遅延で確定させる。
                        handler.postDelayed({
                            if (!settled) {
                                settled = true
                                onResult(if (anySent) TriggerResult.SENT else TriggerResult.FAILED)
                            }
                        }, 500L)
                    }
            }
        }
    }
}
