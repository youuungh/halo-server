package com.ninezero.features.social

import com.ninezero.core.cache.CacheKeys
import com.ninezero.core.cache.CacheService
import com.ninezero.core.common.config.Constants
import com.ninezero.core.common.config.FeedType
import com.ninezero.core.common.config.PostContextType
import com.ninezero.core.common.exception.Errors
import com.ninezero.core.common.exception.InvalidInputException
import com.ninezero.core.common.util.decodeJsonToList
import com.ninezero.core.database.entities.base.BaseIntEntity
import com.ninezero.core.database.entities.social.*
import com.ninezero.features.social.presentation.models.response.*
import com.ninezero.features.user.presentation.models.response.UserPreviewResponse
import com.ninezero.features.user.presentation.models.response.UserSummaryResponse
import org.jetbrains.exposed.sql.SortOrder

/** 미디어 편집 keep/reorder/삭제 계획 */
data class MediaEditPlan<T : BaseIntEntity>(
    val orderedKeepIds: List<Int>,
    val deleteItems: List<T>
)

fun <T : BaseIntEntity> resolveMediaEditPlan(
    currentItems: List<T>,
    keepMediaIds: List<Int>?,
    reorder: List<Int>?,
    newFilesCount: Int,
    sortOrderOf: (T) -> Int
): MediaEditPlan<T> {
    val keepSet = (keepMediaIds ?: emptyList()).toSet()
    val keepItems = currentItems.filter { it.id.value in keepSet }
    if (keepSet.size != keepItems.size) {
        throw InvalidInputException(Errors.Social.Media.INVALID_KEEP_MEDIA_IDS)
    }

    val orderedKeep = if (!reorder.isNullOrEmpty()) {
        if (reorder.size != keepItems.size || reorder.toSet() != keepSet) {
            throw InvalidInputException(Errors.Social.Media.REORDER_SET_MISMATCH)
        }
        val keepMap = keepItems.associateBy { it.id.value }
        reorder.map { id ->
            keepMap[id] ?: throw InvalidInputException(Errors.Social.Media.INVALID_REORDER_MEDIA_ID)
        }
    } else {
        keepItems.sortedBy(sortOrderOf)
    }

    if (orderedKeep.size + newFilesCount > Constants.Social.MAX_MEDIA_ATTACHMENTS_PER_POST) {
        throw InvalidInputException(Errors.Social.Media.ATTACHMENT_LIMIT_EXCEEDED)
    }

    val deleteItems = currentItems.filter { it.id.value !in keepSet }
    return MediaEditPlan(orderedKeep.map { it.id.value }, deleteItems)
}

fun PostDao.toPostResponse(
    author: UserSummaryResponse,
    isLiked: Boolean = false,
    isBookmarked: Boolean = false,
    canAccess: Boolean = true,
    mediaItems: List<MediaItemResponse>? = null
): PostResponse {
    val locked = !canAccess  // 접근 권한 없으면 본문·미디어·태그 제거
    return PostResponse(
        id = this.id.value,
        author = author,
        content = if (locked) "" else this.content,
        postType = postType,
        contextType = contextType,
        status = status,
        mediaItems = if (locked) {
            // 원본 대신 블러 프리뷰만 반환
            (mediaItems ?: emptyList()).mapNotNull { m -> m.previewUrl?.let { p -> m.copy(url = p, thumbnailUrl = p) } }
        } else (mediaItems ?: emptyList()),
        tags = if (locked) emptyList() else (tags?.decodeJsonToList() ?: extractHashtags(this.content)),
        likeCount = likeCount,
        commentCount = commentCount,
        viewCount = viewCount,
        shareCount = shareCount,
        isLiked = isLiked,
        isBookmarked = isBookmarked,
        isPinned = when (contextType) {
            PostContextType.CREATOR_FEED -> isPinnedInFeed
            PostContextType.COMMUNITY -> isPinnedInCommunity
            else -> false
        },
        productInfo = null,  // TODO: 포스트 상품 연동
        requiredTier = requiredTier,
        isSecret = isSecret,
        canAccess = canAccess,
        isBlinded = isBlinded,
        createdAt = createdAt,
        updatedAt = updatedAt
    )
}

fun PostDao.toAdminPostResponse(
    author: UserSummaryResponse,
    mediaItems: List<MediaItemResponse>? = null
): AdminPostResponse {
    return AdminPostResponse(
        id = this.id.value,
        author = author,
        content = this.content,
        postType = postType,
        contextType = contextType,
        status = status,
        mediaItems = mediaItems ?: emptyList(),
        tags = tags?.decodeJsonToList() ?: extractHashtags(this.content),
        likeCount = likeCount,
        commentCount = commentCount,
        viewCount = viewCount,
        shareCount = shareCount,
        requiredTier = requiredTier,
        isActive = isActive,
        isSecret = isSecret,
        isPinnedInFeed = isPinnedInFeed,
        isPinnedInCommunity = isPinnedInCommunity,
        createdAt = createdAt,
        updatedAt = updatedAt
    )
}

fun CommentDao.toCommentResponse(
    author: UserSummaryResponse,
    currentUserId: Int? = null,
    isLiked: Boolean = false,
    isBookmarked: Boolean = false,
    isBlocked: Boolean = false,
    firstReply: CommentResponse? = null,
    hasMoreReplies: Boolean = false,
    moreReplyCount: Int = 0,
    replyPreviewUsers: List<UserPreviewResponse> = emptyList(),
    mediaItems: List<MediaItemResponse>? = null
): CommentResponse {
    val isPlaceholder = isDeleted && !(isBlinded && userId == currentUserId)
    return CommentResponse(
        id = this.id.value,
        postId = postId,
        author = author,
        content = if (isBlocked || isPlaceholder) "" else this.content,
        mediaItems = if (isBlocked || isPlaceholder) emptyList() else (mediaItems ?: emptyList()),
        parentCommentId = parentCommentId,
        likeCount = likeCount,
        replyCount = replyCount,
        isLiked = isLiked,
        isBookmarked = isBookmarked,
        isBlocked = isBlocked,
        isBlinded = isBlinded,
        isDeleted = isPlaceholder,
        firstReply = firstReply,
        hasMoreReplies = hasMoreReplies,
        moreReplyCount = moreReplyCount,
        replyPreviewUsers = replyPreviewUsers,
        createdAt = createdAt,
        updatedAt = updatedAt
    )
}

fun PostMediaDao.toMediaItemResponse(): MediaItemResponse {
    return MediaItemResponse(
        id = this.id.value,
        type = type,
        url = url,
        width = width,
        height = height,
        durationMs = durationMs,
        thumbnailUrl = thumbnailUrl,
        previewUrl = previewUrl,
        sortOrder = sortOrder
    )
}

fun CommentMediaDao.toMediaItemResponse(): MediaItemResponse {
    return MediaItemResponse(
        id = this.id.value,
        type = type,
        url = url,
        width = width,
        height = height,
        durationMs = durationMs,
        thumbnailUrl = thumbnailUrl,
        sortOrder = sortOrder
    )
}

fun batchLoadPostMediaItems(postIds: List<Int>): Map<Int, List<MediaItemResponse>> {
    if (postIds.isEmpty()) return emptyMap()
    return PostMediaDao.find { PostMediaTable.postId inList postIds }
        .orderBy(PostMediaTable.sortOrder to SortOrder.ASC)
        .groupBy { it.postId }
        .mapValues { (_, medias) -> medias.map { it.toMediaItemResponse() } }
}

fun batchLoadCommentMediaItems(commentIds: List<Int>): Map<Int, List<MediaItemResponse>> {
    if (commentIds.isEmpty()) return emptyMap()
    return CommentMediaDao.find { CommentMediaTable.commentId inList commentIds }
        .orderBy(CommentMediaTable.sortOrder to SortOrder.ASC)
        .groupBy { it.commentId }
        .mapValues { (_, medias) -> medias.map { it.toMediaItemResponse() } }
}

fun createFeedResponse(
    posts: List<PostResponse>,
    feedType: FeedType,
    hasNext: Boolean,
    page: Int
): FeedResponse {
    return FeedResponse(
        posts = posts,
        totalCount = posts.size,
        page = page,
        totalPages = 1,
        hasNext = hasNext,
        hasPrevious = page > 1,
        feedType = feedType
    )
}

private val HASHTAG_REGEX = "#[\\w가-힣]+".toRegex()
private val HASHTAG_BODY_REGEX = "[\\w가-힣]+".toRegex()
private val MENTION_REGEX = "@([\\w가-힣]{2,50})".toRegex()

fun extractHashtags(content: String): List<String> {
    return HASHTAG_REGEX.findAll(content)
        .map { it.value.substring(1) }  // # 제거
        .distinct()
        .filter { it.length <= Constants.Social.MAX_HASHTAG_LENGTH }
        .take(Constants.Social.MAX_HASHTAGS_PER_POST)
        .toList()
}

fun isValidHashtag(hashtag: String): Boolean {
    return hashtag.length in Constants.Social.MIN_HASHTAG_LENGTH..Constants.Social.MAX_HASHTAG_LENGTH &&
            hashtag.matches(HASHTAG_BODY_REGEX)
}

/** @username 멘션 추출 */
fun extractMentions(content: String): List<String> {
    return MENTION_REGEX.findAll(content)
        .map { it.groupValues[1].lowercase() }  // @ 제거, username 저장 형태인 소문자로
        .distinct()
        .toList()
}

/** 커뮤니티 목록까지 포함한 포스트 캐시 무효화 */
suspend fun CacheService.invalidatePostCaches(postId: Int, userId: Int, communityCreatorId: Int?) {
    deletePattern(CacheKeys.Patterns.post(postId))
    deletePattern(CacheKeys.Patterns.EXPLORE_FEED)
    deletePattern(CacheKeys.Patterns.TRENDING_FEED)
    deletePattern(CacheKeys.Patterns.PINNED_POSTS)
    deletePattern(CacheKeys.Patterns.CREATOR_PROFILE_SECTIONS)
    delete(CacheKeys.profile(userId))  // 프로필 글 수
    communityCreatorId?.let { deletePattern(CacheKeys.Patterns.community(it)) }
}

/** 커뮤니티 글이면 커뮤니티 주인 id */
val PostDao.communityCreatorId: Int?
    get() = creatorId.takeIf { contextType == PostContextType.COMMUNITY }
