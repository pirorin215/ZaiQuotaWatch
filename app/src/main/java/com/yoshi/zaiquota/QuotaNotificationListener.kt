package com.yoshi.zaiquota

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

/**
 * ntfy 公式アプリの通知を検知 → reset/pct をパース → DataLayer で Watch へ転送。
 *
 * シーケンス:
 *   Mac mini が ntfy に POST
 *   → ntfy 公式アプリが通知を受けて表示
 *   → 本サービスがその通知を即時検知 (NotificationListenerService)
 *   → reset/pct をパース
 *   → DataLayer /zai_quota (setUrgent) で Watch へ push
 *
 * 従来の 60分ポーリング (QuotaFetchWorker) に代わるリアルタイム経路。
 * 動作には「通知へのアクセス」権限の許可が必須 (MainActivity で案内)。
 *
 * NotificationListener の取りこぼし（ntfy アプリの通知 group 化・update 後の
 * onNotificationPosted 不発等）を補うため、QuotaPollWorker が5分周期で
 * ntfy サーバーへ直接 poll し、本リスナーと同じ QuotaRelay.relayToWatch 経路へ
 * データを流すフォールバック経路も併用する。
 */
class QuotaNotificationListener : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val n = sbn ?: return
        // ntfy 公式アプリの通知だけ処理（F-Droid 版 io.heckel.ntfy / Play 版 io.heckel.ntfy.play 両対応）
        if (!n.packageName.startsWith(NTFY_PKG_PREFIX)) return

        val extras = n.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty()
        val fullText = listOf(title, text, bigText).joinToString(" ")
        Log.d(TAG, "ntfy notification: title='$title' text='$text' big='$bigText'")

        // silent=1 はウォッチ要請由来のデータ通知。ユーザー向けではないので即キャンセル。
        // （priority=最低・音なしだが、通知シェードにエントリが残るのを防ぐ）
        // ※ パース前に判定し、通知シェードを早めに綺麗にする
        val silent = QuotaRelay.parseField(fullText, QuotaRelay.KEY_SILENT) == "1"
        if (silent) {
            cancelNotification(sbn.key)
            Log.d(TAG, "Cancelled silent notification (watch poll): ${sbn.key}")
        }

        // 共通経路でパース → DataLayer 転送。
        // 通知の group 化で複数メッセージが bigText に連結されている場合も、
        // 末尾の最新フィールドが採用される（parseField は最後マッチを返すため）。
        QuotaRelay.parseAndRelay(applicationContext, fullText, QuotaRelay.TAG_NTFY_RECEIVED)
    }

    companion object {
        private const val TAG = "QuotaNtfyListener"
        private const val NTFY_PKG_PREFIX = "io.heckel.ntfy"
    }
}
