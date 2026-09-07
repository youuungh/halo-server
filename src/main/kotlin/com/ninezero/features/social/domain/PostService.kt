package com.ninezero.features.social.domain

import com.ninezero.core.cache.CacheKeys
import com.ninezero.core.cache.CacheService
import com.ninezero.core.common.config.*
import com.ninezero.core.common.exception.*
import com.ninezero.core.common.util.*
import com.ninezero.core.database.entities.social.PostDao
import com.ninezero.core.database.entities.social.PostMediaDao
import com.ninezero.core.database.entities.social.PostMediaTable
import com.ninezero.core.storage.FileUploadService
import com.ninezero.core.storage.ImageProcessingService
import com.ninezero.core.storage.StorageConfig
import com.ninezero.core.storage.VideoProcessingService
import com.ninezero.features.notification.domain.NotificationService
import com.ninezero.features.social.*
import com.ninezero.features.social.data.BookmarkRepository
import com.ninezero.features.social.data.FollowRepository
import com.ninezero.features.social.data.LikeRepository
import com.ninezero.features.social.data.PostRepository
import com.ninezero.features.social.presentation.models.request.PostRequest
import com.ninezero.features.social.presentation.models.request.UpdatePostRequest
import com.ninezero.features.social.presentation.models.response.*
import com.ninezero.features.subscription.data.SubscriptionPlanRepository
import com.ninezero.features.subscription.data.SubscriptionRepository
import com.ninezero.features.tag.data.TagRepository
import com.ninezero.features.tag.toTagResponse
import com.ninezero.features.user.data.BlockedUserRepository
import com.ninezero.features.user.data.UserRepository
import com.ninezero.features.user.toSummaryResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.SortOrder
import kotlin.time.Duration.Companion.minutes
import com.ninezero.core.common.config.MediaType as SocialMediaType

class PostService(
    private val userRepository: UserRepository,
    private val postRepository: PostRepository,
    private val likeRepository: LikeRepository,
    private val bookmarkRepository: BookmarkRepository,
    private val followRepository: FollowRepository,
    private val tagRepository: TagRepository,
    private val subscriptionRepository: SubscriptionRepository,
    private val planRepository: SubscriptionPlanRepository,
    private val blockedUserRepository: BlockedUserRepository,
    private val notificationService: NotificationService,
    private val fileUploadService: FileUploadService,
    private val imageProcessingService: ImageProcessingService,
    private val videoProcessingService: VideoProcessingService,
    private val cacheService: CacheService,
    private val coroutineScope: CoroutineScope
) {
    private val logger = logger()

    /** 포스트 생성 */
    suspend fun createPost(userId: Int, request: PostRequest): PostResponse {
        val content = ValidationUtils.sanitizeHtml(request.content)

        ValidationUtils.validatePostContent(content)

        val extractedTags = extractHashtags(content)

        request.tagIds?.let { tagIds ->
            if (request.sectionTagId != null && !tagIds.contains(request.sectionTagId)) {
                throw InvalidTagException(Errors.Social.Tag.SECTION_TAG_NOT_IN_SELECTED_TAGS)
            }
        }

        val (post, response) = query {
            val user = userRepository.findUserById(userId)
                ?: throw UserNotFoundException(Errors.User.USER_NOT_FOUND)

            if (request.requiredTier != SubscriptionPlanTier.FREE) {
                if (user.role != UserRole.CREATOR && user.role != UserRole.ADMIN) {
                    throw CreatorOnlyException(Errors.Social.Post.CREATOR_ONLY_CONTENT)
                }
                // 활성 플랜 없는 등급은 게시 차단
                if (user.role == UserRole.CREATOR) {
                    val hasPlanForTier = planRepository.findActivePlansByCreator(userId, 1, 100)
                        .any { it.tier == request.requiredTier }
                    if (!hasPlanForTier) {
                        throw ValidationException("해당 등급의 멤버십 플랜이 없어 이 등급으로 게시할 수 없습니다.")
                    }
                }
            }

            val isCreator = user.role == UserRole.CREATOR || user.role == UserRole.ADMIN
            val contextType = if (isCreator) PostContextType.CREATOR_FEED else PostContextType.GENERAL
            val creatorId = if (isCreator) userId else null

            val createdPost = PostDao.new {
                this.userId = userId
                this.creatorId = creatorId
                this.contextType = contextType
                this.content = content
                this.postType = request.postType
                this.tags = if (extractedTags.isNotEmpty()) {
                    Json.encodeToString(extractedTags)
                } else null
                this.productId = request.productId
                this.requiredTier = request.requiredTier
            }

            request.tagIds?.let { tagIds ->
                tagRepository.attachTagsToPost(
                    postId = createdPost.id.value,
                    tagIds = tagIds,
                    sectionTagId = request.sectionTagId
                )
            }

            val author = user.toSummaryResponse()
            val isLiked = likeRepository.isPostLiked(userId, createdPost.id.value)
            val isBookmarked = bookmarkRepository.isBookmarked(userId, BookmarkTargetType.POST, createdPost.id.value)

            val mediaItems = batchLoadPostMediaItems(listOf(createdPost.id.value))[createdPost.id.value] ?: emptyList()
            Pair(
                createdPost,
                createdPost.toPostResponse(author = author, isLiked = isLiked, isBookmarked = isBookmarked, mediaItems = mediaItems)
            )
        }

        invalidatePostCaches(post.id.value, userId)

        // 팔로워에게 새 게시물 알림 전송
        if (post.creatorId != null) {
            coroutineScope.launch {
                try {
                    val followerIds = query {
                        followRepository.findPostNotifyFollowers(userId)
                    }

                    if (followerIds.isNotEmpty()) {
                        val creatorUsername = response.author.username
                        notificationService.sendNewPostNotifications(
                            creatorId = userId,
                            creatorUsername = creatorUsername,
                            postId = post.id.value,
                            followerIds = followerIds
                        )
                    }
                } catch (e: Exception) {
                    logger.error("새 포스트 알림 전송 실패: postId=${post.id.value}, error=${e.message}", e)
                }
            }
        }

        return response
    }

    // 포스트 조회
    suspend fun getPostById(postId: Int, currentUserId: Int?): PostResponse {
        val cacheKey = CacheKeys.post(postId, currentUserId)
        cacheService.getJson<PostResponse>(cacheKey)?.let {
            return it
        }

        val response = query {
            val post = postRepository.findPostById(postId)
                ?: run {
                    val inactive = postRepository.findPostsByIdsWithDeleted(listOf(postId)).firstOrNull()

                    // 블라인드 글은 작성자 본인에게만 노출
                    if (inactive != null && inactive.isBlinded && currentUserId == inactive.userId) {
                        inactive
                    } else {
                        // HIDDEN 글은 삭제와 구분해 404
                        if (inactive != null && inactive.status == PostStatus.HIDDEN) {
                            throw NotFoundException(Errors.Social.Post.POST_UNAVAILABLE)
                        }
                        throw PostNotFoundException(postId)
                    }
                }

            // 차단은 throw
            if (currentUserId != null && currentUserId != post.userId &&
                blockedUserRepository.isBlockedEither(currentUserId, post.userId)) {
                throw UserBlockedException(Errors.User.Block.USER_BLOCKED)
            }

            val canAccess = if (currentUserId != null) {
                checkPostAccess(currentUserId, post)
            } else {
                post.requiredTier == SubscriptionPlanTier.FREE && !post.isSecret
            }

            // 접근 가능할 때만 조회수 증가
            if (canAccess) {
                postRepository.incrementViewCount(postId)
            }

            val author = userRepository.findUserById(post.userId)?.toSummaryResponse(
                isFollowing = if (currentUserId != null && currentUserId != post.userId)
                    followRepository.isFollowing(currentUserId, post.userId) else null
            ) ?: throw UserNotFoundException(Errors.Social.Post.AUTHOR_NOT_FOUND)

            val isLiked = currentUserId?.let { likeRepository.isPostLiked(it, post.id.value) } ?: false
            val isBookmarked = currentUserId?.let { bookmarkRepository.isBookmarked(it, BookmarkTargetType.POST, post.id.value) } ?: false
            val mediaItems = batchLoadPostMediaItems(listOf(post.id.value))[post.id.value] ?: emptyList()
            post.toPostResponse(author = author, isLiked = isLiked, isBookmarked = isBookmarked, canAccess = canAccess, mediaItems = mediaItems)
        }

        cacheService.setJson(cacheKey, response, ttl = 5.minutes)

        return response
    }

    // 포스트 수정
    suspend fun updatePost(postId: Int, userId: Int, request: UpdatePostRequest): PostResponse {
        val content = ValidationUtils.sanitizeHtml(request.content)

        ValidationUtils.validatePostContent(content)

        val extractedTags = extractHashtags(content)

        request.tagIds?.let { tagIds ->
            if (request.sectionTagId != null && !tagIds.contains(request.sectionTagId)) {
                throw InvalidTagException(Errors.Social.Tag.SECTION_TAG_NOT_IN_SELECTED_TAGS)
            }
        }

        val response = query {
            val existingPost = verifyPostOwnership(  // 소유자만
                postId = postId,
                userId = userId,
                errorMessage = Errors.Social.Post.POST_UPDATE_PERMISSION_DENIED
            )

            existingPost.content = content
            existingPost.tags = if (extractedTags.isNotEmpty()) {
                Json.encodeToString(extractedTags)
            } else null

            request.tagIds?.let { tagIds ->
                tagRepository.attachTagsToPost(
                    postId = postId,
                    tagIds = tagIds,
                    sectionTagId = request.sectionTagId
                )
            }

            val author = userRepository.findUserById(userId)?.toSummaryResponse()
                ?: throw UserNotFoundException(Errors.User.USER_NOT_FOUND)
            val isLiked = likeRepository.isPostLiked(userId, existingPost.id.value)
            val isBookmarked = bookmarkRepository.isBookmarked(userId, BookmarkTargetType.POST, existingPost.id.value)
            val mediaItems = batchLoadPostMediaItems(listOf(existingPost.id.value))[existingPost.id.value] ?: emptyList()

            existingPost.toPostResponse(author = author, isLiked = isLiked, isBookmarked = isBookmarked, mediaItems = mediaItems)
        }

        invalidatePostCaches(postId, userId)

        return response
    }

    suspend fun uploadPostMedia(
        postId: Int,
        userId: Int,
        mediaFiles: List<ByteArray>,
        contentTypes: List<String>
    ): List<MediaItemResponse> {
        val uploadedNewFiles = mutableListOf<String>()

        try {
            ValidationUtils.validateUploadedMediaFiles(mediaFiles, contentTypes)

            val maxSize = StorageConfig.FileSizeLimit.POST
            // 파일별로 미디어 타입 판별
            val fileTypes = contentTypes.map { inferMediaType(it) }
            mediaFiles.forEachIndexed { index, bytes ->
                val valid = when (fileTypes[index]) {
                    SocialMediaType.IMAGE -> imageProcessingService.validateImage(bytes, maxSize)
                    SocialMediaType.VIDEO -> imageProcessingService.validateVideo(bytes, maxSize)
                }
                if (!valid) {
                    throw when (fileTypes[index]) {
                        SocialMediaType.IMAGE -> ImageValidationFailedException(Errors.File.IMAGE_VALIDATION_FAILED)
                        SocialMediaType.VIDEO -> ValidationException(Errors.File.MEDIA_VALIDATION_FAILED)
                    }
                }
            }

            val currentCount = query {
                verifyPostOwnership(
                    postId = postId,
                    userId = userId,
                    errorMessage = Errors.Common.UNAUTHORIZED
                )

                PostMediaDao.find { PostMediaTable.postId eq postId }.count().toInt()
            }

            // 첨부 개수 제한
            if (currentCount + mediaFiles.size > Constants.Social.MAX_MEDIA_ATTACHMENTS_PER_POST) {
                throw InvalidInputException(Errors.Social.Media.ATTACHMENT_LIMIT_EXCEEDED)
            }

            // faststart만 동기
            val extensions = contentTypes.map { imageProcessingService.getFileExtension(it) }
            val filesToUpload = mediaFiles.mapIndexed { index, bytes ->
                if (fileTypes[index] == SocialMediaType.VIDEO) {
                    videoProcessingService.optimizeForStreaming(bytes) ?: bytes
                } else {
                    bytes
                }
            }
            val newMediaAttachments = fileUploadService.uploadPostMediaList(postId, filesToUpload, extensions)
            uploadedNewFiles.addAll(newMediaAttachments)

            // 미디어별 메타/썸네일/프리뷰 추출
            val mediaExtras = mediaFiles.mapIndexed { index, bytes ->
                when (fileTypes[index]) {
                    SocialMediaType.VIDEO -> {
                        val meta = videoProcessingService.extractMetadata(bytes)
                        val thumb = videoProcessingService.extractThumbnail(bytes)
                        val thumbUrl = thumb?.let {
                            runCatching { fileUploadService.uploadPostThumbnail(postId, it) }.getOrNull()
                        }
                        val previewUrl = thumb?.let { imageProcessingService.generateLockedPreview(it) }?.let { preview ->
                            runCatching { fileUploadService.uploadPostPreview(postId, preview) }.getOrNull()
                        }
                        MediaDerivatives(meta, thumbUrl, previewUrl)
                    }
                    SocialMediaType.IMAGE -> {
                        val thumbUrl = imageProcessingService.generateDisplayImage(bytes)?.let { display ->
                            runCatching { fileUploadService.uploadPostThumbnail(postId, display) }.getOrNull()
                        }
                        val previewUrl = imageProcessingService.generateLockedPreview(bytes)?.let { preview ->
                            runCatching { fileUploadService.uploadPostPreview(postId, preview) }.getOrNull()
                        }
                        MediaDerivatives(null, thumbUrl, previewUrl)
                    }
                }
            }
            // 롤백 대상에 파생물도 포함
            uploadedNewFiles.addAll(mediaExtras.flatMap { listOfNotNull(it.thumbnailUrl, it.previewUrl) })

            val response = query {
                val created = newMediaAttachments.mapIndexed { index, url ->
                    createPostMediaItem(
                        postId = postId,
                        fileBytes = mediaFiles[index],
                        mediaType = fileTypes[index],
                        url = url,
                        sortOrder = currentCount + index,
                        videoMeta = mediaExtras[index].videoMeta,
                        thumbnailUrl = mediaExtras[index].thumbnailUrl,
                        previewUrl = mediaExtras[index].previewUrl
                    )
                }
                created.map { it.toMediaItemResponse() }
            }

            val videoJobs = newMediaAttachments.zip(mediaFiles)
                .filterIndexed { index, _ -> fileTypes[index] == SocialMediaType.VIDEO }
            if (videoJobs.isNotEmpty()) {
                scheduleVideoTranscode(videoJobs)
            }

            invalidatePostCaches(postId, userId)

            return response

        } catch (e: Exception) {
            rollbackUploadedFiles(uploadedNewFiles)  // 실패 시 업로드분 롤백
            throw e
        }
    }

    private fun scheduleVideoTranscode(jobs: List<Pair<String, ByteArray>>) =
        launchVideoTranscode(coroutineScope, videoProcessingService, fileUploadService, jobs) { url ->
            query { !PostMediaDao.find { PostMediaTable.url eq url }.empty() }
        }

    /** 미디어 포함 포스트 수정 */
    suspend fun updatePostWithMedia(
        postId: Int,
        userId: Int,
        content: String?,
        keepMediaIds: List<Int>?,
        newFiles: List<ByteArray>?,
        newContentTypes: List<String>?,
        reorder: List<Int>?
    ): PostResponse {
        val uploadedNewFiles = mutableListOf<String>()
        val mediaAttachmentsToDelete = mutableListOf<String>()
        // 새 미디어 메타/썸네일/프리뷰
        var newMediaExtras: List<MediaDerivatives> = emptyList()
        var videoTranscodeJobs: List<Pair<String, ByteArray>> = emptyList()

        try {
            val finalContent = content?.let { ValidationUtils.sanitizeHtml(it) }

            finalContent?.let {
                ValidationUtils.validatePostContent(it)
            }

            if (!newFiles.isNullOrEmpty() && !newContentTypes.isNullOrEmpty() && newFiles.size != newContentTypes.size) {
                throw InvalidFileException("파일 개수와 콘텐츠 타입 개수가 일치하지 않습니다.")
            }

            val (orderedKeepItemIds, contentToUse, deleteItemIds) = query {  // 소유자만
                val post = verifyPostOwnership(
                    postId = postId,
                    userId = userId,
                    errorMessage = Errors.Social.Post.POST_UPDATE_PERMISSION_DENIED
                )

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

                newFiles.forEachIndexed { index, file ->
                    val mediaType = inferMediaType(newContentTypes[index])
                    val valid = when (mediaType) {
                        SocialMediaType.IMAGE -> imageProcessingService.validateImage(file, maxSize)
                        SocialMediaType.VIDEO -> imageProcessingService.validateVideo(file, maxSize)
                    }
                    if (!valid) {
                        throw when (mediaType) {
                            SocialMediaType.IMAGE -> ImageValidationFailedException(Errors.File.IMAGE_VALIDATION_FAILED)
                            SocialMediaType.VIDEO -> ValidationException(Errors.File.MEDIA_VALIDATION_FAILED)
                        }
                    }
                }

                val extensions = newContentTypes.map { imageProcessingService.getFileExtension(it) }
                // faststart만 동기
                val filesToUpload = newFiles.mapIndexed { index, bytes ->
                    if (inferMediaType(newContentTypes[index]) == SocialMediaType.VIDEO) {
                        videoProcessingService.optimizeForStreaming(bytes) ?: bytes
                    } else {
                        bytes
                    }
                }
                val urls = fileUploadService.uploadPostMediaList(postId, filesToUpload, extensions)
                uploadedNewFiles.addAll(urls)
                videoTranscodeJobs = urls.zip(newFiles).filterIndexed { index, _ ->
                    inferMediaType(newContentTypes[index]) == SocialMediaType.VIDEO
                }

                // 새 미디어 메타/썸네일/프리뷰 추출
                newMediaExtras = newFiles.mapIndexed { index, bytes ->
                    if (inferMediaType(newContentTypes[index]) == SocialMediaType.VIDEO) {
                        val meta = videoProcessingService.extractMetadata(bytes)
                        val thumb = videoProcessingService.extractThumbnail(bytes)
                        val thumbUrl = thumb?.let {
                            runCatching { fileUploadService.uploadPostThumbnail(postId, it) }.getOrNull()
                        }
                        val previewUrl = thumb?.let { imageProcessingService.generateLockedPreview(it) }?.let { preview ->
                            runCatching { fileUploadService.uploadPostPreview(postId, preview) }.getOrNull()
                        }
                        MediaDerivatives(meta, thumbUrl, previewUrl)
                    } else {
                        val thumbUrl = imageProcessingService.generateDisplayImage(bytes)?.let { display ->
                            runCatching { fileUploadService.uploadPostThumbnail(postId, display) }.getOrNull()
                        }
                        val previewUrl = imageProcessingService.generateLockedPreview(bytes)?.let { preview ->
                            runCatching { fileUploadService.uploadPostPreview(postId, preview) }.getOrNull()
                        }
                        MediaDerivatives(null, thumbUrl, previewUrl)
                    }
                }
            }

            val response = query {
                val post = verifyPostOwnership(
                    postId = postId,
                    userId = userId,
                    errorMessage = Errors.Social.Post.POST_UPDATE_PERMISSION_DENIED
                )

                val extractedTags = extractHashtags(contentToUse)

                val updatedPost = postRepository.updatePost(
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
                        createPostMediaItem(
                            postId = postId,
                            fileBytes = newFiles[index],
                            mediaType = inferMediaType(newContentTypes[index]),
                            url = url,
                            sortOrder = orderedKeepItemIds.size + index,
                            videoMeta = newMediaExtras.getOrNull(index)?.videoMeta,
                            thumbnailUrl = newMediaExtras.getOrNull(index)?.thumbnailUrl,
                            previewUrl = newMediaExtras.getOrNull(index)?.previewUrl
                        )
                    }
                }

                deleteItemIds.forEach { mediaId ->
                    PostMediaDao.findById(mediaId)?.delete()
                }

                val author = userRepository.findUserById(userId)?.toSummaryResponse()
                    ?: throw UserNotFoundException(Errors.User.USER_INFO_NOT_FOUND)
                val isLiked = likeRepository.isPostLiked(userId, updatedPost.id.value)
                val isBookmarked = bookmarkRepository.isBookmarked(userId, BookmarkTargetType.POST, updatedPost.id.value)
                val mediaItems = batchLoadPostMediaItems(listOf(updatedPost.id.value))[updatedPost.id.value] ?: emptyList()

                updatedPost.toPostResponse(author = author, isLiked = isLiked, isBookmarked = isBookmarked, mediaItems = mediaItems)
            }

            scheduleVideoTranscode(videoTranscodeJobs)

            mediaAttachmentsToDelete.forEach { mediaUrl ->  // 커밋 후 구 파일 삭제
                try {
                    fileUploadService.deleteFileIfSupabase(mediaUrl)
                } catch (e: Exception) {
                    logger.warn("미디어 삭제 실패: {} - {}", mediaUrl, e.message)
                }
            }

            // 캐시 무효화
            invalidatePostCaches(postId, userId)

            return response

        } catch (e: Exception) {
            rollbackUploadedFiles(  // 실패 시 신규 업로드분 롤백
                uploadedNewFiles + newMediaExtras.flatMap { listOfNotNull(it.thumbnailUrl, it.previewUrl) }
            )
            throw e
        }
    }

    /** 포스트 삭제 (파일 회수) */
    suspend fun deletePost(postId: Int, userId: Int): String {
        val mediaAttachments = query {
            val post = verifyPostOwnership(  // 소유자만
                postId = postId,
                userId = userId,
                errorMessage = Errors.Social.Post.POST_DELETE_PERMISSION_DENIED
            )

            // 원본 + 썸네일 + 프리뷰 수거
            PostMediaDao.find { PostMediaTable.postId eq postId }
                .orderBy(PostMediaTable.sortOrder to SortOrder.ASC)
                .flatMap { listOfNotNull(it.url, it.thumbnailUrl, it.previewUrl) }
                .filterNot { fileUploadService.isSharedSeedFile(it) }
        }

        val deleted = query {
            PostMediaDao.find { PostMediaTable.postId eq postId }.forEach { it.delete() }
            postRepository.deletePost(postId)
        }

        if (!deleted) {
            throw PostDeleteFailedException(Errors.Social.Post.POST_DELETE_FAILED)
        }

        mediaAttachments.forEach { url ->
            try {
                fileUploadService.deleteFileIfSupabase(url)
            } catch (e: Exception) {
                logger.warn("미디어 파일 삭제 실패: {} - {}", url, e.message)
            }
        }

        invalidatePostCaches(postId, userId)

        return Messages.Social.POST_DELETED
    }

    /** 피드 고정 */
    suspend fun togglePinPostInFeed(postId: Int, creatorId: Int): Map<String, Boolean> {
        val isPinned = query {
            val user = userRepository.findUserById(creatorId)
            if (user?.role != UserRole.CREATOR && user?.role != UserRole.ADMIN) {
                throw CreatorOnlyException(Errors.Social.Pin.ONLY_CREATOR_CAN_PIN)
            }

            val post = postRepository.findPostById(postId)
                ?: throw PostNotFoundException(postId)

            // 크리에이터 본인 글만
            if (post.userId != creatorId && post.creatorId != creatorId) {
                throw PermissionDeniedException(Errors.Social.Pin.ONLY_CREATOR_CAN_PIN)
            }

            val currentlyPinned = post.isPinnedInFeed

            if (currentlyPinned) {
                val success = postRepository.unpinPostInFeed(postId)
                if (!success) {
                    throw PostPinException(Errors.Social.Pin.PIN_ERROR)
                }
                false
            } else {
                val pinnedCount = postRepository.countPinnedPostsInFeed(creatorId)
                if (pinnedCount >= Constants.Social.MAX_PINNED_POSTS) {  // 최대 고정 개수 제한
                    throw PostPinLimitExceededException(Errors.Social.Pin.PINNED_POST_LIMIT_EXCEEDED)
                }

                val success = postRepository.pinPostInFeed(postId, creatorId)
                if (!success) {
                    throw PostPinException(Errors.Social.Pin.PIN_ERROR)
                }
                true
            }
        }

        cacheService.deletePattern(CacheKeys.Patterns.PINNED_POSTS)
        cacheService.deletePattern(CacheKeys.Patterns.CREATOR_PROFILE_SECTIONS)

        return mapOf("isPinned" to isPinned)
    }

    suspend fun getPinnedPostsInFeed(creatorId: Int, currentUserId: Int?): PostListResponse {
        val cacheKey = CacheKeys.pinnedPosts(creatorId, currentUserId)
        cacheService.getJson<PostListResponse>(cacheKey)?.let {
            return it
        }

        val response = query {
            val posts = postRepository.findPinnedPostsInFeed(
                creatorId = creatorId,
                limit = Constants.Social.MAX_PINNED_POSTS
            )

            val userIds = posts.map { it.userId }.distinct()
            val users = userRepository.findUsersByIds(userIds)
            val userMap = users.associateBy { it.id.value }

            val postIds = posts.map { it.id.value }
            val statusMaps = buildPostStatusMaps(currentUserId, posts, postIds)

            val postResponses = posts.mapNotNull { post ->
                val author = userMap[post.userId]?.toSummaryResponse(isFollowing = statusMaps.followStatusMap[post.userId]) ?: return@mapNotNull null
                val isLiked = statusMaps.likeStatusMap[post.id.value] ?: false
                val isBookmarked = statusMaps.bookmarkStatusMap[post.id.value] ?: false
                val canAccess = statusMaps.accessMap[post.id.value] ?: false
                post.toPostResponse(author = author, isLiked = isLiked, isBookmarked = isBookmarked, canAccess = canAccess, mediaItems = statusMaps.mediaItemsMap[post.id.value] ?: emptyList())
            }

            val pagination = PaginationInfo(1, Constants.Social.MAX_PINNED_POSTS, postResponses.size)
            createPagedResponse(postResponses, pagination)
        }

        cacheService.setJson(cacheKey, response, ttl = 15.minutes)

        return response
    }

    // 프로필 섹션
    suspend fun getCreatorProfileSections(
        creatorId: Int,
        currentUserId: Int?,
        page: Int,
        limit: Int = Constants.DEFAULT_PAGE_LIMIT
    ): CreatorProfileResponse {
        val (validPage, validLimit) = validatePaginationParams(page, limit)

        val cacheKey = CacheKeys.creatorProfileSections(creatorId, validPage, validLimit, currentUserId)
        cacheService.getJson<CreatorProfileResponse>(cacheKey)?.let {
            return it
        }

        val response = query {
            val pinnedPosts = postRepository.findPinnedPostsInFeed(
                creatorId = creatorId,
                limit = Constants.Social.MAX_PINNED_POSTS
            )

            val sectionTags = tagRepository.findSectionEnabledTags(
                creatorId = creatorId,
                targetType = TagTargetType.POST
            )

            val tagSectionsWithLatest = sectionTags.mapNotNull { tag ->
                val postIds = tagRepository.findPostIdsByTag(
                    tagId = tag.id.value,
                    page = 1,
                    limit = Constants.Social.MAX_ITEMS_PER_SECTION
                )

                if (postIds.isEmpty()) return@mapNotNull null

                val posts = postRepository.findPostsByIds(postIds)
                if (posts.isEmpty()) return@mapNotNull null

                val latestPostDate = posts.maxOfOrNull { it.createdAt }
                Triple(tag, posts, latestPostDate)
            }

            val topTagSections = tagSectionsWithLatest
                .sortedByDescending { it.third }
                .take(Constants.Social.MAX_TAG_SECTIONS)

            val allPosts = postRepository.findPostsByCreatorAndContext(
                creatorId = creatorId,
                contextType = PostContextType.CREATOR_FEED,
                page = page,
                limit = limit
            )

            val allPostsList = mutableListOf<PostDao>()
            allPostsList.addAll(pinnedPosts)
            topTagSections.forEach { (_, posts, _) -> allPostsList.addAll(posts) }
            allPostsList.addAll(allPosts)

            val userIds = allPostsList.map { it.userId }.distinct()
            val users = userRepository.findUsersByIds(userIds)
            val userMap = users.associateBy { it.id.value }

            val allPostIds = allPostsList.map { it.id.value }
            val statusMaps = buildPostStatusMaps(currentUserId, allPostsList, allPostIds)

            val pinnedPostResponses = pinnedPosts.mapNotNull { post ->
                val author = userMap[post.userId]?.toSummaryResponse(isFollowing = statusMaps.followStatusMap[post.userId]) ?: return@mapNotNull null
                val isLiked = statusMaps.likeStatusMap[post.id.value] ?: false
                val isBookmarked = statusMaps.bookmarkStatusMap[post.id.value] ?: false
                val canAccess = statusMaps.accessMap[post.id.value] ?: false
                post.toPostResponse(author = author, isLiked = isLiked, isBookmarked = isBookmarked, canAccess = canAccess, mediaItems = statusMaps.mediaItemsMap[post.id.value] ?: emptyList())
            }

            val tagSectionResponses = topTagSections.map { (tag, posts, _) ->
                val postResponses = posts.mapNotNull { post ->
                    val author = userMap[post.userId]?.toSummaryResponse(isFollowing = statusMaps.followStatusMap[post.userId]) ?: return@mapNotNull null
                    val isLiked = statusMaps.likeStatusMap[post.id.value] ?: false
                    val isBookmarked = statusMaps.bookmarkStatusMap[post.id.value] ?: false
                    val canAccess = statusMaps.accessMap[post.id.value] ?: false
                    post.toPostResponse(author = author, isLiked = isLiked, isBookmarked = isBookmarked, canAccess = canAccess, mediaItems = statusMaps.mediaItemsMap[post.id.value] ?: emptyList())
                }

                val postCount = tagRepository.countPostsByTag(tag.id.value)
                val hasMore = postCount > Constants.Social.MAX_ITEMS_PER_SECTION

                TagSectionResponse(
                    tag = tag.toTagResponse(postCount = postCount),
                    posts = postResponses,
                    hasMore = hasMore
                )
            }

            val allPostResponses = allPosts.mapNotNull { post ->
                val author = userMap[post.userId]?.toSummaryResponse(isFollowing = statusMaps.followStatusMap[post.userId]) ?: return@mapNotNull null
                val isLiked = statusMaps.likeStatusMap[post.id.value] ?: false
                val isBookmarked = statusMaps.bookmarkStatusMap[post.id.value] ?: false
                val canAccess = statusMaps.accessMap[post.id.value] ?: false
                post.toPostResponse(author = author, isLiked = isLiked, isBookmarked = isBookmarked, canAccess = canAccess, mediaItems = statusMaps.mediaItemsMap[post.id.value] ?: emptyList())
            }

            val totalCount = postRepository.countPostsByUserId(creatorId)

            val pagination = PaginationInfo(validPage, validLimit, totalCount)
            val allPostsResponse = createPagedResponse(allPostResponses, pagination)

            CreatorProfileResponse(
                pinnedPosts = pinnedPostResponses,
                tagSections = tagSectionResponses,
                allPosts = allPostsResponse
            )
        }

        cacheService.setJson(cacheKey, response, ttl = 15.minutes)

        return response
    }

    // 포스트 목록
    suspend fun getUserPosts(
        targetUserId: Int,
        currentUserId: Int?,
        page: Int = 1,
        limit: Int = Constants.DEFAULT_PAGE_LIMIT,
        sort: UserPostSortType = UserPostSortType.LATEST,
        contextType: PostContextType? = null
    ): PostListResponse {
        val (validPage, validLimit) = validatePaginationParams(page, limit)

        return query {
            // 블라인드 글은 작성자 본인 프로필에서만 노출
            val isOwnProfile = currentUserId != null && currentUserId == targetUserId
            val posts = postRepository.findPostsByUserId(
                targetUserId, validPage, validLimit, sort, contextType, isOwnProfile
            )
            val totalCount = postRepository.countUserPosts(targetUserId, contextType, isOwnProfile)

            val author = userRepository.findUserById(targetUserId)?.toSummaryResponse(
                isFollowing = if (currentUserId != null && currentUserId != targetUserId)
                    followRepository.isFollowing(currentUserId, targetUserId) else null
            ) ?: throw UserNotFoundException(Errors.User.USER_INFO_NOT_FOUND)

            val postIds = posts.map { it.id.value }
            val statusMaps = buildPostStatusMaps(currentUserId, posts, postIds)

            val postResponses = posts.map { post ->
                val isLiked = statusMaps.likeStatusMap[post.id.value] ?: false
                val isBookmarked = statusMaps.bookmarkStatusMap[post.id.value] ?: false
                val canAccess = statusMaps.accessMap[post.id.value] ?: false
                post.toPostResponse(author = author, isLiked = isLiked, isBookmarked = isBookmarked, canAccess = canAccess, mediaItems = statusMaps.mediaItemsMap[post.id.value] ?: emptyList())
            }

            val pagination = PaginationInfo(validPage, validLimit, totalCount)
            createPagedResponse(postResponses, pagination)
        }
    }

    suspend fun searchPosts(
        query: String,
        currentUserId: Int?,
        page: Int = 1,
        limit: Int = Constants.DEFAULT_PAGE_LIMIT
    ): PostListResponse {
        ValidationUtils.validateSearchQuery(query)

        val (validPage, validLimit) = validatePaginationParams(page, limit)

        return query {
            // 차단 유저 글은 검색에서도 제외
            val blockedIds = if (currentUserId != null) blockedUserRepository.findBlockRelatedUserIds(currentUserId) else emptySet<Int>()
            val posts = postRepository.searchPosts(query.trim(), validPage, validLimit)
                .filter { it.userId !in blockedIds }

            val userIds = posts.map { it.userId }.distinct()
            val users = userRepository.findUsersByIds(userIds)
            val userMap = users.associateBy { it.id.value }

            val postIds = posts.map { it.id.value }
            val statusMaps = buildPostStatusMaps(currentUserId, posts, postIds)

            val postResponses = posts.map { post ->
                val author = userMap[post.userId]?.toSummaryResponse(isFollowing = statusMaps.followStatusMap[post.userId])
                    ?: throw UserNotFoundException(Errors.Social.Post.AUTHOR_NOT_FOUND)

                val isLiked = statusMaps.likeStatusMap[post.id.value] ?: false
                val isBookmarked = statusMaps.bookmarkStatusMap[post.id.value] ?: false
                val canAccess = statusMaps.accessMap[post.id.value] ?: false
                post.toPostResponse(author = author, isLiked = isLiked, isBookmarked = isBookmarked, canAccess = canAccess, mediaItems = statusMaps.mediaItemsMap[post.id.value] ?: emptyList())
            }

            val totalCount = if (posts.size < validLimit) posts.size else posts.size * 2

            val pagination = PaginationInfo(validPage, validLimit, totalCount)
            createPagedResponse(postResponses, pagination)
        }
    }

    suspend fun getPostsByHashtag(
        hashtag: String,
        currentUserId: Int?,
        page: Int = 1,
        limit: Int = Constants.DEFAULT_PAGE_LIMIT
    ): PostListResponse {
        if (!isValidHashtag(hashtag)) {
            throw InvalidHashtagException(Errors.Social.Post.INVALID_HASHTAG)
        }

        val (validPage, validLimit) = validatePaginationParams(page, limit)

        return query {
            // 차단 유저 글은 해시태그 목록에서도 제외
            val blockedIds = if (currentUserId != null) blockedUserRepository.findBlockRelatedUserIds(currentUserId) else emptySet<Int>()
            val posts = postRepository.findPostsByHashtag(hashtag, validPage, validLimit)
                .filter { it.userId !in blockedIds }

            val userIds = posts.map { it.userId }.distinct()
            val users = userRepository.findUsersByIds(userIds)
            val userMap = users.associateBy { it.id.value }

            val postIds = posts.map { it.id.value }
            val statusMaps = buildPostStatusMaps(currentUserId, posts, postIds)

            val postResponses = posts.map { post ->
                val author = userMap[post.userId]?.toSummaryResponse(isFollowing = statusMaps.followStatusMap[post.userId])
                    ?: throw UserNotFoundException(Errors.Social.Post.AUTHOR_NOT_FOUND)

                val isLiked = statusMaps.likeStatusMap[post.id.value] ?: false
                val isBookmarked = statusMaps.bookmarkStatusMap[post.id.value] ?: false
                val canAccess = statusMaps.accessMap[post.id.value] ?: false
                post.toPostResponse(author = author, isLiked = isLiked, isBookmarked = isBookmarked, canAccess = canAccess, mediaItems = statusMaps.mediaItemsMap[post.id.value] ?: emptyList())
            }

            val totalCount = if (posts.size < validLimit) posts.size else posts.size * 2

            val pagination = PaginationInfo(validPage, validLimit, totalCount)
            createPagedResponse(postResponses, pagination)
        }
    }

    suspend fun getPostsByTag(
        tagId: Int,
        currentUserId: Int?,
        page: Int,
        limit: Int = Constants.DEFAULT_PAGE_LIMIT
    ): PostListResponse {
        return query {
            tagRepository.findTagById(tagId)
                ?: throw TagNotFoundException(Errors.Social.Tag.TAG_NOT_FOUND)

            val postIds = tagRepository.findPostIdsByTag(tagId = tagId, page = page, limit = limit)
            val totalCount = tagRepository.countPostIdsByTag(tagId)

            // 차단 유저 글은 태그 목록에서도 제외
            val blockedIds = if (currentUserId != null) blockedUserRepository.findBlockRelatedUserIds(currentUserId) else emptySet<Int>()
            val posts = if (postIds.isNotEmpty()) {
                postRepository.findPostsByIds(postIds).filter { it.userId !in blockedIds }
            } else {
                emptyList()
            }

            val userIds = posts.map { it.userId }.distinct()
            val users = userRepository.findUsersByIds(userIds)
            val userMap = users.associateBy { it.id.value }

            val statusMaps = buildPostStatusMaps(currentUserId, posts, postIds)

            val postResponses = posts.mapNotNull { post ->
                val author = userMap[post.userId]?.toSummaryResponse(isFollowing = statusMaps.followStatusMap[post.userId]) ?: return@mapNotNull null
                val isLiked = statusMaps.likeStatusMap[post.id.value] ?: false
                val isBookmarked = statusMaps.bookmarkStatusMap[post.id.value] ?: false
                val canAccess = statusMaps.accessMap[post.id.value] ?: false
                post.toPostResponse(author = author, isLiked = isLiked, isBookmarked = isBookmarked, canAccess = canAccess, mediaItems = statusMaps.mediaItemsMap[post.id.value] ?: emptyList())
            }

            val pagination = PaginationInfo(page, limit, totalCount)
            createPagedResponse(postResponses, pagination)
        }
    }

    // 관리자 조회
    suspend fun getAdminPosts(page: Int, limit: Int = Constants.DEFAULT_PAGE_LIMIT): PaginatedResponse<AdminPostResponse> {
        val validPage = page.coerceAtLeast(1)
        val validLimit = limit.coerceIn(1, Constants.MAX_PAGE_LIMIT)

        return query {
            val totalCount = postRepository.countAllPosts()
            val posts = postRepository.findAllPosts(validPage, validLimit)

            val userIds = posts.map { it.userId }.distinct()
            val users = userRepository.findUsersByIds(userIds)
            val userMap = users.associateBy { it.id.value }

            val postIds = posts.map { it.id.value }
            val mediaItemsMap = batchLoadPostMediaItems(postIds)

            val postResponses = posts.map { post ->
                val author = userMap[post.userId]?.toSummaryResponse()
                    ?: throw UserNotFoundException(Errors.Social.Post.AUTHOR_NOT_FOUND)

                post.toAdminPostResponse(
                    author = author,
                    mediaItems = mediaItemsMap[post.id.value] ?: emptyList()
                )
            }

            val pagination = PaginationInfo(validPage, validLimit, totalCount)
            createPagedResponse(postResponses, pagination)
        }
    }

    suspend fun getPostDetails(postId: Int): Map<String, Any> {
        return query {
            val post = postRepository.findPostById(postId)
                ?: throw PostNotFoundException(postId)

            val author = userRepository.findUserById(post.userId)?.toSummaryResponse()
                ?: throw UserNotFoundException(Errors.Social.Post.AUTHOR_NOT_FOUND)

            val mediaItems = batchLoadPostMediaItems(listOf(post.id.value))[post.id.value] ?: emptyList()
            val postResponse = post.toPostResponse(author = author, isLiked = false, mediaItems = mediaItems)

            mapOf(
                "post" to postResponse,
                "mediaStats" to getMediaStats(post),
                "hashtagCount" to extractHashtags(post.content).size,
                "isActive" to post.isActive
            )
        }
    }

    private fun getMediaStats(post: PostDao): Map<String, Int> {
        val mediaItems = PostMediaDao.find { PostMediaTable.postId eq post.id.value }.toList()
        val imageCount = mediaItems.count { it.type == SocialMediaType.IMAGE }
        val videoCount = mediaItems.count { it.type == SocialMediaType.VIDEO }

        return mapOf(
            "totalCount" to mediaItems.size,
            "imageCount" to imageCount,
            "videoCount" to videoCount,
            "unknownCount" to 0
        )
    }

    // 접근 판정
    suspend fun checkPostAccess(userId: Int, post: PostDao): Boolean {
        // 잠금 글이고 본인/크리에이터 아닐 때만 구독 조회 (판정은 TierAccessEvaluator)
        val viewerTier = if (
            !post.isSecret &&
            post.requiredTier != SubscriptionPlanTier.FREE &&
            userId != post.userId &&
            userId != post.creatorId
        ) {
            subscriptionRepository.findActiveSubscription(userId, post.creatorId ?: post.userId)
                ?.let { planRepository.findPlanById(it.planId)?.tier }
        } else null

        return TierAccessEvaluator.canAccess(
            viewerId = userId,
            ownerId = post.userId,
            creatorId = post.creatorId,
            requiredTier = post.requiredTier,
            isSecret = post.isSecret,
            viewerTier = viewerTier
        )
    }

    suspend fun checkMultiplePostAccess(userId: Int, posts: List<PostDao>): Map<Int, Boolean> {
        val creatorIds = posts.mapNotNull { it.creatorId }.distinct()

        // 판정은 TierAccessEvaluator
        val subscriptions = if (creatorIds.isEmpty()) emptyList()
            else subscriptionRepository.findActiveSubscriptionsByCreators(userId, creatorIds)
        val subscriptionMap = subscriptions.associateBy { it.creatorId }
        val planMap = if (subscriptions.isEmpty()) emptyMap()
            else planRepository.findPlansByIds(subscriptions.map { it.planId }.distinct()).associateBy { it.id.value }

        return posts.associate { post ->
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

    // 공통 보조
    private fun createPostMediaItem(
        postId: Int,
        fileBytes: ByteArray,
        mediaType: SocialMediaType,
        url: String,
        sortOrder: Int,
        videoMeta: VideoProcessingService.VideoMetadata? = null,
        thumbnailUrl: String? = null,
        previewUrl: String? = null
    ): PostMediaDao {
        val dimensions = if (mediaType == SocialMediaType.IMAGE) {
            imageProcessingService.extractImageDimensions(fileBytes)
        } else {
            null
        }

        return PostMediaDao.new {
            this.postId = postId
            this.type = mediaType
            this.url = url
            this.width = videoMeta?.width ?: dimensions?.first
            this.height = videoMeta?.height ?: dimensions?.second
            this.durationMs = videoMeta?.durationMs
            this.thumbnailUrl = thumbnailUrl
            this.previewUrl = previewUrl
            this.sortOrder = sortOrder
        }
    }

    /** 업로드 미디어 파생물 */
    private data class MediaDerivatives(
        val videoMeta: VideoProcessingService.VideoMetadata?,
        val thumbnailUrl: String?,
        val previewUrl: String?
    )

    private data class PostStatusMaps(
        val likeStatusMap: Map<Int, Boolean>,
        val bookmarkStatusMap: Map<Int, Boolean>,
        val accessMap: Map<Int, Boolean>,
        val mediaItemsMap: Map<Int, List<MediaItemResponse>>,
        val followStatusMap: Map<Int, Boolean>
    )

    private suspend fun buildPostStatusMaps(
        currentUserId: Int?,
        posts: List<PostDao>,
        postIds: List<Int> = posts.map { it.id.value }
    ): PostStatusMaps {
        val likeStatusMap = if (currentUserId != null) {
            likeRepository.checkMultiplePostLikes(currentUserId, postIds)
        } else {
            emptyMap()
        }
        val followStatusMap = if (currentUserId != null) {
            followRepository.checkMultipleFollowStatus(currentUserId, posts.map { it.userId }.distinct())
        } else {
            emptyMap()
        }
        val bookmarkStatusMap = if (currentUserId != null) {
            bookmarkRepository.checkMultipleBookmarks(currentUserId, BookmarkTargetType.POST, postIds)
        } else {
            emptyMap()
        }
        val accessMap = if (currentUserId != null) {
            checkMultiplePostAccess(currentUserId, posts)
        } else {
            posts.associate { it.id.value to (it.requiredTier == SubscriptionPlanTier.FREE && !it.isSecret) }
        }
        val mediaItemsMap = batchLoadPostMediaItems(postIds)

        return PostStatusMaps(likeStatusMap, bookmarkStatusMap, accessMap, mediaItemsMap, followStatusMap)
    }

    private suspend fun verifyPostOwnership(
        postId: Int,
        userId: Int,
        errorMessage: String
    ) = postRepository.findPostById(postId)
        ?.also { post ->
            if (post.userId != userId) {
                throw PermissionDeniedException(errorMessage)
            }
        }
        ?: throw PostNotFoundException(postId)

    private suspend fun invalidatePostCaches(postId: Int, userId: Int) {
        cacheService.deletePattern(CacheKeys.Patterns.post(postId))
        cacheService.deletePattern(CacheKeys.Patterns.EXPLORE_FEED)
        cacheService.deletePattern(CacheKeys.Patterns.TRENDING_FEED)
        cacheService.deletePattern(CacheKeys.Patterns.PINNED_POSTS)
        cacheService.deletePattern(CacheKeys.Patterns.CREATOR_PROFILE_SECTIONS)
        cacheService.delete(CacheKeys.feedStats(userId))
    }

    private suspend fun rollbackUploadedFiles(uploadedUrls: List<String>) {
        if (uploadedUrls.isEmpty()) return

        uploadedUrls.forEach { url ->
            try {
                fileUploadService.deleteFileIfSupabase(url)
            } catch (e: Exception) {
                logger.warn("파일 롤백 실패: {} - {}", url, e.message)
            }
        }
    }
}
