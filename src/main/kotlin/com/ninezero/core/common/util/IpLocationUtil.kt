package com.ninezero.core.common.util

import io.ktor.client.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
private data class IpApiResponse(
    val city: String? = null,
    val country_code: String? = null
)

object IpLocationUtil {
    private val client = HttpClient {
        install(HttpTimeout) {
            requestTimeoutMillis = 3_000
        }
    }
    private val json = Json { ignoreUnknownKeys = true }

    /** IP 위치 조회 */
    suspend fun getLocationFromIp(ipAddress: String): String? {
        if (ipAddress.startsWith("127.") ||
            ipAddress.startsWith("192.168.") ||
            ipAddress.startsWith("10.") ||
            ipAddress == "0:0:0:0:0:0:0:1" ||
            ipAddress == "::1") {
            return "Local Network"
        }

        return try {
            val response: HttpResponse = client.get("https://ipapi.co/$ipAddress/json/")  // 무료 API 하루 1000 요청 제한
            if (response.status.value in 200..299) {
                val data = json.decodeFromString<IpApiResponse>(response.bodyAsText())
                if (data.city != null && data.country_code != null) {
                    "${data.city}, ${data.country_code}"
                } else {
                    null
                }
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }
}
