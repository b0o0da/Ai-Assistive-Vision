package avn.app.voice

import avn.app.core.logging.JsonLinesLogger

typealias VoiceLog = (module: String, event: String, fields: Map<String, Any?>) -> Unit

val silentVoiceLog: VoiceLog = { _, _, _ -> }

fun JsonLinesLogger.asVoiceLog(): VoiceLog = { module, event, fields -> log(module, event, fields = fields) }
