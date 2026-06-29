package com.yoshi.zaiquotawatch

import android.app.Activity
import android.content.ComponentName
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * コンプリケーションをタップすると起動する Activity。
 * 現在のキャッシュ（QuotaStore）を表示し、「更新」ボタンで requestUpdate を呼ぶ。
 * 起動時にも1回 requestUpdate する（タップ＝更新の意図）。
 */
class QuotaRefreshActivity : Activity() {

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

        infoText.text = buildString {
            append("ZAI Quota\n\n")
            append("reset: ${if (reset.isEmpty()) "(空)" else reset}\n")
            append("pct: $pct%\n")
            append("受信: $updatedStr\n\n")
            append("🔄 コンプリケーション更新")
        }
    }
}
