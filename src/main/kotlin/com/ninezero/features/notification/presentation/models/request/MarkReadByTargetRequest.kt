package com.ninezero.features.notification.presentation.models.request

import com.ninezero.core.common.config.NotificationType
import kotlinx.serialization.Serializable

@Serializable
data class MarkReadByTargetRequest(
    val type: NotificationType,     // id 없이 type+targetId
    val targetId: Int               // 수신자는 인증 토큰에서 추출
)
