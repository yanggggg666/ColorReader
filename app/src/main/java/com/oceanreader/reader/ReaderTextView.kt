package com.oceanreader.reader

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Build
import android.text.Layout
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.StaticLayout
import android.text.style.BackgroundColorSpan
import android.text.style.LeadingMarginSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import kotlin.math.abs
import kotlin.math.roundToInt

/** A highlight currently applied to the visible text. */
data class SpanSpec(
    val start: Int,
    val end: Int,
    val color: Int,
    /** 0 = background wash, 1 = underline. */
    val kind: Int = 0,
    /** TTS sentence highlight. */
    val isSpeech: Boolean = false
)

/** A selection range reported to the host. */
data class Selection(val start: Int, val end: Int, val text: String)

/**
 * Renders one chapter of flowing text as vertically paginated pages.
 *
 * Why a custom View instead of Compose text: pagination needs exact line
 * measurement (`StaticLayout`), text selection needs real hit-testing, and the
 * speech highlight has to move in sync with the TTS callbacks — all of which
 * are far cheaper and more accurate on the platform text stack.
 */
class ReaderTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // ------------------------------------------------------------- styling

    var textSizePx: Float = 42f
        set(v) { field = v; invalidateLayout() }
    var lineSpacingMultiplier: Float = 1.6f
        set(v) { field = v; invalidateLayout() }
    var paragraphSpacingPx: Int = 0
        set(v) { field = v; invalidateLayout() }
    var textColor: Int = Color.BLACK
        set(v) { field = v; invalidateLayout() }
    var hintColor: Int = Color.GRAY
        set(v) { field = v; invalidateLayout() }
    var paddingStartPx: Int = 0
        set(v) { field = v; invalidateLayout() }
    var paddingEndPx: Int = 0
        set(v) { field = v; invalidateLayout() }
    var paddingTopPx: Int = 0
        set(v) { field = v; invalidateLayout() }
    var paddingBottomPx: Int = 0
        set(v) { field = v; invalidateLayout() }
    var fontFamily: Typeface = Typeface.DEFAULT
        set(v) { field = v; invalidateLayout() }
    var boldText: Boolean = false
        set(v) { field = v; invalidateLayout() }
    var justifyText: Boolean = false
        set(v) { field = v; invalidateLayout() }
    var firstLineIndentChars: Int = 2
        set(v) { field = v; invalidateLayout() }

    // ------------------------------------------------------------ content

    private var plainText: String = ""
    private var layout: StaticLayout? = null
    private var pageStarts: IntArray = IntArray(0)
    private var pageHeight: Int = 0

    /** Stable id of the chapter currently loaded. */
    var chapterKey: Int = -1
        private set

    var currentPage: Int = 0
        private set

    private val spans = ArrayList<SpanSpec>()
    private val spannable = SpannableStringBuilder()
    private var selectionStart = -1
    private var selectionEnd = -1
    private var selectionAnchor = -1

    var onPageChanged: ((Int) -> Unit)? = null
    var onSelectionChanged: ((Selection?) -> Unit)? = null
    var onTapInMargin: ((Boolean) -> Unit)? = null
    var onLongPressAt: ((Int) -> Unit)? = null

    private var dragOffset = 0f
    private var dragging = false
    private var velocityTracker: VelocityTracker? = null
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val interpolator = DecelerateInterpolator()

    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = textColor
        textSize = textSizePx
        typeface = fontFamily
        isSubpixelText = true
    }

    // -------------------------------------------------------------- setup

    fun setChapter(key: Int, text: String, keepOffset: Int = 0) {
        chapterKey = key
        plainText = text
        selectionStart = -1
        selectionEnd = -1
        selectionAnchor = -1
        spans.clear()
        rebuild()
        currentPage = pageOfOffset(keepOffset.coerceIn(0, plainText.length))
        invalidate()
    }

    fun clearSpans() {
        spans.clear()
        applySpans()
    }

    fun addSpan(spec: SpanSpec) {
        spans.removeAll {
            it.isSpeech && spec.isSpeech && it.start == spec.start && it.end == spec.end
        }
        spans.add(spec)
        applySpans()
    }

    fun removeSpansInRange(from: Int, to: Int) {
        spans.removeAll { it.start < to && it.end > from }
        applySpans()
    }

    /** Replaces the sentence-level speech highlight. */
    fun setSpeechSpan(start: Int, end: Int, color: Int) {
        spans.removeAll { it.isSpeech }
        if (end > start) spans.add(SpanSpec(start, end, color, 0, isSpeech = true))
        applySpans()
    }

    fun setWordSpan(start: Int, end: Int, color: Int) {
        spans.removeAll { it.isWord }
        if (end > start) spans.add(SpanSpec(start, end, color, 1, isWord = true))
        applySpans()
    }

    private fun applySpans() {
        spannable.setSpan(BackgroundColorSpan(Color.TRANSPARENT), 0, 0, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        spannable.setSpan(
            BackgroundColorSpan(Color.TRANSPARENT),
            0,
            spannable.length,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        for (s in spans) {
            val start = s.start.coerceIn(0, spannable.length)
            val end = s.end.coerceIn(start, spannable.length)
            if (end <= start) continue
            if (s.isSpeech) {
                spannable.setSpan(BackgroundColorSpan(s.color), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            } else if (s.kind == 1) {
                spannable.setSpan(
                    UnderlineSpan(),
                    start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            } else {
                spannable.setSpan(BackgroundColorSpan(s.color), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        if (selectionStart >= 0 && selectionEnd > selectionStart) {
            spannable.setSpan(
                BackgroundColorSpan(selectionColor()),
                selectionStart.coerceIn(0, spannable.length),
                selectionEnd.coerceIn(0, spannable.length),
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        invalidate()
    }

    private fun selectionColor(): Int =
        (textColor and 0x00FFFFFF) or 0x55000000

    private fun invalidateLayout() {
        bodyPaint.color = textColor
        bodyPaint.textSize = textSizePx
        bodyPaint.typeface = if (boldText) {
            Typeface.create(fontFamily, Typeface.BOLD)
        } else fontFamily
        rebuild()
        invalidate()
    }

    // ---------------------------------------------------------- pagination

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuild()
    }

    private fun rebuild() {
        if (width <= 0) return
        val availableWidth = (width - paddingStartPx - paddingEndPx).coerceAtLeast(1)
        pageHeight = (height - paddingTopPx - paddingBottomPx).coerceAtLeast(1)

        spannable.clear()
        spannable.append(buildStyledText(plainText))
        applyParagraphStyles()

        val builder = StaticLayout.Builder
            .obtain(spannable, 0, spannable.length, bodyPaint, availableWidth)
            .setAlignment(
                when {
                    justifyText && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ->
                        Layout.Alignment.JUSTIFY
                    firstLineIndentChars <= 0 -> Layout.Alignment.ALIGN_NORMAL
                    else -> Layout.Alignment.ALIGN_NORMAL
                }
            )
            .setLineSpacing(0f, lineSpacingMultiplier)
            .setIncludePad(false)
            .setBreakStrategy(Layout.BREAK_STRATEGY_HIGH_QUALITY)
            .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)

        layout = try {
            builder.build()
        } catch (e: Exception) {
            StaticLayout.Builder
                .obtain(spannable, 0, spannable.length, bodyPaint, availableWidth)
                .setIncludePad(false)
                .build()
        }
        pageStarts = computePages(layout, pageHeight)
        if (currentPage >= pageStarts.size) currentPage = (pageStarts.size - 1).coerceAtLeast(0)
    }

    /** Renders blank lines and headings as styled runs. */
    private fun buildStyledText(text: String): String {
        if (text.isEmpty()) return ""
        val out = StringBuilder(text.length)
        var index = 0
        for (line in text.split('\n')) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) {
                out.append('\n')
            } else if (trimmed.length <= 40 && index == 0 && !trimmed.endsWith('。') &&
                !trimmed.endsWith('.') && !trimmed.endsWith('！') && !trimmed.endsWith('？')
            ) {
                out.append(trimmed).append("\n\n")
            } else {
                out.append(line).append('\n')
            }
            index++
        }
        return out.toString()
    }

    /** Applies the first-line indent to every real paragraph. */
    private fun applyParagraphStyles() {
        if (firstLineIndentChars <= 0) return
        val indent = (textSizePx * firstLineIndentChars).roundToInt()
        var start = 0
        while (start < spannable.length) {
            val end = spannable.indexOf('\n', start).let { if (it < 0) spannable.length else it }
            val line = spannable.subSequence(start, end).toString()
            if (line.isNotBlank()) {
                spannable.setSpan(
                    LeadingMarginSpan.Standard(indent, 0),
                    start,
                    end,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
            start = end + 1
        }
    }

    private fun computePages(l: StaticLayout?, pageHeightPx: Int): IntArray {
        if (l == null || l.lineCount == 0 || pageHeightPx <= 0) return intArrayOf(0)
        val starts = ArrayList<Int>()
        var line = 0
        while (line < l.lineCount) {
            starts.add(l.getLineStart(line))
            val top = l.getLineTop(line)
            var end = line
            while (end + 1 < l.lineCount && (l.getLineTop(end + 1) - top) <= pageHeightPx) {
                end++
            }
            if (end == line) end = line + 1
            line = end
        }
        if (starts.isEmpty()) starts.add(0)
        return starts.toIntArray()
    }

    val pageCount: Int get() = pageStarts.size.coerceAtLeast(1)

    fun pageOfOffset(offset: Int): Int {
        if (pageStarts.isEmpty()) return 0
        var lo = 0
        var hi = pageStarts.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (pageStarts[mid] <= offset) lo = mid else hi = mid - 1
        }
        return lo
    }

    fun offsetOfPage(page: Int): Int =
        pageStarts.getOrElse(page.coerceIn(0, (pageStarts.size - 1).coerceAtLeast(0))) { 0 }

    /** First character of a page — used to persist reading position. */
    fun currentOffset(): Int = offsetOfPage(currentPage)

    fun setPage(page: Int, notify: Boolean = true) {
        val target = page.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
        if (target == currentPage) return
        currentPage = target
        if (notify) onPageChanged?.invoke(target)
        invalidate()
    }

    fun nextPage(): Boolean {
        if (currentPage >= pageCount - 1) return false
        setPage(currentPage + 1)
        return true
    }

    fun previousPage(): Boolean {
        if (currentPage <= 0) return false
        setPage(currentPage - 1)
        return true
    }

    /** Scrolls the sentence currently being spoken into view. */
    fun ensureVisible(offset: Int) {
        val target = pageOfOffset(offset)
        if (target != currentPage) {
            currentPage = target
            onPageChanged?.invoke(target)
            invalidate()
        }
    }

    fun wordAt(x: Float, y: Float): Int? {
        val l = layout ?: return null
        val localX = (x - paddingStartPx).coerceAtLeast(0f)
        val localY = (y - paddingTopPx - dragOffset).coerceAtLeast(0f)
        if (localY > l.height) return null
        val line = l.getLineForVertical(localY.toInt())
        return l.getOffsetForHorizontal(line, localX)
    }

    fun selectWord(offset: Int): Selection {
        val text = plainText
        if (text.isEmpty()) return Selection(0, 0, "")
        var start = offset.coerceIn(0, text.length - 1)
        if (start >= text.length) start = text.length - 1
        while (start > 0 && !isWordSeparator(text[start - 1])) start--
        var end = start
        while (end < text.length && !isWordSeparator(text[end])) end++
        selectionStart = start
        selectionEnd = end
        selectionAnchor = start
        applySpans()
        val sel = Selection(start, end, text.substring(start, end))
        onSelectionChanged?.invoke(sel)
        return sel
    }

    fun setSelection(start: Int, end: Int) {
        selectionStart = start.coerceIn(0, plainText.length)
        selectionEnd = end.coerceIn(selectionStart, plainText.length)
        applySpans()
        onSelectionChanged?.invoke(
            Selection(selectionStart, selectionEnd, plainText.substring(selectionStart, selectionEnd))
        )
    }

    fun clearSelection() {
        selectionStart = -1
        selectionEnd = -1
        selectionAnchor = -1
        applySpans()
        onSelectionChanged?.invoke(null)
    }

    fun selection(): Selection? {
        if (selectionStart < 0 || selectionEnd <= selectionStart) return null
        return Selection(
            selectionStart,
            selectionEnd,
            plainText.substring(selectionStart, selectionEnd)
        )
    }

    private fun isWordSeparator(c: Char): Boolean =
        c.isWhitespace() || c == '\u3000' || c == '\n' || "，。！？；：、,.!?;:()（）《》“”\"'".indexOf(c) >= 0

    // ------------------------------------------------------------ drawing

    override fun onDraw(canvas: Canvas) {
        val l = layout ?: return
        val save = canvas.save()
        canvas.clipRect(
            paddingStartPx, paddingTopPx,
            width - paddingEndPx, height - paddingBottomPx
        )

        fun drawPage(page: Int, offsetY: Float) {
            if (page !in 0 until pageStarts.size) return
            val startOffset = pageStarts[page]
            val line = l.getLineForOffset(startOffset)
            val top = l.getLineTop(line)
            canvas.save()
            canvas.translate(paddingStartPx.toFloat(), paddingTopPx.toFloat() - top + offsetY)
            l.draw(canvas)
            canvas.restore()
        }

        if (dragging && dragOffset != 0f) {
            if (dragOffset > 0) {
                drawPage(currentPage - 1, dragOffset)
                drawPage(currentPage, dragOffset)
            } else {
                drawPage(currentPage, dragOffset)
                drawPage(currentPage + 1, dragOffset)
            }
        } else {
            drawPage(currentPage, 0f)
        }
        canvas.restoreToCount(save)
    }

    // -------------------------------------------------------------- touch

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val l = layout
        if (l == null || plainText.isEmpty()) return false

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                velocityTracker?.recycle()
                velocityTracker = VelocityTracker.obtain()
                velocityTracker?.addMovement(event)
                dragging = true
                dragOffset = 0f
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                velocityTracker?.addMovement(event)
                val delta = event.y - downY
                if (abs(delta) > touchSlop || dragging) {
                    dragOffset = delta
                    invalidate()
                }
                if (selectionAnchor >= 0 && abs(delta) > touchSlop * 2) {
                    // drag to extend the selection
                    val offset = wordAt(event.x, downY) ?: return true
                    selectionEnd = (offset + 1).coerceIn(0, plainText.length)
                    applySpans()
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                velocityTracker?.addMovement(event)
                velocityTracker?.computeCurrentVelocity(1000)
                val velocity = velocityTracker?.yVelocity ?: 0f
                val delta = event.y - downY

                if (abs(delta) > touchSlop) {
                    settleTurn(delta, velocity)
                } else if (selectionAnchor >= 0) {
                    val offset = wordAt(event.x, event.y)
                    if (offset != null && offset != selectionAnchor) {
                        selectionEnd = (offset + 1).coerceIn(0, plainText.length)
                        applySpans()
                    } else {
                        onSelectionChanged?.invoke(selection())
                    }
                } else {
                    val third = width / 3f
                    when {
                        event.x < third -> onTapInMargin?.invoke(true)
                        event.x > width - third -> onTapInMargin?.invoke(false)
                        else -> onTapInMargin?.invoke(null)
                    }
                }
                dragging = false
                velocityTracker?.recycle()
                velocityTracker = null
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                dragging = false
                dragOffset = 0f
                invalidate()
                velocityTracker?.recycle()
                velocityTracker = null
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private var downY = 0f

    override fun onTouchEvent(event: MotionEvent): Boolean {
        return handleTouch(event)
    }

    private fun handleTouch(event: MotionEvent): Boolean {
        if (layout == null || plainText.isEmpty()) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downY = event.y
                velocityTracker?.recycle()
                velocityTracker = VelocityTracker.obtain().also { it.addMovement(event) }
                dragging = true
                dragOffset = 0f
                // Long press selects a word.
                postDelayed(longPressRunnable, ViewConfiguration.getLongPressTimeout().toLong())
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                velocityTracker?.addMovement(event)
                val delta = event.y - downY
                if (abs(delta) > touchSlop) {
                    removeCallbacks(longPressRunnable)
                    dragging = true
                    dragOffset = delta
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPressRunnable)
                velocityTracker?.addMovement(event)
                velocityTracker?.computeCurrentVelocity(1000)
                val velocity = velocityTracker?.yVelocity ?: 0f
                val delta = event.y - downY
                if (abs(delta) > touchSlop) {
                    settleTurn(delta, velocity)
                } else if (longPressTriggered) {
                    longPressTriggered = false
                    val offset = wordAt(event.x, event.y)
                    if (offset != null) {
                        selectWord(offset)
                        onLongPressAt?.invoke(offset)
                    }
                } else {
                    val third = width / 3f
                    onTapInMargin?.invoke(
                        when {
                            event.x < third -> true
                            event.x > width - third -> false
                            else -> null
                        }
                    )
                }
                dragging = false
                dragOffset = 0f
                velocityTracker?.recycle()
                velocityTracker = null
                invalidate()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPressRunnable)
                dragging = false
                dragOffset = 0f
                velocityTracker?.recycle()
                velocityTracker = null
                invalidate()
                return true
            }
        }
        return true
    }

    private var longPressTriggered = false

    private val longPressRunnable = Runnable {
        val offset = wordAt(downEventX, downY)
        if (offset != null) {
            longPressTriggered = true
            selectWord(offset)
            onLongPressAt?.invoke(offset)
            performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        }
    }

    private var downEventX = 0f

    private fun settleTurn(delta: Float, velocity: Float) {
        val threshold = pageHeight * 0.18f
        val flung = abs(velocity) > 900f
        val target = when {
            delta < -threshold -> currentPage + 1
            delta > threshold -> currentPage - 1
            flung && velocity < 0 -> currentPage + 1
            flung && velocity > 0 -> currentPage - 1
            else -> currentPage
        }
        val clamped = target.coerceIn(0, pageCount - 1)
        if (clamped != currentPage) {
            currentPage = clamped
            onPageChanged?.invoke(clamped)
        }
        dragOffset = 0f
        invalidate()
    }

    init {
        isClickable = true
        isFocusable = true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}