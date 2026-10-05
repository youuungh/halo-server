package com.ninezero.features.upload.data

import com.ninezero.core.common.config.MediaDraftStatus
import com.ninezero.core.common.config.MediaDraftTargetType
import com.ninezero.core.common.config.MediaType
import com.ninezero.core.database.entities.upload.MediaDraftDao
import com.ninezero.core.database.entities.upload.MediaDraftTable
import kotlinx.datetime.LocalDateTime
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.update

class MediaDraftRepositoryImpl : MediaDraftRepository {

    /** 초안 생성 */
    override suspend fun createDraft(
        userId: Int,
        targetType: MediaDraftTargetType,
        type: MediaType,
        url: String,
        width: Int?,
        height: Int?,
        durationMs: Long?,
        thumbnailUrl: String?,
        previewUrl: String?,
        status: MediaDraftStatus,
        expiresAt: LocalDateTime
    ): MediaDraftDao {
        return MediaDraftDao.new {
            this.userId = userId
            this.targetType = targetType
            this.type = type
            this.url = url
            this.width = width
            this.height = height
            this.durationMs = durationMs
            this.thumbnailUrl = thumbnailUrl
            this.previewUrl = previewUrl
            this.status = status
            this.expiresAt = expiresAt
        }
    }

    /** 초안 일괄 삭제 */
    override suspend fun deleteDrafts(ids: Collection<Int>): Int {
        if (ids.isEmpty()) return 0
        return MediaDraftTable.deleteWhere { MediaDraftTable.id inList ids }
    }

    /** 가공 결과 반영, 초안이 이미 지워졌으면 false */
    override suspend fun markReady(
        id: Int,
        width: Int?,
        height: Int?,
        durationMs: Long?,
        thumbnailUrl: String?,
        previewUrl: String?
    ): Boolean {
        return MediaDraftTable.update({ MediaDraftTable.id eq id }) {
            it[MediaDraftTable.width] = width
            it[MediaDraftTable.height] = height
            it[MediaDraftTable.durationMs] = durationMs
            it[MediaDraftTable.thumbnailUrl] = thumbnailUrl
            it[MediaDraftTable.previewUrl] = previewUrl
            it[MediaDraftTable.status] = MediaDraftStatus.READY
        } > 0
    }

    /** 가공 실패 */
    override suspend fun markFailed(id: Int) {
        MediaDraftDao.findById(id)?.status = MediaDraftStatus.FAILED
    }

    /** 초안 조회 */
    override suspend fun findDraftById(id: Int): MediaDraftDao? {
        return MediaDraftDao.findById(id)
    }

    /** 게시 회수용 잠금 조회 */
    override suspend fun findDraftsByIdsForUpdate(ids: Collection<Int>): List<MediaDraftDao> {
        if (ids.isEmpty()) return emptyList()
        return MediaDraftDao.find { MediaDraftTable.id inList ids }
            .forUpdate()
            .toList()
    }

    /** 사용자의 남은 초안 */
    override suspend fun findDraftsByUser(userId: Int): List<MediaDraftDao> {
        return MediaDraftDao.find { MediaDraftTable.userId eq userId }.toList()
    }

    /** 기준 시각 전에 만료된 초안 */
    override suspend fun findExpiredDrafts(before: LocalDateTime, limit: Int): List<MediaDraftDao> {
        return MediaDraftDao.find { MediaDraftTable.expiresAt less before }
            .orderBy(MediaDraftTable.id to SortOrder.ASC)
            .limit(limit)
            .toList()
    }
}
