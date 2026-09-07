package com.ninezero.features.chat.presentation.models.response

import kotlinx.serialization.Serializable

@Serializable
data class PostMessageInfo(
    val postId: Int,
    val content: String,
    val authorName: String,
    val authorAvatarUrl: String?,
    val imageUrl: String?,
    val mediaAttachments: List<String> = emptyList(),
    val mediaType: String? = null,
    val thumbnailUrl: String? = null,
    val mediaThumbnails: List<String> = emptyList(),    // 타일별 표시용
    val mediaTypes: List<String> = emptyList(),         // 재생 배지 판별용
    val likeCount: Int = 0,
    val commentCount: Int = 0,
    val shareCount: Int = 0,
    val canAccess: Boolean = true,
    val creatorId: Int? = null,                         // 잠금 시 구독 시트를 열 크리에이터
    val requiredTier: String? = null,                   // 잠금 티어 표시용
    val isHidden: Boolean = false                       // 크리에이터 해제로 숨겨진 글, 삭제와 구분
)
