package com.ninezero.core.storage

import com.ninezero.core.common.config.DotenvConfig
import com.ninezero.core.common.config.UploadPurpose
import java.util.UUID

/** 저장소 공개 URL·경로 변환기 */
object StoragePaths {
    private val publicBase = DotenvConfig.getRequired("SUPABASE_URL").trimEnd('/') + "/storage/v1/object/public/"

    fun publicUrl(bucket: String, path: String): String = "$publicBase$bucket/$path"

    /** 서명 URL 경로 대조용 영문·숫자·밑줄 파일명 */
    fun fileName(prefix: String, id: Int, extension: String): String =
        "${prefix}_${id}_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}.$extension"

    /** 서명 URL 업로드 파일의 bucket 내부 경로 */
    fun uploadPath(purpose: UploadPurpose, targetId: Int?, userId: Int, extension: String): String = when (purpose) {
        UploadPurpose.CHAT_VIDEO -> "chats/$targetId/videos/${fileName("chat_video", userId, extension)}"
        UploadPurpose.CHAT_FILE -> "chats/$targetId/files/${fileName("chat_file", userId, extension)}"
        UploadPurpose.POST_VIDEO, UploadPurpose.DRAFT_POST_VIDEO, UploadPurpose.DRAFT_COMMENT_VIDEO -> mediaPath(userId, extension)
    }

    fun mediaPath(userId: Int, extension: String): String = "users/$userId/${fileName("media", userId, extension)}"

    /** 우리 공개 URL의 bucket 내부 경로 */
    fun pathOf(url: String, bucket: String): String? {
        val prefix = "$publicBase$bucket/"
        if (!url.startsWith(prefix)) return null
        // 상위 경로 이동·인코딩 우회 차단
        return url.substring(prefix.length)
            .takeIf { it.isNotEmpty() && ".." !in it && it.none { c -> c == '%' || c == '?' || c == '#' } }
    }

    /** 해당 bucket의 pathPrefix 아래 파일인지 */
    fun isOwned(url: String, bucket: String, pathPrefix: String): Boolean =
        pathOf(url, bucket)?.startsWith(pathPrefix) == true
}
