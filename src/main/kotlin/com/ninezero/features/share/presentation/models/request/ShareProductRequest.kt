package com.ninezero.features.share.presentation.models.request

import kotlinx.serialization.Serializable

@Serializable
data class ShareProductRequest(
    val productId: Int,
    val recipientUserIds: List<Int>,
    val message: String? = null,
    val clientTempId: String? = null,  // 재전송 중복 방지용 클라이언트 생성 식별자(카드)
    val messageTempId: String? = null  // 메시지 재전송 중복 방지용, 없으면 clientTempId로
)
