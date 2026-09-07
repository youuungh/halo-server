package com.ninezero.features.user.domain

import com.ninezero.core.cache.CacheKeys
import com.ninezero.core.cache.CacheService
import com.ninezero.core.common.config.UserRole
import com.ninezero.core.common.exception.Errors
import com.ninezero.core.common.exception.InvalidImageUrlException
import com.ninezero.core.common.exception.NotFoundException
import com.ninezero.core.common.exception.UserNotFoundException
import com.ninezero.core.common.util.ValidationUtils
import com.ninezero.core.common.util.getJson
import com.ninezero.core.common.util.logger
import com.ninezero.core.common.util.query
import com.ninezero.core.common.util.setJson
import com.ninezero.core.database.entities.user.UserProfileDao
import com.ninezero.core.database.entities.user.UserProfileTable
import com.ninezero.core.storage.AvatarUploadResult
import com.ninezero.core.storage.FileUploadService
import com.ninezero.core.storage.ImageProcessingService
import com.ninezero.core.storage.StorageConfig
import com.ninezero.features.social.data.FollowRepository
import com.ninezero.features.social.data.PostRepository
import com.ninezero.features.subscription.data.SubscriptionPlanRepository
import com.ninezero.features.subscription.data.SubscriptionRepository
import com.ninezero.features.user.data.BlockedUserRepository
import com.ninezero.features.user.data.UserRepository
import com.ninezero.features.user.presentation.models.response.ProfileResponse
import com.ninezero.features.user.presentation.models.request.UpdateProfileRequest
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
    private val logger = logger()

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
            cacheService.getJson<ProfileResponse>(cacheKey)?.let {  // 비차단만 캐시 사용
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

        val displayName = request.displayName?.let { ValidationUtils.sanitizeHtml(it) }
        val bio = request.bio?.let { ValidationUtils.sanitizeHtml(it) }
        val location = request.location?.let { ValidationUtils.sanitizeHtml(it) }
        val website = request.website?.let { ValidationUtils.sanitizeUrl(it) }

        val response = query {
            val user = userRepository.findUserById(userId)
                ?: throw UserNotFoundException(userId)

            val existingProfile = UserProfileDao.find { UserProfileTable.userId eq userId }.firstOrNull()

            val profile = if (existingProfile != null) {
                displayName?.let { existingProfile.displayName = it }
                bio?.let { existingProfile.bio = it }
                location?.let { existingProfile.location = it }
                website?.let { existingProfile.website = it }
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

        cacheService.delete(CacheKeys.user(userId))
        cacheService.delete(CacheKeys.profile(userId))  // 저장 후 user/profile 캐시 무효화

        return response
    }

    suspend fun uploadAvatar(userId: Int, imageData: ByteArray, contentType: String): ProfileResponse {
        var uploadResult: AvatarUploadResult? = null

        try {
            data class UserInfo(val username: String, val role: UserRole)
            data class OldAvatar(val url: String?, val thumbUrl: String?)

            val (userInfo, oldAvatar) = query {
                val foundUser = userRepository.findUserById(userId)
                    ?: throw UserNotFoundException(userId)

                val existingProfile = UserProfileDao.find { UserProfileTable.userId eq userId }.firstOrNull()

                UserInfo(foundUser.username, foundUser.role) to OldAvatar(
                    url = existingProfile?.avatarUrl,
                    thumbUrl = existingProfile?.avatarThumbUrl
                )
            }

            if (!imageProcessingService.validateImage(imageData, StorageConfig.FileSizeLimit.AVATAR)) {
                throw InvalidImageUrlException(Errors.File.INVALID_IMAGE_FILE)
            }

            val extension = imageProcessingService.getFileExtension(contentType)

            // 새 이미지 업로드
            uploadResult = fileUploadService.uploadAvatar(userId, imageData, extension)

            val profileResponse = try {
                query {
                    val existingProfile = UserProfileDao.find { UserProfileTable.userId eq userId }.firstOrNull()

                    val profile = existingProfile ?: run {
                        val user = userRepository.findUserById(userId)!!
                        UserProfileDao.new {
                            this.userId = user.id
                            this.displayName = userInfo.username
                            this.bio = null
                            this.location = null
                            this.website = null
                        }
                    }

                    profile.avatarUrl = uploadResult.avatarUrl
                    profile.avatarThumbUrl = uploadResult.avatarThumbUrl

                    val stats = computeProfileStats(userId, userInfo.role)

                    profile.toProfileResponse(
                        username = userInfo.username,
                        role = userInfo.role,
                        followerCount = stats.followerCount,
                        followingCount = stats.followingCount,
                        postCount = stats.postCount,
                        subscriberCount = stats.subscriberCount,
                        hasSubscriptionPlans = stats.hasSubscriptionPlans
                    )
                }
            } catch (e: Exception) {
                // DB 업데이트 실패 시 업로드한 파일 롤백
                fileUploadService.deleteFileIfSupabase(uploadResult.avatarUrl)
                uploadResult.avatarThumbUrl?.let { fileUploadService.deleteFileIfSupabase(it) }
                throw e
            }

            // DB 업데이트 성공 후에만 기존 아바타 삭제
            if (oldAvatar.url != null) {
                fileUploadService.deleteFileIfSupabase(oldAvatar.url)
            }
            if (oldAvatar.thumbUrl != null) {
                fileUploadService.deleteFileIfSupabase(oldAvatar.thumbUrl)
            }

            cacheService.delete(CacheKeys.user(userId))
            cacheService.delete(CacheKeys.profile(userId))

            return profileResponse

        } catch (e: Exception) {
            // 예외 발생 시 업로드한 파일 롤백
            uploadResult?.let { result ->
                try {
                    fileUploadService.deleteFileIfSupabase(result.avatarUrl)
                    result.avatarThumbUrl?.let { fileUploadService.deleteFileIfSupabase(it) }
                } catch (rollbackError: Exception) {
                    logger.warn("아바타 롤백 실패: {} - {}", result.avatarUrl, rollbackError.message)
                }
            }
            throw e
        }
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
            subscriptionPlanRepository.findPlansByCreator(userId, page = 1, limit = 1).isNotEmpty()
        } else false
        return ProfileStats(followerCount, followingCount, postCount, subscriberCount, hasSubscriptionPlans)
    }
}
