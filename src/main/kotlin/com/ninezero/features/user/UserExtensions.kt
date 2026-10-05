package com.ninezero.features.user

import com.ninezero.core.common.config.UserRole
import com.ninezero.core.common.util.UserAgentParser
import com.ninezero.core.database.entities.user.*
import com.ninezero.features.user.presentation.models.response.*

/** 트랜잭션 스코프 내 호출 */
fun UserDao.toUserResponse(): UserResponse {
    return UserResponse(
        id = id.value,
        email = email,
        username = username,
        role = role,
        createdAt = createdAt,
        hasPassword = passwordHash != null,
        socialProviders = SocialAccountDao.find { SocialAccountTable.userId eq this@toUserResponse.id.value }
            .map { it.provider.name }
    )
}

fun UserDao.toUserPreviewResponse(): UserPreviewResponse {
    return UserPreviewResponse(
        id = id.value,
        avatarUrl = profile?.avatarUrl,
        avatarThumbUrl = profile?.avatarThumbUrl
    )
}

fun UserDao.toUserSummaryResponse(
    followerCount: Int = 0,
    followingCount: Int = 0,
    postCount: Int = 0,
    isFollowing: Boolean? = null
): UserSummaryResponse {
    return UserSummaryResponse(
        id = id.value,
        username = this.username,
        displayName = profile?.displayName ?: this.username,
        avatarUrl = profile?.avatarUrl,
        avatarThumbUrl = profile?.avatarThumbUrl,
        role = this.role,
        followerCount = followerCount,
        followingCount = followingCount,
        postCount = postCount,
        isFollowing = isFollowing,
        isDeleted = !this.isActive
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
        id = id.value,
        userId = userId.value,
        username = username,
        role = role,
        displayName = displayName,
        bio = if (isBlocked) null else bio,
        avatarUrl = avatarUrl,
        avatarThumbUrl = avatarThumbUrl,
        location = if (isBlocked) null else location,
        website = if (isBlocked) null else website,
        followerCount = if (isBlocked) 0 else followerCount,
        followingCount = if (isBlocked) 0 else followingCount,
        postCount = if (isBlocked) 0 else postCount,
        subscriberCount = if (isBlocked) null else subscriberCount,
        hasSubscriptionPlans = if (isBlocked) false else hasSubscriptionPlans,
        isCreator = role == UserRole.CREATOR || role == UserRole.ADMIN,
        isBlocked = isBlocked,
        isBlockedByMe = isBlockedByMe,
        isBlockedByOther = isBlockedByOther,
        createdAt = createdAt,
        updatedAt = updatedAt
    )
}

fun CreatorApplicationDao.toCreatorApplicationResponse(
    username: String,
    displayName: String? = null,
    avatarUrl: String? = null
): CreatorApplicationResponse {
    return CreatorApplicationResponse(
        id = id.value,
        userId = userId,
        username = username,
        displayName = displayName,
        avatarUrl = avatarUrl,
        reason = reason,
        portfolioUrl = portfolioUrl,
        status = status,
        rejectionReason = rejectionReason,
        reviewedBy = reviewedBy,
        createdAt = createdAt,
        reviewedAt = reviewedAt
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

fun UserAddressDao.toAddressResponse(): AddressResponse {
    return AddressResponse(
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
}
