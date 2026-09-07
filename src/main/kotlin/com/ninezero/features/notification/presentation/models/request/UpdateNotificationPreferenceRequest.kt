package com.ninezero.features.notification.presentation.models.request

import kotlinx.serialization.Serializable

@Serializable
data class UpdateNotificationPreferenceRequest(
    val socialEnabled: Boolean? = null,
    val creatorActivityEnabled: Boolean? = null,
    val commerceEnabled: Boolean? = null,
    val chatEnabled: Boolean? = null,
    val systemEnabled: Boolean? = null
)
