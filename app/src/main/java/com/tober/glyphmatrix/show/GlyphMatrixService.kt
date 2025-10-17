package com.tober.glyphmatrix.show

import android.app.Service
import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log

import androidx.core.graphics.createBitmap
import androidx.core.graphics.get
import androidx.core.graphics.scale
import androidx.core.graphics.set

import com.nothing.ketchum.Glyph
import com.nothing.ketchum.GlyphMatrixFrame
import com.nothing.ketchum.GlyphMatrixManager
import com.nothing.ketchum.GlyphMatrixObject

import kotlin.math.ceil
import kotlin.math.sqrt
import kotlin.random.Random
import org.json.JSONArray

class GlyphMatrixService : Service() {
    private val tag = "Glyph Matrix Service"

    private var glyphMatrixManager: GlyphMatrixManager? = null
    private var glyphMatrixManagerCallback: GlyphMatrixManager.Callback? = null
    private var initialized = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private var clearRunnable: Runnable? = null

    private var animationRunnable: Runnable? = null

    private val matrixSize = 25
    private val cx = (matrixSize - 1) / 2.0
    private val cy = (matrixSize - 1) / 2.0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()

        Log.d(tag, "onCreate")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            val preferences = getSharedPreferences(Constants.PREFERENCES_NAME, MODE_PRIVATE)

            val active = preferences.getBoolean(Constants.PREFERENCES_ACTIVE, true)
            if (!active) return START_NOT_STICKY

            clearRunnable?.let { mainHandler.removeCallbacks(it) }
            clearRunnable = null

            animationRunnable?.let { mainHandler.removeCallbacks(it) }
            animationRunnable = null

            if (initialized) onGlyph()
            else onInit()
        } catch (e: Exception) {
            Log.e(tag, "Failed to start service: $e")
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()

        Log.d(tag, "onDestroy")

        initialized = false
    }

    private fun onInit() {
        if (initialized) return

        Log.d(tag, "onInit")

        glyphMatrixManager = GlyphMatrixManager.getInstance(this)
        glyphMatrixManagerCallback = object : GlyphMatrixManager.Callback {
            override fun onServiceConnected(componentName: ComponentName?) {
                Log.d(tag, "Connected: $componentName")

                try {
                    glyphMatrixManager?.register(Glyph.DEVICE_23112)
                    initialized = true

                    Log.d(tag, "Initialized")

                    onGlyph()
                } catch (e: Exception) {
                    Log.e(tag, "Failed initialization: $e")
                }
            }

            override fun onServiceDisconnected(componentName: ComponentName?) {
                Log.d(tag, "Disconnected: $componentName")
                initialized = false
            }
        }

        glyphMatrixManager?.init(glyphMatrixManagerCallback)
    }

    private fun onGlyph() {
        Log.d(tag, "onGlyph")

        val preferences = getSharedPreferences(Constants.PREFERENCES_NAME, MODE_PRIVATE)
        var glyph: Bitmap? = null

        val glyphs = preferences.getString(Constants.PREFERENCES_GLYPHS, null)
        if (!glyphs.isNullOrBlank()) {
            val arr = JSONArray(glyphs)

            if (arr.length() > 0) {
                val random = Random.nextInt(0, arr.length())
                glyph = BitmapFactory.decodeFile(arr.getJSONObject(random).optString(Constants.GLYPH_GLYPH))
            }
        }

        if (glyph == null) return

        val timeout = preferences.getLong(Constants.PREFERENCES_GLYPH_TIMEOUT, 5L).coerceAtLeast(1L) * 1000L

        if (preferences.getBoolean(Constants.PREFERENCES_ANIMATE_GLYPHS, true)) {
            val speed = preferences.getLong(Constants.PREFERENCES_ANIMATE_SPEED, 10L).coerceAtLeast(1L)

            showAnimated(glyph, timeout, speed)
        } else {
            showSimple(glyph, timeout)
        }
    }

    private fun showSimple(glyph: Bitmap, timeout: Long) {
        try {
            val objBuilder = GlyphMatrixObject.Builder()
            val image = objBuilder
                .setImageSource(glyph)
                .setScale(100)
                .setOrientation(0)
                .setPosition(0, 0)
                .setReverse(false)
                .build()

            val frameBuilder = GlyphMatrixFrame.Builder()
            val frame = frameBuilder.addTop(image).build(this)
            val rendered = frame.render()

            glyphMatrixManager?.setAppMatrixFrame(rendered)
        } catch (e: Exception) {
            Log.e(tag, "Failed to show glyph: $e")
        }

        val runnable = Runnable {
            try {
                glyphMatrixManager?.closeAppMatrix()
            } catch (e: Exception) {
                Log.e(tag, "Failed to stop glyph: $e")
            } finally {
                clearRunnable = null
            }
        }

        clearRunnable = runnable
        mainHandler.postDelayed(runnable, timeout)
    }

    private fun showAnimated(glyph: Bitmap, timeout: Long, speed: Long) {
        val src = glyph.scale(matrixSize, matrixSize)
        val maxRadius = ceil(sqrt(cx * cx + cy * cy)).toInt()

        var radius = 0

        val runnable = object : Runnable {
            override fun run() {
                if (radius <= maxRadius) {
                    val masked = buildMaskedFrame(radius, src)
                    try {
                        val objBuilder = GlyphMatrixObject.Builder()
                        val image = objBuilder
                            .setImageSource(masked)
                            .setScale(100)
                            .setOrientation(0)
                            .setPosition(0, 0)
                            .setReverse(false)
                            .build()

                        val frameBuilder = GlyphMatrixFrame.Builder()
                        val frame = frameBuilder.addTop(image).build(this@GlyphMatrixService)
                        val rendered = frame.render()
                        glyphMatrixManager?.setAppMatrixFrame(rendered)
                    } catch (e: Exception) {
                        Log.e(tag, "Failed to show glyph: $e")
                    }

                    radius++

                    mainHandler.postDelayed(this, speed)
                } else {
                    animationRunnable = null

                    val runnable = Runnable {
                        hideAnimated(glyph, speed)
                    }

                    clearRunnable = runnable
                    mainHandler.postDelayed(runnable, timeout)
                }
            }
        }

        animationRunnable = runnable
        mainHandler.post(runnable)
    }

    private fun hideAnimated(glyph: Bitmap, speed: Long) {
        clearRunnable = null

        val src = glyph.scale(matrixSize, matrixSize)
        val maxRadius = ceil(sqrt(cx * cx + cy * cy)).toInt()

        var radius = maxRadius
        val runnable = object : Runnable {
            override fun run() {
                if (radius >= 0) {
                    val masked = buildMaskedFrame(radius, src)
                    try {
                        val objBuilder = GlyphMatrixObject.Builder()
                        val image = objBuilder
                            .setImageSource(masked)
                            .setScale(100)
                            .setOrientation(0)
                            .setPosition(0, 0)
                            .setReverse(false)
                            .build()

                        val frameBuilder = GlyphMatrixFrame.Builder()
                        val frame = frameBuilder.addTop(image).build(this@GlyphMatrixService)
                        val rendered = frame.render()
                        glyphMatrixManager?.setAppMatrixFrame(rendered)
                    } catch (e: Exception) {
                        Log.e(tag, "Failed to show glyph: $e")
                    }

                    radius--

                    mainHandler.postDelayed(this, speed)
                } else {
                    animationRunnable = null

                    try {
                        glyphMatrixManager?.closeAppMatrix()
                    } catch (e: Exception) {
                        Log.e(tag, "Failed to stop glyph: $e")
                    }
                }
            }
        }

        animationRunnable = runnable
        mainHandler.post(runnable)
    }

    private fun buildMaskedFrame(radius: Int, src: Bitmap): Bitmap {
        val out = createBitmap(matrixSize, matrixSize)
        val rSq = radius * radius
        for (y in 0 until matrixSize) {
            val dy = (y - cy)
            val dySq = dy * dy
            for (x in 0 until matrixSize) {
                val dx = (x - cx)
                val distSq = dx * dx + dySq
                if (distSq <= rSq) {
                    out[x, y] = src[x, y]
                } else {
                    out[x, y] = 0
                }
            }
        }
        return out
    }
}
