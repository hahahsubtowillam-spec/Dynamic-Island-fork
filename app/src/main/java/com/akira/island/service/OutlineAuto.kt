package com.akira.island.service

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import android.view.WindowManager
import com.akira.island.core.IslandBus
import com.akira.island.data.SettingsRepo
import kotlin.math.sqrt

/** Автоконтур: смотрит на цвет рядом с капсулой и включает контур, когда остров сливается с фоном. */
class OutlineAuto(private val service: AccessibilityService, private val bounds: () -> Rect?) {
    private val handler = Handler(Looper.getMainLooper())
    private val check = Runnable { sample() }
    private var last = 0L
    private var busy = false

    fun onEvent() {
        android.util.Log.d("OutlineAuto", "event auto=" + SettingsRepo.current.outlineAuto + " screenOn=" + IslandBus.screenOn.value + " sdk=" + Build.VERSION.SDK_INT)
        if (!SettingsRepo.current.outlineAuto) {
            if (IslandBus.outlineBlend.value) IslandBus.outlineBlend.value = false
            return
        }
        if (Build.VERSION.SDK_INT < 30 || !IslandBus.screenOn.value) return
        handler.removeCallbacks(check)
        handler.postDelayed(check, maxOf(500L, 1000L - (SystemClock.elapsedRealtime() - last)))
    }

    private fun sample() {
        android.util.Log.d("OutlineAuto", "sample busy=" + busy)
        if (busy && SystemClock.elapsedRealtime() - last < 3000L) return
        if (busy) android.util.Log.d("OutlineAuto", "watchdog: previous screenshot lost, resetting")
        if (!SettingsRepo.current.outlineAuto || !IslandBus.screenOn.value) return
        busy = true
        last = SystemClock.elapsedRealtime()
        val started = runCatching {
            service.takeScreenshot(Display.DEFAULT_DISPLAY, service.mainExecutor, object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                    android.util.Log.d("OutlineAuto", "screenshot ok")
                    val buffer = result.hardwareBuffer
                    runCatching {
                        val hw = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                        val soft = hw?.copy(Bitmap.Config.ARGB_8888, false)
                        hw?.recycle()
                        if (soft != null) {
                            decide(soft)
                            soft.recycle()
                        }
                    }
                    buffer.close()
                    busy = false
                }

                override fun onFailure(errorCode: Int) {
                    android.util.Log.d("OutlineAuto", "screenshot failed code=" + errorCode)
                    busy = false
                }
            })
        }.onFailure { android.util.Log.d("OutlineAuto", "takeScreenshot threw " + it) }.isSuccess
        if (!started) busy = false
    }

    private fun capsuleRect(width: Int, d: Float): Rect {
        bounds()?.let { if (it.width() > 1 && it.height() > 1) return it }
        runCatching {
            val wm = service.getSystemService(WindowManager::class.java)
            val cut = wm.currentWindowMetrics.windowInsets.displayCutout
            val r = cut?.boundingRects?.filter { it.top <= 1 && it.width() < width / 2 }?.minByOrNull { it.top }
            if (r != null) return Rect(r)
        }
        return Rect(width / 2 - (40 * d).toInt(), 0, width / 2 + (40 * d).toInt(), (30 * d).toInt())
    }

    private fun decide(bmp: Bitmap) {
        val d = service.resources.displayMetrics.density
        val cap = capsuleRect(bmp.width, d)
        if (cap.height() > (70 * d).toInt()) return
        val gap = (16 * d).toInt()
        val span = (12 * d).toInt()
        val y1 = cap.top.coerceIn(0, bmp.height - 1)
        val y2 = cap.bottom.coerceIn(y1 + 1, bmp.height)
        var r = 0L; var g = 0L; var b = 0L; var n = 0
        for ((a, z) in listOf((cap.left - gap - span) to (cap.left - gap), (cap.right + gap) to (cap.right + gap + span))) {
            val x1 = a.coerceIn(0, bmp.width - 1)
            val x2 = z.coerceIn(x1 + 1, bmp.width)
            val w = x2 - x1
            val h = y2 - y1
            val px = IntArray(w * h)
            bmp.getPixels(px, 0, w, x1, y1, w, h)
            for (p in px) {
                r += (p shr 16) and 255; g += (p shr 8) and 255; b += p and 255; n++
            }
        }
        if (n == 0) return
        val c = SettingsRepo.current.color.toInt()
        val dr = r / n - ((c shr 16) and 255)
        val dg = g / n - ((c shr 8) and 255)
        val db = b / n - (c and 255)
        val dist = sqrt((dr * dr + dg * dg + db * db).toDouble())
        android.util.Log.d("OutlineAuto", "decide cap=" + cap + " dist=" + dist + " color=" + Integer.toHexString(c))
        val now = IslandBus.outlineBlend.value
        val next = when {
            dist < 36 -> true
            dist > 56 -> false
            else -> now
        }
        if (next != now) IslandBus.outlineBlend.value = next
    }
}
