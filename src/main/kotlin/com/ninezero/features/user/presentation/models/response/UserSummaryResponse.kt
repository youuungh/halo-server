package com.ninezero.features.user.presentation.models.response

import com.ninezero.core.common.config.UserRole
import com.ninezero.core.common.util.CursorResponse
import com.ninezero.core.common.util.PaginatedResponse
import kotlinx.serialization.Serializable

@Serializable
data class UserSummaryResponse(
    val id: Int,
    val username: String,
    val displayName: String? = null,
    val avatarUrl: String? = null,
    val avatarThumbUrl: String? = null,
    val role: UserRole,
    val followerCount: Int = 0,
    val followingCount: Int = 0,
    val postCount: Int = 0,
    val isFollowing: Boolean? = null,
    val isDeleted: Boolean = false  // 탈퇴한 사용자
)

typealias BlockedUserListResponse = CursorResponse<UserSummaryResponse>

typealias UserListResponse = PaginatedResponse<UserSummaryResponse>
