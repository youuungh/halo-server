package com.ninezero.features.subscription.presentation.models.request

import kotlinx.serialization.Serializable

@Serializable
data class SubscribeRequest(
    val planId: Int,
    val autoRenew: Boolean = true
)