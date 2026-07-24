package com.yoshi.zaiquotawatch

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.util.Log
import androidx.wear.watchface.complications.data.ColorRamp
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.RangedValueComplicationData
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.data.SmallImage
import androidx.wear.watchface.complications.data.SmallImageType
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService

/**
 * Z.ai クォータを Complication に表示。
 *
 * 表示要素: リングゲージ（使用率）＋ リセット時刻テキスト（常時）。
 * - RANGED_VALUE: 文字盤の円形/リングスロットで使用率をゲージ描画（Pixel Watch
 *   バッテリーと同じ仕組み）。値の方向は「使用率そのまま」。
 *     - 通常時: value = pct（満タン=枯渇間近、空=余裕）
 *     - 枯渇時: value = 100 - 残り分数/300*100（残り時間が少ないほど満タン＝もうすぐ解除）
 *   リング中央のテキストにはリセット時刻を常に表示する（ない時は --:--）。
 * - SHORT_TEXT: 四角スロット等のフォールバック。リセット時刻のみ（ゲージ非表示）。
 *
 * ntfy には直接アクセスせず、QuotaDataListenerService が DataLayer から更新した
 * キャッシュ（QuotaStore）を読んで表示。タップで QuotaRefreshActivity を起動。
 */
class ZaiQuotaComplicationService : SuspendingComplicationDataSourceService() {

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        Log.d(TAG, "onComplicationRequest type=${request.complicationType}")
        val state = QuotaStore.gaugeState(this)
        val reset = QuotaStore.getReset(this)
        Log.d(TAG, "state=$state reset='$reset'")

        // 表示テキストは常にリセット時刻（枯渇時は＝解除時刻）。未設定時のみフォールバック。
        val resetText = resetDisplay(reset)
        val tapIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, QuotaRefreshActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        return when (request.complicationType) {
            ComplicationType.RANGED_VALUE -> {
                if (state.value < 0) {
                    // 未取得時はプレースホルダ表示に任せる
                    return null
                }
                RangedValueComplicationData.Builder(
                    value = state.value.toFloat(),
                    min = 0f,
                    max = 100f,
                    contentDescription = PlainComplicationText.Builder(contentDesc(state, resetText)).build()
                )
                    .setText(PlainComplicationText.Builder(resetText).build())
                    .setColorRamp(RAMP)
                    .setTapAction(tapIntent)
                    .build()
            }
            ComplicationType.SHORT_TEXT -> {
                ShortTextComplicationData.Builder(
                    text = PlainComplicationText.Builder(resetText).build(),
                    contentDescription = PlainComplicationText.Builder(contentDesc(state, resetText)).build()
                )
                    .setSmallImage(smallImage(state))
                    .setTapAction(tapIntent)
                    .build()
            }
            else -> null
        }
    }

    override fun getPreviewData(type: ComplicationType): ComplicationData? = when (type) {
        ComplicationType.RANGED_VALUE -> RangedValueComplicationData.Builder(
            value = 80f,
            min = 0f,
            max = 100f,
            contentDescription = PlainComplicationText.Builder("ZAI quota reset 01:19").build()
        )
            .setText(PlainComplicationText.Builder("01:19").build())
            .setColorRamp(RAMP)
            .build()
        ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(
            text = PlainComplicationText.Builder("01:19").build(),
            contentDescription = PlainComplicationText.Builder("ZAI quota reset preview").build()
        ).build()
        else -> null
    }

    /** リセット時刻表示文字列。空/none/未取得は --:-- 。 */
    private fun resetDisplay(reset: String): String =
        if (reset.isBlank() || reset.equals("none", ignoreCase = true)) "--:--" else reset

    private fun contentDesc(state: QuotaStore.GaugeState, resetText: String): String =
        if (state.isDepleted) "ZAI quota exhausted, reset $resetText"
        else "ZAI quota reset $resetText"

    /** 枯渇時は黄ドット、通常時は青ドット。SHORT_TEXT 専用。 */
    private fun smallImage(state: QuotaStore.GaugeState): SmallImage {
        val res = if (state.isDepleted) R.drawable.ic_dot_yellow else R.drawable.ic_dot_blue
        return SmallImage.Builder(Icon.createWithResource(this, res), SmallImageType.ICON).build()
    }

    private companion object {
        const val TAG = "ZaiQuotaComplication"
        // リング色ランプ: value=min(0)=空き=青、value=max(100)=枯渇間近=赤。
        // RANGED_VALUE では最初の色が min、最後の色が max に対応する。
        val RAMP = ColorRamp(
            intArrayOf(0xFF4285F4.toInt(), 0xFFFBBC04.toInt(), 0xFFEA4335.toInt()),
            interpolated = true
        )
    }
}
