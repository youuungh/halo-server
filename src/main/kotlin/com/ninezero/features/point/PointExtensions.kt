package com.ninezero.features.point

import com.ninezero.core.common.util.toAmountString
import com.ninezero.core.database.entities.point.PointHistoryDao
import com.ninezero.features.point.presentation.models.response.PointHistoryResponse

fun PointHistoryDao.toPointHistoryResponse(): PointHistoryResponse {
    return PointHistoryResponse(
        id = id.value,
        type = type.name,
        amount = amount.toAmountString(),
        balanceBefore = balanceBefore.toAmountString(),
        balanceAfter = balanceAfter.toAmountString(),
        orderId = orderId,
        description = description,
        expiresAt = expiresAt,
        createdAt = createdAt
    )
}
