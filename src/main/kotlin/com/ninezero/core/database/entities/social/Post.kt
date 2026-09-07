package com.ninezero.core.database.entities.social

import com.ninezero.core.common.config.PostContextType
import com.ninezero.core.common.config.PostStatus
import com.ninezero.core.common.config.PostType
import com.ninezero.core.common.config.SubscriptionPlanTier
import com.ninezero.core.database.entities.base.BaseIntEntity
import com.ninezero.core.database.entities.base.BaseIntEntityClass
import com.ninezero.core.database.entities.base.BaseIntIdTable
import com.ninezero.core.database.entities.user.UserTable
import org.jetbrains.exposed.dao.id.EntityID

object PostTable : BaseIntIdTable("posts") {
    val userId = integer("user_id").references(UserTable.id)
    val creatorId = integer("creator_id").nullable()
    val contextType = enumerationByName<PostContextType>("context_type", 50).default(PostContextType.GENERAL)
    val content = text("content")
    val postType = enumerationByName<PostType>("post_type", 50).default(PostType.TEXT)
    val status = enumerationByName<PostStatus>("status", 50).default(PostStatus.ACTIVE)
    val mediaAttachments = text("media_urls").nullable()
    val tags = text("tags").nullable()
    val productId = integer("product_id").nullable()
    val likeCount = integer("like_count").default(0)
    val commentCount = integer("comment_count").default(0)
    val viewCount = integer("view_count").default(0)
    val shareCount = integer("share_count").default(0)
    val isPinnedInFeed = bool("is_pinned_in_feed").default(false)
    val isPinnedInCommunity = bool("is_pinned_in_community").default(false)
    val pinnedOrderInFeed = integer("pinned_order_in_feed").nullable()
    val pinnedOrderInCommunity = integer("pinned_order_in_community").nullable()
    val isActive = bool("is_active").default(true)
    val isBlinded = bool("is_blinded").default(false)
    val moderationLocked = bool("moderation_locked").default(false)
    val requiredTier = enumerationByName<SubscriptionPlanTier>("required_tier", 50).default(SubscriptionPlanTier.FREE)
    val isSecret = bool("is_secret").default(false)

    init {
        index(false, userId, createdAt)                             // 사용자별 포스트 조회 최적화
        index(false, status, createdAt)                             // 상태별 포스트 조회 최적화
        index(false, isActive, createdAt)                           // 활성 포스트 조회 최적화
        index(false, creatorId, contextType, createdAt)             // 크리에이터 피드 조회 최적화
        index(false, creatorId, isPinnedInFeed, pinnedOrderInFeed)  // 고정 포스트 조회 최적화
        index(false, creatorId, isPinnedInCommunity, pinnedOrderInCommunity)
    }
}

class PostDao(id: EntityID<Int>) : BaseIntEntity(id, PostTable) {
    companion object : BaseIntEntityClass<PostDao>(PostTable)

    var userId by PostTable.userId
    var creatorId by PostTable.creatorId
    var contextType by PostTable.contextType
    var content by PostTable.content
    var postType by PostTable.postType
    var status by PostTable.status
    var mediaAttachments by PostTable.mediaAttachments
    var tags by PostTable.tags
    var productId by PostTable.productId
    var likeCount by PostTable.likeCount
    var commentCount by PostTable.commentCount
    var viewCount by PostTable.viewCount
    var shareCount by PostTable.shareCount
    var isPinnedInFeed by PostTable.isPinnedInFeed
    var isPinnedInCommunity by PostTable.isPinnedInCommunity
    var pinnedOrderInFeed by PostTable.pinnedOrderInFeed
    var pinnedOrderInCommunity by PostTable.pinnedOrderInCommunity
    var isActive by PostTable.isActive
    var isBlinded by PostTable.isBlinded
    var moderationLocked by PostTable.moderationLocked
    var requiredTier by PostTable.requiredTier
    var isSecret by PostTable.isSecret
}
