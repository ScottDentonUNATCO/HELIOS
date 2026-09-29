package com.omni.vision

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.WindowManager

/**
 * Gives Helios agents a high-frame-rate 1:1 view of the screen.
 *
 * Capture path: MediaProjection -> VirtualDisplay at NATIVE display metrics
 * (no downscale) -> ImageReader (RGBA_8888) -> VisionHub ring buffer.
 * Agents read VisionHub.ring.latest(): always the newest full-res frame, so a
 * slow vision model never stalls capture.
 *
 * Honest limits (OS-enforced, cannot be bypassed):
 * - The user must accept the system screen-capture consent dialog; consent
 *   is re-requested after every reboot.
 * - FLAG_SECURE windows (banking apps, DRM video) are blacked out by the OS.
 * - Sideload-only: Play policy bans autonomous agents via this API.
 *
 * Kill switch: the persistent notification carries a STOP action that tears
 * down projection immediately ("what the fuck are you doing? Stop that.").
 */
class ScreenCaptureService : Service() {

    companion object {
        const val ACTION_START = "com.omni.app.vision.START"
        const val ACTION_STOP = "com.omni.app.vision.STOP"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_DATA = "data"
        private const val CHANNEL_ID = "helios_vision"
        private const val NOTIF_ID = 41

        fun start(context: Context, resultCode: Int, data: Intent) {
            val i = Intent(context, ScreenCaptureService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_DATA, data)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i)
            else context.startService(i)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, ScreenCaptureService::class.java).setAction(ACTION_STOP))
        }
    }

    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { tearDown(); stopSelf(); return START_NOT_STICKY }
            ACTION_START -> {
                val code = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                val data: Intent? = if (Build.VERSION.SDK_INT >= 33)
                    intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_DATA)
                if (code != Activity.RESULT_OK || data == null) { stopSelf(); return START_NOT_STICKY }
                startCapture(code, data)
            }
        }
        return START_NOT_STICKY
    }

    private fun startCapture(resultCode: Int, data: Intent) {
        val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = mgr.getMediaProjection(resultCode, data)

        // 1:1 — native display metrics, no downscale.
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(metrics)
        val w = metrics.widthPixels; val h = metrics.heightPixels
        val dpi = metrics.densityDpi

        val r = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3)
        r.setOnImageAvailableListener({ rd ->
            val img = rd.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val plane = img.planes[0]
                val buf = plane.buffer
                val rowStride = plane.rowStride
                val pxStride = plane.pixelStride
                val pixels = IntArray(w * h)
                val row = ByteArray(rowStride)
                for (y in 0 until h) {
                    buf.position(y * rowStride)
                    buf.get(row, 0, rowStride)
                    var p = 0
                    for (x in 0 until w) {
                        // RGBA_8888 -> ARGB int
                        val rr = row[p].toInt() and 0xFF
                        val gg = row[p + 1].toInt() and 0xFF
                        val bb = row[p + 2].toInt() and 0xFF
                        val aa = row[p + 3].toInt() and 0xFF
                        pixels[y * w + x] = (aa shl 24) or (rr shl 16) or (gg shl 8) or bb
                        p += pxStride
                    }
                }
                VisionHub.ring.push(Frame(w, h, pixels, System.nanoTime()))
                VisionHub.fps.tick(System.nanoTime())
                VisionHub.capturing = true
            } finally { img.close() }
        }, null)
        reader = r

        display = projection!!.createVirtualDisplay(
            "helios-agent-eyes",
            w, h, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            r.surface, null, null,
        )
        startForeground(NOTIF_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Helios agent eyes", NotificationManager.IMPORTANCE_LOW))
        val stopIntent = PendingIntent.getService(
            this, 0, Intent(this, ScreenCaptureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = if (Build.VERSION.SDK_INT >= 26)
            Notification.Builder(this, CHANNEL_ID) else @Suppress("DEPRECATION") Notification.Builder(this)
        return builder
            .setContentTitle("Helios is watching the screen")
            .setContentText("Agents see what you see. Tap STOP to cut their eyes.")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "STOP", stopIntent)
            .build()
    }

    private fun tearDown() {
        VisionHub.capturing = false
        try { display?.release() } catch (_: Exception) {}
        try { reader?.close() } catch (_: Exception) {}
        try { projection?.stop() } catch (_: Exception) {}
        display = null; reader = null; projection = null
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE)
    }

    override fun onDestroy() { tearDown(); super.onDestroy() }
}

/**
 * Transparent consent trampoline: fires the OS screen-capture dialog and
 * forwards the result to [ScreenCaptureService]. Not exported.
 */
class VisionConsentActivity : Activity() {
    companion object { private const val REQ = 9001 }

    override fun onStart() {
        super.onStart()
        val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        @Suppress("DEPRECATION") startActivityForResult(mgr.createScreenCaptureIntent(), REQ)
    }

    @Deprecated("use Activity Result API on next pass")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ && resultCode == RESULT_OK && data != null)
            ScreenCaptureService.start(this, resultCode, data)
        finish()
    }
}
