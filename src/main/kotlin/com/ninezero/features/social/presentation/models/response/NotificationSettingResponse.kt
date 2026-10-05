package com.ninezero.features.social.presentation.models.response

import kotlinx.serialization.Serializable

/** 바뀐 설정 하나만 채움 */
@Serializable
data class NotificationSettingResponse(
    val notifyNewPost: Boolean? = null,
    val notifyNewProduct: Boolean? = null
)
