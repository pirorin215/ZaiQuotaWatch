package com.yoshi.zaiquota

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * デバッグ用の簡易ログリングバッファ（SharedPreferences 永続化）。
 * プロセスをまたいで Service → MainActivity 間で共有し、Pull 経路の各段階の到達状況を切り分ける。
 *   ① Watch要求受信 / ② ntfy POST結果 / ③ ntfy通知検知 / ④ Watch送信完了
 */
object DebugLog {
    private const val PREFS = "zai_quota_debug"
    private const val KEY_ENTRIES = "entries"
    private const val MAX_ENTRIES = 20

    data class Entry(val time: Long, val tag: String, val message: String)

    private fun Context.prefs() =
        getSharedPreferences(PREFS, Context.MODE_PRIVATE)

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
    fun getRecent(context: Context): List<Entry> = readAll(context).reversed()

    /** 指定 tag の最新エントリのタイムスタンプを取得（無ければ 0）。 */
    @Synchronized
    fun getLastTime(context: Context, tag: String): Long =
        readAll(context).filter { it.tag == tag }.maxOfOrNull { it.time } ?: 0L

    @Synchronized
    fun clear(context: Context) {
        context.prefs().edit().clear().apply()
    }

    private fun readAll(context: Context): List<Entry> {
        val json = context.prefs().getString(KEY_ENTRIES, null) ?: return emptyList()
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
        context.prefs().edit().putString(KEY_ENTRIES, arr.toString()).apply()
    }
}
