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
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/**
 * Interfaces:
 *  - touch / mouse: Extract and Rebuild buttons
 *  - keyboard: hold Ctrl and type a label (see KeyNav)
 *  - share: Gallery → Share a video (extract) or several images (rebuild)
 *  - API for the AI / other features:
 *      action com.themasihzn.frameextractor.EXTRACT  + data = video uri
 *      action com.themasihzn.frameextractor.REBUILD  + EXTRA_STREAM = image uris
 */
class MainActivity : Activity() {
    companion object {
        const val ACTION_EXTRACT = "com.themasihzn.frameextractor.EXTRACT"
        const val ACTION_REBUILD = "com.themasihzn.frameextractor.REBUILD"
        private const val REQ_VIDEO = 1
        private const val REQ_IMAGES = 2
    }

    private lateinit var status: TextView
    private lateinit var bar: ProgressBar
    private lateinit var keyNav: KeyNav
    @Volatile private var busy = false

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun smallButton(label: String) = Button(this).apply {
        text = label
        textSize = 13f
        isAllCaps = false
        minHeight = 0; minimumHeight = dp(36)
        minWidth = 0; minimumWidth = dp(56)
        setPadding(dp(8), 0, dp(8), 0)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Compact layout: fits the smallest multi-window / pop-up size.
        val extract = smallButton("Extract")
        val rebuild = smallButton("Rebuild")
        bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100; visibility = View.GONE
        }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(extract)
            addView(rebuild, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(4) })
            addView(bar, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(8) })
        }
        val hud = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.parseColor("#F6C453"))
            visibility = View.GONE
        }
        status = TextView(this).apply {
            textSize = 12f
            text = "Extract: video → changed frames. Rebuild: frames → video. Ctrl+type a label to press it."
            setPadding(0, dp(4), 0, 0)
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(6))
            addView(top); addView(hud); addView(status)
        }
        setContentView(ScrollView(this).apply { addView(col) })
        keyNav = KeyNav(this, hud)

        extract.setOnClickListener {
            if (busy) return@setOnClickListener
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE); type = "video/*"
            }, REQ_VIDEO)
        }
        rebuild.setOnClickListener {
            if (busy) return@setOnClickListener
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE); type = "image/*"
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            }, REQ_IMAGES)
        }
        handleIntent(intent)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        keyNav.dispatch(event) || super.dispatchKeyEvent(event)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    @Suppress("DEPRECATION")
    private fun streams(i: Intent): List<Uri> {
        val list: List<Uri>? = if (Build.VERSION.SDK_INT >= 33)
            i.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else i.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
        val one: Uri? = if (list == null) {
            if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else i.getParcelableExtra(Intent.EXTRA_STREAM)
        } else null
        return list ?: listOfNotNull(one)
    }

    private fun handleIntent(i: Intent?) {
        i ?: return
        when (i.action) {
            ACTION_EXTRACT -> (i.data ?: streams(i).firstOrNull())?.let { runExtract(it) }
            ACTION_REBUILD -> streams(i).takeIf { it.isNotEmpty() }?.let { runRebuild(it) }
            Intent.ACTION_SEND -> {
                val u = streams(i).firstOrNull() ?: return
                if (i.type?.startsWith("image") == true) runRebuild(listOf(u)) else runExtract(u)
            }
            Intent.ACTION_SEND_MULTIPLE -> streams(i).takeIf { it.isNotEmpty() }?.let { runRebuild(it) }
        }
    }

    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (res != RESULT_OK || data == null) return
        when (req) {
            REQ_VIDEO -> data.data?.let { runExtract(it) }
            REQ_IMAGES -> {
                val uris = mutableListOf<Uri>()
                data.clipData?.let { c -> for (k in 0 until c.itemCount) uris += c.getItemAt(k).uri }
                if (uris.isEmpty()) data.data?.let { uris += it }
                if (uris.isNotEmpty()) runRebuild(uris)
            }
        }
    }

    private fun runExtract(uri: Uri) = work { extract(uri) }
    private fun runRebuild(uris: List<Uri>) = work {
        say(Rebuilder(this) { s, p -> progress(p); say(s) }.rebuild(uris))
        progress(100)
    }

    private fun work(job: () -> Unit) {
        if (busy) return
        busy = true
        progress(0)
        Thread {
            try { job() } catch (e: Exception) { say("Error: ${e.message}") }
            busy = false
        }.start()
    }

    private fun say(s: String) = runOnUiThread { status.text = s }
    private fun progress(p: Int) = runOnUiThread { bar.visibility = View.VISIBLE; bar.progress = p }

    private fun extract(uri: Uri) {
        val r = MediaMetadataRetriever()
        r.setDataSource(this, uri)
        val durMs = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 0L
        // Each run gets its own album folder.
        val folder = "Pictures/frameExtractor/" +
            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        var last: IntArray? = null
        var saved = 0
        var t = 0L
        while (t <= durMs) {
            val f = r.getFrameAtTime(t * 1000, MediaMetadataRetriever.OPTION_CLOSEST)
            if (f != null) {
                val sig = signature(f)
                if (last == null || diff(last, sig) > 6.0) {
                    save(f, folder, saved, t)
                    saved++
                    last = sig
                }
                f.recycle()
            }
            progress(if (durMs > 0) (t * 100 / durMs).toInt() else 0)
            say("${t / 1000}/${durMs / 1000}s · kept $saved")
            t += 1000
        }
        r.release()
        progress(100)
        say("Done: $saved frames in $folder")
    }

    private fun signature(b: Bitmap): IntArray {
        val s = Bitmap.createScaledBitmap(b, 48, 96, true)
        val px = IntArray(48 * 96)
        s.getPixels(px, 0, 48, 0, 0, 48, 96)
        if (s !== b) s.recycle()
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

    // File name carries the timestamp (_t<ms>) so Rebuild restores the original timing.
    private fun save(b: Bitmap, folder: String, n: Int, tMs: Long) {
        val v = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "frame_${"%04d".format(n)}_t$tMs.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, folder)
        }
        val out = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v) ?: return
        contentResolver.openOutputStream(out)?.use { b.compress(Bitmap.CompressFormat.JPEG, 92, it) }
    }
}
