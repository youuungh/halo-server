package com.ninezero.core.database.entities.social

import com.ninezero.core.common.config.MediaType
import com.ninezero.core.database.entities.base.BaseIntEntity
import com.ninezero.core.database.entities.base.BaseIntEntityClass
import com.ninezero.core.database.entities.base.BaseIntIdTable
import org.jetbrains.exposed.dao.id.EntityID

object CommentMediaTable : BaseIntIdTable("comment_media") {
    val commentId = integer("comment_id").references(CommentTable.id)
    val type = enumerationByName<MediaType>("type", 50)
    val url = varchar("url", 1000)
    val width = integer("width").nullable()
    val height = integer("height").nullable()
    val durationMs = long("duration_ms").nullable()
    val thumbnailUrl = varchar("thumbnail_url", 1000).nullable()
    val sortOrder = integer("sort_order").default(0)

    init {
        index(false, commentId, sortOrder)
    }
}

class CommentMediaDao(id: EntityID<Int>) : BaseIntEntity(id, CommentMediaTable) {
    companion object : BaseIntEntityClass<CommentMediaDao>(CommentMediaTable)

    var commentId by CommentMediaTable.commentId
    var type by CommentMediaTable.type
    var url by CommentMediaTable.url
    var width by CommentMediaTable.width
    var height by CommentMediaTable.height
    var durationMs by CommentMediaTable.durationMs
    var thumbnailUrl by CommentMediaTable.thumbnailUrl
    var sortOrder by CommentMediaTable.sortOrder
}
