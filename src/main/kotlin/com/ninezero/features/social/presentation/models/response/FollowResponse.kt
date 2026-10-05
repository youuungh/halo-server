package com.ninezero.features.social.presentation.models.response

import com.ninezero.features.user.presentation.models.response.UserSummaryResponse
import kotlinx.serialization.Serializable

@Serializable
data class FollowResponse(
    val followerCount: Int,
    val followingCount: Int,
    val userId: Int? = null
)

/** 커서 목록 + 탭 숫자용 전체 수 */
@Serializable
data class FollowListResponse(
    val items: List<UserSummaryResponse>,
    val nextCursor: String?,  // 마지막 페이지면 null
    val hasNext: Boolean,
    val totalCount: Int
)
