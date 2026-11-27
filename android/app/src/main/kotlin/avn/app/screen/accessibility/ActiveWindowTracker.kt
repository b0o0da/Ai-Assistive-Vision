package avn.app.screen.accessibility

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The app the user is looking at. [activity] is null when the window event did not name one. */
data class ForegroundApp(val packageName: String, val activity: String?, val sinceMs: Long)

/**
 * WS17-02: which app is in front, kept up to date from the accessibility service's
 * TYPE_WINDOW_STATE_CHANGED events (one per app switch, delivered within the service's
 * `notificationTimeout` of 100 ms, see `res/xml/accessibility_service_config.xml`).
 *
 * - Our own app and system UI (status bar, notification shade, keyboards, system dialogs) are
 *   ignored: pulling down the shade or opening the keyboard does not change "the app in front".
 * - A popup or dialog inside the same app (class `android.widget.*` / `android.view.*`) keeps the
 *   current activity name instead of replacing it.
 * - Only package and activity class names are kept, never window titles or screen text.
 *
 * The screen ladder (WS18) reads [foreground] to know which app profile and deny-list applies.
 */
@Singleton
class ActiveWindowTracker(
    private val ownPackage: String,
    private val ignoredPackages: Set<String>,
    private val nowMs: () -> Long
) {
    @Inject constructor() : this(OWN_PACKAGE, SYSTEM_UI_PACKAGES, System::currentTimeMillis)

    private val _foreground = MutableStateFlow<ForegroundApp?>(null)
    val foreground: StateFlow<ForegroundApp?> = _foreground.asStateFlow()

    private val _switches = ArrayDeque<ForegroundApp>()

    /** Last switches, oldest first (at most [HISTORY]); for the debug screen and tests. */
    val switches: List<ForegroundApp> get() = synchronized(this) { _switches.toList() }

    /** Returns true when the foreground app changed. */
    @Synchronized
    fun onWindowStateChanged(packageName: String?, className: String?): Boolean {
        val pkg = packageName?.trim().orEmpty()
        if (pkg.isEmpty() || pkg == ownPackage || pkg in ignoredPackages) return false
        val activity = className?.trim()?.takeIf { it.isNotEmpty() && !isWidget(it) }
        val current = _foreground.value
        if (current != null && current.packageName == pkg) {
            if (activity != null && activity != current.activity) _foreground.value = current.copy(activity = activity)
            return false
        }
        val next = ForegroundApp(pkg, activity, nowMs())
        _foreground.value = next
        _switches.addLast(next)
        while (_switches.size > HISTORY) _switches.removeFirst()
        return true
    }

    private fun isWidget(className: String) =
        className.startsWith("android.widget.") || className.startsWith("android.view.")

    companion object {
        const val OWN_PACKAGE = "avn.app"
        const val HISTORY = 20

        /** System UI and input methods: never "the app in front". */
        val SYSTEM_UI_PACKAGES = setOf(
            "android",
            "com.android.systemui",
            "com.android.permissioncontroller",
            "com.google.android.permissioncontroller",
            "com.google.android.inputmethod.latin",
            "com.samsung.android.honeyboard",
            "com.touchtype.swiftkey",
            "com.sec.android.inputmethod"
        )
    }
}

/** Keeps the Android event handling in the service thin and testable on a laptop. */
object WindowEventRouter {
    /** Same value as `AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED`. */
    const val TYPE_WINDOW_STATE_CHANGED = 0x00000020

    fun route(
        eventType: Int,
        packageName: CharSequence?,
        className: CharSequence?,
        tracker: ActiveWindowTracker
    ): Boolean = eventType == TYPE_WINDOW_STATE_CHANGED &&
        tracker.onWindowStateChanged(packageName?.toString(), className?.toString())
}
