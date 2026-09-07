package com.ninezero.features.banner.presentation.models.response

import kotlinx.serialization.Serializable

@Serializable
data class BannerListResponse(
    val banners: List<BannerResponse>,
    val totalCount: Int
)
