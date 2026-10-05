package com.ninezero.core.database.entities.tag

import com.ninezero.core.database.entities.social.PostTable
import org.jetbrains.exposed.sql.Table

object PostTagTable : Table("post_tags") {
    val postId = integer("post_id").references(PostTable.id).index()  // 포스트별 태그 조회 최적화
    val tagId = integer("tag_id").references(TagTable.id)
    val isSectionTag = bool("is_section_tag").default(false)

    override val primaryKey = PrimaryKey(postId, tagId)

    init {
        index(false, tagId, isSectionTag)   // 태그별 포스트 조회 최적화
    }
}
