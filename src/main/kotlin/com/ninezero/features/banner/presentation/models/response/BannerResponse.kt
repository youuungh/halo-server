package com.ninezero.features.banner.presentation.models.response

import com.ninezero.core.common.config.BannerType
import kotlinx.serialization.Serializable

@Serializable
data class BannerResponse(
    val id: Int,
    val imageUrl: String,
    val title: String,
    val description: String? = null,
    val link: String,
    val type: BannerType,
    val order: Int
)