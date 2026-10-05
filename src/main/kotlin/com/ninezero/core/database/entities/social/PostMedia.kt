package com.ninezero.core.database.entities.social

import com.ninezero.core.common.config.MediaType
import com.ninezero.core.database.entities.base.BaseIntEntity
import com.ninezero.core.database.entities.base.BaseIntEntityClass
import com.ninezero.core.database.entities.base.BaseIntIdTable
import org.jetbrains.exposed.dao.id.EntityID

object PostMediaTable : BaseIntIdTable("post_media") {
    val postId = integer("post_id").references(PostTable.id)
    val type = enumerationByName<MediaType>("type", 50)
    val url = varchar("url", 1000)
    val width = integer("width").nullable()
    val height = integer("height").nullable()
    val durationMs = long("duration_ms").nullable()
    val thumbnailUrl = varchar("thumbnail_url", 1000).nullable()
    val previewUrl = varchar("preview_url", 1000).nullable()  // 잠금용 블러 프리뷰
    val sortOrder = integer("sort_order").default(0)

    init {
        index(false, postId, sortOrder)
    }
}

class PostMediaDao(id: EntityID<Int>) : BaseIntEntity(id, PostMediaTable) {
    companion object : BaseIntEntityClass<PostMediaDao>(PostMediaTable)

    var postId by PostMediaTable.postId
    var type by PostMediaTable.type
    var url by PostMediaTable.url
    var width by PostMediaTable.width
    var height by PostMediaTable.height
    var durationMs by PostMediaTable.durationMs
    var thumbnailUrl by PostMediaTable.thumbnailUrl
    var previewUrl by PostMediaTable.previewUrl
    var sortOrder by PostMediaTable.sortOrder
}
