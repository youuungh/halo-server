package com.ninezero.core.storage

import com.ninezero.core.common.config.Constants
import com.ninezero.core.common.config.DotenvConfig
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.storage.Storage
import kotlin.time.Duration.Companion.minutes

object StorageConfig {
    private val supabaseUrl = DotenvConfig.getRequired("SUPABASE_URL")
    private val supabaseKey = DotenvConfig.getRequired("SUPABASE_SERVICE_ROLE_KEY")

    val client = createSupabaseClient(
        supabaseUrl = supabaseUrl,
        supabaseKey = supabaseKey
    ) {
        install(Storage) {
            transferTimeout = 5.minutes  // 트랜스코딩 영상 재업로드
        }
    }

    /** 전체 버킷 목록 */
    object Buckets {
        const val AVATARS = "avatars"
        const val PRODUCTS = "products"
        const val POSTS = "posts"
        const val COMMENTS = "comments"
        const val CHAT_IMAGES = "chat-images"
        const val CHAT_VIDEOS = "chat-videos"
        const val CHAT_FILES = "chat-files"
        const val REVIEW_IMAGES = "review-images"

        // 버킷 추가 시 여기도 추가
        val ALL = listOf(
            AVATARS, PRODUCTS, POSTS, COMMENTS,
            CHAT_IMAGES, CHAT_VIDEOS, CHAT_FILES, REVIEW_IMAGES
        )
    }

    object FileSizeLimit {
        private const val MB = 1024 * 1024L
        const val AVATAR = Constants.FileSize.MEDIA_MB * MB
        const val PRODUCT = Constants.FileSize.MEDIA_MB * MB
        const val POST = Constants.FileSize.MEDIA_MB * MB
        const val COMMENT = Constants.FileSize.MEDIA_MB * MB
        const val CHAT_IMAGE = Constants.FileSize.MEDIA_MB * MB
        const val CHAT_VIDEO = Constants.FileSize.CHAT_VIDEO_MB * MB
        const val CHAT_FILE = Constants.FileSize.CHAT_FILE_MB * MB
        const val REVIEW_IMAGE = Constants.FileSize.MEDIA_MB * MB
    }

    object AllowedMimeTypes {
        val VIDEOS = setOf(
            "video/mp4",
            "video/quicktime",
            "video/x-msvideo"
        )
    }
}
