package com.ninezero.features.banner.domain

import com.ninezero.core.common.config.BannerType
import com.ninezero.features.banner.presentation.models.response.BannerResponse
import kotlinx.serialization.Serializable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
internal data class BannersData(
    val banners: List<BannerItem>
)

@Serializable
internal data class BannerItem(
    val id: Int,
    val imageUrl: String,
    val title: String,
    val description: String? = null,
    val link: String,
    val type: BannerType,
    val order: Int,
    val isActive: Boolean = true
)

class BannerService {
    @Volatile  // 요청 코루틴 간 공유 캐시
    private var cachedData: BannersData? = null

    @Volatile
    private var lastLoadTime: Long = 0L

    companion object {
        private const val CACHE_DURATION_MS = 5 * 60 * 1000L  // 5분
    }

    suspend fun getActiveBanners(limit: Int? = null): List<BannerResponse>? {
        val data = loadBanners() ?: return null  // 5분 캐시
        return data.banners
            .filter { it.isActive }
            .sortedBy { it.order }  // order 오름차순
            .let { if (limit != null) it.take(limit) else it }
            .map { it.toBannerResponse() }
    }

    suspend fun getBannerById(bannerId: Int): BannerResponse? {
        val data = loadBanners() ?: return null
        val banner = data.banners.find { it.id == bannerId && it.isActive } ?: return null
        return banner.toBannerResponse()
    }

    private suspend fun loadBanners(): BannersData? {
        val now = System.currentTimeMillis()
        if (cachedData == null || (now - lastLoadTime) > CACHE_DURATION_MS) {
            cachedData = withContext(Dispatchers.IO) {
                runCatching {
                    Json.decodeFromString<BannersData>(File("data/banners.json").readText())
                }.getOrNull()
            }
            lastLoadTime = now
        }
        return cachedData
    }

    private fun BannerItem.toBannerResponse() = BannerResponse(
        id = id,
        imageUrl = imageUrl,
        title = title,
        description = description,
        link = link,
        type = type,
        order = order
    )
}
