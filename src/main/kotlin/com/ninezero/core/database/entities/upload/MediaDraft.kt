package com.ninezero.core.database.entities.upload

import com.ninezero.core.common.config.MediaDraftStatus
import com.ninezero.core.common.config.MediaDraftTargetType
import com.ninezero.core.common.config.MediaType
import com.ninezero.core.database.entities.base.BaseIntEntity
import com.ninezero.core.database.entities.base.BaseIntEntityClass
import com.ninezero.core.database.entities.base.BaseIntIdTable
import com.ninezero.core.database.entities.user.UserTable
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.kotlin.datetime.datetime

object MediaDraftTable : BaseIntIdTable("media_drafts") {
    val userId = integer("user_id").references(UserTable.id)
    val targetType = enumerationByName<MediaDraftTargetType>("target_type", 20)
    val type = enumerationByName<MediaType>("type", 50)
    val url = varchar("url", 1000)
    val width = integer("width").nullable()
    val height = integer("height").nullable()
    val durationMs = long("duration_ms").nullable()
    val thumbnailUrl = varchar("thumbnail_url", 1000).nullable()
    val previewUrl = varchar("preview_url", 1000).nullable()  // 잠금용 블러 프리뷰
    val status = enumerationByName<MediaDraftStatus>("status", 20).default(MediaDraftStatus.READY)
    val expiresAt = datetime("expires_at")

    init {
        index(false, status, expiresAt)     // 만료 정리 조회 최적화
    }
}

class MediaDraftDao(id: EntityID<Int>) : BaseIntEntity(id, MediaDraftTable) {
    companion object : BaseIntEntityClass<MediaDraftDao>(MediaDraftTable)

    var userId by MediaDraftTable.userId
    var targetType by MediaDraftTable.targetType
    var type by MediaDraftTable.type
    var url by MediaDraftTable.url
    var width by MediaDraftTable.width
    var height by MediaDraftTable.height
    var durationMs by MediaDraftTable.durationMs
    var thumbnailUrl by MediaDraftTable.thumbnailUrl
    var previewUrl by MediaDraftTable.previewUrl
    var status by MediaDraftTable.status
    var expiresAt by MediaDraftTable.expiresAt
}
