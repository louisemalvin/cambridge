package dev.mobilewebcam.sender.control.http

import kotlinx.serialization.json.Json

object ProtocolJson {
    val instance: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }
}
