package com.ninezero.features.upload.data

import com.ninezero.core.common.config.UploadPurpose
import com.ninezero.core.database.entities.upload.MediaUploadDao
import kotlinx.datetime.LocalDateTime

interface MediaUploadRepository {

    // 업로드 기록 생성/삭제
    suspend fun createUpload(
        userId: Int,
        purpose: UploadPurpose,
        targetId: Int?,
        bucket: String,
        path: String,
        mimeType: String,
        declaredSize: Long,
        expiresAt: LocalDateTime
    ): MediaUploadDao
    suspend fun deleteUploads(ids: Collection<Int>): Int
    suspend fun deleteConsumedUploadsBefore(before: LocalDateTime): Int

    // 업로드 기록 조회
    suspend fun findUploadById(id: Int): MediaUploadDao?
    suspend fun findUploadByIdForUpdate(id: Int): MediaUploadDao?
    suspend fun findExpiredIssuedUploads(before: LocalDateTime, limit: Int): List<MediaUploadDao>

    // 업로드 상태 수정
    suspend fun markConsumed(id: Int, consumedAt: LocalDateTime)
}
