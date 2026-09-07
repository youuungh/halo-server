package com.ninezero.features.notification.presentation.models.response

import kotlinx.serialization.Serializable

@Serializable
data class NotificationPreferenceResponse(
    val socialEnabled: Boolean,
    val creatorActivityEnabled: Boolean,
    val commerceEnabled: Boolean,
    val chatEnabled: Boolean,
    val systemEnabled: Boolean
)
