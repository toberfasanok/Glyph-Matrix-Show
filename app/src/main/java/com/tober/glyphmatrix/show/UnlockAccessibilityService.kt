package com.tober.glyphmatrix.show

import android.content.Intent
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.view.accessibility.AccessibilityEvent

class UnlockAccessibilityService : AccessibilityService() {
    private val tag = "Unlock Service"

    @Volatile
    private var screenOn: Boolean = false

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        super.onServiceConnected()

        Log.d(tag, "Connected")

        serviceInfo = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                         AccessibilityEvent.TYPE_WINDOWS_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            notificationTimeout = 100
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }

        screenOn = isScreenInteractive()

        Log.d(tag, "Initial screen interactive = $screenOn")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val type = event.eventType
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            type != AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            return
        }

        val currentlyInteractive = isScreenInteractive()

        if (!screenOn && currentlyInteractive) {
            mainHandler.postDelayed({
                if (isScreenInteractive()) {
                    Log.i(tag, "onScreenOn")
                    onScreenOn()
                }
            }, 120L)
        }

        screenOn = currentlyInteractive
    }

    override fun onInterrupt() {}

    private fun isScreenInteractive(): Boolean {
        val pm = getSystemService(POWER_SERVICE) as? PowerManager
        return pm?.isInteractive == true
    }

    private fun onScreenOn() {
        val intent = Intent(this, GlyphMatrixService::class.java)
        startService(intent)
    }
}
