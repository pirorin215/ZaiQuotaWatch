package com.yoshi.zaiquota

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * スマホ側で受信した最新のクォータ情報を SharedPreferences にキャッシュする。
 * MainActivity の UI 表示用。save() すると flow も更新され、画面が即座に切り替わる。
 */
object QuotaStore {
    private const val PREFS = "zai_quota_phone"
    private const val KEY_RESET = "reset"
    private const val KEY_PCT = "pct"
    private const val KEY_UPDATED = "updated"

    /** 直近の保存値。NotificationListener が save すると即座に更新される。MainActivity が collect して画面更新。 */
    val flow = MutableStateFlow<Snapshot?>(null)

    data class Snapshot(val reset: String, val pct: Int, val updated: Long)

    fun save(context: Context, reset: String, pct: Int) {
        val now = System.currentTimeMillis()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_RESET, reset)
            .putInt(KEY_PCT, pct)
            .putLong(KEY_UPDATED, now)
            .apply()
        flow.value = Snapshot(reset, pct, now)
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
