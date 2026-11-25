package avn.app.screen.accessibility

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
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
        WindowEventRouter.route(event.eventType, event.packageName, event.className, activeWindow)
    }

    override fun onInterrupt() = Unit
}
