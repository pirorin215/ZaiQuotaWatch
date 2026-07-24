package com.yoshi.zaiquota

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * クォータデータ（reset/pct）をパースして Watch へ転送する共通経路。
 *
 * QuotaNotificationListener（ntfy 公式アプリ通知経由）と
 * QuotaPollWorker（ntfy サーバー直接 poll 経由）の両方から利用する。
 * どちらの経路で取得したデータも最終的にここを通って DataLayer へ送られる。
 *
 * メッセージフォーマット（Mac omp_usage.300s.sh が送信）:
 *   "reset=01:19 pct=18 silent=1"     ← ウォッチ要求由来の silent ミラー
 *   "reset=01:19 pct=100"             ← 状態機械による枯渇通知
 *   "pct=5 prev=100 reset=01:19"      ← 回復通知（prev 付き）
 *
 * silent フラグは通知シェードを汚さないよう Toast を抑制する目的のみ。
 * reset が空で pct も無効（-1）の場合はスキップ（無意味なデータ送信を避ける）。
 */
object QuotaRelay {

    private const val TAG = "QuotaRelay"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * メッセージから "key=value" 形式のフィールドを抽出する共通パーサ。
     * ntfy アプリが複数メッセージを1通知へ group 化した場合、bigText に
     * "pct=1 ... pct=5 ... pct=18" のように連結される。最新値（末尾マッチ）を
     * 返さないと最も古い値が採用されてしまうため findAll の最後を採用する。
     */
    fun parseField(message: String, field: String): String =
        Regex("$field=([^\\s]+)").findAll(message).lastOrNull()?.groupValues?.getOrNull(1) ?: ""

    /**
     * reset/pct をパース → QuotaStore へ保存 → DataLayer で Watch へ push。
     * UI スレッドから呼ばれることを想定（Toast 表示のため）。
     *
     * @param message パース対象の生メッセージ
     * @param context Application/Service Context
     * @param sourceTag デバッグログの受信元タグ
     *   （"③ntfy受信" = リスナー、"③poll受信" = フォールバック poll）
     */
    fun parseAndRelay(context: Context, message: String, sourceTag: String) {
        val reset = parseField(message, KEY_RESET)
        // "none" 等のプレースホルダは空扱い（Mac 側で RESET_TIME が取れなかったケース）
        val normalizedReset = if (reset.isEmpty() || reset == "none") "" else reset
        val pct = parseField(message, KEY_PCT).toIntOrNull() ?: -1
        val silent = parseField(message, KEY_SILENT) == "1"

        Log.d(TAG, "Parsed [$sourceTag]: reset='$normalizedReset' pct=$pct silent=$silent")
        DebugLog.append(context, sourceTag, "pct=${pct}% reset=${normalizedReset}${if (silent) " [silent]" else ""}")

        // 両方無効なら送らない（Mac 側の空メッセージ/パース失敗等）
        if (normalizedReset.isEmpty() && pct < 0) {
            Log.d(TAG, "No valid fields in message, skip")
            return
        }

        relayToWatch(context, normalizedReset, pct, silent)
    }

    /**
     * QuotaStore へ保存し DataLayer へ urgent push する。
     * pct=-1（取得失敗）のときは前回値を保持するため保存しない（Watch UI が混乱するのを防ぐ）。
     */
    fun relayToWatch(context: Context, reset: String, pct: Int, silent: Boolean) {
        scope.launch {
            try {
                if (pct >= 0) {
                    QuotaStore.save(context, if (reset.isEmpty()) "" else reset, pct)
                }
                val request = PutDataMapRequest.create(DATA_PATH).apply {
                    if (reset.isNotEmpty()) dataMap.putString(KEY_RESET, reset)
                    dataMap.putInt(KEY_PCT, pct)
                    dataMap.putLong(KEY_TIMESTAMP, System.currentTimeMillis())
                }.asPutDataRequest().setUrgent()
                Wearable.getDataClient(context).putDataItem(request).await()
                Log.d(TAG, "Pushed to Watch: reset=$reset pct=$pct silent=$silent")
                DebugLog.append(context, TAG_WATCH_PUSHED, "✅ pct=${pct}%${if (silent) " [silent]" else ""}")
                // silent はウォッチ要請由来。ユーザー操作のない自動更新なので Toast 抑制
                if (!silent && pct >= 0) {
                    debugToast(context, "✅ ntfy→Watch送信\nreset=$reset pct=${pct}%")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to push to Watch", e)
                DebugLog.append(context, TAG_WATCH_PUSHED, "❌ ${e.javaClass.simpleName}: ${e.message}")
                if (!silent) {
                    debugToast(context, "❌ Watch送信エラー\n${e.message}")
                }
            }
        }
    }

    /** Toast はメインスレッドで出す必要があるため Handler 経由で post。 */
    private fun debugToast(context: Context, message: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }

    const val DATA_PATH = "/zai_quota"
    const val KEY_RESET = "reset"
    const val KEY_PCT = "pct"
    const val KEY_TIMESTAMP = "timestamp"
    const val KEY_SILENT = "silent"

    const val TAG_NTFY_RECEIVED = "③ntfy受信"
    const val TAG_POLL_RECEIVED = "③poll受信"
    const val TAG_WATCH_PUSHED = "④Watch送信"
}
