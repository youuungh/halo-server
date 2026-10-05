package com.ninezero.features.commerce.domain

import com.ninezero.core.common.exception.Errors
import com.ninezero.core.common.exception.ForbiddenException
import com.ninezero.core.common.exception.OrderNotFoundException
import com.ninezero.core.database.entities.commerce.OrderDao
import com.ninezero.core.database.entities.commerce.OrderTable
import com.ninezero.features.commerce.data.OrderRepository

/** 주문 잠금 후 최신 상태 재조회 */
internal fun lockOrder(orderId: Int): OrderDao =
    OrderDao.find { OrderTable.id eq orderId }.forUpdate().singleOrNull()
        ?.also { it.refresh(flush = false) }
        ?: throw OrderNotFoundException(Errors.Commerce.Order.ORDER_NOT_FOUND)

internal suspend fun requireOwnedOrder(
    orderRepository: OrderRepository,
    orderId: Int,
    userId: Int,
    errorMessage: String
): OrderDao {
    val order = orderRepository.findOrderById(orderId)
        ?: throw OrderNotFoundException(Errors.Commerce.Order.ORDER_NOT_FOUND)

    if (order.userId != userId) {
        throw ForbiddenException(errorMessage)
    }

    return order
}
