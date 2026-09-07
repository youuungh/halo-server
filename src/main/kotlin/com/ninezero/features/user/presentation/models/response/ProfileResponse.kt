package com.ninezero.features.user.presentation.models.response

import com.ninezero.core.common.config.UserRole
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Serializable

@Serializable
data class ProfileResponse(
    val id: Int,
    val userId: Int,
    val username: String,
    val role: UserRole,
    val displayName: String,
    val bio: String? = null,
    val avatarUrl: String? = null,
    val avatarThumbUrl: String? = null,
    val location: String? = null,
    val website: String? = null,
    val followerCount: Int = 0,
    val followingCount: Int = 0,
    val postCount: Int = 0,
    val subscriberCount: Int? = null,
    val hasSubscriptionPlans: Boolean = false,
    val isCreator: Boolean = false,
    /** 양방향 차단 여부 */
    val isBlocked: Boolean = false,         // 둘 중 하나라도 차단했으면 true
    val isBlockedByMe: Boolean = false,
    val isBlockedByOther: Boolean = false,
    val isFollowing: Boolean = false,       // 캐시 없음, 응답 시 합성
    val notifyNewPost: Boolean = false,
    val notifyNewProduct: Boolean = false,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime
)
