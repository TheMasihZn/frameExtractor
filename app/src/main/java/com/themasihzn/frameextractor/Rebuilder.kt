package com.themasihzn.frameextractor

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns

/**
 * Rebuilds a video from extracted key frames. Each frame is held until the
 * next one's timestamp (read from "_t<ms>" in the file name), so a screen
 * recording comes back with its original timing. Frames without a timestamp
 * are shown one second each, in name order.
 */
class Rebuilder(private val ctx: Context, private val say: (String, Int) -> Unit) {

    private data class Key(val uri: Uri, val name: String, var tMs: Long)

    fun rebuild(uris: List<Uri>): String {
        val keys = uris.map { Key(it, nameOf(it), -1) }.toMutableList()
        val re = Regex("_t(\\d+)")
        keys.forEach { k -> k.tMs = re.find(k.name)?.groupValues?.get(1)?.toLong() ?: -1 }
        if (keys.any { it.tMs < 0 }) {
            keys.sortBy { it.name }
            keys.forEachIndexed { i, k -> k.tMs = i * 1000L }
        } else keys.sortBy { it.tMs }
        val t0 = keys.first().tMs
        keys.forEach { it.tMs -= t0 }

        val first = decode(keys.first().uri) ?: error("Can't read first image")
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        val caps = codec.codecInfo.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC).videoCapabilities
        var w = first.width and 1.inv()
        var h = first.height and 1.inv()
        while (!caps.isSizeSupported(w, h) && w > 64) {
            w = (w * 3 / 4) and 15.inv(); h = (h * 3 / 4) and 15.inv()
        }
        first.recycle()

        val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BIT_RATE, (w * h * 2).coerceAtMost(12_000_000))
            setInteger(MediaFormat.KEY_FRAME_RATE, 1)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 5)
        }
        codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()

        val name = "rebuilt_${System.currentTimeMillis()}.mp4"
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/frameExtractor")
        }
        val outUri = ctx.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("Can't create output video")
        val pfd = ctx.contentResolver.openFileDescriptor(outUri, "rw") ?: error("Can't open output")
        val muxer = MediaMuxer(pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var track = -1
        val info = MediaCodec.BufferInfo()

        fun drain(endOfStream: Boolean) {
            while (true) {
                val idx = codec.dequeueOutputBuffer(info, if (endOfStream) 10_000 else 0)
                when {
                    idx == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!endOfStream) return
                    idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        track = muxer.addTrack(codec.outputFormat); muxer.start()
                    }
                    idx >= 0 -> {
                        val buf = codec.getOutputBuffer(idx)!!
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                        if (info.size > 0 && track >= 0) muxer.writeSampleData(track, buf, info)
                        codec.releaseOutputBuffer(idx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                    }
                }
            }
        }

        val endMs = keys.last().tMs + 1000
        var frames = 0
        for ((i, k) in keys.withIndex()) {
            val bmp = decode(k.uri) ?: continue
            val yuv = toYuv(bmp, w, h)
            bmp.recycle()
            val until = if (i + 1 < keys.size) keys[i + 1].tMs else endMs
            var t = k.tMs
            // Hold the frame once per second until the next change (keeps seeking working).
            while (t < until) {
                queue(codec, yuv, w, h, t * 1000) { drain(false) }
                frames++
                t += 1000
            }
            say("Rebuilding ${i + 1}/${keys.size} frames", ((i + 1) * 100 / keys.size))
        }
        var eos: Int
        do { eos = codec.dequeueInputBuffer(10_000); drain(false) } while (eos < 0)
        codec.queueInputBuffer(eos, 0, 0, endMs * 1000, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
        drain(true)
        codec.stop(); codec.release()
        if (track >= 0) muxer.stop()
        muxer.release(); pfd.close()
        return "Done: Movies/frameExtractor/$name (${endMs / 1000}s, $frames frames)"
    }

    private class Yuv(val y: ByteArray, val u: ByteArray, val v: ByteArray)

    private fun toYuv(src: Bitmap, w: Int, h: Int): Yuv {
        val b = if (src.width == w && src.height == h) src else Bitmap.createScaledBitmap(src, w, h, true)
        val px = IntArray(w * h)
        b.getPixels(px, 0, w, 0, 0, w, h)
        if (b !== src) b.recycle()
        val y = ByteArray(w * h); val u = ByteArray(w * h / 4); val v = ByteArray(w * h / 4)
        for (j in 0 until h) for (i in 0 until w) {
            val c = px[j * w + i]
            val r = Color.red(c); val g = Color.green(c); val bl = Color.blue(c)
            y[j * w + i] = (((66 * r + 129 * g + 25 * bl + 128) shr 8) + 16).toByte()
            if (j % 2 == 0 && i % 2 == 0) {
                val k = (j / 2) * (w / 2) + i / 2
                u[k] = (((-38 * r - 74 * g + 112 * bl + 128) shr 8) + 128).toByte()
                v[k] = (((112 * r - 94 * g - 18 * bl + 128) shr 8) + 128).toByte()
            }
        }
        return Yuv(y, u, v)
    }

    private fun queue(codec: MediaCodec, yuv: Yuv, w: Int, h: Int, ptsUs: Long, idle: () -> Unit) {
        var idx: Int
        do { idx = codec.dequeueInputBuffer(10_000); if (idx < 0) idle() } while (idx < 0)
        val img = codec.getInputImage(idx)!!
        val planes = img.planes
        fun write(p: android.media.Image.Plane, data: ByteArray, pw: Int, ph: Int) {
            val buf = p.buffer
            for (row in 0 until ph) for (col in 0 until pw)
                buf.put(row * p.rowStride + col * p.pixelStride, data[row * pw + col])
        }
        write(planes[0], yuv.y, w, h)
        write(planes[1], yuv.u, w / 2, h / 2)
        write(planes[2], yuv.v, w / 2, h / 2)
        codec.queueInputBuffer(idx, 0, w * h * 3 / 2, ptsUs, 0)
        idle()
    }

    private fun decode(uri: Uri): Bitmap? =
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }

    private fun nameOf(uri: Uri): String =
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null } ?: uri.lastPathSegment ?: ""
}
