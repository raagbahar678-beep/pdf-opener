package com.example.myapp

import android.app.Activity
import android.app.AlertDialog
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.OverScroller
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

data class PdfItem(val uri: Uri, val name: String)

// ───────────────────────── PDF document wrapper ─────────────────────────
// PdfRenderer reads pages on demand from the file descriptor, so even
// multi-GB PDFs are never loaded into memory. Only one page is rendered at a time.
class PdfDoc private constructor(
    private val pfd: ParcelFileDescriptor,
    private val renderer: PdfRenderer
) {
    val count: Int = renderer.pageCount
    private var closed = false

    fun render(index: Int, targetW: Int): Bitmap? {
        return synchronized(this) { renderLocked(index, targetW) }
    }

    private fun renderLocked(index: Int, targetW: Int): Bitmap? {
        if (closed) return null
        if (index < 0 || index >= count) return null
        var opened: PdfRenderer.Page? = null
        try {
            val pg = renderer.openPage(index)
            opened = pg
            val pw = max(1, pg.width)
            val ph = max(1, pg.height)
            var w = min(max(targetW, 200), 4096)
            var h = max(1, (ph.toDouble() * w / pw).toInt())
            val maxPix = 12000000.0
            if (w.toDouble() * h > maxPix) {
                val f = sqrt(maxPix / (w.toDouble() * h))
                w = max(1, (w * f).toInt())
                h = max(1, (ph.toDouble() * w / pw).toInt())
            }
            if (h > 8192) {
                h = 8192
                w = max(1, (pw.toDouble() * h / ph).toInt())
            }
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(Color.WHITE)
            pg.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            return bmp
        } catch (t: Throwable) {
            return null
        } finally {
            try {
                opened?.close()
            } catch (t: Throwable) {
            }
        }
    }

    fun close() {
        synchronized(this) {
            if (closed) return
            closed = true
            try {
                renderer.close()
            } catch (t: Throwable) {
            }
            try {
                pfd.close()
            } catch (t: Throwable) {
            }
        }
    }

    companion object {
        fun open(cr: ContentResolver, uri: Uri): PdfDoc {
            val pfd = cr.openFileDescriptor(uri, "r") ?: throw IOException("Cannot open file")
            try {
                val r = PdfRenderer(pfd)
                if (r.pageCount <= 0) {
                    r.close()
                    throw IOException("Empty PDF")
                }
                return PdfDoc(pfd, r)
            } catch (t: Throwable) {
                try {
                    pfd.close()
                } catch (e: Throwable) {
                }
                throw t
            }
        }
    }
}

// ───────────────────────── Draggable scroll bar ─────────────────────────
class ScrollBarView(ctx: Context, private val vertical: Boolean) : View(ctx) {
    var thumbPos = 0f
    var thumbSize = 1f
    var onDrag: ((Float) -> Unit)? = null
    private val density = ctx.resources.displayMetrics.density
    private val trackP = Paint()
    private val thumbP = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        trackP.color = 0x33FFFFFF
        thumbP.color = 0xCC4FC3F7.toInt()
    }

    fun setThumb(pos: Float, size: Float) {
        thumbPos = pos
        thumbSize = size
        invalidate()
    }

    private fun thumbLen(len: Float): Float {
        return max(len * thumbSize, 28f * density)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), trackP)
        if (thumbSize >= 0.999f) return
        val len = if (vertical) height.toFloat() else width.toFloat()
        val tl = thumbLen(len)
        if (len - tl <= 1f) return
        val ts = thumbPos * (len - tl)
        if (vertical) {
            canvas.drawRoundRect(RectF(2f, ts, width - 2f, ts + tl), 6f, 6f, thumbP)
        } else {
            canvas.drawRoundRect(RectF(ts, 2f, ts + tl, height - 2f), 6f, 6f, thumbP)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (thumbSize >= 0.999f) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                val len = if (vertical) height.toFloat() else width.toFloat()
                val tl = thumbLen(len)
                if (len - tl <= 1f) return true
                val c = if (vertical) e.y else e.x
                val p = ((c - tl / 2f) / (len - tl)).coerceIn(0f, 1f)
                thumbPos = p
                invalidate()
                onDrag?.invoke(p)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> return true
        }
        return true
    }
}

// ───────────────────────── Zoom / pan page view ─────────────────────────
class PageView(ctx: Context) : View(ctx) {
    var bmp: Bitmap? = null
    var zoom = 1f
    var ox = 0f
    var oy = 0f
    var restoreFx = 0f
    var restoreFy = 0f
    var onChanged: (() -> Unit)? = null
    var onSettled: (() -> Unit)? = null
    var onEdge: ((Int) -> Unit)? = null
    var onTouched: (() -> Unit)? = null
    var onResized: (() -> Unit)? = null
    var onTap: (() -> Unit)? = null

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val density = ctx.resources.displayMetrics.density
    private var lastEdge = 0L
    private var overAccum = 0f
    private var midActive = false
    private var lastX = 0f
    private var lastY = 0f
    private val scroller = OverScroller(ctx)
    private val settleRun = Runnable { onSettled?.invoke() }

    private val flingRun = object : Runnable {
        override fun run() {
            if (scroller.computeScrollOffset()) {
                ox = scroller.currX.toFloat()
                oy = scroller.currY.toFloat()
                clamp()
                invalidate()
                onChanged?.invoke()
                postOnAnimation(this)
            }
        }
    }

    private val scaleDet = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            zoomBy(d.scaleFactor, d.focusX, d.focusY)
            return true
        }
    })

    private val gestDet = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            scroller.forceFinished(true)
            overAccum = 0f
            return true
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            onTap?.invoke()
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (zoom > 1.3f) fitWidth() else zoomBy(2.5f / zoom, e.x, e.y)
            return true
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            val before = oy
            panBy(-dx, -dy)
            if (abs(oy - before) < 0.5f && abs(dy) > abs(dx)) {
                overAccum += dy
                val thr = 140f * density
                if (overAccum > thr) {
                    overAccum = 0f
                    onEdge?.invoke(1)
                } else if (overAccum < -thr) {
                    overAccum = 0f
                    onEdge?.invoke(-1)
                }
            } else {
                overAccum = 0f
            }
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            startFling(vx, vy)
            return true
        }
    })

    private fun dispW(): Float = width * zoom

    private fun dispH(b: Bitmap): Float = width * zoom * b.height / b.width

    fun clamp() {
        val b = bmp ?: return
        val vw = width.toFloat()
        val vh = height.toFloat()
        val dw = vw * zoom
        val dh = dw * b.height / b.width
        ox = if (dw <= vw) (vw - dw) / 2f else ox.coerceIn(vw - dw, 0f)
        oy = if (dh <= vh) (vh - dh) / 2f else oy.coerceIn(vh - dh, 0f)
    }

    // mode: 0 keep position, 1 top, 2 bottom, 3 restore saved fractions
    fun setBitmap(b: Bitmap, mode: Int) {
        bmp = b
        val vw = width.toFloat()
        val vh = height.toFloat()
        val dw = vw * zoom
        val dh = dw * b.height / b.width
        when (mode) {
            1 -> oy = 0f
            2 -> oy = -1e9f
            3 -> {
                ox = -restoreFx * (dw - vw)
                oy = -restoreFy * (dh - vh)
            }
        }
        clamp()
        invalidate()
        onChanged?.invoke()
    }

    fun clear() {
        bmp = null
        invalidate()
    }

    fun panBy(dx: Float, dy: Float) {
        ox += dx
        oy += dy
        clamp()
        invalidate()
        onChanged?.invoke()
    }

    fun wheelPan(dx: Float, dy: Float, edge: Boolean) {
        val by = oy
        panBy(dx, dy)
        if (edge && abs(dy) > 0f && abs(oy - by) < 0.5f) {
            val now = SystemClock.uptimeMillis()
            if (now - lastEdge > 180) {
                lastEdge = now
                onEdge?.invoke(if (dy < 0f) 1 else -1)
            }
        }
    }

    fun zoomBy(f: Float, fx: Float, fy: Float) {
        val nz = (zoom * f).coerceIn(0.25f, 8f)
        if (nz == zoom) return
        val r = nz / zoom
        ox = fx - (fx - ox) * r
        oy = fy - (fy - oy) * r
        zoom = nz
        clamp()
        invalidate()
        onChanged?.invoke()
        removeCallbacks(settleRun)
        postDelayed(settleRun, 250)
    }

    fun fitWidth() {
        zoom = 1f
        ox = 0f
        oy = 0f
        clamp()
        invalidate()
        onChanged?.invoke()
        removeCallbacks(settleRun)
        postDelayed(settleRun, 150)
    }

    fun fracX(): Float {
        if (bmp == null) return 0f
        val dw = width * zoom
        if (dw <= width) return 0f
        return (-ox / (dw - width)).coerceIn(0f, 1f)
    }

    fun sizeX(): Float {
        if (bmp == null) return 1f
        val dw = width * zoom
        return if (dw <= width) 1f else width / dw
    }

    fun fracY(): Float {
        val b = bmp ?: return 0f
        val dh = dispH(b)
        if (dh <= height) return 0f
        return (-oy / (dh - height)).coerceIn(0f, 1f)
    }

    fun sizeY(): Float {
        val b = bmp ?: return 1f
        val dh = dispH(b)
        return if (dh <= height) 1f else height / dh
    }

    fun setFx(f: Float) {
        if (bmp == null) return
        val dw = width * zoom
        if (dw > width) {
            ox = -f * (dw - width)
            clamp()
            invalidate()
            onChanged?.invoke()
        }
    }

    fun setFy(f: Float) {
        val b = bmp ?: return
        val dh = dispH(b)
        if (dh > height) {
            oy = -f * (dh - height)
            clamp()
            invalidate()
            onChanged?.invoke()
        }
    }

    private fun startFling(vx: Float, vy: Float) {
        val b = bmp ?: return
        val vw = width.toFloat()
        val vh = height.toFloat()
        val dw = vw * zoom
        val dh = dw * b.height / b.width
        val minX = if (dw <= vw) ox.toInt() else (vw - dw).toInt()
        val maxX = if (dw <= vw) ox.toInt() else 0
        val minY = if (dh <= vh) oy.toInt() else (vh - dh).toInt()
        val maxY = if (dh <= vh) oy.toInt() else 0
        scroller.fling(ox.toInt(), oy.toInt(), vx.toInt(), vy.toInt(), minX, maxX, minY, maxY)
        postOnAnimation(flingRun)
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        clamp()
        onResized?.invoke()
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(0xFF3A3A3A.toInt())
        val b = bmp ?: return
        val s = dispW() / b.width
        canvas.save()
        canvas.translate(ox, oy)
        canvas.scale(s, s)
        canvas.drawBitmap(b, 0f, 0f, paint)
        canvas.restore()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_DOWN) onTouched?.invoke()
        val mid = (e.buttonState and MotionEvent.BUTTON_TERTIARY) != 0
        if (mid) {
            if (e.actionMasked == MotionEvent.ACTION_MOVE && midActive) {
                panBy(e.x - lastX, e.y - lastY)
            }
            midActive = true
            lastX = e.x
            lastY = e.y
            return true
        }
        midActive = false
        scaleDet.onTouchEvent(e)
        if (!scaleDet.isInProgress) gestDet.onTouchEvent(e)
        return true
    }

    override fun onGenericMotionEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_SCROLL) {
            onTouched?.invoke()
            val v = e.getAxisValue(MotionEvent.AXIS_VSCROLL)
            val h = e.getAxisValue(MotionEvent.AXIS_HSCROLL)
            val ctrl = (e.metaState and KeyEvent.META_CTRL_ON) != 0
            val shift = (e.metaState and KeyEvent.META_SHIFT_ON) != 0
            if (ctrl) {
                if (v != 0f) zoomBy(if (v > 0f) 1.15f else 1f / 1.15f, e.x, e.y)
            } else if (shift) {
                if (v != 0f) {
                    panBy(v * 120f, 0f)
                } else if (h != 0f) {
                    panBy(-h * 120f, 0f)
                }
            } else {
                if (h != 0f) panBy(-h * 120f, 0f)
                if (v != 0f) wheelPan(0f, v * 120f, true)
            }
            return true
        }
        return super.onGenericMotionEvent(e)
    }
}

// ───────────────────────── One PDF pane ─────────────────────────
class PdfPane(private val a: MainActivity, val index: Int) {
    val root = LinearLayout(a)
    private val titleRow = LinearLayout(a)
    private val navRow = LinearLayout(a)
    private val zoomRow = LinearLayout(a)
    private val hRow = LinearLayout(a)
    private val title = TextView(a)
    private val pageEdit = EditText(a)
    private val totalTv = TextView(a)
    private val seek = SeekBar(a)
    val pv = PageView(a)
    private val vbar = ScrollBarView(a, true)
    private val hbar = ScrollBarView(a, false)

    var item: PdfItem? = null
    var doc: PdfDoc? = null
    var page = 0
    var count = 0

    private val cache = HashMap<Int, Bitmap>()
    private val exec: ExecutorService = Executors.newSingleThreadExecutor()
    @Volatile
    private var reqId = 0
    private var openToken = 0
    private var deferredMode = -1
    private var seekTarget = 0
    private val seekRun = Runnable { goToPage(seekTarget, 1) }

    init {
        val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
        val match = ViewGroup.LayoutParams.MATCH_PARENT

        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(0xFF2B2B2B.toInt())

        // title row
        titleRow.orientation = LinearLayout.HORIZONTAL
        titleRow.gravity = Gravity.CENTER_VERTICAL
        title.setTextColor(Color.WHITE)
        title.textSize = 12f
        title.setSingleLine(true)
        title.ellipsize = TextUtils.TruncateAt.MIDDLE
        title.setPadding(a.dp(6), a.dp(4), a.dp(4), a.dp(4))
        title.text = "(empty pane " + (index + 1) + " - tap here or + to add a PDF)"
        title.setOnClickListener {
            a.setActive(index)
            if (item == null) a.showFilesDialog(index)
        }
        val closeBtn = a.mkBtn("X")
        closeBtn.setOnClickListener {
            a.setActive(index)
            clear()
        }
        titleRow.addView(title, LinearLayout.LayoutParams(0, wrap, 1f))
        val addBtn = a.mkBtn("+")
        addBtn.setOnClickListener {
            a.setActive(index)
            a.showFilesDialog(index)
        }
        titleRow.addView(addBtn, LinearLayout.LayoutParams(a.dp(34), wrap))
        titleRow.addView(closeBtn, LinearLayout.LayoutParams(a.dp(34), wrap))
        root.addView(titleRow, LinearLayout.LayoutParams(match, wrap))

        // page navigation row
        navRow.orientation = LinearLayout.HORIZONTAL
        navRow.gravity = Gravity.CENTER_VERTICAL
        val prevBtn = a.mkBtn("<")
        val nextBtn = a.mkBtn(">")
        pageEdit.inputType = InputType.TYPE_CLASS_NUMBER
        pageEdit.imeOptions = EditorInfo.IME_ACTION_GO
        pageEdit.setSingleLine(true)
        pageEdit.textSize = 13f
        pageEdit.gravity = Gravity.CENTER
        pageEdit.setTextColor(Color.WHITE)
        pageEdit.setHintTextColor(Color.GRAY)
        pageEdit.hint = "page"
        pageEdit.setSelectAllOnFocus(true)
        pageEdit.setPadding(0, a.dp(4), 0, a.dp(4))
        pageEdit.setOnEditorActionListener { _, actionId, ev ->
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE ||
                (ev != null && ev.keyCode == KeyEvent.KEYCODE_ENTER && ev.action == KeyEvent.ACTION_DOWN)
            ) {
                a.setActive(index)
                goFromBox()
                true
            } else {
                false
            }
        }
        totalTv.setTextColor(Color.LTGRAY)
        totalTv.textSize = 12f
        totalTv.text = "/ 0"
        totalTv.setPadding(a.dp(2), 0, a.dp(2), 0)
        prevBtn.setOnClickListener {
            a.setActive(index)
            prevPage()
        }
        nextBtn.setOnClickListener {
            a.setActive(index)
            nextPage()
        }
        navRow.addView(prevBtn, LinearLayout.LayoutParams(a.dp(34), wrap))
        navRow.addView(pageEdit, LinearLayout.LayoutParams(0, wrap, 1f))
        navRow.addView(totalTv, LinearLayout.LayoutParams(wrap, wrap))
        navRow.addView(nextBtn, LinearLayout.LayoutParams(a.dp(34), wrap))
        root.addView(navRow, LinearLayout.LayoutParams(match, wrap))

        // go / zoom row
        zoomRow.orientation = LinearLayout.HORIZONTAL
        val goBtn = a.mkBtn("Go")
        val zOut = a.mkBtn("-")
        val zIn = a.mkBtn("+")
        val zFit = a.mkBtn("Fit")
        goBtn.setOnClickListener {
            a.setActive(index)
            goFromBox()
        }
        zOut.setOnClickListener {
            a.setActive(index)
            zoomOut()
        }
        zIn.setOnClickListener {
            a.setActive(index)
            zoomIn()
        }
        zFit.setOnClickListener {
            a.setActive(index)
            fit()
        }
        zoomRow.addView(goBtn, LinearLayout.LayoutParams(0, wrap, 1f))
        zoomRow.addView(zOut, LinearLayout.LayoutParams(0, wrap, 1f))
        zoomRow.addView(zIn, LinearLayout.LayoutParams(0, wrap, 1f))
        zoomRow.addView(zFit, LinearLayout.LayoutParams(0, wrap, 1f))
        root.addView(zoomRow, LinearLayout.LayoutParams(match, wrap))

        // page slider (fast scrubbing through huge documents)
        seek.isFocusable = false
        seek.isFocusableInTouchMode = false
        seek.max = 0
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser) {
                    a.setActive(index)
                    seekTarget = p
                    pageEdit.setText((p + 1).toString())
                    root.removeCallbacks(seekRun)
                    root.postDelayed(seekRun, 80)
                }
            }

            override fun onStartTrackingTouch(s: SeekBar?) {
            }

            override fun onStopTrackingTouch(s: SeekBar?) {
            }
        })
        root.addView(seek, LinearLayout.LayoutParams(match, wrap))

        // page area with scroll bars (outside the picture)
        val contentRow = LinearLayout(a)
        contentRow.orientation = LinearLayout.HORIZONTAL
        contentRow.addView(pv, LinearLayout.LayoutParams(0, match, 1f))
        contentRow.addView(vbar, LinearLayout.LayoutParams(a.dp(14), match))
        root.addView(contentRow, LinearLayout.LayoutParams(match, 0, 1f))

        hRow.orientation = LinearLayout.HORIZONTAL
        hRow.addView(hbar, LinearLayout.LayoutParams(0, match, 1f))
        hRow.addView(View(a), LinearLayout.LayoutParams(a.dp(14), match))
        root.addView(hRow, LinearLayout.LayoutParams(match, a.dp(14)))

        pv.onTouched = { a.setActive(index) }
        pv.onTap = {
            if (item == null) a.showFilesDialog(index)
        }
        pv.onEdge = { dir ->
            if (dir > 0) {
                if (page < count - 1) goToPage(page + 1, 1)
            } else {
                if (page > 0) goToPage(page - 1, 2)
            }
        }
        pv.onChanged = { updateBars() }
        pv.onSettled = {
            requestRender(0)
            a.updateStatus()
        }
        pv.onResized = {
            val m = deferredMode
            deferredMode = -1
            if (m >= 0) {
                requestRender(m)
            } else if (doc != null) {
                requestRender(0)
            }
        }
        hbar.onDrag = { f -> pv.setFx(f) }
        vbar.onDrag = { f -> pv.setFy(f) }

        setActive(false)
    }

    fun setActive(on: Boolean) {
        titleRow.setBackgroundColor(if (on) 0xFF1565C0.toInt() else 0xFF444444.toInt())
    }

    // show = false hides every control of the pane so only the PDF page remains
    fun setChrome(show: Boolean) {
        val v = if (show) View.VISIBLE else View.GONE
        titleRow.visibility = v
        navRow.visibility = v
        zoomRow.visibility = v
        seek.visibility = v
        vbar.visibility = v
        hRow.visibility = v
    }

    private fun updateBars() {
        hbar.setThumb(pv.fracX(), pv.sizeX())
        vbar.setThumb(pv.fracY(), pv.sizeY())
    }

    fun openDoc(pdf: PdfItem, startPage: Int, fx: Float, fy: Float, z: Float) {
        openToken++
        val token = openToken
        closeDoc()
        item = pdf
        page = max(0, startPage)
        count = 0
        title.text = "Opening: " + pdf.name
        totalTv.text = "/ 0"
        pv.clear()
        pv.zoom = z.coerceIn(0.25f, 8f)
        pv.restoreFx = fx
        pv.restoreFy = fy
        updateBars()
        Thread {
            try {
                val d = PdfDoc.open(a.contentResolver, pdf.uri)
                a.runOnUiThread {
                    if (token != openToken) {
                        Thread { d.close() }.start()
                        return@runOnUiThread
                    }
                    doc = d
                    count = d.count
                    page = page.coerceIn(0, count - 1)
                    seek.max = max(0, count - 1)
                    seek.progress = page
                    pageEdit.setText((page + 1).toString())
                    totalTv.text = "/ " + count
                    title.text = pdf.name
                    requestRender(3)
                    a.onPaneChanged(this)
                }
            } catch (t: Throwable) {
                a.runOnUiThread {
                    if (token == openToken) {
                        title.text = pdf.name + " (cannot open)"
                        a.toast("Cannot open: " + pdf.name)
                    }
                }
            }
        }.start()
    }

    private fun closeDoc() {
        val d = doc
        doc = null
        cache.clear()
        reqId++
        if (d != null) {
            Thread { d.close() }.start()
        }
    }

    fun clear() {
        openToken++
        closeDoc()
        item = null
        page = 0
        count = 0
        title.text = "(empty pane " + (index + 1) + " - tap here or + to add a PDF)"
        pageEdit.setText("")
        totalTv.text = "/ 0"
        seek.max = 0
        pv.clear()
        updateBars()
        a.updateStatus()
    }

    fun release() {
        root.removeCallbacks(seekRun)
        reqId++
        exec.shutdownNow()
        val d = doc
        doc = null
        if (d != null) {
            Thread { d.close() }.start()
        }
    }

    private fun neededWidth(vw: Int): Int {
        val w = (vw * pv.zoom * 1.25f).toInt()
        return max(vw, min(w, 4096))
    }

    // mode: 0 keep position, 1 top, 2 bottom, 3 restore saved
    fun requestRender(mode: Int) {
        val d = doc ?: return
        val vw = pv.width
        if (vw <= 0 || pv.height <= 0) {
            deferredMode = mode
            return
        }
        val p = page
        val needW = neededWidth(vw)
        reqId++
        val id = reqId
        val c = cache[p]
        if (c != null && c.width >= (needW * 0.8f).toInt()) {
            pv.setBitmap(c, mode)
            prefetch(d, p, needW, id)
            return
        }
        exec.execute {
            if (id != reqId) return@execute
            val b = d.render(p, needW)
            a.runOnUiThread {
                if (doc === d) {
                    if (b != null) putCache(p, b)
                    if (id == reqId) {
                        if (b != null) {
                            pv.setBitmap(b, mode)
                            prefetch(d, p, needW, id)
                        } else {
                            a.toast("Cannot render page " + (p + 1))
                        }
                    }
                }
            }
        }
    }

    private fun prefetch(d: PdfDoc, p: Int, needW: Int, id: Int) {
        for (q in intArrayOf(p + 1, p - 1)) {
            if (q < 0 || q >= count || cache.containsKey(q)) continue
            exec.execute {
                if (id != reqId) return@execute
                val b = d.render(q, needW)
                if (b != null) {
                    a.runOnUiThread {
                        if (doc === d) putCache(q, b)
                    }
                }
            }
        }
    }

    private fun putCache(p: Int, b: Bitmap) {
        cache[p] = b
        while (cache.size > 4) {
            var far = -1
            var fd = -1
            for (k in cache.keys) {
                val dd = abs(k - page)
                if (dd > fd) {
                    fd = dd
                    far = k
                }
            }
            if (far < 0) break
            cache.remove(far)
        }
    }

    fun goToPage(p: Int, mode: Int) {
        if (doc == null || count <= 0) return
        val np = p.coerceIn(0, count - 1)
        page = np
        pageEdit.setText((np + 1).toString())
        if (seek.progress != np) seek.progress = np
        requestRender(mode)
        a.onPaneChanged(this)
    }

    private fun goFromBox() {
        if (doc == null) return
        val n = pageEdit.text.toString().trim().toIntOrNull()
        if (n == null) {
            pageEdit.setText((page + 1).toString())
            return
        }
        goToPage(n - 1, 1)
        a.releaseFocus()
    }

    fun nextPage() {
        if (doc != null && page < count - 1) goToPage(page + 1, 1)
    }

    fun prevPage() {
        if (doc != null && page > 0) goToPage(page - 1, 1)
    }

    fun zoomIn() {
        pv.zoomBy(1.25f, pv.width / 2f, pv.height / 2f)
    }

    fun zoomOut() {
        pv.zoomBy(0.8f, pv.width / 2f, pv.height / 2f)
    }

    fun fit() {
        pv.fitWidth()
    }

    fun focusPageBox() {
        pageEdit.requestFocus()
        pageEdit.selectAll()
        val imm = a.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(pageEdit, 0)
    }

    fun toJson(): JSONObject? {
        val it = item ?: return null
        val fx = if (pv.bmp == null) pv.restoreFx else pv.fracX()
        val fy = if (pv.bmp == null) pv.restoreFy else pv.fracY()
        return JSONObject()
            .put("u", it.uri.toString())
            .put("n", it.name)
            .put("p", page)
            .put("z", pv.zoom.toDouble())
            .put("fx", fx.toDouble())
            .put("fy", fy.toDouble())
    }
}

// ───────────────────────── Main activity ─────────────────────────
class MainActivity : Activity() {

    companion object {
        const val REQ_OPEN = 101
        const val REQ_TREE = 102
    }

    private lateinit var prefs: android.content.SharedPreferences
    private lateinit var rootL: LinearLayout
    private lateinit var container: LinearLayout
    private lateinit var status: TextView
    private lateinit var paneBtn: Button
    private val panes = ArrayList<PdfPane>()
    private val openedFiles = ArrayList<PdfItem>()
    private val paneFolderFiles = Array(3) { ArrayList<PdfItem>() }
    private val paneFolderUri = arrayOfNulls<String>(3)
    private val paneFolderName = arrayOf("", "", "")
    private var treeTarget = 0
    private var paneCount = 2
    private var active = 0
    private var chromeHidden = false
    private var orientMode = 0
    private lateinit var toolbar: LinearLayout
    private lateinit var showStrip: TextView
    private lateinit var orientBtn: Button
    private val orientNames = arrayOf("Auto", "Locked", "Portrait", "Landscape")

    fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    fun toast(s: String) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    }

    fun mkBtn(text: String): Button {
        val b = Button(this)
        b.text = text
        b.textSize = 12f
        b.isAllCaps = false
        b.setPadding(dp(2), 0, dp(2), 0)
        b.minWidth = 0
        b.minimumWidth = 0
        b.minHeight = 0
        b.minimumHeight = dp(34)
        b.isFocusable = false
        b.isFocusableInTouchMode = false
        return b
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN)
        prefs = getSharedPreferences("pdfviewer", Context.MODE_PRIVATE)

        rootL = LinearLayout(this)
        rootL.orientation = LinearLayout.VERTICAL
        rootL.setBackgroundColor(0xFF202020.toInt())
        rootL.fitsSystemWindows = true
        rootL.isFocusableInTouchMode = true

        val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
        val match = ViewGroup.LayoutParams.MATCH_PARENT

        // slim strip, visible only while the toolbars are hidden (outside the PDF area)
        showStrip = TextView(this)
        showStrip.text = "▼  Show toolbar"
        showStrip.textSize = 11f
        showStrip.setTextColor(Color.WHITE)
        showStrip.gravity = Gravity.CENTER
        showStrip.setBackgroundColor(0xFF1565C0.toInt())
        showStrip.visibility = View.GONE
        showStrip.setOnClickListener { setChromeHidden(false) }
        rootL.addView(showStrip, LinearLayout.LayoutParams(match, dp(22)))

        // toolbar: two rows of equal-weight buttons
        toolbar = LinearLayout(this)
        toolbar.orientation = LinearLayout.VERTICAL
        val row1 = LinearLayout(this)
        row1.orientation = LinearLayout.HORIZONTAL
        val row2 = LinearLayout(this)
        row2.orientation = LinearLayout.HORIZONTAL
        val bOpen = mkBtn("Open PDFs")
        val bFolder = mkBtn("Folder")
        val bList = mkBtn("File list")
        paneBtn = mkBtn("Panes: 2")
        orientBtn = mkBtn("Orient: Auto")
        val bHide = mkBtn("Hide bars")
        bOpen.setOnClickListener { openFiles() }
        bFolder.setOnClickListener { openFolder() }
        bList.setOnClickListener { showFilesDialog(active) }
        paneBtn.setOnClickListener { setPaneCount(if (paneCount >= 3) 1 else paneCount + 1) }
        orientBtn.setOnClickListener { showOrientDialog() }
        bHide.setOnClickListener { setChromeHidden(true) }
        row1.addView(bOpen, LinearLayout.LayoutParams(0, wrap, 1f))
        row1.addView(bFolder, LinearLayout.LayoutParams(0, wrap, 1f))
        row1.addView(bList, LinearLayout.LayoutParams(0, wrap, 1f))
        row2.addView(paneBtn, LinearLayout.LayoutParams(0, wrap, 1f))
        row2.addView(orientBtn, LinearLayout.LayoutParams(0, wrap, 1f))
        row2.addView(bHide, LinearLayout.LayoutParams(0, wrap, 1f))
        toolbar.addView(row1, LinearLayout.LayoutParams(match, wrap))
        toolbar.addView(row2, LinearLayout.LayoutParams(match, wrap))
        rootL.addView(toolbar, LinearLayout.LayoutParams(match, wrap))

        status = TextView(this)
        status.setTextColor(Color.LTGRAY)
        status.textSize = 11f
        status.maxLines = 2
        status.ellipsize = TextUtils.TruncateAt.END
        status.setPadding(dp(6), dp(2), dp(6), dp(2))
        rootL.addView(status, LinearLayout.LayoutParams(match, wrap))

        container = LinearLayout(this)
        container.orientation = LinearLayout.HORIZONTAL
        rootL.addView(container, LinearLayout.LayoutParams(match, 0, 1f))

        for (i in 0 until 3) {
            panes.add(PdfPane(this, i))
        }

        setContentView(rootL)
        rootL.requestFocus()

        loadOpened()
        restoreSession()
        setPaneCount(paneCount)
        orientMode = prefs.getInt("orient", 0).coerceIn(0, 3)
        applyOrientation()
        chromeHidden = prefs.getBoolean("chromeHidden", false)
        applyChrome()
        handleViewIntent(intent)

        val oldFolder = prefs.getString("folder", null)
        for (i in 0 until 3) {
            val fs = prefs.getString("folder" + i, oldFolder)
            if (fs != null) {
                paneFolderUri[i] = fs
                scanFolder(i, Uri.parse(fs), false)
            }
        }
        updateStatus()
    }

    // ───── hide / show all toolbars ─────
    fun setChromeHidden(h: Boolean) {
        chromeHidden = h
        prefs.edit().putBoolean("chromeHidden", h).apply()
        applyChrome()
    }

    @Suppress("DEPRECATION")
    private fun applyChrome() {
        val showBars = !chromeHidden
        toolbar.visibility = if (showBars) View.VISIBLE else View.GONE
        status.visibility = if (showBars) View.VISIBLE else View.GONE
        showStrip.visibility = if (showBars) View.GONE else View.VISIBLE
        for (p in panes) p.setChrome(showBars)
        var flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        if (chromeHidden) {
            flags = flags or View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        }
        window.decorView.systemUiVisibility = flags
    }

    // ───── orientation lock ─────
    private fun applyOrientation() {
        requestedOrientation = when (orientMode) {
            1 -> ActivityInfo.SCREEN_ORIENTATION_LOCKED
            2 -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            3 -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            else -> ActivityInfo.SCREEN_ORIENTATION_SENSOR
        }
        orientBtn.text = "Orient: " + orientNames[orientMode]
    }

    private fun setOrientMode(m: Int) {
        orientMode = m.coerceIn(0, 3)
        prefs.edit().putInt("orient", orientMode).apply()
        applyOrientation()
    }

    private fun showOrientDialog() {
        val items = arrayOf(
            "Auto-rotate (follow phone)",
            "Lock current orientation",
            "Portrait",
            "Landscape"
        )
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Screen orientation")
            .setSingleChoiceItems(items, orientMode) { d, which ->
                setOrientMode(which)
                d.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (chromeHidden) {
            setChromeHidden(false)
        } else {
            super.onBackPressed()
        }
    }

    // ───── focus / keyboard helpers ─────
    fun releaseFocus() {
        currentFocus?.clearFocus()
        rootL.requestFocus()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(rootL.windowToken, 0)
    }

    override fun dispatchKeyEvent(ev: KeyEvent): Boolean {
        if (ev.action == KeyEvent.ACTION_DOWN) {
            if (handleKey(ev.keyCode, ev.isCtrlPressed, currentFocus is EditText)) return true
        }
        return super.dispatchKeyEvent(ev)
    }

    private fun handleKey(code: Int, ctrl: Boolean, inEdit: Boolean): Boolean {
        val p = panes[active]
        if (ctrl) {
            return when (code) {
                KeyEvent.KEYCODE_O -> { openFiles(); true }
                KeyEvent.KEYCODE_D -> { openFolder(); true }
                KeyEvent.KEYCODE_L -> { showFilesDialog(active); true }
                KeyEvent.KEYCODE_G -> {
                    if (chromeHidden) setChromeHidden(false)
                    p.focusPageBox()
                    true
                }
                KeyEvent.KEYCODE_H -> { setChromeHidden(!chromeHidden); true }
                KeyEvent.KEYCODE_R -> { setOrientMode((orientMode + 1) % 4); true }
                KeyEvent.KEYCODE_1 -> { setPaneCount(1); true }
                KeyEvent.KEYCODE_2 -> { setPaneCount(2); true }
                KeyEvent.KEYCODE_3 -> { setPaneCount(3); true }
                KeyEvent.KEYCODE_EQUALS, KeyEvent.KEYCODE_PLUS, KeyEvent.KEYCODE_NUMPAD_ADD -> { p.zoomIn(); true }
                KeyEvent.KEYCODE_MINUS, KeyEvent.KEYCODE_NUMPAD_SUBTRACT -> { p.zoomOut(); true }
                KeyEvent.KEYCODE_0, KeyEvent.KEYCODE_NUMPAD_0 -> { p.fit(); true }
                else -> false
            }
        }
        when (code) {
            KeyEvent.KEYCODE_PAGE_DOWN -> {
                p.nextPage()
                return true
            }
            KeyEvent.KEYCODE_PAGE_UP -> {
                p.prevPage()
                return true
            }
            KeyEvent.KEYCODE_ESCAPE -> {
                if (inEdit) {
                    releaseFocus()
                    return true
                }
            }
        }
        if (inEdit) return false
        return when (code) {
            KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_DPAD_RIGHT -> { p.nextPage(); true }
            KeyEvent.KEYCODE_DPAD_LEFT -> { p.prevPage(); true }
            KeyEvent.KEYCODE_H -> { setChromeHidden(!chromeHidden); true }
            KeyEvent.KEYCODE_R -> { setOrientMode((orientMode + 1) % 4); true }
            KeyEvent.KEYCODE_DPAD_DOWN -> { p.pv.wheelPan(0f, -120f, true); true }
            KeyEvent.KEYCODE_DPAD_UP -> { p.pv.wheelPan(0f, 120f, true); true }
            KeyEvent.KEYCODE_MOVE_HOME -> { p.goToPage(0, 1); true }
            KeyEvent.KEYCODE_MOVE_END -> { p.goToPage(p.count - 1, 1); true }
            KeyEvent.KEYCODE_PLUS, KeyEvent.KEYCODE_EQUALS, KeyEvent.KEYCODE_NUMPAD_ADD -> { p.zoomIn(); true }
            KeyEvent.KEYCODE_MINUS, KeyEvent.KEYCODE_NUMPAD_SUBTRACT -> { p.zoomOut(); true }
            KeyEvent.KEYCODE_TAB -> {
                if (paneCount > 1) setActive((active + 1) % paneCount)
                true
            }
            else -> false
        }
    }

    // ───── panes ─────
    fun setPaneCount(n: Int) {
        paneCount = n.coerceIn(1, 3)
        paneBtn.text = "Panes: " + paneCount
        container.removeAllViews()
        for (i in 0 until paneCount) {
            val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            lp.setMargins(dp(2), dp(2), dp(2), dp(2))
            container.addView(panes[i].root, lp)
        }
        setActive(active)
    }

    fun setActive(i: Int) {
        active = i.coerceIn(0, paneCount - 1)
        for (k in 0 until panes.size) {
            panes[k].setActive(k == active)
        }
        updateStatus()
    }

    fun onPaneChanged(p: PdfPane) {
        val f = p.item
        if (f != null) {
            prefs.edit().putInt(pageKey(f.uri), p.page).apply()
        }
        updateStatus()
    }

    fun updateStatus() {
        if (panes.isEmpty() || !::status.isInitialized) return
        val p = panes[active]
        val f = p.item
        val sb = StringBuilder()
        sb.append("Pane ").append(active + 1).append("/").append(paneCount)
        if (f != null) {
            sb.append(" | ").append(f.name)
            sb.append(" | page ").append(p.page + 1).append(" / ").append(p.count)
            sb.append(" | ").append((p.pv.zoom * 100).toInt()).append("%")
        } else {
            sb.append(" | empty - use Open PDFs, Folder or File list")
        }
        status.text = sb.toString()
    }

    private fun pageKey(u: Uri): String = "pg_" + u.toString().hashCode()

    fun openInPane(i: Int, f: PdfItem) {
        val idx = i.coerceIn(0, 2)
        val savedPage = prefs.getInt(pageKey(f.uri), 0)
        addOpened(f)
        saveOpened()
        panes[idx].openDoc(f, savedPage, 0f, 0f, 1f)
        setActive(idx)
    }

    // ───── opening files / folder ─────
    private fun openFiles() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
        i.addCategory(Intent.CATEGORY_OPENABLE)
        i.type = "application/pdf"
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        startActivityForResult(i, REQ_OPEN)
    }

    private fun openFolder() {
        openFolderFor(active)
    }

    private fun openFolderFor(pane: Int) {
        treeTarget = pane.coerceIn(0, 2)
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        i.addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
        )
        startActivityForResult(i, REQ_TREE)
    }

    private fun queryName(u: Uri): String {
        var n: String? = null
        try {
            contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) n = c.getString(0)
            }
        } catch (t: Throwable) {
        }
        val r = n
        return if (r.isNullOrEmpty()) "document.pdf" else r
    }

    private fun persist(u: Uri) {
        try {
            contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (t: Throwable) {
        }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data == null) return
        if (requestCode == REQ_OPEN) {
            val uris = ArrayList<Uri>()
            val cd = data.clipData
            if (cd != null) {
                for (i in 0 until cd.itemCount) {
                    val u = cd.getItemAt(i).uri
                    if (u != null) uris.add(u)
                }
            } else {
                val u = data.data
                if (u != null) uris.add(u)
            }
            if (uris.isEmpty()) return
            val items = ArrayList<PdfItem>()
            for (u in uris) {
                persist(u)
                val f = PdfItem(u, queryName(u))
                addOpened(f)
                items.add(f)
            }
            saveOpened()
            if (items.size == 1) {
                openInPane(active, items[0])
            } else {
                val n = min(items.size, 3)
                setPaneCount(n)
                for (i in 0 until n) {
                    openInPane(i, items[i])
                }
                setActive(0)
            }
        } else if (requestCode == REQ_TREE) {
            val tree = data.data ?: return
            persist(tree)
            val pi = treeTarget
            paneFolderUri[pi] = tree.toString()
            prefs.edit().putString("folder" + pi, tree.toString()).apply()
            scanFolder(pi, tree, true)
        }
    }

    private fun handleViewIntent(i: Intent?) {
        if (i == null || i.action != Intent.ACTION_VIEW) return
        val u = i.data ?: return
        persist(u)
        val f = PdfItem(u, queryName(u))
        addOpened(f)
        saveOpened()
        openInPane(active, f)
    }

    // Most recently opened file goes to the top of the history.
    private fun addOpened(f: PdfItem) {
        val key = f.uri.toString()
        var k = openedFiles.size - 1
        while (k >= 0) {
            if (openedFiles[k].uri.toString() == key) openedFiles.removeAt(k)
            k--
        }
        openedFiles.add(0, f)
        while (openedFiles.size > 500) openedFiles.removeAt(openedFiles.size - 1)
    }

    private fun confirmClearHistory() {
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Clear history?")
            .setMessage("Forget all " + openedFiles.size + " opened PDFs and their remembered pages? PDFs open in the panes stay open.")
            .setPositiveButton("Clear") { _, _ -> clearHistory() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun clearHistory() {
        openedFiles.clear()
        saveOpened()
        val ed = prefs.edit()
        for (k in ArrayList<String>(prefs.all.keys)) {
            if (k.startsWith("pg_")) ed.remove(k)
        }
        ed.apply()
        toast("History cleared")
    }

    private fun saveOpened() {
        val arr = JSONArray()
        for (f in openedFiles) {
            arr.put(JSONObject().put("u", f.uri.toString()).put("n", f.name))
        }
        prefs.edit().putString("opened", arr.toString()).apply()
    }

    private fun loadOpened() {
        val s = prefs.getString("opened", null) ?: return
        try {
            val arr = JSONArray(s)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val u = o.optString("u", "")
                if (u.isEmpty()) continue
                openedFiles.add(PdfItem(Uri.parse(u), o.optString("n", "document.pdf")))
            }
        } catch (t: Throwable) {
        }
    }

    // Scans the chosen folder (and sub-folders) for PDFs on a background thread.
    private fun folderLabel(tree: Uri): String {
        try {
            val s = DocumentsContract.getTreeDocumentId(tree).substringAfterLast('/').substringAfterLast(':')
            return if (s.isEmpty()) "storage" else s
        } catch (t: Throwable) {
            return "folder"
        }
    }

    private fun scanFolder(pane: Int, tree: Uri, showDialog: Boolean) {
        if (showDialog) toast("Scanning folder...")
        Thread {
            val list = ArrayList<PdfItem>()
            try {
                val rootId = DocumentsContract.getTreeDocumentId(tree)
                val stack = ArrayList<Pair<String, String>>()
                stack.add(Pair(rootId, ""))
                while (stack.isNotEmpty() && list.size < 50000) {
                    val cur = stack.removeAt(stack.size - 1)
                    val childUri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, cur.first)
                    contentResolver.query(
                        childUri,
                        arrayOf(
                            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                            DocumentsContract.Document.COLUMN_MIME_TYPE
                        ),
                        null, null, null
                    )?.use { c ->
                        while (c.moveToNext()) {
                            val id = c.getString(0) ?: continue
                            val nm = c.getString(1) ?: ""
                            val mt = c.getString(2) ?: ""
                            if (mt == DocumentsContract.Document.MIME_TYPE_DIR) {
                                stack.add(Pair(id, cur.second + nm + "/"))
                            } else if (mt == "application/pdf" || nm.endsWith(".pdf", true)) {
                                list.add(PdfItem(DocumentsContract.buildDocumentUriUsingTree(tree, id), cur.second + nm))
                            }
                        }
                    }
                }
            } catch (t: Throwable) {
            }
            list.sortWith(compareBy<PdfItem> { it.name.lowercase() })
            runOnUiThread {
                paneFolderFiles[pane].clear()
                paneFolderFiles[pane].addAll(list)
                paneFolderName[pane] = folderLabel(tree)
                if (showDialog) {
                    toast("Pane " + (pane + 1) + " folder: " + list.size + " PDF files")
                    showFilesDialog(pane)
                }
                updateStatus()
            }
        }.start()
    }

    // ───── file list dialog (search + tap to open) ─────
    // Per-pane chooser: this pane's own folder + history, Browse PDF, Choose folder, Clear history.
    fun showFilesDialog(pane: Int) {
        val target = pane.coerceIn(0, 2)
        val all = ArrayList<PdfItem>()
        val seen = HashSet<String>()
        for (f in paneFolderFiles[target]) {
            if (seen.add(f.uri.toString())) all.add(f)
        }
        for (f in openedFiles) {
            if (seen.add(f.uri.toString())) all.add(f)
        }
        val shown = ArrayList<PdfItem>(all)
        val histSet = HashSet<String>()
        for (f in openedFiles) histSet.add(f.uri.toString())
        val ctx = this

        val adapter = object : BaseAdapter() {
            override fun getCount(): Int = shown.size
            override fun getItem(position: Int): Any = shown[position]
            override fun getItemId(position: Int): Long = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                val tv: TextView
                if (convertView is TextView) {
                    tv = convertView
                } else {
                    tv = TextView(ctx)
                    tv.textSize = 14f
                    tv.setTextColor(Color.WHITE)
                    tv.setPadding(dp(12), dp(10), dp(12), dp(10))
                    tv.maxLines = 2
                    tv.ellipsize = TextUtils.TruncateAt.MIDDLE
                }
                tv.text = (if (histSet.contains(shown[position].uri.toString())) "★ " else "") + shown[position].name
                return tv
            }
        }

        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(8), dp(8), dp(8), 0)
        val fname = if (paneFolderName[target].isEmpty()) "none" else paneFolderName[target]
        val hint = TextView(this)
        hint.setTextColor(Color.LTGRAY)
        hint.textSize = 12f
        hint.text = "Pane " + (target + 1) + " folder: " + fname + " (" + paneFolderFiles[target].size +
            " PDFs). Tap = open in this pane. Long-press = open in next pane. ★ = in your history."
        box.addView(hint)

        val btnRow = LinearLayout(this)
        btnRow.orientation = LinearLayout.HORIZONTAL
        val bBrowse = mkBtn("Browse PDF")
        val bFolder = mkBtn("Choose folder")
        val bClear = mkBtn("Clear history")
        btnRow.addView(bBrowse, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        btnRow.addView(bFolder, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        btnRow.addView(bClear, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        box.addView(btnRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val search = EditText(this)
        search.hint = "Search..."
        search.setHintTextColor(Color.GRAY)
        search.setTextColor(Color.WHITE)
        search.setSingleLine(true)
        box.addView(search, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val lv = ListView(this)
        lv.adapter = adapter
        val h = (resources.displayMetrics.heightPixels * 0.5f).toInt()
        box.addView(lv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h))

        val dlg = AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Pane " + (target + 1) + " - choose PDF")
            .setView(box)
            .setNegativeButton("Close", null)
            .create()

        bBrowse.setOnClickListener {
            dlg.dismiss()
            setActive(target)
            openFiles()
        }
        bFolder.setOnClickListener {
            dlg.dismiss()
            openFolderFor(target)
        }
        bClear.setOnClickListener {
            dlg.dismiss()
            confirmClearHistory()
        }
        lv.setOnItemClickListener { _, _, pos, _ ->
            if (pos >= 0 && pos < shown.size) {
                val f = shown[pos]
                dlg.dismiss()
                openInPane(target, f)
            }
        }
        lv.setOnItemLongClickListener { _, _, pos, _ ->
            if (pos >= 0 && pos < shown.size) {
                val f = shown[pos]
                dlg.dismiss()
                openInPane((target + 1) % paneCount, f)
            }
            true
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {
            }

            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {
            }

            override fun afterTextChanged(s: Editable?) {
                val q = (s?.toString() ?: "").trim().lowercase()
                shown.clear()
                if (q.isEmpty()) {
                    shown.addAll(all)
                } else {
                    for (f in all) {
                        if (f.name.lowercase().contains(q)) shown.add(f)
                    }
                }
                adapter.notifyDataSetChanged()
            }
        })
        dlg.show()
    }

    // ───── memory: save / restore where the user stopped ─────
    private fun saveSession() {
        try {
            val o = JSONObject()
            o.put("count", paneCount)
            o.put("active", active)
            val arr = JSONArray()
            for (p in panes) {
                arr.put(p.toJson() ?: JSONObject())
            }
            o.put("panes", arr)
            prefs.edit().putString("session", o.toString()).apply()
        } catch (t: Throwable) {
        }
    }

    private fun restoreSession() {
        val s = prefs.getString("session", null) ?: return
        try {
            val o = JSONObject(s)
            paneCount = o.optInt("count", 2).coerceIn(1, 3)
            active = o.optInt("active", 0).coerceIn(0, 2)
            val arr = o.optJSONArray("panes") ?: return
            for (i in 0 until min(arr.length(), 3)) {
                val po = arr.optJSONObject(i) ?: continue
                val u = po.optString("u", "")
                if (u.isEmpty()) continue
                panes[i].openDoc(
                    PdfItem(Uri.parse(u), po.optString("n", "document.pdf")),
                    po.optInt("p", 0),
                    po.optDouble("fx", 0.0).toFloat(),
                    po.optDouble("fy", 0.0).toFloat(),
                    po.optDouble("z", 1.0).toFloat()
                )
            }
        } catch (t: Throwable) {
        }
    }

    override fun onPause() {
        saveSession()
        super.onPause()
    }

    override fun onDestroy() {
        saveSession()
        for (p in panes) p.release()
        super.onDestroy()
    }
}
