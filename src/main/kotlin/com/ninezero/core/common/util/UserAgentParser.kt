package com.ninezero.core.common.util

data class DeviceInfo(
    val deviceType: String,
    val browser: String,
    val os: String
)

object UserAgentParser {
    fun parse(userAgent: String): DeviceInfo {
        val ua = userAgent.lowercase()
        return DeviceInfo(
            deviceType = parseDeviceType(ua),
            browser = parseBrowser(userAgent),
            os = parseOS(userAgent)
        )
    }

    private fun parseDeviceType(ua: String): String {
        return when {
            ua.contains("mobile") -> "Mobile"
            ua.contains("tablet") || ua.contains("ipad") -> "Tablet"
            else -> "Desktop"
        }
    }

    private fun parseBrowser(userAgent: String): String {
        return when {
            userAgent.contains("Edg/") -> {
                val version = Regex("Edg/(\\d+\\.\\d+\\.\\d+\\.\\d+)").find(userAgent)?.groupValues?.get(1)
                "Edge ${version ?: ""}"
            }
            userAgent.contains("Chrome/") -> {
                val version = Regex("Chrome/(\\d+\\.\\d+\\.\\d+\\.\\d+)").find(userAgent)?.groupValues?.get(1)
                "Chrome ${version ?: ""}"
            }
            userAgent.contains("Firefox/") -> {
                val version = Regex("Firefox/(\\d+\\.\\d+)").find(userAgent)?.groupValues?.get(1)
                "Firefox ${version ?: ""}"
            }
            userAgent.contains("Safari/") && !userAgent.contains("Chrome") -> {
                val version = Regex("Version/(\\d+\\.\\d+)").find(userAgent)?.groupValues?.get(1)
                "Safari ${version ?: ""}"
            }
            else -> "Unknown Browser"
        }
    }

    fun parseModel(userAgent: String): String? =
        Regex("Android [\\d.]+; ([^;)]+)").find(userAgent)
            ?.groupValues?.get(1)
            ?.substringBefore(" Build/")
            ?.trim()
            ?.takeIf { it.isNotEmpty() && !it.equals("Mobile", ignoreCase = true) && it != "K" }  // 크롬 UA reduction 익명값 K는 제외

    private fun parseOS(userAgent: String): String {
        return when {
            userAgent.contains("Windows NT 10.0") -> "Windows 10/11"
            userAgent.contains("Windows NT 6.3") -> "Windows 8.1"
            userAgent.contains("Windows NT 6.2") -> "Windows 8"
            userAgent.contains("Windows NT 6.1") -> "Windows 7"
            userAgent.contains("Windows") -> "Windows"
            userAgent.contains("Mac OS X") -> {
                val version = Regex("Mac OS X (\\d+[._]\\d+)").find(userAgent)?.groupValues?.get(1)?.replace('_', '.')
                "macOS ${version ?: ""}"
            }
            userAgent.contains("Android") -> {
                val version = Regex("Android (\\d+\\.?\\d*)").find(userAgent)?.groupValues?.get(1)
                "Android ${version ?: ""}"
            }
            userAgent.contains("Linux") -> "Linux"
            userAgent.contains("iOS") || userAgent.contains("iPhone") || userAgent.contains("iPad") -> {
                val version = Regex("OS (\\d+[._]\\d+)").find(userAgent)?.groupValues?.get(1)?.replace('_', '.')
                "iOS ${version ?: ""}"
            }
            else -> "Unknown OS"
        }
    }
}