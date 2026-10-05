package com.ninezero.features.banner.domain

import com.ninezero.core.common.config.BannerType
import com.ninezero.core.common.exception.Errors
import com.ninezero.core.common.exception.InternalServerException
import com.ninezero.core.common.util.logger
import com.ninezero.features.banner.presentation.models.response.BannerListResponse
import com.ninezero.features.banner.presentation.models.response.BannerResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
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
    private val logger = logger()

    @Volatile
    private var cachedData: BannersData? = null  // 요청 코루틴 간 공유 캐시

    @Volatile
    private var lastLoadTime: Long = 0L

    suspend fun getActiveBanners(limit: Int? = null): BannerListResponse {
        val banners = loadBanners().banners
            .filter { it.isActive }
            .sortedBy { it.order }
            .let { if (limit != null) it.take(limit) else it }
            .map { it.toBannerResponse() }
        return BannerListResponse(banners = banners, totalCount = banners.size)
    }

    private suspend fun loadBanners(): BannersData {
        val now = System.currentTimeMillis()
        if (cachedData == null || (now - lastLoadTime) > CACHE_DURATION_MS) {
            cachedData = withContext(Dispatchers.IO) {
                runCatching {
                    Json.decodeFromString<BannersData>(File("data/banners.json").readText())
                }.onFailure { logger.error("배너 데이터 로드 실패: ${it.message}", it) }
                    .getOrNull()
            }
            lastLoadTime = now
        }
        return cachedData ?: throw InternalServerException(Errors.Banner.BANNER_LOAD_ERROR)
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

    private companion object {
        const val CACHE_DURATION_MS = 5 * 60 * 1000L  // 5분
    }
}
