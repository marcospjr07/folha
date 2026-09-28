package dev.folha.ui

import android.annotation.SuppressLint
import android.content.Context
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.OverScroller
import kotlin.math.abs
import kotlin.math.roundToInt

/** A bounded editor: only navigation gestures belong to this parent, never text or IME state. */
class EditorViewport @JvmOverloads constructor(
    context: Context, val editor: FolhaEditText = FolhaEditText(context),
) : FrameLayout(context) {
    private val scroller = OverScroller(context)
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downY = 0f
    private var navigating = false
    private val range: Int
        get() = ((editor.layout?.height ?: 0) - editor.height +
            editor.totalPaddingTop + editor.totalPaddingBottom).coerceAtLeast(0)

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            if (!navigating) return false
            editor.scrollTo(0, (editor.scrollY + distanceY.roundToInt()).coerceIn(0, range))
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
            if (!navigating) return false
            scroller.fling(0, editor.scrollY, 0, -velocityY.roundToInt(), 0, 0, 0, range)
            postInvalidateOnAnimation()
            return true
        }
    }).apply { setIsLongpressEnabled(false) }

    init {
        isSaveEnabled = false
        addView(editor, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        // Dispatch first: selection can disallow interception, and a drag sends native CANCEL
        // to EditText before we scroll. Its ACTION_UP (which requests IME) then never occurs.
        val handled = super.dispatchTouchEvent(event)
        gestures.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            navigating = false
        }
        return handled
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        if (!editor.isEnabled) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downY = event.y
                // A touch stops at the current position, not at the fling's final destination.
                navigating = !scroller.isFinished
                scroller.forceFinished(true)
            }
            MotionEvent.ACTION_MOVE -> if (abs(event.y - downY) > touchSlop) navigating = true
            MotionEvent.ACTION_POINTER_UP -> downY = event.getY(if (event.actionIndex == 0) 1 else 0)
        }
        return navigating
    }

    // This parent only consumes navigation; taps and accessibility clicks belong to EditText.
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean = editor.isEnabled

    internal fun stopFling() { scroller.forceFinished(true) }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            editor.scrollTo(0, scroller.currY.coerceIn(0, range))
            postInvalidateOnAnimation()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        scroller.forceFinished(true)
        super.onSizeChanged(w, h, oldw, oldh)
    }

    override fun onDetachedFromWindow() {
        scroller.forceFinished(true)
        super.onDetachedFromWindow()
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        if (!hasWindowFocus) stopFling()
        super.onWindowFocusChanged(hasWindowFocus)
    }
}
