package com.ninezero.features.social

import com.ninezero.core.common.config.FeedType
import com.ninezero.core.common.config.PostContextType
import com.ninezero.core.common.util.CursorPaginationInfo
import com.ninezero.core.common.util.decodeJsonToList
import com.ninezero.core.common.util.extractHashtags
import com.ninezero.core.database.entities.social.CommentMediaDao
import com.ninezero.core.database.entities.social.CommentMediaTable
import com.ninezero.core.database.entities.social.CommentDao
import com.ninezero.core.database.entities.social.PostMediaDao
import com.ninezero.core.database.entities.social.PostMediaTable
import com.ninezero.core.database.entities.social.PostDao
import com.ninezero.features.social.presentation.models.response.CommentResponse
import com.ninezero.features.social.presentation.models.response.MediaItemResponse
import com.ninezero.features.social.presentation.models.response.AdminPostResponse
import com.ninezero.features.social.presentation.models.response.FeedResponse
import com.ninezero.features.social.presentation.models.response.PostResponse
import com.ninezero.features.user.presentation.models.response.UserPreviewResponse
import com.ninezero.features.user.presentation.models.response.UserSummaryResponse
import com.ninezero.core.common.config.Constants
import com.ninezero.core.common.exception.Errors
import com.ninezero.core.common.exception.InvalidInputException
import com.ninezero.core.database.entities.base.BaseIntEntity
import org.jetbrains.exposed.sql.SortOrder
import kotlin.math.ceil

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
    val locked = !canAccess  // 접근 권한 없으면 본문·미디어·태그 제거 플래그
    return PostResponse(
        id = this.id.value,
        author = author,
        content = if (locked) "" else this.content,
        postType = this.postType,
        contextType = this.contextType,
        status = this.status,
        mediaItems = if (locked) {
            // 원본 대신 블러 프리뷰만 반환
            (mediaItems ?: emptyList()).mapNotNull { m -> m.previewUrl?.let { p -> m.copy(url = p, thumbnailUrl = p) } }
        } else (mediaItems ?: emptyList()),
        tags = if (locked) emptyList() else (this.tags?.decodeJsonToList() ?: extractHashtags(this.content)),
        likeCount = this.likeCount,
        commentCount = this.commentCount,
        viewCount = this.viewCount,
        shareCount = this.shareCount,
        isLiked = isLiked,
        isBookmarked = isBookmarked,
        isPinned = when (this.contextType) {
            PostContextType.CREATOR_FEED -> this.isPinnedInFeed
            PostContextType.COMMUNITY -> this.isPinnedInCommunity
            else -> false
        },
        productInfo = null,  // TODO: 연동 시 this.productId 사용
        requiredTier = this.requiredTier,
        isSecret = this.isSecret,
        canAccess = canAccess,
        isBlinded = this.isBlinded,
        createdAt = this.createdAt,
        updatedAt = this.updatedAt
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
        postType = this.postType,
        contextType = this.contextType,
        status = this.status,
        mediaItems = mediaItems ?: emptyList(),
        tags = this.tags?.decodeJsonToList() ?: extractHashtags(this.content),
        likeCount = this.likeCount,
        commentCount = this.commentCount,
        viewCount = this.viewCount,
        shareCount = this.shareCount,
        requiredTier = this.requiredTier,
        isActive = this.isActive,
        isSecret = this.isSecret,
        isPinnedInFeed = this.isPinnedInFeed,
        isPinnedInCommunity = this.isPinnedInCommunity,
        createdAt = this.createdAt,
        updatedAt = this.updatedAt
    )
}

fun CommentDao.toCommentResponse(
    author: UserSummaryResponse,
    isLiked: Boolean = false,
    isBookmarked: Boolean = false,
    isBlocked: Boolean = false,
    firstReply: CommentResponse? = null,
    hasMoreReplies: Boolean = false,
    moreReplyCount: Int = 0,
    replyPreviewUsers: List<UserPreviewResponse> = emptyList(),
    mediaItems: List<MediaItemResponse>? = null
): CommentResponse {
    return CommentResponse(
        id = this.id.value,
        postId = this.postId,
        author = author,
        content = if (isBlocked) "" else this.content,
        mediaItems = if (isBlocked) emptyList() else (mediaItems ?: emptyList()),
        parentCommentId = this.parentCommentId,
        likeCount = this.likeCount,
        replyCount = this.replyCount,
        isLiked = isLiked,
        isBookmarked = isBookmarked,
        isBlocked = isBlocked,
        isBlinded = this.isBlinded,
        firstReply = firstReply,
        hasMoreReplies = hasMoreReplies,
        moreReplyCount = moreReplyCount,
        replyPreviewUsers = replyPreviewUsers,
        createdAt = this.createdAt,
        updatedAt = this.updatedAt
    )
}

fun PostMediaDao.toMediaItemResponse(): MediaItemResponse {
    return MediaItemResponse(
        id = this.id.value,
        type = this.type,
        url = this.url,
        width = this.width,
        height = this.height,
        durationMs = this.durationMs,
        thumbnailUrl = this.thumbnailUrl,
        previewUrl = this.previewUrl,
        sortOrder = this.sortOrder
    )
}

fun CommentMediaDao.toMediaItemResponse(): MediaItemResponse {
    return MediaItemResponse(
        id = this.id.value,
        type = this.type,
        url = this.url,
        width = this.width,
        height = this.height,
        durationMs = this.durationMs,
        thumbnailUrl = this.thumbnailUrl,
        sortOrder = this.sortOrder
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
    cursorPagination: CursorPaginationInfo,
    page: Int,
    totalCount: Int? = null
): FeedResponse {
    return FeedResponse(
        posts = posts,
        totalCount = totalCount ?: posts.size,
        page = page,
        totalPages = totalCount?.let {
            ceil(it.toDouble() / cursorPagination.limit).toInt()
        } ?: 1,
        hasNext = cursorPagination.hasNext,
        hasPrevious = page > 1,
        lastPostId = posts.lastOrNull()?.id,
        feedType = feedType
    )
}
