package com.yoshi.zaiquotawatch

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.util.Log
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.SmallImage
import androidx.wear.watchface.complications.data.SmallImageType
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService

/**
 * Z.ai クォータのリセット時刻を Complication に表示。
 * ntfy には直接アクセスせず、QuotaDataListenerService が DataLayer から更新した
 * キャッシュ（QuotaStore）を読んで表示。タップで requestUpdate（最新キャッシュ反映）。
 */
class ZaiQuotaComplicationService : SuspendingComplicationDataSourceService() {

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        Log.d("ZaiQuotaComplication", "onComplicationRequest called, type: ${request.complicationType}")
        if (request.complicationType != ComplicationType.SHORT_TEXT) return null

        val reset = QuotaStore.getReset(this)
        val pct = QuotaStore.getPct(this)
        Log.d("ZaiQuotaComplication", "Retrieved reset='$reset' pct=$pct")
        val iconRes = if (pct == 100) R.drawable.ic_dot_yellow else R.drawable.ic_dot_blue
        val icon = SmallImage.Builder(Icon.createWithResource(this, iconRes), SmallImageType.ICON).build()
        val text = when {
            pct !in 0..100 -> "ZAI …"
            reset.isEmpty() || reset == "none" -> "$pct%"
            else -> "$pct% $reset"
        }
        Log.d("ZaiQuotaComplication", "Built complication text: '$text'")
        val tapIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, QuotaRefreshActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return ShortTextComplicationData.Builder(
            text = PlainComplicationText.Builder(text).build(),
            contentDescription = PlainComplicationText.Builder("Z.ai quota $text").build()
            )
            .setSmallImage(icon)
            .setTapAction(tapIntent)
            .build()
    }

    override fun getPreviewData(type: ComplicationType): ComplicationData? {
        val text = "15:55 解除"
        return ShortTextComplicationData.Builder(
            text = PlainComplicationText.Builder(text).build(),
            contentDescription = PlainComplicationText.Builder("Z.ai quota reset preview").build()
        ).build()
    }
}
