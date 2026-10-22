package com.ninezero.features.social.domain

import com.ninezero.core.cache.CacheKeys
import com.ninezero.core.cache.CacheService
import com.ninezero.core.common.config.BookmarkTargetType
import com.ninezero.core.common.config.CommunityPostSortType
import com.ninezero.core.common.config.Constants
import com.ninezero.core.common.config.LikeType
import com.ninezero.core.common.config.MediaDraftTargetType
import com.ninezero.core.common.config.PostContextType
import com.ninezero.core.common.config.PostType
import com.ninezero.core.common.config.UserRole
import com.ninezero.core.common.exception.CreatorOnlyException
import com.ninezero.core.common.exception.Errors
import com.ninezero.core.common.exception.ForbiddenException
import com.ninezero.core.common.exception.InvalidInputException
import com.ninezero.core.common.exception.NotFoundException
import com.ninezero.core.common.exception.PostDeleteFailedException
import com.ninezero.core.common.exception.PostNotFoundException
import com.ninezero.core.common.exception.PostPinLimitExceededException
import com.ninezero.core.common.exception.PostUpdateFailedException
import com.ninezero.core.common.exception.UserNotFoundException
import com.ninezero.core.common.util.*
import com.ninezero.core.database.entities.social.PostMediaDao
import com.ninezero.core.database.entities.social.PostMediaTable
import com.ninezero.core.storage.FileUploadService
import com.ninezero.core.storage.VideoProcessingService
import com.ninezero.features.social.batchLoadPostMediaItems
import com.ninezero.features.social.communityCreatorId
import com.ninezero.features.social.data.BookmarkRepository
import com.ninezero.features.social.data.FollowRepository
import com.ninezero.features.social.data.LikeRepository
import com.ninezero.features.social.data.PostRepository
import com.ninezero.features.social.extractHashtags
import com.ninezero.features.social.invalidatePostCaches
import com.ninezero.features.social.presentation.models.request.CommunityPostRequest
import com.ninezero.features.social.presentation.models.response.CommunityResponse
import com.ninezero.features.social.presentation.models.response.PinToggleResponse
import com.ninezero.features.social.presentation.models.response.PostResponse
import com.ninezero.features.social.toMediaItemResponse
import com.ninezero.features.social.toPostResponse
import com.ninezero.features.upload.domain.MediaDraftService
import com.ninezero.features.user.data.BlockedUserRepository
import com.ninezero.features.user.data.UserRepository
import com.ninezero.features.user.toUserSummaryResponse
import kotlinx.coroutines.CoroutineScope
import kotlin.time.Duration.Companion.minutes

class CommunityService(
    private val postRepository: PostRepository,
    private val userRepository: UserRepository,
    private val likeRepository: LikeRepository,
    private val bookmarkRepository: BookmarkRepository,
    private val followRepository: FollowRepository,
    private val blockedUserRepository: BlockedUserRepository,
    private val postService: PostService,
    private val postMediaService: PostMediaService,
    private val mediaDraftService: MediaDraftService,
    private val fileUploadService: FileUploadService,
    private val videoProcessingService: VideoProcessingService,
    private val cacheService: CacheService,
    private val coroutineScope: CoroutineScope
) {
    // 커뮤니티 글 생성/수정/삭제
    suspend fun createCommunityPost(
        userId: Int,
        creatorId: Int,
        request: CommunityPostRequest
    ): PostResponse {
        val content = request.content.trim()
        ValidationUtils.validatePostContent(content)
        ValidationUtils.validateClientRequestId(request.clientRequestId)
        val extractedTags = extractHashtags(content)

        // 같은 요청이 반복되면 만든 글을 그대로
        request.clientRequestId?.let { clientRequestId ->
            query { postRepository.findPostByClientRequestId(userId, clientRequestId) }
                ?.let { return postService.getPostById(it.id.value, userId) }
        }

        val (response, media) = query {
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
                tags = extractedTags,
                isSecret = request.isSecret,
                clientRequestId = request.clientRequestId
            )

            // 초안을 미디어 행으로 옮기고 초안은 지움
            val media = mediaDraftService.claimInTransaction(userId, MediaDraftTargetType.POST, request.mediaDraftIds)
                .map { it.toPreparedMedia() }
            val mediaItems = media.mapIndexed { index, item ->
                createPostMediaItem(post.id.value, item, sortOrder = index).toMediaItemResponse()
            }

            post.toPostResponse(author = user.toUserSummaryResponse(), mediaItems = mediaItems) to media
        }

        launchPostVideoTranscode(coroutineScope, videoProcessingService, fileUploadService, media)
        cacheService.invalidatePostCaches(response.id, userId, communityCreatorId = creatorId)

        return response
    }

    suspend fun updateCommunityPost(
        postId: Int,
        userId: Int,
        request: CommunityPostRequest
    ): PostResponse {
        val content = request.content.trim()
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
                tags = extractedTags
            ) ?: throw PostUpdateFailedException(Errors.Social.Post.POST_UPDATE_FAILED)

            val author = userRepository.findUserById(updatedPost.userId)?.toUserSummaryResponse()
                ?: throw UserNotFoundException(Errors.Social.Post.AUTHOR_NOT_FOUND)

            val isLiked = likeRepository.isLiked(userId, LikeType.POST, updatedPost.id.value)
            val isBookmarked = bookmarkRepository.isBookmarked(userId, BookmarkTargetType.POST, updatedPost.id.value)
            val mediaItems = batchLoadPostMediaItems(listOf(updatedPost.id.value))[updatedPost.id.value] ?: emptyList()

            val postResponse = updatedPost.toPostResponse(author = author, isLiked = isLiked, isBookmarked = isBookmarked, mediaItems = mediaItems)
            postResponse to post.communityCreatorId
        }

        cacheService.invalidatePostCaches(postId, userId, communityCreatorId = targetCreatorId)

        return response
    }

    suspend fun deleteCommunityPost(postId: Int, userId: Int) {
        val (targetCreatorId, authorId, mediaAttachments) = query {
            // 작성자에게 보이는 블라인드 글도 삭제 대상
            val post = postRepository.findPostById(postId)
                ?: postRepository.findPostByIdWithDeleted(postId)?.takeIf { it.isBlinded }
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
            val medias = PostMediaDao.find { PostMediaTable.postId eq postId }.toList()
            val urls = medias.flatMap { listOfNotNull(it.url, it.thumbnailUrl, it.previewUrl) }
            medias.forEach { it.delete() }

            Triple(post.communityCreatorId, post.userId, urls)
        }

        fileUploadService.deleteByUrls(mediaAttachments)

        cacheService.invalidatePostCaches(postId, authorId, communityCreatorId = targetCreatorId)  // 크리에이터가 지워도 글 수는 작성자 프로필
    }

    // 커뮤니티 글 조회
    suspend fun getCommunityPosts(
        creatorId: Int,
        currentUserId: Int?,
        cursor: String? = null,
        limit: Int = Constants.DEFAULT_PAGE_LIMIT,
        sort: CommunityPostSortType = CommunityPostSortType.LATEST
    ): CommunityResponse {
        val validLimit = validateCursorLimit(limit)
        val pageCursor = PageCursor.decode(cursor)

        val cacheKey = CacheKeys.community(creatorId, pageCursor?.encode().orEmpty(), validLimit, sort.name, currentUserId)
        cacheService.getJson<CommunityResponse>(cacheKey)?.let {
            return it
        }

        val response = query {
            // 고정 공지는 첫 페이지에만
            val pinnedPosts = if (pageCursor == null) {
                postRepository.findPinnedPostsInCommunity(
                    creatorId = creatorId,
                    limit = Constants.Social.MAX_PINNED_COMMUNITY_POSTS
                )
            } else {
                emptyList()
            }

            val blockedUserIds = if (currentUserId != null) {
                blockedUserRepository.findBlockRelatedUserIds(currentUserId)
            } else {
                emptySet()
            }

            // 차단 글은 쿼리에서 빼 페이지 채움, 관심없음은 피드에서만
            val (communityPosts, nextCursor) = postRepository.findCommunityPosts(
                creatorId = creatorId,
                cursor = pageCursor,
                limit = validLimit + 1,
                excludedAuthorIds = blockedUserIds,
                sort = sort
            ).toCursorPage(validLimit) { post ->
                when (sort) {
                    CommunityPostSortType.LATEST, CommunityPostSortType.OLDEST -> PageCursor.of(post.createdAt, post.id.value)
                    CommunityPostSortType.POPULAR -> PageCursor.of(post.likeCount, post.id.value)
                    CommunityPostSortType.MOST_VIEWED -> PageCursor.of(post.viewCount, post.id.value)
                }
            }

            val allPosts = pinnedPosts + communityPosts
            val userIds = allPosts.map { it.userId }.distinct()
            val users = userRepository.findUsersByIds(userIds)
            val userMap = users.associateBy { it.id.value }

            val postIds = allPosts.map { it.id.value }
            val likeStatusMap = if (currentUserId != null) {
                likeRepository.checkMultipleLikes(currentUserId, LikeType.POST, postIds)
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
                postService.checkMultiplePostAccess(currentUserId, allPosts)
            } else {
                // 비회원도 비밀글·티어 잠금 판정
                allPosts.associate {
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

            val postMediaMap = batchLoadPostMediaItems(allPosts.map { it.id.value })

            val pinnedPostResponses = pinnedPosts.mapNotNull { post ->
                val author = userMap[post.userId]?.toUserSummaryResponse(isFollowing = followStatusMap[post.userId]) ?: return@mapNotNull null
                val isLiked = likeStatusMap[post.id.value] ?: false
                val isBookmarked = bookmarkStatusMap[post.id.value] ?: false
                val canAccess = accessMap[post.id.value] ?: false
                post.toPostResponse(author = author, isLiked = isLiked, isBookmarked = isBookmarked, canAccess = canAccess, mediaItems = postMediaMap[post.id.value] ?: emptyList())
            }

            val communityPostResponses = communityPosts.mapNotNull { post ->
                val author = userMap[post.userId]?.toUserSummaryResponse(isFollowing = followStatusMap[post.userId]) ?: return@mapNotNull null
                val isLiked = likeStatusMap[post.id.value] ?: false
                val isBookmarked = bookmarkStatusMap[post.id.value] ?: false
                val canAccess = accessMap[post.id.value] ?: false
                post.toPostResponse(author = author, isLiked = isLiked, isBookmarked = isBookmarked, canAccess = canAccess, mediaItems = postMediaMap[post.id.value] ?: emptyList())
            }

            CommunityResponse(
                pinnedPosts = pinnedPostResponses,
                posts = CursorResponse(communityPostResponses, nextCursor, hasNext = nextCursor != null)
            )
        }

        cacheService.setJson(cacheKey, response, ttl = 10.minutes)

        return response
    }

    // 공지 고정
    suspend fun togglePinCommunityPost(postId: Int, creatorId: Int): PinToggleResponse {
        val isPinned = query {
            val user = userRepository.findUserById(creatorId)
            if (user?.role != UserRole.CREATOR && user?.role != UserRole.ADMIN) {
                throw CreatorOnlyException(Errors.Social.Pin.ONLY_CREATOR_CAN_PIN)
            }

            val post = postRepository.findPostById(postId)
                ?: throw PostNotFoundException(Errors.Social.Post.POST_NOT_FOUND)

            // 자기 커뮤니티 글만
            if (post.creatorId != creatorId || post.contextType != PostContextType.COMMUNITY) {
                throw ForbiddenException(Errors.Social.Pin.ONLY_CREATOR_CAN_PIN)
            }

            if (post.isPinnedInCommunity) {
                postRepository.unpinPostInCommunity(postId)
                false
            } else {
                // 비밀글은 공지 고정 불가
                if (post.isSecret) {
                    throw InvalidInputException(Errors.Social.Pin.SECRET_POST_CANNOT_PIN)
                }

                // 최대 고정 개수 제한
                val pinnedCount = postRepository.countPinnedPostsInCommunity(creatorId)
                if (pinnedCount >= Constants.Social.MAX_PINNED_COMMUNITY_POSTS) {
                    throw PostPinLimitExceededException(Errors.Social.Pin.PINNED_COMMUNITY_LIMIT_EXCEEDED)
                }

                postRepository.pinPostInCommunity(postId)
                true
            }
        }

        cacheService.deletePattern(CacheKeys.Patterns.community(creatorId))
        cacheService.deletePattern(CacheKeys.Patterns.post(postId))  // 고정 여부

        return PinToggleResponse(isPinned = isPinned)
    }

    // 커뮤니티 미디어
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
        query {
            val post = postRepository.findPostById(postId)
                ?: throw PostNotFoundException(Errors.Social.Post.POST_NOT_FOUND)

            if (post.userId != userId) {
                throw ForbiddenException(Errors.Social.Post.POST_UPDATE_PERMISSION_DENIED)
            }

            if (post.creatorId != creatorId) {  // 이 커뮤니티 글만
                throw ForbiddenException(Errors.Social.Post.POST_UPDATE_PERMISSION_DENIED)
            }
        }

        val response = postMediaService.updatePostWithMedia(
            postId = postId,
            userId = userId,
            content = content,
            keepMediaIds = keepMediaIds,
            newFiles = newFiles,
            newContentTypes = newContentTypes,
            reorder = reorder
        )

        return response
    }
}
