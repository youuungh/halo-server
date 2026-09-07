package com.ninezero.features.user

import com.ninezero.core.common.config.UserRole
import com.ninezero.core.common.util.UserAgentParser
import com.ninezero.core.database.entities.user.CreatorApplicationDao
import com.ninezero.core.database.entities.user.SocialAccountDao
import com.ninezero.core.database.entities.user.SocialAccountTable
import com.ninezero.core.database.entities.user.UserDao
import com.ninezero.core.database.entities.user.UserAddressDao
import com.ninezero.core.database.entities.user.UserProfileDao
import com.ninezero.core.database.entities.user.UserSessionDao
import com.ninezero.features.user.presentation.models.response.AddressResponse
import com.ninezero.features.user.presentation.models.response.CreatorApplicationResponse
import com.ninezero.features.user.presentation.models.response.DeviceResponse
import com.ninezero.features.user.presentation.models.response.UserPreviewResponse
import com.ninezero.features.user.presentation.models.response.UserResponse
import com.ninezero.features.user.presentation.models.response.UserSummaryResponse
import com.ninezero.features.user.presentation.models.response.ProfileResponse

/** 트랜잭션 스코프 내 호출 */
fun UserDao.toUserResponse(): UserResponse {
    return UserResponse(
        id = this.id.value,
        email = this.email,
        username = this.username,
        role = this.role,
        createdAt = this.createdAt,
        hasPassword = this.passwordHash != null,
        socialProviders = SocialAccountDao.find { SocialAccountTable.userId eq this@toUserResponse.id.value }  // 소셜 연동 조회 포함이라 트랜잭션 필요
            .map { it.provider.name }
    )
}

fun UserDao.toPreviewResponse(): UserPreviewResponse {
    return UserPreviewResponse(
        id = this.id.value,
        avatarUrl = this.profile?.avatarUrl,
        avatarThumbUrl = this.profile?.avatarThumbUrl
    )
}

fun UserDao.toSummaryResponse(
    followerCount: Int = 0,
    followingCount: Int = 0,
    postCount: Int = 0,
    isFollowing: Boolean? = null
): UserSummaryResponse {
    return UserSummaryResponse(
        id = this.id.value,
        username = this.username,
        displayName = this.profile?.displayName ?: this.username,
        avatarUrl = this.profile?.avatarUrl,
        avatarThumbUrl = this.profile?.avatarThumbUrl,
        role = this.role,
        followerCount = followerCount,
        followingCount = followingCount,
        postCount = postCount,
        isFollowing = isFollowing
    )
}

fun UserProfileDao.toProfileResponse(
    username: String,
    role: UserRole,
    followerCount: Int = 0,
    followingCount: Int = 0,
    postCount: Int = 0,
    subscriberCount: Int? = null,
    hasSubscriptionPlans: Boolean = false,
    isBlocked: Boolean = false,
    isBlockedByMe: Boolean = false,
    isBlockedByOther: Boolean = false
): ProfileResponse {
    return ProfileResponse(
        id = this.id.value,
        userId = this.userId.value,
        username = username,
        role = role,
        displayName = this.displayName,
        bio = if (isBlocked) null else this.bio,
        avatarUrl = this.avatarUrl,
        avatarThumbUrl = this.avatarThumbUrl,
        location = if (isBlocked) null else this.location,
        website = if (isBlocked) null else this.website,
        followerCount = if (isBlocked) 0 else followerCount,
        followingCount = if (isBlocked) 0 else followingCount,
        postCount = if (isBlocked) 0 else postCount,
        subscriberCount = if (isBlocked) null else subscriberCount,
        hasSubscriptionPlans = if (isBlocked) false else hasSubscriptionPlans,
        isCreator = role == UserRole.CREATOR || role == UserRole.ADMIN,
        isBlocked = isBlocked,
        isBlockedByMe = isBlockedByMe,
        isBlockedByOther = isBlockedByOther,
        createdAt = this.createdAt,
        updatedAt = this.updatedAt
    )
}

fun CreatorApplicationDao.toCreatorApplicationResponse(username: String): CreatorApplicationResponse {
    return CreatorApplicationResponse(
        id = this.id.value,
        userId = this.userId,
        username = username,
        reason = this.reason,
        portfolioUrl = this.portfolioUrl,
        status = this.status,
        rejectionReason = this.rejectionReason,
        reviewedBy = this.reviewedBy,
        createdAt = this.createdAt,
        reviewedAt = this.reviewedAt
    )
}

fun UserSessionDao.toDeviceResponse(currentSessionId: String): DeviceResponse {
    return DeviceResponse(
        id = sessionId,
        deviceType = deviceType,
        deviceName = UserAgentParser.parseModel(userAgent) ?: os,
        browser = browser,
        os = os,
        ipAddress = ipAddress,
        location = location,
        lastActive = lastActive,
        isCurrent = sessionId == currentSessionId,
        createdAt = createdAt
    )
}

fun UserAddressDao.toAddressResponse() = AddressResponse(
    id = id.value,
    recipientName = recipientName,
    recipientPhone = recipientPhone,
    zipCode = zipCode,
    address = address,
    addressDetail = addressDetail,
    memo = memo,
    isDefault = isDefault,
    createdAt = createdAt,
    updatedAt = updatedAt
)
