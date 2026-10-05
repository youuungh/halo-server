package com.ninezero.features.user.domain

import com.ninezero.core.cache.CacheKeys
import com.ninezero.core.cache.CacheService
import com.ninezero.core.common.config.UserRole
import com.ninezero.core.common.exception.Errors
import com.ninezero.core.common.exception.InvalidImageUrlException
import com.ninezero.core.common.exception.NotFoundException
import com.ninezero.core.common.exception.UserNotFoundException
import com.ninezero.core.common.util.ValidationUtils
import com.ninezero.core.common.util.query
import com.ninezero.core.database.entities.user.UserProfileDao
import com.ninezero.core.database.entities.user.UserProfileTable
import com.ninezero.core.storage.FileUploadService
import com.ninezero.core.storage.ImageProcessingService
import com.ninezero.core.storage.StorageConfig
import com.ninezero.features.social.data.FollowRepository
import com.ninezero.features.social.data.PostRepository
import com.ninezero.features.subscription.data.SubscriptionPlanRepository
import com.ninezero.features.subscription.data.SubscriptionRepository
import com.ninezero.features.user.data.BlockedUserRepository
import com.ninezero.features.user.data.UserRepository
import com.ninezero.features.user.presentation.models.request.UpdateProfileRequest
import com.ninezero.features.user.presentation.models.response.ProfileResponse
import com.ninezero.features.user.toProfileResponse
import kotlin.time.Duration.Companion.minutes

class ProfileService(
    private val userRepository: UserRepository,
    private val followRepository: FollowRepository,
    private val postRepository: PostRepository,
    private val subscriptionPlanRepository: SubscriptionPlanRepository,
    private val subscriptionRepository: SubscriptionRepository,
    private val blockedUserRepository: BlockedUserRepository,
    private val fileUploadService: FileUploadService,
    private val imageProcessingService: ImageProcessingService,
    private val cacheService: CacheService
) {

    suspend fun getUserProfile(userId: Int, currentUserId: Int? = null): ProfileResponse {
        // notifySettings null이면 미팔로우
        val (isBlockedByMe, isBlockedByOther, notifySettings) = if (currentUserId != null && currentUserId != userId) {
            query {
                Triple(
                    blockedUserRepository.isBlocked(currentUserId, userId),
                    blockedUserRepository.isBlocked(userId, currentUserId),
                    followRepository.findNotifySettings(currentUserId, userId)
                )
            }
        } else {
            Triple(false, false, null)
        }
        val isBlocked = isBlockedByMe || isBlockedByOther
        val isFollowing = notifySettings != null

        // 차단 시 캐시 우회
        if (!isBlocked) {
            val cacheKey = CacheKeys.profile(userId)
            cacheService.getJson<ProfileResponse>(cacheKey)?.let {
                return it.copy(
                    isFollowing = isFollowing,
                    notifyNewPost = notifySettings?.first ?: false,
                    notifyNewProduct = notifySettings?.second ?: false
                )
            }
        }

        val response = query {
            val user = userRepository.findUserById(userId)
                ?: throw UserNotFoundException(userId)

            // 탈퇴한 사용자는 프로필 접근 차단
            if (!user.isActive) {
                throw NotFoundException(Errors.User.DELETED_USER)
            }

            val profile = UserProfileDao.find { UserProfileTable.userId eq userId }.firstOrNull()
                ?: UserProfileDao.new {
                    this.userId = user.id
                    this.displayName = user.username
                    this.bio = null
                    this.avatarUrl = null
                    this.location = null
                    this.website = null
                }

            val stats = computeProfileStats(userId, user.role)

            profile.toProfileResponse(
                username = user.username,
                role = user.role,
                followerCount = stats.followerCount,
                followingCount = stats.followingCount,
                postCount = stats.postCount,
                subscriberCount = stats.subscriberCount,
                hasSubscriptionPlans = stats.hasSubscriptionPlans,
                isBlocked = isBlocked,
                isBlockedByMe = isBlockedByMe,
                isBlockedByOther = isBlockedByOther
            )
        }

        // 비차단만 캐시 저장
        if (!isBlocked) {
            val cacheKey = CacheKeys.profile(userId)
            cacheService.setJson(cacheKey, response, ttl = 30.minutes)
        }

        return response.copy(
            isFollowing = isFollowing,
            notifyNewPost = notifySettings?.first ?: false,
            notifyNewProduct = notifySettings?.second ?: false
        )
    }

    suspend fun updateProfile(userId: Int, request: UpdateProfileRequest): ProfileResponse {
        ValidationUtils.validateDisplayName(request.displayName)
        ValidationUtils.validateBio(request.bio)
        ValidationUtils.validateLocation(request.location)
        ValidationUtils.validateWebsite(request.website)

        // null은 그대로 두고 빈 값은 지움
        val displayName = request.displayName?.trim()
        val bio = request.bio?.trim()?.ifEmpty { null }
        val location = request.location?.trim()?.ifEmpty { null }
        val website = request.website?.let { ValidationUtils.sanitizeUrl(it) }

        val response = query {
            val user = userRepository.findUserById(userId)
                ?: throw UserNotFoundException(userId)

            val existingProfile = UserProfileDao.find { UserProfileTable.userId eq userId }.firstOrNull()

            val profile = if (existingProfile != null) {
                displayName?.let { existingProfile.displayName = it }
                if (request.bio != null) existingProfile.bio = bio
                if (request.location != null) existingProfile.location = location
                if (request.website != null) existingProfile.website = website
                existingProfile
            } else {
                UserProfileDao.new {
                    this.userId = user.id
                    this.displayName = displayName ?: user.username
                    this.bio = bio
                    this.location = location
                    this.website = website
                }
            }

            val stats = computeProfileStats(userId, user.role)

            profile.toProfileResponse(
                username = user.username,
                role = user.role,
                followerCount = stats.followerCount,
                followingCount = stats.followingCount,
                postCount = stats.postCount,
                subscriberCount = stats.subscriberCount,
                hasSubscriptionPlans = stats.hasSubscriptionPlans
            )
        }

        cacheService.delete(CacheKeys.profile(userId))  // 저장 후 profile 캐시 무효화

        return response
    }

    suspend fun uploadAvatar(userId: Int, imageData: ByteArray): ProfileResponse {
        val (user, oldProfile) = query {
            val foundUser = userRepository.findUserById(userId)
                ?: throw UserNotFoundException(userId)

            foundUser to UserProfileDao.find { UserProfileTable.userId eq userId }.firstOrNull()
        }
        val oldAvatarUrl = oldProfile?.avatarUrl
        val oldAvatarThumbUrl = oldProfile?.avatarThumbUrl

        if (!imageProcessingService.validateImage(imageData, StorageConfig.FileSizeLimit.AVATAR)) {
            throw InvalidImageUrlException(Errors.File.INVALID_IMAGE_FILE)
        }

        val extension = imageProcessingService.imageExtensionForBytes(imageData)

        // 새 이미지 업로드
        val uploadResult = fileUploadService.uploadAvatar(userId, imageData, extension)

        val profileResponse = try {
            query {
                val existingProfile = UserProfileDao.find { UserProfileTable.userId eq userId }.firstOrNull()

                val profile = existingProfile ?: UserProfileDao.new {
                    this.userId = user.id
                    this.displayName = user.username
                    this.bio = null
                    this.location = null
                    this.website = null
                }

                profile.avatarUrl = uploadResult.avatarUrl
                profile.avatarThumbUrl = uploadResult.avatarThumbUrl

                val stats = computeProfileStats(userId, user.role)

                profile.toProfileResponse(
                    username = user.username,
                    role = user.role,
                    followerCount = stats.followerCount,
                    followingCount = stats.followingCount,
                    postCount = stats.postCount,
                    subscriberCount = stats.subscriberCount,
                    hasSubscriptionPlans = stats.hasSubscriptionPlans
                )
            }
        } catch (e: Exception) {
            // DB 업데이트 실패 시 업로드한 파일 롤백
            fileUploadService.deleteByUrl(uploadResult.avatarUrl)
            uploadResult.avatarThumbUrl?.let { fileUploadService.deleteByUrl(it) }
            throw e
        }

        // DB 업데이트 성공 후에만 기존 아바타 삭제
        if (oldAvatarUrl != null) {
            fileUploadService.deleteByUrl(oldAvatarUrl)
        }
        if (oldAvatarThumbUrl != null) {
            fileUploadService.deleteByUrl(oldAvatarThumbUrl)
        }

        cacheService.delete(CacheKeys.profile(userId))

        return profileResponse
    }

    private data class ProfileStats(
        val followerCount: Int,
        val followingCount: Int,
        val postCount: Int,
        val subscriberCount: Int?,
        val hasSubscriptionPlans: Boolean
    )

    private suspend fun computeProfileStats(userId: Int, role: UserRole): ProfileStats {
        val followerCount = followRepository.countFollowers(userId)
        val followingCount = followRepository.countFollowing(userId)
        val postCount = postRepository.countPostsByUserId(userId)
        val isCreator = role == UserRole.CREATOR || role == UserRole.ADMIN
        val subscriberCount = if (isCreator) subscriptionRepository.countCreatorSubscribers(userId) else null
        val hasSubscriptionPlans = if (isCreator) {
            subscriptionPlanRepository.countActivePlansByCreator(userId) > 0  // 비활성 플랜만 있으면 구독 시트가 빔
        } else false
        return ProfileStats(followerCount, followingCount, postCount, subscriberCount, hasSubscriptionPlans)
    }
}
