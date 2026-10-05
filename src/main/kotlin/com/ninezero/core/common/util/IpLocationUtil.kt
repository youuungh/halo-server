package com.ninezero.core.common.util

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.Inet6Address
import java.net.InetAddress

@Serializable
private data class IpApiResponse(
    val city: String? = null,
    @SerialName("country_code") val countryCode: String? = null
)

object IpLocationUtil {
    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 3_000
        }
    }

    /** IP 위치 조회 */
    suspend fun getLocationFromIp(ipAddress: String): String? {
        if (isLocalAddress(ipAddress)) {
            return "Local Network"
        }

        return try {
            val response = client.get("https://ipapi.co/$ipAddress/json/")  // 무료 API 하루 1000 요청 제한
            if (response.status.isSuccess()) {
                val data = response.body<IpApiResponse>()
                if (data.city != null && data.countryCode != null) {
                    "${data.city}, ${data.countryCode}"
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

    /** 루프백·사설·링크로컬 주소 판정 */
    private fun isLocalAddress(ipAddress: String): Boolean {
        val address = parseIpLiteral(ipAddress) ?: return false
        return address.isLoopbackAddress || address.isAnyLocalAddress ||
            address.isSiteLocalAddress || address.isLinkLocalAddress ||
            (address is Inet6Address && (address.address[0].toInt() and 0xFE) == 0xFC)  // IPv6 고유 로컬 fc00::/7
    }

    /** DNS 조회 없는 IP 리터럴 해석 */
    private fun parseIpLiteral(ip: String): InetAddress? {
        if (':' in ip) return runCatching { InetAddress.getByName("[$ip]") }.getOrNull()  // 괄호 안은 IPv6 리터럴로만 해석
        val octets = ip.split('.').map { it.toIntOrNull() ?: return null }
        if (octets.size != 4 || octets.any { it !in 0..255 }) return null
        return InetAddress.getByAddress(ByteArray(4) { octets[it].toByte() })
    }
}
