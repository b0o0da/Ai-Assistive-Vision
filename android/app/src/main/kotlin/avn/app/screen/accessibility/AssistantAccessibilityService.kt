package avn.app.screen.accessibility

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import avn.app.BuildConfig
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Declared so the special access can be granted during onboarding (WS17-01). WS17-02: forwards
 * window-state changes to [ActiveWindowTracker] so the app always knows which app is in front.
 * Node-tree reading arrives with WS17-03; nothing here reads or stores screen content.
 */
@AndroidEntryPoint
class AssistantAccessibilityService : AccessibilityService() {
    @Inject lateinit var activeWindow: ActiveWindowTracker

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val switched = WindowEventRouter.route(event.eventType, event.packageName, event.className, activeWindow)
        if (switched && BuildConfig.DEBUG) {
            // Debug builds only: switch time and package name, never window titles or screen text.
            val now = activeWindow.foreground.value ?: return
            Log.i(LOG_TAG, "${now.sinceMs} ${now.packageName}")
        }
    }

    override fun onInterrupt() = Unit

    companion object {
        const val LOG_TAG = "AVN_WINDOW"
    }
}
