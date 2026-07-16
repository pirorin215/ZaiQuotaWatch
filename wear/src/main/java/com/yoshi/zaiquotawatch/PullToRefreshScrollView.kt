package com.yoshi.zaiquotawatch

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.widget.ScrollView

/**
 * 下に引っ張って離すと更新要求できる ScrollView。
 * Wear の狭い画面でも扱いやすいよう、依存ライブラリを使わず自作。
 *
 * 仕組み:
 * - コンテンツ先頭（scrollY == 0）で下ドラッグ（指を下へ動かす）すると View 全体が下へ変位。
 * - 変位量が [THRESHOLD] を超えた状態で指を離すと [onRefresh] が呼ばれる。
 * - 離すとバネアニメーションで元の位置へ戻る。
 * - リフレッシュ中は上端付近にインジケータ ([indicator]) を表示し、[completeRefresh] で消える。
 */
class PullToRefreshScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : ScrollView(context, attrs, defStyleAttr) {

    /** 引き離しで更新確定する変位量（px） */
    private val threshold: Int = (PULL_THRESHOLD_DP * resources.displayMetrics.density).toInt()

    /** インジケータとして外部から差し込む View（任意）。リフレッシュ中に表示される。 */
    var indicator: View? = null

    /** リフレッシュ確定時のコールバック */
    var onRefresh: (() -> Unit)? = null

    private val handler = Handler(Looper.getMainLooper())
    private var startY = 0f
    private var dragging = false
    private var refreshing = false

    /** 現在の引き量（正 = 下へ）。これで content の translationY を動かす。 */
    private var pullDistance = 0f

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (refreshing) return super.onInterceptTouchEvent(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startY = ev.y
                dragging = false
            }
            MotionEvent.ACTION_MOVE -> {
                // 一番上までスクロール済みの状態でさらに下へ引っ張り始めたら奪う
                if (scrollY == 0 && ev.y - startY > touchSlop) {
                    dragging = true
                    return true
                }
            }
        }
        return super.onInterceptTouchEvent(ev)
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (!dragging || refreshing) return super.onTouchEvent(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                val raw = ev.y - startY
                // 指の動きの半分だけ追従（抵抗感）。下方向（正）のみ許可。
                val damped = (raw * DAMPING).coerceAtLeast(0f)
                pullDistance = damped
                applyTranslation()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false
                if (pullDistance >= threshold) {
                    triggerRefresh()
                } else {
                    animateReset()
                }
                return true
            }
        }
        return super.onTouchEvent(ev)
    }

    private fun applyTranslation() {
        val child = getChildAt(0) ?: return
        child.translationY = pullDistance
        indicator?.let {
            it.alpha = (pullDistance / threshold).coerceIn(0f, 1f)
            it.translationY = pullDistance
        }
    }

    private fun triggerRefresh() {
        refreshing = true
        // 引いた位置で固定表示し、インジケータを見せる
        indicator?.let {
            it.alpha = 1f
            it.visibility = VISIBLE
        }
        val child = getChildAt(0) ?: return
        // 引いたまま待つと押しにくいので、少し戻してインジケータだけ見せる
        animateChildTo(child, INDICATOR_HOLD_HEIGHT_PX.toFloat())
        onRefresh?.invoke()
    }

    /** リフレッシュ完了を通知し、インジケータを消して元位置へ戻す。 */
    fun completeRefresh() {
        if (!refreshing) return
        refreshing = false
        indicator?.visibility = GONE
        animateReset()
    }

    private fun animateReset() {
        val child = getChildAt(0) ?: return
        animateChildTo(child, 0f)
        indicator?.let { ind ->
            ind.animate().alpha(0f).setDuration(RESET_DURATION_MS).start()
        }
    }

    private fun animateChildTo(child: View, targetY: Float) {
        child.animate()
            .translationY(targetY)
            .setDuration(RESET_DURATION_MS)
            .start()
        indicator?.animate()?.translationY(targetY)?.setDuration(RESET_DURATION_MS)?.start()
    }

    private val touchSlop: Int by lazy {
        // 指がタップかドラッグか判定する最小距離
        android.view.ViewConfiguration.get(context).scaledTouchSlop
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        indicator?.visibility = GONE
    }

    companion object {
        private const val PULL_THRESHOLD_DP = 80
        private const val DAMPING = 0.5f
        private const val RESET_DURATION_MS = 250L
        // リフレッシュ中にコンテンツを下げておく量（dp ではなく画素で固定）
        private val INDICATOR_HOLD_HEIGHT_PX = 0
    }
}
