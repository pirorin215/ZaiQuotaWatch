package com.yoshi.zaiquota

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * デバッグ用の簡易ログリングバッファ。SharedPreferences に永続化する。
 * プロセスをまたいで Service → MainActivity 間で共有し、Pull 経路の各段階の
 * 到達状況を画面で確認できるようにする。
 *
 * 用途: ウォッチからの更新要求が「どこまで届いているか」を切り分ける。
 *   ① Watch要求受信  → RefreshTriggerReceiver
 *   ② ntfy POST結果  → RefreshTriggerReceiver
 *   ③ ntfy通知検知   → QuotaNotificationListener
 *   ④ Watch送信完了  → QuotaNotificationListener
 */
object DebugLog {
    private const val PREFS = "zai_quota_debug"
    private const val KEY_ENTRIES = "entries"
    private const val MAX_ENTRIES = 20

    data class Entry(val time: Long, val tag: String, val message: String)

    /** 1行追加する（MAX超は最古を破棄）。 */
    @Synchronized
    fun append(context: Context, tag: String, message: String) {
        val entries = readAll(context).toMutableList()
        entries.add(Entry(System.currentTimeMillis(), tag, message))
        while (entries.size > MAX_ENTRIES) entries.removeAt(0)
        writeAll(context, entries)
    }

    /** 最新順で全件取得（UI 表示用）。 */
    @Synchronized
    fun getRecent(context: Context): List<Entry> =
        readAll(context).reversed()

    @Synchronized
    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    private fun readAll(context: Context): List<Entry> {
        val json = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_ENTRIES, null) ?: return emptyList()
        return runCatching {
            JSONArray(json).let { arr ->
                (0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i)
                    Entry(o.getLong("time"), o.getString("tag"), o.getString("message"))
                }
            }
        }.getOrElse { emptyList() }
    }

    private fun writeAll(context: Context, entries: List<Entry>) {
        val arr = JSONArray()
        entries.forEach { e ->
            arr.put(JSONObject().put("time", e.time).put("tag", e.tag).put("message", e.message))
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_ENTRIES, arr.toString())
            .apply()
    }
}
