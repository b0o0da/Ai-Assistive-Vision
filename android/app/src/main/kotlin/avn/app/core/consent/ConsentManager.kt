package avn.app.core.consent

import android.content.SharedPreferences

/** One switch per capability (WS28-01). Sensor scopes gate starting the sensor; cloud scopes gate uploads. */
enum class ConsentScope(val label: String) {
    CAMERA("Use the camera to warn about obstacles and read text"),
    MICROPHONE("Listen for the wake word and voice commands"),
    SCREEN_READING("Read what is on the phone screen"),
    CLOUD_FRAMES("Send camera pictures to the cloud for descriptions"),
    CLOUD_SCREEN("Send screen content to the cloud for summaries"),
    FACES("Recognise people who agreed to be enrolled")
}

/** What [ConsentGuard] reads. [ConsentManager] in the app, [FakeConsentStore] in tests. */
interface ConsentStore {
    fun isGranted(scope: ConsentScope): Boolean
}

/** Every sensor start and cloud call checks consent through [ConsentGuard]. A grant is stored as its timestamp. */
class ConsentManager(private val prefs: SharedPreferences, private val now: () -> Long = System::currentTimeMillis) :
    ConsentStore {
    fun grant(scope: ConsentScope) = prefs.edit().putLong(scope.name, now()).apply()

    fun revoke(scope: ConsentScope) = prefs.edit().remove(scope.name).apply()

    override fun isGranted(scope: ConsentScope): Boolean = prefs.contains(scope.name)

    fun grantedAt(scope: ConsentScope): Long? = if (isGranted(scope)) prefs.getLong(scope.name, 0) else null
}
