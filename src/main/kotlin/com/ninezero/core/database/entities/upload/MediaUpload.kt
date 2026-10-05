package com.ninezero.core.database.entities.upload

import com.ninezero.core.common.config.UploadPurpose
import com.ninezero.core.common.config.UploadStatus
import com.ninezero.core.database.entities.base.BaseIntEntity
import com.ninezero.core.database.entities.base.BaseIntEntityClass
import com.ninezero.core.database.entities.base.BaseIntIdTable
import com.ninezero.core.database.entities.user.UserTable
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.kotlin.datetime.datetime

object MediaUploadTable : BaseIntIdTable("media_uploads") {
    val userId = integer("user_id").references(UserTable.id)
    val purpose = enumerationByName<UploadPurpose>("purpose", 30)
    val targetId = integer("target_id").nullable()  // 채팅방·포스트·댓글 ID, 초안은 없음
    val bucket = varchar("bucket", 50)
    val path = varchar("path", 500).uniqueIndex()
    val mimeType = varchar("mime_type", 100)
    val declaredSize = long("declared_size")
    val status = enumerationByName<UploadStatus>("status", 20).default(UploadStatus.ISSUED)
    val expiresAt = datetime("expires_at")
    val consumedAt = datetime("consumed_at").nullable()

    init {
        index(false, status, expiresAt)  // 만료 정리 조회 최적화
    }
}

class MediaUploadDao(id: EntityID<Int>) : BaseIntEntity(id, MediaUploadTable) {
    companion object : BaseIntEntityClass<MediaUploadDao>(MediaUploadTable)

    var userId by MediaUploadTable.userId
    var purpose by MediaUploadTable.purpose
    var targetId by MediaUploadTable.targetId
    var bucket by MediaUploadTable.bucket
    var path by MediaUploadTable.path
    var mimeType by MediaUploadTable.mimeType
    var declaredSize by MediaUploadTable.declaredSize
    var status by MediaUploadTable.status
    var expiresAt by MediaUploadTable.expiresAt
    var consumedAt by MediaUploadTable.consumedAt
}
