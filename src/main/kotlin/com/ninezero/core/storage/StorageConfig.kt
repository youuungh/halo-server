package com.ninezero.core.storage

import com.ninezero.core.common.config.DotenvConfig
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.storage.Storage

object StorageConfig {
    private val supabaseUrl = DotenvConfig.getRequired("SUPABASE_URL")
    private val supabaseKey = DotenvConfig.getRequired("SUPABASE_SERVICE_ROLE_KEY")

    val client = createSupabaseClient(
        supabaseUrl = supabaseUrl,
        supabaseKey = supabaseKey
    ) {
        install(Storage)
    }

    object Buckets {
        const val AVATARS = "avatars"
        const val PRODUCTS = "products"
        const val POSTS = "posts"
        const val COMMENTS = "comments"
        const val CHAT_IMAGES = "chat-images"
        const val CHAT_VIDEOS = "chat-videos"
        const val CHAT_FILES = "chat-files"
        const val REVIEW_IMAGES = "review-images"

        /** 전체 버킷 목록 */
        val ALL = listOf(  // 버킷 추가 시 여기도 추가
            AVATARS, PRODUCTS, POSTS, COMMENTS,
            CHAT_IMAGES, CHAT_VIDEOS, CHAT_FILES, REVIEW_IMAGES
        )
    }

    object FileSizeLimit {
        const val AVATAR = 10 * 1024 * 1024L
        const val PRODUCT = 10 * 1024 * 1024L
        const val POST = 10 * 1024 * 1024L
        const val COMMENT = 10 * 1024 * 1024L
        const val CHAT_IMAGE = 10 * 1024 * 1024L
        const val CHAT_VIDEO = 50 * 1024 * 1024L
        const val CHAT_FILE = 20 * 1024 * 1024L
        const val REVIEW_IMAGE = 10 * 1024 * 1024L
    }

    object AllowedMimeTypes {
        val VIDEOS = setOf(
            "video/mp4",
            "video/quicktime",
            "video/x-msvideo"
        )
    }
}