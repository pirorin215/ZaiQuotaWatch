package com.yoshi.zaiquotawatch

import android.content.Context
import java.util.Calendar

/**
 * クォータ情報（リセット時刻・使用率）を SharedPreferences にキャッシュ。
 * QuotaDataListenerService が DataLayer から取得した値を保存し、Complication が読む。
 */
object QuotaStore {
    private const val PREFS = "zai_quota"
    private const val KEY_RESET = "reset" // "15:55" or "none"
    private const val KEY_PCT = "pct"     // 0-100, -1=未取得
    private const val KEY_UPDATED = "updated"

    /** 枯渇解除までの最大待ち時間（5時間=300分）。ゲージ満タン基準。 */
    const val MAX_RESET_MINUTES = 5 * 60

    fun save(context: Context, reset: String, pct: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_RESET, reset)
            .putInt(KEY_PCT, pct)
            .putLong(KEY_UPDATED, System.currentTimeMillis())
            .apply()
    }

    fun getReset(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_RESET, "") ?: ""

    fun getPct(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_PCT, -1)

    fun getUpdatedTime(context: Context): Long =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(KEY_UPDATED, 0L)

    /**
     * reset が "HH:mm" 形式なら現在時刻からの残り分数（0以上・日跨ぎ考慮）を返す。
     * それ以外（空/"none"/パース失敗）は null。
     */
    fun minutesUntilReset(reset: String, now: Long = System.currentTimeMillis()): Int? {
        if (reset.isBlank() || reset.equals("none", ignoreCase = true)) return null
        val parts = reset.split(":")
        if (parts.size != 2) return null
        val h = parts[0].toIntOrNull() ?: return null
        val m = parts[1].toIntOrNull() ?: return null
        if (h !in 0..23 || m !in 0..59) return null

        val nowCal = Calendar.getInstance().apply { timeInMillis = now }
        val target = (nowCal.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, h)
            set(Calendar.MINUTE, m)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (target.timeInMillis <= nowCal.timeInMillis) {
            target.add(Calendar.DAY_OF_MONTH, 1)
        }
        val diffMin = ((target.timeInMillis - nowCal.timeInMillis) / 60000L).toInt()
        return diffMin.coerceAtLeast(0)
    }

    /**
     * Complication / Activity ゲージ描画用の統一状態。
     *
     * @param value 0..100 のゲージ値（使用率方向）。未取得時は -1。
     *   - 通常時: pct そのまま（満タン=枯渇間近、空=余裕）
     *   - 枯渇時: 100 - (残り分数/300*100)（残り少ないほど満タン＝もうすぐ解除）
     * @param isDepleted 枯渇中（pct==100 かつ reset 有効）か。
     */
    data class GaugeState(val value: Int, val isDepleted: Boolean)

    /**
     * キャッシュから現在のゲージ状態を計算。
     * pct < 0（未取得）は value=-1 で返す（呼び側で未描画扱い）。
     */
    fun gaugeState(context: Context): GaugeState {
        val pct = getPct(context)
        val reset = getReset(context)
        if (pct < 0) return GaugeState(-1, false)

        val minToReset = minutesUntilReset(reset)
        val depleted = pct == 100 && minToReset != null
        return if (depleted) {
            val ratio = (minToReset!!.coerceIn(0, MAX_RESET_MINUTES).toDouble() / MAX_RESET_MINUTES * 100).toInt()
            val v = (100 - ratio).coerceIn(0, 100)
            GaugeState(v, true)
        } else {
            GaugeState(pct.coerceIn(0, 100), false)
        }
    }
}

