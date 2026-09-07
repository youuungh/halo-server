package com.ninezero.features.subscription

import com.ninezero.core.common.util.calcDaysRemaining
import com.ninezero.core.common.util.decodeJsonToList
import com.ninezero.core.common.util.nowUtc
import com.ninezero.core.common.util.toAmountString
import com.ninezero.core.database.entities.subscription.SubscriptionDao
import com.ninezero.core.database.entities.subscription.SubscriptionPlanDao
import com.ninezero.features.subscription.presentation.models.response.SubscriptionPlanResponse
import com.ninezero.features.subscription.presentation.models.response.SubscriptionResponse
import com.ninezero.features.user.presentation.models.response.UserSummaryResponse

fun SubscriptionPlanDao.toPlanResponse(creator: UserSummaryResponse): SubscriptionPlanResponse {
    return SubscriptionPlanResponse(
        id = id.value,
        creatorId = creatorId,
        creatorUsername = creator.username,
        name = name,
        tier = tier,
        description = description,
        price = price.toAmountString(),
        benefits = benefits.decodeJsonToList(),
        subscriberCount = subscriberCount,
        isActive = isActive,
        createdAt = createdAt,
        updatedAt = updatedAt
    )
}

fun SubscriptionDao.toSubscriptionResponse(
    creator: UserSummaryResponse,
    planName: String,
    planPrice: String
): SubscriptionResponse {
    val now = nowUtc()
    val daysRemaining = calcDaysRemaining(expiresAt, now)

    return SubscriptionResponse(
        id = id.value,
        userId = userId,
        creatorId = creatorId,
        creatorUsername = creator.username,
        creatorAvatarUrl = creator.avatarUrl,
        creatorRole = creator.role,
        planId = planId,
        planName = planName,
        planPrice = planPrice,
        status = status,
        startedAt = startedAt,
        expiresAt = expiresAt,
        cancelledAt = cancelledAt,
        autoRenew = autoRenew,
        daysRemaining = daysRemaining,
        createdAt = createdAt
    )
}
