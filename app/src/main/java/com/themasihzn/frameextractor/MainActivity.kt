package com.themasihzn.frameextractor

import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

class MainActivity : Activity() {
    private lateinit var status: TextView
    @Volatile private var busy = false

    private lateinit var bar: ProgressBar

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Compact layout: fits the smallest multi-window / pop-up size.
        val pick = Button(this).apply {
            text = "Pick"
            textSize = 13f
            minHeight = 0; minimumHeight = dp(36)
            minWidth = 0; minimumWidth = dp(56)
            setPadding(dp(8), 0, dp(8), 0)
        }
        bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            visibility = View.GONE
        }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(pick)
            addView(bar, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginStart = dp(8) })
        }
        status = TextView(this).apply {
            textSize = 12f
            text = "Pick or share a video. Changed frames go to Pictures/frameExtractor."
            setPadding(0, dp(4), 0, 0)
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(6))
            addView(top)
            addView(status)
        }
        setContentView(ScrollView(this).apply { addView(col) })
        pick.setOnClickListener {
            if (busy) return@setOnClickListener
            val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "video/*"
            }
            startActivityForResult(i, 1)
        }
        handleShare(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    // Lets you use "Share" from the Gallery straight into this app.
    private fun handleShare(i: Intent?) {
        if (i?.action != Intent.ACTION_SEND) return
        val uri: Uri? = if (Build.VERSION.SDK_INT >= 33)
            i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else @Suppress("DEPRECATION") i.getParcelableExtra(Intent.EXTRA_STREAM)
        uri?.let { start(it) }
    }

    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        val uri = data?.data ?: return
        if (res == RESULT_OK) start(uri)
    }

    private fun start(uri: Uri) {
        if (busy) return
        busy = true
        Thread {
            try { extract(uri) } catch (e: Exception) { say("Error: ${e.message}") }
            busy = false
        }.start()
    }

    private fun say(s: String) = runOnUiThread { status.text = s }

    private fun extract(uri: Uri) {
        val r = MediaMetadataRetriever()
        r.setDataSource(this, uri)
        val durMs = r.extractMetadata(
            MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 0L
        val stamp = System.currentTimeMillis()
        var last: IntArray? = null
        var saved = 0
        var t = 0L
        while (t <= durMs) {
            val f = r.getFrameAtTime(t * 1000, MediaMetadataRetriever.OPTION_CLOSEST)
            if (f != null) {
                val sig = signature(f)
                if (last == null || diff(last, sig) > 6.0) {
                    save(f, stamp, saved)
                    saved++
                    last = sig
                }
                f.recycle()
            }
            val pct = if (durMs > 0) (t * 100 / durMs).toInt() else 0
            runOnUiThread { bar.visibility = View.VISIBLE; bar.progress = pct }
            say("${t / 1000}/${durMs / 1000}s · kept $saved")
            t += 1000
        }
        r.release()
        runOnUiThread { bar.progress = 100 }
        say("Done: $saved frames in Pictures/frameExtractor")
    }

    private fun signature(b: Bitmap): IntArray {
        val s = Bitmap.createScaledBitmap(b, 48, 96, true)
        val px = IntArray(48 * 96)
        s.getPixels(px, 0, 48, 0, 0, 48, 96)
        if (s != b) s.recycle()
        return IntArray(px.size) {
            val c = px[it]
            (Color.red(c) * 30 + Color.green(c) * 59 + Color.blue(c) * 11) / 100
        }
    }

    private fun diff(a: IntArray, b: IntArray): Double {
        var sum = 0L
        for (i in a.indices) sum += abs(a[i] - b[i])
        return sum.toDouble() / a.size
    }

    private fun save(b: Bitmap, stamp: Long, n: Int) {
        val v = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "frame_${stamp}_${"%04d".format(n)}.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/frameExtractor")
        }
        val out = contentResolver.insert(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v) ?: return
        contentResolver.openOutputStream(out)?.use {
            b.compress(Bitmap.CompressFormat.JPEG, 92, it)
        }
    }
}
