package com.ninezero.features.point

import com.ninezero.core.database.entities.point.PointHistoryDao
import com.ninezero.core.common.util.toAmountString
import com.ninezero.features.point.presentation.models.response.PointHistoryResponse

fun PointHistoryDao.toHistoryResponse(): PointHistoryResponse {
    return PointHistoryResponse(
        id = this.id.value,
        type = this.type.name,
        amount = this.amount.toAmountString(),
        balanceBefore = this.balanceBefore.toAmountString(),
        balanceAfter = this.balanceAfter.toAmountString(),
        orderId = this.orderId,
        description = this.description,
        expiresAt = this.expiresAt,
        createdAt = this.createdAt
    )
}
