package avn.contracts

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Values mirror contracts/schemas/common.schema.json. Do not rename without a Technical Decision.

@Serializable
enum class Source {
    @SerialName("camera")
    CAMERA,

    @SerialName("voice")
    VOICE,

    @SerialName("screen")
    SCREEN,

    @SerialName("navigation")
    NAVIGATION,

    @SerialName("notification")
    NOTIFICATION,

    @SerialName("system")
    SYSTEM
}

@Serializable
enum class EventType {
    @SerialName("vehicle_approaching")
    VEHICLE_APPROACHING,

    @SerialName("obstacle_ahead")
    OBSTACLE_AHEAD,

    @SerialName("person_close")
    PERSON_CLOSE,

    @SerialName("person_known")
    PERSON_KNOWN,

    @SerialName("person_unknown")
    PERSON_UNKNOWN,

    @SerialName("scene_answer")
    SCENE_ANSWER,

    @SerialName("text_result")
    TEXT_RESULT,

    @SerialName("screen_summary")
    SCREEN_SUMMARY,

    @SerialName("notification")
    NOTIFICATION,

    @SerialName("nav_instruction")
    NAV_INSTRUCTION,

    @SerialName("system_notice")
    SYSTEM_NOTICE,

    @SerialName("sos_countdown")
    SOS_COUNTDOWN
}

@Serializable
enum class Side {
    @SerialName("left")
    LEFT,

    @SerialName("center")
    CENTER,

    @SerialName("right")
    RIGHT
}

/** Hazard distance. Never meters, speed or time-to-collision. */
@Serializable
enum class DistanceBand {
    @SerialName("near")
    NEAR,

    @SerialName("medium")
    MEDIUM,

    @SerialName("far")
    FAR
}

@Serializable
enum class Level {
    @SerialName("critical")
    CRITICAL,

    @SerialName("warning")
    WARNING,

    @SerialName("info")
    INFO
}

@Serializable
enum class ScreenType {
    @SerialName("chat_list")
    CHAT_LIST,

    @SerialName("chat_thread")
    CHAT_THREAD,

    @SerialName("feed_post")
    FEED_POST,

    @SerialName("other")
    OTHER,

    @SerialName("blocked")
    BLOCKED
}

@Serializable
enum class PlanStep {
    @SerialName("launch_app")
    LAUNCH_APP,

    @SerialName("wait")
    WAIT,

    @SerialName("read_screen")
    READ_SCREEN,

    @SerialName("summarize")
    SUMMARIZE,

    @SerialName("scroll")
    SCROLL,

    @SerialName("back")
    BACK,

    @SerialName("tap_label")
    TAP_LABEL,

    @SerialName("navigate")
    NAVIGATE,

    @SerialName("sos")
    SOS,

    @SerialName("describe")
    DESCRIBE,

    @SerialName("ask")
    ASK
}

/** Mirrors envelope.schema.json error.code. Same five values as backend ErrorCode. */
@Serializable
enum class ErrorCode {
    @SerialName("timeout")
    TIMEOUT,

    @SerialName("rate_limited")
    RATE_LIMITED,

    @SerialName("unauthorized")
    UNAUTHORIZED,

    @SerialName("bad_request")
    BAD_REQUEST,

    @SerialName("provider_error")
    PROVIDER_ERROR
}
