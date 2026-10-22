package com.ninezero.core.database.entities.social

import com.ninezero.core.database.entities.base.BaseIntEntity
import com.ninezero.core.database.entities.base.BaseIntEntityClass
import com.ninezero.core.database.entities.base.BaseIntIdTable
import com.ninezero.core.database.entities.user.UserTable
import org.jetbrains.exposed.dao.id.EntityID

object CommentTable : BaseIntIdTable("comments") {
    val postId = integer("post_id").references(PostTable.id)
    val userId = integer("user_id").references(UserTable.id)
    val parentCommentId = integer("parent_comment_id").references(id).nullable().index()  // 대댓글 조회 최적화
    val content = text("content")
    val likeCount = integer("like_count").default(0)
    val replyCount = integer("reply_count").default(0)
    val isActive = bool("is_active").default(true)
    val isBlinded = bool("is_blinded").default(false)
    val moderationLocked = bool("moderation_locked").default(false)
    val isDeleted = bool("is_deleted").default(false)  // 지워지거나 블라인드됐지만 답글이 남아 자리만 표시
    val clientRequestId = varchar("client_request_id", 64).nullable()  // 앱 작성 요청 식별자, 같은 요청이 반복돼도 댓글은 하나

    init {
        index(false, postId, createdAt)     // 포스트별 댓글 조회 최적화
        index(false, userId, createdAt)     // 사용자별 댓글 조회 최적화
        uniqueIndex(userId, clientRequestId)
    }
}

class CommentDao(id: EntityID<Int>) : BaseIntEntity(id, CommentTable) {
    companion object : BaseIntEntityClass<CommentDao>(CommentTable)

    var postId by CommentTable.postId
    var userId by CommentTable.userId
    var parentCommentId by CommentTable.parentCommentId
    var content by CommentTable.content
    var likeCount by CommentTable.likeCount
    var replyCount by CommentTable.replyCount
    var isActive by CommentTable.isActive
    var isBlinded by CommentTable.isBlinded
    var moderationLocked by CommentTable.moderationLocked
    var isDeleted by CommentTable.isDeleted
    var clientRequestId by CommentTable.clientRequestId
}
