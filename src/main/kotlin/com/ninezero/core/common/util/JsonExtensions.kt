package com.ninezero.core.common.util

import kotlinx.serialization.json.Json

fun List<String>.encodeToJson(): String {
    return if (this.isEmpty()) "" else Json.encodeToString(this)
}

fun String?.decodeJsonToList(): List<String> {
    if (this.isNullOrBlank()) return emptyList()
    return try {
        Json.decodeFromString<List<String>>(this)
    } catch (_: Exception) {
        emptyList()
    }
}
