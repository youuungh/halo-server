package com.ninezero.features.upload.data

import com.ninezero.core.common.config.UploadPurpose
import com.ninezero.core.common.config.UploadStatus
import com.ninezero.core.database.entities.upload.MediaUploadDao
import com.ninezero.core.database.entities.upload.MediaUploadTable
import kotlinx.datetime.LocalDateTime
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.jdbc.deleteWhere

class MediaUploadRepositoryImpl : MediaUploadRepository {

    /** 업로드 기록 생성 */
    override suspend fun createUpload(
        userId: Int,
        purpose: UploadPurpose,
        targetId: Int?,
        bucket: String,
        path: String,
        mimeType: String,
        declaredSize: Long,
        expiresAt: LocalDateTime
    ): MediaUploadDao {
        return MediaUploadDao.new {
            this.userId = userId
            this.purpose = purpose
            this.targetId = targetId
            this.bucket = bucket
            this.path = path
            this.mimeType = mimeType
            this.declaredSize = declaredSize
            this.expiresAt = expiresAt
        }
    }

    /** 업로드 기록 일괄 삭제 */
    override suspend fun deleteUploads(ids: Collection<Int>): Int {
        if (ids.isEmpty()) return 0
        return MediaUploadTable.deleteWhere { MediaUploadTable.id inList ids }
    }

    /** 기준 시각 이전에 사용된 기록 삭제 */
    override suspend fun deleteConsumedUploadsBefore(before: LocalDateTime): Int {
        return MediaUploadTable.deleteWhere {
            (status eq UploadStatus.CONSUMED) and (consumedAt less before)
        }
    }

    /** 업로드 기록 조회 */
    override suspend fun findUploadById(id: Int): MediaUploadDao? {
        return MediaUploadDao.findById(id)
    }

    /** 사용 처리용 잠금 조회 */
    override suspend fun findUploadByIdForUpdate(id: Int): MediaUploadDao? {
        return MediaUploadDao.find { MediaUploadTable.id eq id }
            .forUpdate()
            .singleOrNull()
            ?.also { it.refresh(flush = false) }
    }

    /** 기준 시각 전에 만료된 미사용 기록 */
    override suspend fun findExpiredIssuedUploads(before: LocalDateTime, limit: Int): List<MediaUploadDao> {
        return MediaUploadDao.find {
            (MediaUploadTable.status eq UploadStatus.ISSUED) and (MediaUploadTable.expiresAt less before)
        }
            .orderBy(MediaUploadTable.id to SortOrder.ASC)
            .limit(limit)
            .toList()
    }

    /** 사용 처리 */
    override suspend fun markConsumed(id: Int, consumedAt: LocalDateTime) {
        MediaUploadDao.findById(id)?.apply {
            status = UploadStatus.CONSUMED
            this.consumedAt = consumedAt
        }
    }
}
