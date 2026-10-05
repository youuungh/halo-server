package com.ninezero.features.upload.data

import com.ninezero.core.common.config.MediaDraftStatus
import com.ninezero.core.common.config.MediaDraftTargetType
import com.ninezero.core.common.config.MediaType
import com.ninezero.core.database.entities.upload.MediaDraftDao
import kotlinx.datetime.LocalDateTime

interface MediaDraftRepository {

    // 초안 생성/삭제
    suspend fun createDraft(
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
    ): MediaDraftDao
    suspend fun deleteDrafts(ids: Collection<Int>): Int

    // 영상 가공 결과
    suspend fun markReady(
        id: Int,
        width: Int?,
        height: Int?,
        durationMs: Long?,
        thumbnailUrl: String?,
        previewUrl: String?
    ): Boolean
    suspend fun markFailed(id: Int)

    // 초안 조회
    suspend fun findDraftById(id: Int): MediaDraftDao?
    suspend fun findDraftsByIdsForUpdate(ids: Collection<Int>): List<MediaDraftDao>
    suspend fun findDraftsByUser(userId: Int): List<MediaDraftDao>
    suspend fun findExpiredDrafts(before: LocalDateTime, limit: Int): List<MediaDraftDao>
}
