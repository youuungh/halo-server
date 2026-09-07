package com.ninezero.features.social.presentation.models.response

import com.ninezero.features.user.presentation.models.response.UserSummaryResponse
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Serializable

@Serializable
data class LikeUserInfoResponse(
    val user: UserSummaryResponse,
    val likedAt: LocalDateTime
)
