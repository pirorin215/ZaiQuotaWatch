package com.yoshi.zaiquotawatch

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.util.Log
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.LongTextComplicationData
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.data.SmallImage
import androidx.wear.watchface.complications.data.SmallImageType
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService

/**
 * Z.ai クォータを Complication に表示。
 *
 * 表示要素: リセット時刻テキスト ＋ 色付きドット（枯渇=赤、通常=青）。
 *
 * 設計メモ（なぜ RANGED_VALUE を使わないか）:
 * - RANGED_VALUE（リングゲージ型）を使うと、Pixel Watch 標準など多くの Watchface が
 *   ColorRamp を無視し、さらに MonochromaticImage を白で tint して「バッテリー風の
 *   統一デザイン」として描画してしまう。このとき ColorRamp も SmallImage の色も
 *   Watchface 側のテーマ色（多くは白）で上書きされ、枯渇状態を色で伝えられない。
 * - SHORT_TEXT であれば SmallImage の色がそのまま描画されるため、確実に青/赤を
 *   出せる。使用率の量的表示は Activity 側（タップ後の画面）のゲージバーに任せる。
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
            ComplicationType.SHORT_TEXT -> {
                ShortTextComplicationData.Builder(
                    text = PlainComplicationText.Builder(resetText).build(),
                    contentDescription = PlainComplicationText.Builder(contentDesc(state, resetText)).build()
                )
                    .setSmallImage(smallImage(state))
                    .setTapAction(tapIntent)
                    .build()
            }
            ComplicationType.LONG_TEXT -> {
                // 大きなテキスト領域を持つスロット用。時刻を大きく表示したい場合に選んでもらう。
                LongTextComplicationData.Builder(
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
        ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(
            text = PlainComplicationText.Builder("1:19").build(),
            contentDescription = PlainComplicationText.Builder("ZAI quota reset preview").build()
        )
            .setSmallImage(
                SmallImage.Builder(
                    Icon.createWithResource(this, R.drawable.ic_dot_blue),
                    SmallImageType.ICON
                ).build()
            )
            .build()
        ComplicationType.LONG_TEXT -> LongTextComplicationData.Builder(
            text = PlainComplicationText.Builder("1:19").build(),
            contentDescription = PlainComplicationText.Builder("ZAI quota reset preview").build()
        )
            .setSmallImage(
                SmallImage.Builder(
                    Icon.createWithResource(this, R.drawable.ic_dot_blue),
                    SmallImageType.ICON
                ).build()
            )
            .build()
        else -> null
    }

    /**
     * リセット時刻表示文字列。空/none/未取得は --:--。
     * 先頭ゼロは抜いて文字数を削り、Complication スロット内で相対的に大きく
     * 描かれるようにする（例: "01:19" → "1:19", "09:05" → "9:05"）。
     */
    private fun resetDisplay(reset: String): String {
        if (reset.isBlank() || reset.equals("none", ignoreCase = true)) return "--:--"
        val parts = reset.split(":")
        if (parts.size != 2) return reset
        val h = parts[0].toIntOrNull()?.toString() ?: parts[0]
        return "$h:${parts[1]}"
    }

    private fun contentDesc(state: QuotaStore.GaugeState, resetText: String): String =
        if (state.isDepleted) "ZAI quota exhausted, reset $resetText"
        else "ZAI quota reset $resetText"

    /** 枯渇時は赤ドット（RAMP_RED と同色）、通常時は青ドット。 */
    private fun smallImage(state: QuotaStore.GaugeState): SmallImage {
        val res = if (state.isDepleted) R.drawable.ic_dot_red else R.drawable.ic_dot_blue
        return SmallImage.Builder(Icon.createWithResource(this, res), SmallImageType.ICON).build()
    }

    private companion object {
        const val TAG = "ZaiQuotaComplication"
    }
}
