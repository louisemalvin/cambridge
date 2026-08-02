package dev.mobilewebcam.sender.connection.control.http

import kotlinx.serialization.json.Json

object ProtocolJson {
    val instance: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }
}
