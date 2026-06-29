package com.yoshi.zaiquotawatch

import android.content.Context

/**
 * クォータ情報（リセット時刻・使用率）を SharedPreferences にキャッシュ。
 * QuotaDataListenerService が DataLayer から取得した値を保存し、Complication が読む。
 */
object QuotaStore {
    private const val PREFS = "zai_quota"
    private const val KEY_RESET = "reset" // "15:55" or "none"
    private const val KEY_PCT = "pct"     // 0-100, -1=未取得
    private const val KEY_UPDATED = "updated"

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
}
