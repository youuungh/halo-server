package com.ninezero.features.social.domain

import com.ninezero.core.cache.CacheKeys
import com.ninezero.core.cache.CacheService
import com.ninezero.core.common.config.BookmarkTargetType
import com.ninezero.core.common.config.CommunityPostSortType
import com.ninezero.core.common.config.Constants
import com.ninezero.core.common.config.MediaType as SocialMediaType
import com.ninezero.core.common.config.PostType
import com.ninezero.core.common.config.SubscriptionPlanTier
import com.ninezero.core.common.util.TierAccessEvaluator
import com.ninezero.core.common.config.UserRole
import com.ninezero.core.common.exception.CreatorOnlyException
import com.ninezero.core.common.exception.Errors
import com.ninezero.core.common.exception.ForbiddenException
import com.ninezero.core.common.exception.ImageValidationFailedException
import com.ninezero.core.common.exception.InvalidFileException
import com.ninezero.core.common.exception.InvalidInputException
import com.ninezero.core.common.exception.NotFoundException
import com.ninezero.core.common.exception.PostDeleteFailedException
import com.ninezero.core.common.exception.PostNotFoundException
import com.ninezero.core.common.exception.PostPinException
import com.ninezero.core.common.exception.PostPinLimitExceededException
import com.ninezero.core.common.exception.PostUpdateFailedException
import com.ninezero.core.common.exception.UserNotFoundException
import com.ninezero.core.common.util.*
import com.ninezero.core.database.entities.social.PostMediaDao
import com.ninezero.core.database.entities.social.PostMediaTable
import com.ninezero.core.database.entities.social.PostDao
import com.ninezero.core.storage.FileUploadService
import com.ninezero.core.storage.ImageProcessingService
import com.ninezero.core.storage.StorageConfig
import com.ninezero.features.social.data.BookmarkRepository
import com.ninezero.features.social.data.FollowRepository
import com.ninezero.features.social.data.LikeRepository
import com.ninezero.features.social.data.PostRepository
import com.ninezero.features.social.presentation.models.request.CommunityPostRequest
import com.ninezero.features.social.presentation.models.response.CommunityResponse
import com.ninezero.features.social.presentation.models.response.MediaItemResponse
import com.ninezero.features.social.presentation.models.response.PostResponse
import com.ninezero.features.social.toMediaItemResponse
import com.ninezero.features.social.batchLoadPostMediaItems
import com.ninezero.features.social.resolveMediaEditPlan
import com.ninezero.features.social.toPostResponse
import com.ninezero.features.subscription.data.SubscriptionPlanRepository
import com.ninezero.features.subscription.data.SubscriptionRepository
import com.ninezero.features.user.data.UserRepository
import com.ninezero.features.user.toSummaryResponse
import org.jetbrains.exposed.sql.SortOrder
import kotlin.time.Duration.Companion.minutes

class CommunityService(
    private val postRepository: PostRepository,
    private val userRepository: UserRepository,
    private val likeRepository: LikeRepository,
    private val bookmarkRepository: BookmarkRepository,
    private val followRepository: FollowRepository,
    private val subscriptionRepository: SubscriptionRepository,
    private val planRepository: SubscriptionPlanRepository,
    private val fileUploadService: FileUploadService,
    private val imageProcessingService: ImageProcessingService,
    private val cacheService: CacheService
) {
    private val logger = logger()

    // 커뮤니티 글 생성/수정/삭제
    suspend fun createCommunityPost(
        userId: Int,
        creatorId: Int,
        request: CommunityPostRequest
    ): PostResponse {
        val content = ValidationUtils.sanitizeHtml(request.content)
        ValidationUtils.validatePostContent(content)
        val extractedTags = extractHashtags(content)

        val response = query {
            val user = userRepository.findUserById(userId)
                ?: throw UserNotFoundException(Errors.User.USER_NOT_FOUND)

            // 주인이 크리에이터 아니면 닫힌 커뮤니티
            val owner = userRepository.findUserById(creatorId)
                ?: throw NotFoundException(Errors.Social.Community.COMMUNITY_NOT_AVAILABLE)
            if (owner.role != UserRole.CREATOR && owner.role != UserRole.ADMIN) {
                throw NotFoundException(Errors.Social.Community.COMMUNITY_NOT_AVAILABLE)
            }

            val isCreator = userId == creatorId &&
                    (user.role == UserRole.CREATOR || user.role == UserRole.ADMIN)
            val isFollower = followRepository.isFollowing(userId, creatorId)

            if (!isCreator && !isFollower) {  // 팔로워/크리에이터만
                throw ForbiddenException(Errors.Social.Community.ONLY_FOLLOWERS_CAN_POST)
            }

            val post = postRepository.createCommunityPost(
                userId = userId,
                creatorId = creatorId,
                content = content,
                postType = PostType.IMAGE,
                mediaAttachments = emptyList(),
                tags = extractedTags,
                isSecret = request.isSecret
            )

            val isLiked = likeRepository.isPostLiked(userId, post.id.value)
            val isBookmarked = bookmarkRepository.isBookmarked(userId, BookmarkTargetType.POST, post.id.value)
            val mediaItems = batchLoadPostMediaItems(listOf(post.id.value))[post.id.value] ?: emptyList()

            post.toPostResponse(author = user.toSummaryResponse(), isLiked = isLiked, isBookmarked = isBookmarked, mediaItems = mediaItems)
        }

        cacheService.deletePattern(CacheKeys.Patterns.community(creatorId))

        return response
    }

    suspend fun updateCommunityPost(
        postId: Int,
        userId: Int,
        request: CommunityPostRequest
    ): PostResponse {
        val content = ValidationUtils.sanitizeHtml(request.content)
        ValidationUtils.validatePostContent(content)
        val extractedTags = extractHashtags(content)

        val (response, targetCreatorId) = query {
            val post = postRepository.findPostById(postId)
                ?: throw PostNotFoundException(Errors.Social.Post.POST_NOT_FOUND)

            if (post.userId != userId) {  // 작성자 본인만
                throw ForbiddenException(Errors.Social.Post.POST_UPDATE_PERMISSION_DENIED)
            }

            val updatedPost = postRepository.updatePost(
                postId = postId,
                content = content,
                mediaAttachments = emptyList(),
                tags = extractedTags
            ) ?: throw PostUpdateFailedException(Errors.Social.Post.POST_UPDATE_FAILED)

            val author = userRepository.findUserById(updatedPost.userId)?.toSummaryResponse()
                ?: throw UserNotFoundException(Errors.Social.Post.AUTHOR_NOT_FOUND)

            val isLiked = likeRepository.isPostLiked(userId, updatedPost.id.value)
            val isBookmarked = bookmarkRepository.isBookmarked(userId, BookmarkTargetType.POST, updatedPost.id.value)
            val mediaItems = batchLoadPostMediaItems(listOf(updatedPost.id.value))[updatedPost.id.value] ?: emptyList()

            Pair(
                updatedPost.toPostResponse(author = author, isLiked = isLiked, isBookmarked = isBookmarked, mediaItems = mediaItems),
                post.creatorId ?: post.userId
            )
        }

        cacheService.deletePattern(CacheKeys.Patterns.community(targetCreatorId))

        return response
    }

    suspend fun deleteCommunityPost(postId: Int, userId: Int) {
        val (targetCreatorId, mediaAttachments) = query {
            val post = postRepository.findPostById(postId)
                ?: throw PostNotFoundException(Errors.Social.Post.POST_NOT_FOUND)

            val user = userRepository.findUserById(userId)
                ?: throw UserNotFoundException(Errors.User.USER_NOT_FOUND)

            val isAuthor = post.userId == userId
            val isCreator = post.creatorId == userId &&
                    (user.role == UserRole.CREATOR || user.role == UserRole.ADMIN)

            if (!isAuthor && !isCreator) {  // 작성자/크리에이터/관리자만
                throw ForbiddenException(Errors.Social.Post.POST_DELETE_PERMISSION_DENIED)
            }

            val deleted = postRepository.deletePost(postId)
            if (!deleted) {
                throw PostDeleteFailedException(Errors.Social.Post.POST_DELETE_FAILED)
            }

            // 원본 + 썸네일 + 프리뷰 수거
            val urls = PostMediaDao.find { PostMediaTable.postId eq postId }
                .flatMap { listOfNotNull(it.url, it.thumbnailUrl, it.previewUrl) }
                .filterNot { fileUploadService.isSharedSeedFile(it) }
            PostMediaDao.find { PostMediaTable.postId eq postId }.forEach { it.delete() }

            Pair(post.creatorId ?: post.userId, urls)
        }

        mediaAttachments.forEach { url ->
            try {
                fileUploadService.deleteFileIfSupabase(url)
            } catch (e: Exception) {
                logger.warn("커뮤니티 미디어 삭제 실패: {} - {}", url, e.message)
            }
        }

        // post 상세 캐시도 무효화
        cacheService.deletePattern(CacheKeys.Patterns.community(targetCreatorId))
        cacheService.deletePattern(CacheKeys.Patterns.post(postId))
    }

    // 커뮤니티 글 조회
    suspend fun getCommunityPosts(
        creatorId: Int,
        currentUserId: Int?,
        page: Int,
        limit: Int = Constants.DEFAULT_PAGE_LIMIT,
        sort: CommunityPostSortType = CommunityPostSortType.LATEST
    ): CommunityResponse {
        val (validPage, validLimit) = validatePaginationParams(page, limit)

        val cacheKey = CacheKeys.community(creatorId, validPage, validLimit, currentUserId) + ":$sort"
        cacheService.getJson<CommunityResponse>(cacheKey)?.let {
            return it
        }

        val response = query {
            val pinnedPosts = postRepository.findPinnedPostsInCommunity(
                creatorId = creatorId,
                limit = Constants.Social.MAX_PINNED_COMMUNITY_POSTS
            )

            val communityPosts = postRepository.findCommunityPosts(
                creatorId = creatorId,
                page = page,
                limit = limit,
                sort = sort
            )

            val allPosts = pinnedPosts + communityPosts
            val userIds = allPosts.map { it.userId }.distinct()
            val users = userRepository.findUsersByIds(userIds)
            val userMap = users.associateBy { it.id.value }

            val postIds = allPosts.map { it.id.value }
            val likeStatusMap = if (currentUserId != null) {
                likeRepository.checkMultiplePostLikes(currentUserId, postIds)
            } else {
                emptyMap()
            }
            val bookmarkStatusMap = if (currentUserId != null) {
                bookmarkRepository.checkMultipleBookmarks(currentUserId, BookmarkTargetType.POST, postIds)
            } else {
                emptyMap()
            }
            val followStatusMap = if (currentUserId != null) {
                followRepository.checkMultipleFollowStatus(currentUserId, userIds)
            } else {
                emptyMap()
            }

            val accessMap = if (currentUserId != null) {
                checkMultiplePostAccess(currentUserId, allPosts)
            } else {
                // 비밀글 FREE 티어 체크 누락 시 누수
                allPosts.associate { it.id.value to (it.requiredTier == SubscriptionPlanTier.FREE && !it.isSecret) }
            }

            val postMediaMap = batchLoadPostMediaItems(allPosts.map { it.id.value })

            val pinnedPostResponses = pinnedPosts.mapNotNull { post ->
                val author = userMap[post.userId]?.toSummaryResponse(isFollowing = followStatusMap[post.userId]) ?: return@mapNotNull null
                val isLiked = likeStatusMap[post.id.value] ?: false
                val isBookmarked = bookmarkStatusMap[post.id.value] ?: false
                val canAccess = accessMap[post.id.value] ?: false
                post.toPostResponse(author = author, isLiked = isLiked, isBookmarked = isBookmarked, canAccess = canAccess, mediaItems = postMediaMap[post.id.value] ?: emptyList())
            }

            val communityPostResponses = communityPosts.mapNotNull { post ->
                val author = userMap[post.userId]?.toSummaryResponse(isFollowing = followStatusMap[post.userId]) ?: return@mapNotNull null
                val isLiked = likeStatusMap[post.id.value] ?: false
                val isBookmarked = bookmarkStatusMap[post.id.value] ?: false
                val canAccess = accessMap[post.id.value] ?: false
                post.toPostResponse(author = author, isLiked = isLiked, isBookmarked = isBookmarked, canAccess = canAccess, mediaItems = postMediaMap[post.id.value] ?: emptyList())
            }

            val totalCount = postRepository.countCommunityPosts(creatorId)
            val pagination = PaginationInfo(validPage, validLimit, totalCount)
            val allPostsResponse = createPagedResponse(communityPostResponses, pagination)

            CommunityResponse(
                pinnedPosts = pinnedPostResponses,
                posts = allPostsResponse
            )
        }

        cacheService.setJson(cacheKey, response, ttl = 10.minutes)

        return response
    }

    // 공지 고정
    suspend fun togglePinCommunityPost(postId: Int, creatorId: Int): Map<String, Boolean> {
        val isPinned = query {
            val user = userRepository.findUserById(creatorId)
            if (user?.role != UserRole.CREATOR && user?.role != UserRole.ADMIN) {
                throw CreatorOnlyException(Errors.Social.Pin.ONLY_CREATOR_CAN_PIN)
            }

            val post = postRepository.findPostById(postId)
                ?: throw PostNotFoundException(Errors.Social.Post.POST_NOT_FOUND)

            if (post.creatorId != creatorId) {  // 크리에이터만
                throw CreatorOnlyException(Errors.Social.Pin.ONLY_CREATOR_CAN_PIN)
            }

            val currentlyPinned = post.isPinnedInCommunity

            if (currentlyPinned) {
                val success = postRepository.unpinPostInCommunity(postId)
                if (!success) {
                    throw PostPinException(Errors.Social.Pin.PIN_ERROR)
                }
                false
            } else {
                // 비밀글은 공지 고정 불가
                if (post.isSecret) {
                    throw PostPinException(Errors.Social.Pin.SECRET_POST_CANNOT_PIN)
                }

                // 최대 고정 개수 제한
                val pinnedCount = postRepository.countPinnedPostsInCommunity(creatorId)
                if (pinnedCount >= Constants.Social.MAX_PINNED_COMMUNITY_POSTS) {
                    throw PostPinLimitExceededException(Errors.Social.Pin.PINNED_COMMUNITY_LIMIT_EXCEEDED)
                }

                val success = postRepository.pinPostInCommunity(postId, creatorId)
                if (!success) {
                    throw PostPinException(Errors.Social.Pin.PIN_ERROR)
                }
                true
            }
        }

        cacheService.deletePattern(CacheKeys.Patterns.community(creatorId))

        return mapOf("isPinned" to isPinned)
    }

    // 커뮤니티 미디어
    suspend fun uploadCommunityMedia(
        postId: Int,
        userId: Int,
        creatorId: Int,
        mediaFiles: List<ByteArray>,
        contentTypes: List<String>,
        mediaType: PostType
    ): List<MediaItemResponse> {
        val uploadedNewFiles = mutableListOf<String>()

        try {
            ValidationUtils.validateUploadedMediaFiles(mediaFiles, contentTypes)

            val maxSize = StorageConfig.FileSizeLimit.POST
            val isValid = when (mediaType) {
                PostType.IMAGE -> mediaFiles.all { imageProcessingService.validateImage(it, maxSize) }
                else -> throw IllegalArgumentException(Errors.Social.Post.INVALID_MEDIA_TYPE)
            }

            if (!isValid) {
                throw ImageValidationFailedException(Errors.File.IMAGE_VALIDATION_FAILED)
            }

            val currentCount = query {
                val user = userRepository.findUserById(userId)
                    ?: throw UserNotFoundException(Errors.User.USER_NOT_FOUND)

                val isCreator = userId == creatorId &&
                        (user.role == UserRole.CREATOR || user.role == UserRole.ADMIN)
                val isFollower = followRepository.isFollowing(userId, creatorId)

                if (!isCreator && !isFollower) {  // 팔로워/크리에이터만
                    throw ForbiddenException(Errors.Social.Community.ONLY_FOLLOWERS_CAN_POST)
                }

                val post = postRepository.findPostById(postId)
                    ?: throw PostNotFoundException(Errors.Social.Post.POST_NOT_FOUND)

                if (post.userId != userId) {
                    throw ForbiddenException(Errors.Common.UNAUTHORIZED)
                }

                if (post.creatorId != creatorId) {
                    throw ForbiddenException(Errors.Social.Community.ONLY_FOLLOWERS_CAN_POST)
                }

                PostMediaDao.find { PostMediaTable.postId eq postId }.count().toInt()
            }

            // 첨부 개수 제한
            if (currentCount + mediaFiles.size > Constants.Social.MAX_MEDIA_ATTACHMENTS_PER_POST) {
                throw InvalidInputException(Errors.Social.Media.ATTACHMENT_LIMIT_EXCEEDED)
            }

            val extensions = contentTypes.map { imageProcessingService.getFileExtension(it) }
            val newMediaAttachments = fileUploadService.uploadPostMediaList(postId, mediaFiles, extensions)
            uploadedNewFiles.addAll(newMediaAttachments)

            // 목록용 썸네일
            val thumbnailUrls = mediaFiles.map { bytes ->
                imageProcessingService.generateDisplayImage(bytes)?.let { display ->
                    runCatching { fileUploadService.uploadPostThumbnail(postId, display) }.getOrNull()
                }
            }
            val previewUrls = mediaFiles.map { bytes ->
                imageProcessingService.generateLockedPreview(bytes)?.let { preview ->
                    runCatching { fileUploadService.uploadPostPreview(postId, preview) }.getOrNull()
                }
            }
            // 롤백 대상에 파생물도 포함
            uploadedNewFiles.addAll(thumbnailUrls.filterNotNull())
            uploadedNewFiles.addAll(previewUrls.filterNotNull())

            val response = query {
                newMediaAttachments.mapIndexed { index, url ->
                    createCommunityMediaItem(
                        postId = postId,
                        fileBytes = mediaFiles[index],
                        url = url,
                        sortOrder = currentCount + index,
                        thumbnailUrl = thumbnailUrls[index],
                        previewUrl = previewUrls[index]
                    ).toMediaItemResponse()
                }
            }

            cacheService.deletePattern(CacheKeys.Patterns.community(creatorId))
            cacheService.deletePattern(CacheKeys.Patterns.post(postId))

            return response

        } catch (e: Exception) {
            rollbackUploadedFiles(uploadedNewFiles)  // 실패 시 업로드 파일 롤백
            throw e
        }
    }

    suspend fun updateCommunityWithMedia(
        postId: Int,
        userId: Int,
        creatorId: Int,
        content: String?,
        keepMediaIds: List<Int>?,
        newFiles: List<ByteArray>?,
        newContentTypes: List<String>?,
        reorder: List<Int>?
    ): PostResponse {
        val uploadedNewFiles = mutableListOf<String>()
        val mediaAttachmentsToDelete = mutableListOf<String>()
        var newThumbnailUrls: List<String?> = emptyList()
        var newPreviewUrls: List<String?> = emptyList()
        // 커밋 후 실패는 롤백 안 함
        var committed = false

        try {
            val finalContent = content?.let { ValidationUtils.sanitizeHtml(it) }

            finalContent?.let {
                ValidationUtils.validatePostContent(it)
            }

            if (!newFiles.isNullOrEmpty() && !newContentTypes.isNullOrEmpty() && newFiles.size != newContentTypes.size) {
                throw InvalidFileException("파일 개수와 콘텐츠 타입 개수가 일치하지 않습니다.")
            }

            val (orderedKeepItemIds, contentToUse, deleteItemIds) = query {
                val post = postRepository.findPostById(postId)
                    ?: throw PostNotFoundException(Errors.Social.Post.POST_NOT_FOUND)

                if (post.userId != userId) {
                    throw ForbiddenException(Errors.Social.Post.POST_UPDATE_PERMISSION_DENIED)
                }

                if (post.creatorId != creatorId) {
                    throw ForbiddenException(Errors.Social.Community.ONLY_FOLLOWERS_CAN_POST)
                }

                val currentItems = PostMediaDao.find { PostMediaTable.postId eq postId }
                    .orderBy(PostMediaTable.sortOrder to SortOrder.ASC)
                    .toList()

                val plan = resolveMediaEditPlan(currentItems, keepMediaIds, reorder, newFiles?.size ?: 0) { it.sortOrder }

                // 원본 + 썸네일 + 프리뷰 수거
                mediaAttachmentsToDelete.addAll(
                    plan.deleteItems.flatMap { listOfNotNull(it.url, it.thumbnailUrl, it.previewUrl) }
                        .filterNot { fileUploadService.isSharedSeedFile(it) }
                )

                Triple(
                    plan.orderedKeepIds,
                    finalContent ?: post.content,
                    plan.deleteItems.map { it.id.value }
                )
            }

            if (!newFiles.isNullOrEmpty() && !newContentTypes.isNullOrEmpty()) {
                val maxSize = StorageConfig.FileSizeLimit.POST
                val allValid = newFiles.all { imageProcessingService.validateImage(it, maxSize) }

                if (!allValid) {
                    throw ImageValidationFailedException(Errors.File.IMAGE_VALIDATION_FAILED)
                }

                val extensions = newContentTypes.map { imageProcessingService.getFileExtension(it) }
                val urls = fileUploadService.uploadPostMediaList(postId, newFiles, extensions)
                uploadedNewFiles.addAll(urls)

                newThumbnailUrls = newFiles.map { bytes ->
                    imageProcessingService.generateDisplayImage(bytes)?.let { display ->
                        runCatching { fileUploadService.uploadPostThumbnail(postId, display) }.getOrNull()
                    }
                }
                newPreviewUrls = newFiles.map { bytes ->
                    imageProcessingService.generateLockedPreview(bytes)?.let { preview ->
                        runCatching { fileUploadService.uploadPostPreview(postId, preview) }.getOrNull()
                    }
                }
            }

            val updatedPost = query {
                val post = postRepository.findPostById(postId)
                    ?: throw PostNotFoundException(Errors.Social.Post.POST_NOT_FOUND)

                if (post.userId != userId) {
                    throw ForbiddenException(Errors.Social.Post.POST_UPDATE_PERMISSION_DENIED)
                }

                val extractedTags = extractHashtags(contentToUse)

                val result = postRepository.updatePost(
                    postId = postId,
                    content = contentToUse,
                    mediaAttachments = emptyList(),
                    tags = extractedTags
                ) ?: throw PostUpdateFailedException(Errors.Social.Post.POST_UPDATE_FAILED)

                orderedKeepItemIds.forEachIndexed { index, mediaId ->
                    val media = PostMediaDao.findById(mediaId)
                        ?: throw InvalidInputException(Errors.Social.Media.KEEP_MEDIA_NOT_FOUND)
                    media.sortOrder = index
                }

                if (!newFiles.isNullOrEmpty() && !newContentTypes.isNullOrEmpty()) {
                    uploadedNewFiles.forEachIndexed { index, url ->
                        createCommunityMediaItem(
                            postId = postId,
                            fileBytes = newFiles[index],
                            url = url,
                            sortOrder = orderedKeepItemIds.size + index,
                            thumbnailUrl = newThumbnailUrls.getOrNull(index),
                            previewUrl = newPreviewUrls.getOrNull(index)
                        )
                    }
                }

                deleteItemIds.forEach { mediaId ->
                    PostMediaDao.findById(mediaId)?.delete()
                }

                result
            }
            committed = true

            mediaAttachmentsToDelete.forEach { url ->
                try {
                    fileUploadService.deleteFileIfSupabase(url)
                } catch (e: Exception) {
                    logger.warn("이미지 삭제 실패: {} - {}", url, e.message)
                }
            }

            val response = query {
                val post = postRepository.findPostById(updatedPost.id.value)
                    ?: throw PostNotFoundException(Errors.Social.Post.POST_NOT_FOUND)
                val author = userRepository.findUserById(userId)?.toSummaryResponse()
                    ?: throw UserNotFoundException(Errors.User.USER_INFO_NOT_FOUND)
                val isLiked = likeRepository.isPostLiked(userId, post.id.value)
                val isBookmarked = bookmarkRepository.isBookmarked(userId, BookmarkTargetType.POST, post.id.value)
                val mediaItems = batchLoadPostMediaItems(listOf(post.id.value))[post.id.value] ?: emptyList()
                post.toPostResponse(author = author, isLiked = isLiked, isBookmarked = isBookmarked, mediaItems = mediaItems)
            }

            // post 상세 캐시도 무효화
            cacheService.deletePattern(CacheKeys.Patterns.community(creatorId))
            cacheService.deletePattern(CacheKeys.Patterns.post(postId))

            return response

        } catch (e: Exception) {
            // 커밋 후 실패는 롤백 금지
            // 썸네일/프리뷰는 별도 합산해 회수
            if (!committed) {
                rollbackUploadedFiles(
                    uploadedNewFiles + newThumbnailUrls.filterNotNull() + newPreviewUrls.filterNotNull()
                )
            }
            throw e
        }
    }

    private fun createCommunityMediaItem(
        postId: Int,
        fileBytes: ByteArray,
        url: String,
        sortOrder: Int,
        thumbnailUrl: String? = null,
        previewUrl: String? = null
    ): PostMediaDao {
        val dimensions = imageProcessingService.extractImageDimensions(fileBytes)

        return PostMediaDao.new {
            this.postId = postId
            this.type = SocialMediaType.IMAGE
            this.url = url
            this.width = dimensions?.first
            this.height = dimensions?.second
            this.durationMs = null
            this.thumbnailUrl = thumbnailUrl
            this.previewUrl = previewUrl
            this.sortOrder = sortOrder
        }
    }

    private suspend fun rollbackUploadedFiles(urls: List<String>) {
        urls.forEach { url ->
            try {
                fileUploadService.deleteFileIfSupabase(url)
            } catch (e: Exception) {
                logger.warn("롤백 중 파일 삭제 실패: {} - {}", url, e.message)
            }
        }
    }

    // 접근 판정
    private suspend fun checkMultiplePostAccess(userId: Int, posts: List<PostDao>): Map<Int, Boolean> {
        val creatorIds = posts.mapNotNull { it.creatorId }.distinct()

        if (creatorIds.isEmpty()) {
            return posts.associate { post ->
                // viewerTier=null로 위임
                post.id.value to TierAccessEvaluator.canAccess(
                    viewerId = userId,
                    ownerId = post.userId,
                    creatorId = post.creatorId,
                    requiredTier = post.requiredTier,
                    isSecret = post.isSecret,
                    viewerTier = null
                )
            }
        }

        val subscriptions = subscriptionRepository.findActiveSubscriptionsByCreators(userId, creatorIds)
        val subscriptionMap = subscriptions.associateBy { it.creatorId }

        val planIds = subscriptions.map { it.planId }.distinct()
        val plans = planRepository.findPlansByIds(planIds)
        val planMap = plans.associateBy { it.id.value }

        return posts.associate { post ->
            // TierAccessEvaluator에 위임
            val viewerTier = subscriptionMap[post.creatorId ?: post.userId]?.let { planMap[it.planId]?.tier }
            post.id.value to TierAccessEvaluator.canAccess(
                viewerId = userId,
                ownerId = post.userId,
                creatorId = post.creatorId,
                requiredTier = post.requiredTier,
                isSecret = post.isSecret,
                viewerTier = viewerTier
            )
        }
    }
}
