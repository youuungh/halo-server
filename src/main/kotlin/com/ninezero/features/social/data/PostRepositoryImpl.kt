package com.ninezero.features.social.data

import com.ninezero.core.common.config.CommunityPostSortType
import com.ninezero.core.common.config.PostContextType
import com.ninezero.core.common.config.PostStatus
import com.ninezero.core.common.config.PostType
import com.ninezero.core.common.config.SubscriptionPlanTier
import com.ninezero.core.common.config.UserPostSortType
import com.ninezero.core.common.util.toOffset
import com.ninezero.core.database.entities.social.PostDao
import com.ninezero.core.database.entities.social.PostMediaDao
import com.ninezero.core.database.entities.social.PostMediaTable
import com.ninezero.core.database.entities.social.PostTable
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.minus
import org.jetbrains.exposed.sql.SqlExpressionBuilder.plus
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours

class PostRepositoryImpl : PostRepository {

    /** 포스트 생성 */
    override suspend fun createPost(
        userId: Int,
        content: String,
        postType: PostType,
        mediaAttachments: List<String>,
        tags: List<String>,
        productId: Int?,
        requiredTier: SubscriptionPlanTier,
        contextType: PostContextType,
        creatorId: Int?
    ): PostDao {
        return PostDao.new {
            this.userId = userId
            this.creatorId = creatorId
            this.contextType = contextType
            this.content = content
            this.postType = postType
            this.mediaAttachments = if (mediaAttachments.isNotEmpty()) Json.encodeToString(mediaAttachments) else null  // JSON 문자열 저장, 비면 null
            this.tags = if (tags.isNotEmpty()) Json.encodeToString(tags) else null
            this.productId = productId
            this.requiredTier = requiredTier
        }
    }

    /** 커뮤니티 포스트 생성 */
    override suspend fun createCommunityPost(
        userId: Int,
        creatorId: Int,
        content: String,
        postType: PostType,
        mediaAttachments: List<String>,
        tags: List<String>,
        isSecret: Boolean
    ): PostDao {
        return PostDao.new {
            this.userId = userId
            this.creatorId = creatorId
            this.contextType = PostContextType.COMMUNITY
            this.content = content
            this.postType = postType
            this.mediaAttachments = if (mediaAttachments.isNotEmpty()) Json.encodeToString(mediaAttachments) else null  // 비면 null
            this.tags = if (tags.isNotEmpty()) Json.encodeToString(tags) else null
            this.isSecret = isSecret
        }
    }

    /** 포스트 수정 */
    override suspend fun updatePost(
        postId: Int,
        content: String,
        mediaAttachments: List<String>,
        tags: List<String>
    ): PostDao? {
        val post = PostDao.findById(postId) ?: return null  // isActive 확인 없음, 없으면 null

        post.content = content
        post.mediaAttachments = if (mediaAttachments.isNotEmpty()) Json.encodeToString(mediaAttachments) else null  // 빈 목록은 null
        post.tags = if (tags.isNotEmpty()) Json.encodeToString(tags) else null

        return post
    }

    /** 포스트 삭제 */
    override suspend fun deletePost(postId: Int): Boolean {
        val post = PostDao.findById(postId) ?: return false
        post.isActive = false
        return true
    }

    /** 크리에이터 해제용 포스트 숨김 */
    override suspend fun hideCreatorPosts(creatorId: Int): List<Int> {
        val postIds = PostTable
            .select(PostTable.id)
            .where {  // 커뮤니티 전체 + 티어 잠금 피드 글
                (((PostTable.contextType eq PostContextType.COMMUNITY) and (PostTable.creatorId eq creatorId)) or
                        ((PostTable.userId eq creatorId) and (PostTable.contextType eq PostContextType.CREATOR_FEED) and
                                (PostTable.requiredTier neq SubscriptionPlanTier.FREE))) and
                        (PostTable.isActive eq true)
            }
            .map { it[PostTable.id].value }

        if (postIds.isEmpty()) return emptyList()

        PostTable.update({ PostTable.id inList postIds }) {
            // updatedAt 유지, 복구 오염 방지
            it[isActive] = false
            it[status] = PostStatus.HIDDEN  // 복구 마커
        }

        return postIds
    }

    /** 재승인 시 숨긴 포스트 복구 */
    override suspend fun restoreCreatorHiddenPosts(creatorId: Int): List<Int> {
        val postIds = PostTable
            .select(PostTable.id)
            .where {
                (PostTable.status eq PostStatus.HIDDEN) and  // HIDDEN 마커 붙은 글만
                        (((PostTable.contextType eq PostContextType.COMMUNITY) and (PostTable.creatorId eq creatorId)) or
                                ((PostTable.userId eq creatorId) and (PostTable.contextType eq PostContextType.CREATOR_FEED)))
            }
            .map { it[PostTable.id].value }

        if (postIds.isEmpty()) return emptyList()

        PostTable.update({ PostTable.id inList postIds }) {
            it[isActive] = true
            it[status] = PostStatus.ACTIVE
        }

        return postIds
    }

    /** 탈퇴 정리용 HIDDEN 글 미디어 URL 수거 */
    override suspend fun findHiddenCreatorPostMediaUrls(creatorId: Int): List<String> {
        val hiddenPostIds = PostTable
            .select(PostTable.id)
            .where {
                (PostTable.status eq PostStatus.HIDDEN) and  // 해제 때 숨긴 글
                        (((PostTable.contextType eq PostContextType.COMMUNITY) and (PostTable.creatorId eq creatorId)) or
                                ((PostTable.userId eq creatorId) and (PostTable.contextType eq PostContextType.CREATOR_FEED)))
            }
            .map { it[PostTable.id].value }

        if (hiddenPostIds.isEmpty()) return emptyList()

        return PostMediaDao.find { PostMediaTable.postId inList hiddenPostIds }
            .flatMap { media -> listOfNotNull(media.url, media.thumbnailUrl, media.previewUrl) }  // 파일만 정리, rows는 잔존
    }

    /** 피드 고정 */
    override suspend fun pinPostInFeed(postId: Int, creatorId: Int): Boolean {
        val post = PostDao.findById(postId) ?: return false

        if (post.creatorId != creatorId) return false

        val nextOrder = getNextPinnedOrderInFeed(creatorId)
        post.isPinnedInFeed = true
        post.pinnedOrderInFeed = nextOrder
        return true
    }

    /** 피드 고정 해제 */
    override suspend fun unpinPostInFeed(postId: Int): Boolean {
        val post = PostDao.findById(postId) ?: return false
        post.isPinnedInFeed = false
        post.pinnedOrderInFeed = null
        return true
    }

    /** 크리에이터의 피드 고정 포스트 목록 */
    override suspend fun findPinnedPostsInFeed(creatorId: Int, limit: Int): List<PostDao> {
        return PostDao.find {
            (PostTable.creatorId eq creatorId) and
                    (PostTable.isPinnedInFeed eq true) and
                    (PostTable.isActive eq true)
        }.orderBy(PostTable.createdAt to SortOrder.DESC, PostTable.id to SortOrder.DESC)  // pinnedOrderInFeed 아닌 최신순
            .limit(limit)
            .toList()
    }

    /** 크리에이터의 피드 고정 포스트 수 */
    override suspend fun countPinnedPostsInFeed(creatorId: Int): Int {
        return PostDao.find {
            (PostTable.creatorId eq creatorId) and (PostTable.isPinnedInFeed eq true)  // isActive 무시
        }.count().toInt()
    }

    /** 피드 고정 다음 순번 */
    override suspend fun getNextPinnedOrderInFeed(creatorId: Int): Int {
        val maxOrder = PostDao.find {
            (PostTable.creatorId eq creatorId) and (PostTable.isPinnedInFeed eq true)
        }.maxOfOrNull { it.pinnedOrderInFeed ?: 0 } ?: 0

        return maxOrder + 1
    }

    /** 커뮤니티 고정 */
    override suspend fun pinPostInCommunity(postId: Int, creatorId: Int): Boolean {
        val post = PostDao.findById(postId) ?: return false

        if (post.creatorId != creatorId || post.contextType != PostContextType.COMMUNITY) {  // 불일치·비커뮤니티면 false
            return false
        }

        post.isPinnedInCommunity = true
        return true
    }

    /** 커뮤니티 고정 해제 */
    override suspend fun unpinPostInCommunity(postId: Int): Boolean {
        val post = PostDao.findById(postId) ?: return false
        post.isPinnedInCommunity = false
        return true
    }

    /** 크리에이터의 커뮤니티 고정 포스트 목록 */
    override suspend fun findPinnedPostsInCommunity(creatorId: Int, limit: Int): List<PostDao> {
        return PostDao.find {
            (PostTable.creatorId eq creatorId) and
                    (PostTable.contextType eq PostContextType.COMMUNITY) and
                    (PostTable.isPinnedInCommunity eq true) and
                    (PostTable.isActive eq true)
        }.orderBy(PostTable.createdAt to SortOrder.DESC, PostTable.id to SortOrder.DESC)  // 최신순
            .limit(limit)
            .toList()
    }

    /** 크리에이터의 커뮤니티 고정 포스트 수 */
    override suspend fun countPinnedPostsInCommunity(creatorId: Int): Int {
        return PostDao.find {
            (PostTable.creatorId eq creatorId) and
                    (PostTable.contextType eq PostContextType.COMMUNITY) and
                    (PostTable.isPinnedInCommunity eq true)
        }.count().toInt()
    }

    /** 포스트 조회 */
    override suspend fun findPostById(postId: Int): PostDao? {
        return PostDao.find { PostTable.id eq postId and PostTable.isActive }.firstOrNull()
    }

    /** 포스트 일괄 조회 */
    override suspend fun findPostsByIds(postIds: List<Int>): List<PostDao> {
        if (postIds.isEmpty()) return emptyList()

        return PostDao.find {
            (PostTable.id inList postIds) and PostTable.isActive
        }.toList()
    }

    /** 포스트 일괄 조회 */
    override suspend fun findPostsByIdsWithDeleted(postIds: List<Int>): List<PostDao> {
        if (postIds.isEmpty()) return emptyList()

        return PostDao.find {
            PostTable.id inList postIds  // 삭제·숨김·블라인드 포함
        }.toList()
    }

    /** 유저 포스트 목록 */
    override suspend fun findPostsByUserId(
        userId: Int,
        page: Int,
        limit: Int,
        sort: UserPostSortType,
        contextType: PostContextType?,
        includeBlindedForAuthor: Boolean
    ): List<PostDao> {
        val sortOrder = when (sort) {
            UserPostSortType.LATEST -> PostTable.createdAt to SortOrder.DESC
            UserPostSortType.OLDEST -> PostTable.createdAt to SortOrder.ASC
        }

        // includeBlindedForAuthor = 본인 블라인드 글 포함
        val visible = SqlExpressionBuilder.run {
            if (includeBlindedForAuthor) {
                (PostTable.isActive eq true) or (PostTable.isBlinded eq true)
            } else {
                PostTable.isActive eq true
            }
        }

        val results = when (contextType) {
            null -> PostDao.find {
                (PostTable.userId eq userId) and visible
            }
            PostContextType.COMMUNITY -> PostDao.find {
                (PostTable.userId eq userId) and visible and (PostTable.contextType eq PostContextType.COMMUNITY)
            }
            else -> PostDao.find {
                (PostTable.userId eq userId) and visible and (PostTable.contextType neq PostContextType.COMMUNITY)
            }
        }
        return results
            .orderBy(sortOrder, PostTable.id to SortOrder.DESC)
            .limit(limit).offset(page.toOffset(limit))
            .toList()
    }

    /** 유저 포스트 수 */
    override suspend fun countUserPosts(
        userId: Int,
        contextType: PostContextType?,
        includeBlindedForAuthor: Boolean
    ): Int {
        // 필터 어긋나면 페이지네이션 파손
        val visible = SqlExpressionBuilder.run {
            if (includeBlindedForAuthor) {
                (PostTable.isActive eq true) or (PostTable.isBlinded eq true)
            } else {
                PostTable.isActive eq true
            }
        }
        return when (contextType) {
            null -> PostDao.find {
                (PostTable.userId eq userId) and visible
            }
            PostContextType.COMMUNITY -> PostDao.find {
                (PostTable.userId eq userId) and visible and (PostTable.contextType eq PostContextType.COMMUNITY)
            }
            else -> PostDao.find {
                (PostTable.userId eq userId) and visible and (PostTable.contextType neq PostContextType.COMMUNITY)
            }
        }.count().toInt()
    }

    /** 홈 피드 포스트 목록 */
    override suspend fun findHomeFeedPosts(userIds: List<Int>, page: Int, limit: Int, lastPostId: Int?): List<PostDao> {
        // 비밀글은 홈 피드에서 제외
        val query = PostTable.selectAll().where {
            (PostTable.userId inList userIds) and PostTable.isActive and (PostTable.isSecret eq false)
        }

        if (lastPostId != null) {
            query.andWhere { PostTable.id less lastPostId }
        }

        return PostDao.wrapRows(
            query
                .orderBy(PostTable.createdAt to SortOrder.DESC, PostTable.id to SortOrder.DESC)  // 최신순
                .limit(limit)
                .offset(if (lastPostId != null) 0 else page.toOffset(limit))  // lastPostId 있으면 page 무시
        ).toList()
    }

    /** 탐색 피드 포스트 목록 */
    override suspend fun findExploreFeedPosts(userId: Int?, page: Int, limit: Int): List<PostDao> {
        return PostDao.find { (PostTable.isActive eq true) and (PostTable.isSecret eq false) }  // 비밀글 제외, userId 파라미터 미사용
            .orderBy(
                (PostTable.likeCount + PostTable.commentCount) to SortOrder.DESC,
                PostTable.createdAt to SortOrder.DESC,
                PostTable.id to SortOrder.DESC
            )
            .limit(limit).offset(page.toOffset(limit))
            .toList()
    }

    /** 트렌딩 포스트 목록 */
    override suspend fun findTrendingPosts(hours: Int, page: Int, limit: Int): List<PostDao> {
        val cutoffTime = Clock.System.now()
            .minus(hours.hours)
            .toLocalDateTime(TimeZone.UTC)

        return PostDao.find {
            (PostTable.isActive eq true) and
                    (PostTable.isSecret eq false) and  // 비밀글 제외
                    (PostTable.createdAt greaterEq cutoffTime)
        }
            .orderBy((PostTable.likeCount + PostTable.commentCount) to SortOrder.DESC, PostTable.id to SortOrder.DESC)
            .limit(limit)
            .offset(page.toOffset(limit))
            .toList()
    }

    /** 트렌딩 포스트 수 */
    override suspend fun countTrendingPosts(hours: Int): Long {
        val cutoffTime = Clock.System.now()
            .minus(hours.hours)
            .toLocalDateTime(TimeZone.UTC)

        return PostDao.find {
            (PostTable.isActive eq true) and
                    (PostTable.isSecret eq false) and
                    (PostTable.createdAt greaterEq cutoffTime)
        }.count()
    }

    /** 크리에이터 커뮤니티의 포스트 목록 */
    override suspend fun findCommunityPosts(
        creatorId: Int,
        page: Int,
        limit: Int,
        sort: CommunityPostSortType
    ): List<PostDao> {
        return PostDao.find {
            (PostTable.creatorId eq creatorId) and
                    (PostTable.contextType eq PostContextType.COMMUNITY) and
                    // 비밀글도 노출
                    (PostTable.isPinnedInCommunity eq false) and  // 고정 제외
                    (PostTable.isActive eq true)
        }.orderBy(
            when (sort) {
                CommunityPostSortType.LATEST -> PostTable.createdAt to SortOrder.DESC
                CommunityPostSortType.POPULAR -> PostTable.likeCount to SortOrder.DESC
                CommunityPostSortType.MOST_VIEWED -> PostTable.viewCount to SortOrder.DESC
                CommunityPostSortType.OLDEST -> PostTable.createdAt to SortOrder.ASC
            },
            PostTable.id to SortOrder.DESC
        )
            .limit(limit)
            .offset(page.toOffset(limit))
            .toList()
    }

    /** 크리에이터의 contextType별 포스트 목록 */
    override suspend fun findPostsByCreatorAndContext(
        creatorId: Int,
        contextType: PostContextType,
        page: Int,
        limit: Int
    ): List<PostDao> {
        return PostDao.find {
            (PostTable.creatorId eq creatorId) and
                    (PostTable.contextType eq contextType) and
                    (PostTable.isActive eq true)  // 고정·비밀글 포함
        }.orderBy(PostTable.createdAt to SortOrder.DESC, PostTable.id to SortOrder.DESC)  // 최신순
            .limit(limit)
            .offset(page.toOffset(limit))
            .toList()
    }

    /** 해시태그 포스트 목록 */
    override suspend fun findPostsByHashtag(hashtag: String, page: Int, limit: Int): List<PostDao> {
        return PostDao.find {
            PostTable.content like "%#$hashtag%" and PostTable.isActive and (PostTable.isSecret eq false)  // 비밀글 제외
        }
            .orderBy(PostTable.createdAt to SortOrder.DESC, PostTable.id to SortOrder.DESC)  // 최신순
            .limit(limit).offset(page.toOffset(limit))
            .toList()
    }

    /** 포스트 검색 */
    override suspend fun searchPosts(query: String, page: Int, limit: Int): List<PostDao> {
        // 비밀글은 검색에서 제외
        return PostDao.find {
            PostTable.content like "%$query%" and PostTable.isActive and (PostTable.isSecret eq false)
        }
            .orderBy(PostTable.createdAt to SortOrder.DESC, PostTable.id to SortOrder.DESC)  // 최신순
            .limit(limit).offset(page.toOffset(limit))
            .toList()
    }

    /** 전체 포스트 목록 */
    override suspend fun findAllPosts(page: Int, limit: Int): List<PostDao> {
        return PostDao.all()  // 삭제·숨김·비밀글 포함
            .orderBy(PostTable.createdAt to SortOrder.DESC, PostTable.id to SortOrder.DESC)  // 최신순
            .limit(limit).offset(page.toOffset(limit))
            .toList()
    }

    /** 전체 포스트 수 */
    override suspend fun countAllPosts(): Int {
        return PostDao.all().count().toInt()  // 삭제·숨김 포함
    }

    /** 포스트 viewCount +1 */
    override suspend fun incrementViewCount(postId: Int): Boolean {
        val updated = PostTable.update({ PostTable.id eq postId }) {
            it[viewCount] = viewCount.plus(1)
        }
        return updated > 0
    }

    /** 포스트 likeCount +1 */
    override suspend fun incrementLikeCount(postId: Int): Boolean {
        val updated = PostTable.update({ PostTable.id eq postId }) {
            it[likeCount] = likeCount.plus(1)
        }
        return updated > 0
    }

    /** 포스트 likeCount -1 */
    override suspend fun decrementLikeCount(postId: Int): Boolean {
        val updated = PostTable.update({ PostTable.id eq postId }) {
            it[likeCount] = likeCount.minus(1)
        }
        return updated > 0
    }

    /** 포스트 commentCount +1 */
    override suspend fun incrementCommentCount(postId: Int): Boolean {
        val updated = PostTable.update({ PostTable.id eq postId }) {
            it[commentCount] = commentCount.plus(1)
        }
        return updated > 0
    }

    /** 포스트 commentCount -1 */
    override suspend fun decrementCommentCount(postId: Int): Boolean {
        val updated = PostTable.update({ PostTable.id eq postId }) {
            it[commentCount] = commentCount.minus(1)
        }
        return updated > 0
    }

    /** 포스트 shareCount +1 */
    override suspend fun incrementShareCount(postId: Int): Boolean {
        val updated = PostTable.update({ PostTable.id eq postId }) {
            it[shareCount] = shareCount.plus(1)
        }
        return updated > 0
    }

    /** 유저의 포스트 수 */
    override suspend fun countPostsByUserId(userId: Int): Int {
        return PostDao.find { PostTable.userId eq userId and PostTable.isActive }.count().toInt()  // contextType 무관
    }

    /** 유저별 포스트 수 일괄 조회 */
    override suspend fun countPostsByUserIds(userIds: List<Int>): Map<Int, Int> {
        if (userIds.isEmpty()) return emptyMap()

        return PostTable.select(PostTable.userId, PostTable.id.count())
            .where { PostTable.userId inList userIds and PostTable.isActive }
            .groupBy(PostTable.userId)  // 0인 유저는 키 없음
            .associate { it[PostTable.userId] to it[PostTable.id.count()].toInt() }
    }

    /** 크리에이터 커뮤니티의 포스트 수 */
    override suspend fun countCommunityPosts(creatorId: Int): Int {
        return PostDao.find {
            (PostTable.creatorId eq creatorId) and
                    (PostTable.contextType eq PostContextType.COMMUNITY) and
                    (PostTable.isActive eq true)  // 고정 포함
        }.count().toInt()
    }
}
