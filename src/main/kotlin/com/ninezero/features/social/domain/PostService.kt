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
import com.ninezero.core.storage.VideoProcessingService
import com.ninezero.features.notification.domain.NotificationService
import com.ninezero.features.social.*
import com.ninezero.features.social.communityCreatorId
import com.ninezero.features.social.data.BookmarkRepository
import com.ninezero.features.social.data.FollowRepository
import com.ninezero.features.social.data.LikeRepository
import com.ninezero.features.social.data.PostRepository
import com.ninezero.features.social.invalidatePostCaches
import com.ninezero.features.social.presentation.models.request.PostRequest
import com.ninezero.features.social.presentation.models.request.UpdatePostRequest
import com.ninezero.features.social.presentation.models.response.*
import com.ninezero.features.subscription.data.SubscriptionPlanRepository
import com.ninezero.features.subscription.data.SubscriptionRepository
import com.ninezero.features.tag.data.TagRepository
import com.ninezero.features.tag.domain.requireOwnedTags
import com.ninezero.features.tag.toTagResponse
import com.ninezero.features.upload.domain.MediaDraftService
import com.ninezero.features.user.data.BlockedUserRepository
import com.ninezero.features.user.data.UserRepository
import com.ninezero.features.user.presentation.models.response.UserSummaryResponse
import com.ninezero.features.user.toUserSummaryResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.Json
import kotlin.time.Duration.Companion.minutes

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
    private val mediaDraftService: MediaDraftService,
    private val fileUploadService: FileUploadService,
    private val videoProcessingService: VideoProcessingService,
    private val cacheService: CacheService,
    private val coroutineScope: CoroutineScope
) {
    private val logger = logger()

    // 포스트 생성
    suspend fun createPost(userId: Int, request: PostRequest): PostResponse {
        val content = request.content.trim()

        ValidationUtils.validatePostContent(content)
        ValidationUtils.validateClientRequestId(request.clientRequestId)
        if (request.postType == PostType.TEXT && request.mediaDraftIds.isNotEmpty()) {
            throw InvalidInputException(Errors.Social.Media.TEXT_POST_WITH_MEDIA)
        }
        if (request.postType.requiresMedia() && request.mediaDraftIds.isEmpty()) {
            throw InvalidInputException(Errors.Social.Media.MEDIA_REQUIRED)
        }

        val extractedTags = extractHashtags(content)

        request.tagIds?.let { tagIds ->
            if (request.sectionTagId != null && request.sectionTagId !in tagIds) {
                throw InvalidTagException(Errors.Tag.SECTION_TAG_NOT_IN_SELECTED_TAGS)
            }
        }

        // 같은 요청이 반복되면 만든 글을 그대로
        request.clientRequestId?.let { clientRequestId ->
            query { postRepository.findPostByClientRequestId(userId, clientRequestId) }
                ?.let { return getPostById(it.id.value, userId) }
        }

        val (post, response, media) = query {
            val user = userRepository.findUserById(userId)
                ?: throw UserNotFoundException(Errors.User.USER_NOT_FOUND)

            if (request.requiredTier != SubscriptionPlanTier.FREE) {
                if (user.role != UserRole.CREATOR && user.role != UserRole.ADMIN) {
                    throw CreatorOnlyException(Errors.Social.Post.CREATOR_ONLY_CONTENT)
                }
                // 활성 플랜 없는 등급은 게시 차단
                if (user.role == UserRole.CREATOR &&
                    !planRepository.existsActivePlanForTier(userId, request.requiredTier)) {
                    throw ValidationException(Errors.Social.Post.TIER_PLAN_REQUIRED)
                }
            }

            val isCreator = user.role == UserRole.CREATOR || user.role == UserRole.ADMIN
            val contextType = if (isCreator) PostContextType.CREATOR_FEED else PostContextType.GENERAL
            val creatorId = if (isCreator) userId else null

            val createdPost = postRepository.createPost(
                userId = userId,
                content = content,
                postType = request.postType,
                tags = extractedTags,
                productId = request.productId,
                requiredTier = request.requiredTier,
                contextType = contextType,
                creatorId = creatorId,
                clientRequestId = request.clientRequestId
            )

            request.tagIds?.let { tagIds ->
                requireOwnedTags(tagRepository, userId, tagIds, TagTargetType.POST)
                tagRepository.attachTagsToPost(
                    postId = createdPost.id.value,
                    tagIds = tagIds,
                    sectionTagId = request.sectionTagId
                )
            }

            // 초안을 미디어 행으로 옮기고 초안은 지움
            val media = mediaDraftService.claimInTransaction(userId, MediaDraftTargetType.POST, request.mediaDraftIds)
                .map { it.toPreparedMedia() }
            val mediaItems = media.mapIndexed { index, item ->
                createPostMediaItem(createdPost.id.value, item, sortOrder = index).toMediaItemResponse()
            }

            Triple(createdPost, createdPost.toPostResponse(author = user.toUserSummaryResponse(), mediaItems = mediaItems), media)
        }

        launchPostVideoTranscode(coroutineScope, videoProcessingService, fileUploadService, media)
        cacheService.invalidatePostCaches(post.id.value, userId, communityCreatorId = null)

        if (post.contextType == PostContextType.CREATOR_FEED) {
            launchNewPostNotifications(coroutineScope, followRepository, notificationService, userId, response.author.username, post.id.value)
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
                    val inactive = postRepository.findPostByIdWithDeleted(postId)

                    // 블라인드 글은 작성자 본인에게만 노출
                    if (inactive != null && inactive.isBlinded && currentUserId == inactive.userId) {
                        inactive
                    } else {
                        // HIDDEN 글은 삭제와 구분해 404
                        if (inactive != null && inactive.status == PostStatus.HIDDEN) {
                            throw NotFoundException(Errors.Social.Post.POST_UNAVAILABLE)
                        }
                        throw PostNotFoundException(Errors.Social.Post.POST_NOT_FOUND)
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
                TierAccessEvaluator.canAccess(
                    viewerId = null,
                    ownerId = post.userId,
                    creatorId = post.creatorId,
                    requiredTier = post.requiredTier,
                    isSecret = post.isSecret,
                    viewerTier = null
                )
            }

            // 접근 가능할 때만 조회수 증가
            if (canAccess) {
                postRepository.incrementViewCount(postId)
            }

            val author = userRepository.findUserById(post.userId)?.toUserSummaryResponse(
                isFollowing = if (currentUserId != null && currentUserId != post.userId)
                    followRepository.isFollowing(currentUserId, post.userId) else null
            ) ?: throw UserNotFoundException(Errors.Social.Post.AUTHOR_NOT_FOUND)

            val isLiked = currentUserId?.let { likeRepository.isLiked(it, LikeType.POST, post.id.value) } ?: false
            val isBookmarked = currentUserId?.let { bookmarkRepository.isBookmarked(it, BookmarkTargetType.POST, post.id.value) } ?: false
            val mediaItems = batchLoadPostMediaItems(listOf(post.id.value))[post.id.value] ?: emptyList()
            post.toPostResponse(author = author, isLiked = isLiked, isBookmarked = isBookmarked, canAccess = canAccess, mediaItems = mediaItems)
        }

        cacheService.setJson(cacheKey, response, ttl = 5.minutes)

        return response
    }

    // 포스트 수정
    suspend fun updatePost(postId: Int, userId: Int, request: UpdatePostRequest): PostResponse {
        val content = request.content.trim()

        ValidationUtils.validatePostContent(content)

        val extractedTags = extractHashtags(content)

        request.tagIds?.let { tagIds ->
            if (request.sectionTagId != null && request.sectionTagId !in tagIds) {
                throw InvalidTagException(Errors.Tag.SECTION_TAG_NOT_IN_SELECTED_TAGS)
            }
        }

        val (response, communityCreatorId) = query {
            val existingPost = requireOwnedPost(postRepository,   // 소유자만
                postId = postId,
                userId = userId,
                errorMessage = Errors.Social.Post.POST_UPDATE_PERMISSION_DENIED
            )

            existingPost.content = content
            existingPost.tags = if (extractedTags.isNotEmpty()) {
                Json.encodeToString(extractedTags)
            } else null

            request.tagIds?.let { tagIds ->
                requireOwnedTags(tagRepository, userId, tagIds, TagTargetType.POST)
                tagRepository.attachTagsToPost(
                    postId = postId,
                    tagIds = tagIds,
                    sectionTagId = request.sectionTagId
                )
            }

            val author = userRepository.findUserById(userId)?.toUserSummaryResponse()
                ?: throw UserNotFoundException(Errors.User.USER_NOT_FOUND)
            val isLiked = likeRepository.isLiked(userId, LikeType.POST, existingPost.id.value)
            val isBookmarked = bookmarkRepository.isBookmarked(userId, BookmarkTargetType.POST, existingPost.id.value)
            val mediaItems = batchLoadPostMediaItems(listOf(existingPost.id.value))[existingPost.id.value] ?: emptyList()

            val postResponse = existingPost.toPostResponse(author = author, isLiked = isLiked, isBookmarked = isBookmarked, mediaItems = mediaItems)
            postResponse to existingPost.communityCreatorId
        }

        cacheService.invalidatePostCaches(postId, userId, communityCreatorId)

        return response
    }

    // 포스트 삭제
    /** 포스트 삭제와 파일 회수 */
    suspend fun deletePost(postId: Int, userId: Int) {
        val (mediaAttachments, communityCreatorId) = query {
            // 작성자에게 보이는 블라인드 글도 삭제 대상
            val post = postRepository.findPostById(postId)
                ?: postRepository.findPostByIdWithDeleted(postId)?.takeIf { it.isBlinded }
                ?: throw PostNotFoundException(Errors.Social.Post.POST_NOT_FOUND)
            if (post.userId != userId) {  // 소유자만
                throw ForbiddenException(Errors.Social.Post.POST_DELETE_PERMISSION_DENIED)
            }

            // 원본 + 썸네일 + 프리뷰 수거
            val medias = PostMediaDao.find { PostMediaTable.postId eq postId }.toList()
            val urls = medias.flatMap { listOfNotNull(it.url, it.thumbnailUrl, it.previewUrl) }
            medias.forEach { it.delete() }
            postRepository.deletePost(postId)
            urls to post.communityCreatorId
        }

        fileUploadService.deleteByUrls(mediaAttachments)

        cacheService.invalidatePostCaches(postId, userId, communityCreatorId)

        // 지운 글로 가는 새 글 알림 정리
        try {
            notificationService.deactivateNewPostNotifications(postId)
        } catch (e: Exception) {
            logger.warn("새 글 알림 정리 실패: postId={}, error={}", postId, e.message)
        }
    }

    // 피드 고정
    suspend fun togglePinPostInFeed(postId: Int, creatorId: Int): PinToggleResponse {
        val isPinned = query {
            val user = userRepository.findUserById(creatorId)
            if (user?.role != UserRole.CREATOR && user?.role != UserRole.ADMIN) {
                throw CreatorOnlyException(Errors.Social.Pin.ONLY_CREATOR_CAN_PIN)
            }

            val post = postRepository.findPostById(postId)
                ?: throw PostNotFoundException(Errors.Social.Post.POST_NOT_FOUND)

            // 크리에이터 피드의 본인 글만
            if (post.creatorId != creatorId || post.contextType != PostContextType.CREATOR_FEED) {
                throw ForbiddenException(Errors.Social.Pin.ONLY_CREATOR_CAN_PIN)
            }

            if (post.isPinnedInFeed) {
                postRepository.unpinPostInFeed(postId)
                false
            } else {
                val pinnedCount = postRepository.countPinnedPostsInFeed(creatorId)
                if (pinnedCount >= Constants.Social.MAX_PINNED_POSTS) {  // 최대 고정 개수 제한
                    throw PostPinLimitExceededException(Errors.Social.Pin.PINNED_POST_LIMIT_EXCEEDED)
                }

                postRepository.pinPostInFeed(postId)
                true
            }
        }

        cacheService.deletePattern(CacheKeys.Patterns.post(postId))  // 고정 여부
        cacheService.deletePattern(CacheKeys.Patterns.PINNED_POSTS)
        cacheService.deletePattern(CacheKeys.Patterns.CREATOR_PROFILE_SECTIONS)

        return PinToggleResponse(isPinned = isPinned)
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
            val statusMaps = buildPostStatusMaps(currentUserId, posts)

            val postResponses = posts.mapNotNull { post ->
                val author = userMap[post.userId]
                    ?.toUserSummaryResponse(isFollowing = statusMaps.followStatusMap[post.userId])
                    ?: return@mapNotNull null
                statusMaps.toResponse(post, author)
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
                val postIds = tagRepository.findSectionPostIds(
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
                page = validPage,
                limit = validLimit
            )

            val allPostsList = pinnedPosts + topTagSections.flatMap { (_, posts, _) -> posts } + allPosts

            val userIds = allPostsList.map { it.userId }.distinct()
            val users = userRepository.findUsersByIds(userIds)
            val userMap = users.associateBy { it.id.value }

            val allPostIds = allPostsList.map { it.id.value }
            val statusMaps = buildPostStatusMaps(currentUserId, allPostsList)

            val pinnedPostResponses = pinnedPosts.mapNotNull { post ->
                val author = userMap[post.userId]
                    ?.toUserSummaryResponse(isFollowing = statusMaps.followStatusMap[post.userId])
                    ?: return@mapNotNull null
                statusMaps.toResponse(post, author)
            }

            val tagSectionResponses = topTagSections.map { (tag, posts, _) ->
                val postResponses = posts.mapNotNull { post ->
                    val author = userMap[post.userId]
                        ?.toUserSummaryResponse(isFollowing = statusMaps.followStatusMap[post.userId])
                        ?: return@mapNotNull null
                    statusMaps.toResponse(post, author)
                }

                val postCount = tagRepository.countSectionPosts(tag.id.value)
                val hasMore = postCount > Constants.Social.MAX_ITEMS_PER_SECTION

                TagSectionResponse(
                    tag = tag.toTagResponse(count = postCount),
                    posts = postResponses,
                    hasMore = hasMore
                )
            }

            val allPostResponses = allPosts.mapNotNull { post ->
                val author = userMap[post.userId]
                    ?.toUserSummaryResponse(isFollowing = statusMaps.followStatusMap[post.userId])
                    ?: return@mapNotNull null
                statusMaps.toResponse(post, author)
            }

            // 목록과 같은 조건으로 개수 집계
            val totalCount = postRepository.countPostsByCreatorAndContext(creatorId, PostContextType.CREATOR_FEED)

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
        cursor: String? = null,
        limit: Int = Constants.DEFAULT_PAGE_LIMIT,
        sort: UserPostSortType = UserPostSortType.LATEST,
        contextType: PostContextType? = null
    ): CursorResponse<PostResponse> {
        val validLimit = validateCursorLimit(limit)
        val pageCursor = PageCursor.decode(cursor)

        return query {
            // 블라인드 글은 작성자 본인 프로필에서만 노출
            val isOwnProfile = currentUserId != null && currentUserId == targetUserId
            val (posts, nextCursor) = postRepository.findPostsByUserId(
                targetUserId, pageCursor, validLimit + 1, sort, contextType, isOwnProfile
            ).toCursorPage(validLimit) { PageCursor.of(it.createdAt, it.id.value) }

            val author = userRepository.findUserById(targetUserId)?.toUserSummaryResponse(
                isFollowing = if (currentUserId != null && currentUserId != targetUserId)
                    followRepository.isFollowing(currentUserId, targetUserId) else null
            ) ?: throw UserNotFoundException(Errors.User.USER_INFO_NOT_FOUND)

            val postIds = posts.map { it.id.value }
            val statusMaps = buildPostStatusMaps(currentUserId, posts)

            val postResponses = posts.map { post -> statusMaps.toResponse(post, author) }

            CursorResponse(postResponses, nextCursor, hasNext = nextCursor != null)
        }
    }

    suspend fun getPostsByTag(
        tagId: Int,
        currentUserId: Int?,
        page: Int,
        limit: Int = Constants.DEFAULT_PAGE_LIMIT
    ): PostListResponse {
        val (validPage, validLimit) = validatePaginationParams(page, limit)

        return query {
            tagRepository.findTagById(tagId)
                ?: throw TagNotFoundException(Errors.Tag.TAG_NOT_FOUND)

            val postIds = tagRepository.findSectionPostIds(tagId = tagId, page = validPage, limit = validLimit)
            val totalCount = tagRepository.countSectionPosts(tagId)

            // 차단 유저 글은 태그 목록에서도 제외
            val blockedIds = if (currentUserId != null) blockedUserRepository.findBlockRelatedUserIds(currentUserId) else emptySet()
            val posts = if (postIds.isNotEmpty()) {
                postRepository.findPostsByIds(postIds).filter { it.userId !in blockedIds }
            } else {
                emptyList()
            }

            val userIds = posts.map { it.userId }.distinct()
            val users = userRepository.findUsersByIds(userIds)
            val userMap = users.associateBy { it.id.value }

            val statusMaps = buildPostStatusMaps(currentUserId, posts)

            val postResponses = posts.mapNotNull { post ->
                val author = userMap[post.userId]
                    ?.toUserSummaryResponse(isFollowing = statusMaps.followStatusMap[post.userId])
                    ?: return@mapNotNull null
                statusMaps.toResponse(post, author)
            }

            val pagination = PaginationInfo(validPage, validLimit, totalCount)
            createPagedResponse(postResponses, pagination)
        }
    }

    // 관리자 조회
    suspend fun getAdminPosts(page: Int, limit: Int = Constants.DEFAULT_PAGE_LIMIT): PaginatedResponse<AdminPostResponse> {
        val (validPage, validLimit) = validatePaginationParams(page, limit)

        return query {
            val totalCount = postRepository.countAllPosts()
            val posts = postRepository.findAllPosts(validPage, validLimit)

            val userIds = posts.map { it.userId }.distinct()
            val users = userRepository.findUsersByIds(userIds)
            val userMap = users.associateBy { it.id.value }

            val postIds = posts.map { it.id.value }
            val mediaItemsMap = batchLoadPostMediaItems(postIds)

            val postResponses = posts.map { post ->
                val author = userMap[post.userId]?.toUserSummaryResponse()
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

    // 접근 판정
    suspend fun checkPostAccess(userId: Int, post: PostDao): Boolean {
        // 잠금 글이고 본인·크리에이터 아닐 때만 구독 조회
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

    /** 비밀글을 비참여자에게 숨길지 판정 */
    suspend fun isSecretPostHiddenFrom(postId: Int, currentUserId: Int?): Boolean {
        val post = postRepository.findPostByIdWithDeleted(postId)  // 비활성 글도 비밀글 판정
        if (post == null || !post.isSecret) return false  // 티어 잠금 글 댓글은 공개
        return currentUserId == null || !checkPostAccess(currentUserId, post)  // 참여자만 열람
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
    private data class PostStatusMaps(
        val likeStatusMap: Map<Int, Boolean>,
        val bookmarkStatusMap: Map<Int, Boolean>,
        val accessMap: Map<Int, Boolean>,
        val mediaItemsMap: Map<Int, List<MediaItemResponse>>,
        val followStatusMap: Map<Int, Boolean>
    ) {
        fun toResponse(post: PostDao, author: UserSummaryResponse): PostResponse = post.toPostResponse(
            author = author,
            isLiked = likeStatusMap[post.id.value] ?: false,
            isBookmarked = bookmarkStatusMap[post.id.value] ?: false,
            canAccess = accessMap[post.id.value] ?: false,
            mediaItems = mediaItemsMap[post.id.value] ?: emptyList()
        )
    }

    private suspend fun buildPostStatusMaps(currentUserId: Int?, posts: List<PostDao>): PostStatusMaps {
        val postIds = posts.map { it.id.value }
        val likeStatusMap = if (currentUserId != null) {
            likeRepository.checkMultipleLikes(currentUserId, LikeType.POST, postIds)
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
            posts.associate {
                it.id.value to TierAccessEvaluator.canAccess(
                    viewerId = null,
                    ownerId = it.userId,
                    creatorId = it.creatorId,
                    requiredTier = it.requiredTier,
                    isSecret = it.isSecret,
                    viewerTier = null
                )
            }
        }
        val mediaItemsMap = batchLoadPostMediaItems(postIds)

        return PostStatusMaps(likeStatusMap, bookmarkStatusMap, accessMap, mediaItemsMap, followStatusMap)
    }
}
