package com.ninezero.features.commerce.domain

import com.ninezero.core.common.exception.Errors
import com.ninezero.core.common.exception.OrderNotFoundException
import com.ninezero.core.common.exception.PermissionDeniedException
import com.ninezero.core.database.entities.commerce.OrderDao
import com.ninezero.features.commerce.data.OrderRepository

internal suspend fun requireOwnedOrder(
    orderRepository: OrderRepository,
    orderId: Int,
    userId: Int,
    errorMessage: String,
    onForbidden: (String) -> Exception = ::PermissionDeniedException
): OrderDao {
    val order = orderRepository.findOrderById(orderId)
        ?: throw OrderNotFoundException(Errors.Commerce.Order.ORDER_NOT_FOUND)

    if (!orderRepository.isOrderOwnedBy(orderId, userId)) {
        throw onForbidden(errorMessage)
    }

    return order
}
