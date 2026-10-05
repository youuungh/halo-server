package com.ninezero.features.commerce.domain

import com.ninezero.core.common.config.Constants
import com.ninezero.core.common.config.OrderStatus
import com.ninezero.core.common.config.PaymentStatus
import com.ninezero.core.common.config.ShippingStatus
import com.ninezero.core.common.config.SubscriptionPlanTier
import com.ninezero.core.common.config.UserRole
import com.ninezero.core.common.exception.ConflictException
import com.ninezero.core.common.exception.Errors
import com.ninezero.core.common.exception.ForbiddenException
import com.ninezero.core.common.exception.UserNotFoundException
import com.ninezero.core.common.util.nowUtc
import com.ninezero.core.database.entities.commerce.OrderDao
import com.ninezero.core.database.entities.commerce.OrderItemDao
import com.ninezero.core.database.entities.commerce.OrderShipmentDao
import com.ninezero.core.database.entities.commerce.ProductDao
import com.ninezero.features.commerce.data.PaymentRepository
import com.ninezero.features.commerce.presentation.models.response.OrderCreatorGroupResponse
import com.ninezero.features.user.data.UserRepository
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.format
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.Json
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days

// 금액 계산
/** 그룹 취소로 되돌릴 금액 */
internal data class GroupCancelAmounts(
    val gross: BigDecimal,
    val couponShare: BigDecimal,
    val net: BigDecimal,
    val pointShare: BigDecimal
) {
    /** 현금 환불액 */
    val cashRefund: BigDecimal get() = (net - pointShare).coerceAtLeast(BigDecimal.ZERO).setScale(0, RoundingMode.DOWN)  // 토스 요청액과 같은 원 단위
}

internal fun calcGroupCancelAmounts(
    creatorId: Int,
    pointsUsed: BigDecimal,
    orderItems: List<OrderItemDao>,
    products: Map<Int, ProductDao>,
    shipmentsBefore: List<OrderShipmentDao>
): GroupCancelAmounts {
    val shipmentsByCreator = shipmentsBefore.associateBy { it.creatorId }
    val gross = calcGroupGross(creatorId, orderItems, products, shipmentsByCreator)
    // 이 그룹에 귀속된 쿠폰 할인
    val couponShare = shipmentsByCreator[creatorId]?.couponDiscount ?: BigDecimal.ZERO
    val net = (gross - couponShare).coerceAtLeast(BigDecimal.ZERO)
    // 포인트는 순액 비율로 배분
    val activeNet = shipmentsBefore
        .filter { it.status != OrderStatus.CANCELLED }
        .sumOf { shipment ->
            (calcGroupGross(shipment.creatorId, orderItems, products, shipmentsByCreator) - shipment.couponDiscount)
                .coerceAtLeast(BigDecimal.ZERO)
        }

    return GroupCancelAmounts(
        gross = gross,
        couponShare = couponShare,
        net = net,
        pointShare = splitDiscount(pointsUsed, net, activeNet)
    )
}

private fun calcGroupGross(
    creatorId: Int,
    orderItems: List<OrderItemDao>,
    products: Map<Int, ProductDao>,
    shipmentsByCreator: Map<Int, OrderShipmentDao>
): BigDecimal {
    val itemSubtotal = orderItems
        .filter { item -> products[item.productId]?.creatorId == creatorId }
        .sumOf { it.price.multiply(BigDecimal(it.quantity)) }

    val shippingFee = shipmentsByCreator[creatorId]?.shippingFee ?: BigDecimal.ZERO
    return itemSubtotal.add(shippingFee)
}

private fun splitDiscount(
    totalDiscount: BigDecimal,
    targetGross: BigDecimal,
    totalGross: BigDecimal
): BigDecimal {
    if (totalDiscount <= BigDecimal.ZERO || targetGross <= BigDecimal.ZERO || totalGross <= BigDecimal.ZERO) {
        return BigDecimal.ZERO
    }

    // 1P 단위, 끝자리는 마지막 남은 그룹이 잔액째 받음
    return totalDiscount
        .multiply(targetGross)
        .divide(totalGross, 0, RoundingMode.HALF_UP)
        .coerceAtMost(totalDiscount)
}

// 배송 상태 병합
internal fun mergeOrderState(
    order: OrderDao,
    shipments: List<OrderShipmentDao>
) {
    val activeShipments = shipments.filter { it.status != OrderStatus.CANCELLED }
    if (activeShipments.isEmpty()) {
        order.status = OrderStatus.CANCELLED
        order.shippingStatus = ShippingStatus.PREPARING
        order.trackingNumber = null
        order.carrier = null
        order.estimatedDeliveryDate = null
        order.actualDeliveryDate = null
        return
    }

    order.status = when {
        activeShipments.all { it.status == OrderStatus.DELIVERED } -> OrderStatus.DELIVERED
        activeShipments.any {
            it.status == OrderStatus.CONFIRMED ||
                it.status == OrderStatus.DELIVERED ||
                !it.trackingNumber.isNullOrBlank() ||
                it.shippingStatus != ShippingStatus.PREPARING
        } -> OrderStatus.CONFIRMED
        else -> OrderStatus.PENDING
    }

    order.shippingStatus = when {
        activeShipments.all { it.shippingStatus == ShippingStatus.DELIVERED } -> ShippingStatus.DELIVERED
        activeShipments.any { it.shippingStatus == ShippingStatus.OUT_FOR_DELIVERY } -> ShippingStatus.OUT_FOR_DELIVERY
        activeShipments.any { it.shippingStatus == ShippingStatus.IN_TRANSIT } -> ShippingStatus.IN_TRANSIT
        activeShipments.any { it.shippingStatus == ShippingStatus.SHIPPED } -> ShippingStatus.SHIPPED
        else -> ShippingStatus.PREPARING
    }

    val singleActiveShipment = activeShipments.singleOrNull()
    order.trackingNumber = singleActiveShipment?.trackingNumber
    order.carrier = singleActiveShipment?.carrier
    order.estimatedDeliveryDate = singleActiveShipment?.estimatedDeliveryDate
    order.actualDeliveryDate = singleActiveShipment?.actualDeliveryDate
}

internal fun mergeOrderStatus(
    order: OrderDao,
    creatorGroups: List<OrderCreatorGroupResponse>
): OrderStatus {
    if (creatorGroups.isEmpty()) return order.status

    val activeGroups = creatorGroups.filter { it.status != OrderStatus.CANCELLED }
    if (activeGroups.isEmpty()) return OrderStatus.CANCELLED
    if (activeGroups.all { it.status == OrderStatus.DELIVERED }) return OrderStatus.DELIVERED

    return if (activeGroups.any {
            it.status == OrderStatus.CONFIRMED ||
                it.status == OrderStatus.DELIVERED ||
                !it.trackingNumber.isNullOrBlank() ||
                it.shippingStatus != ShippingStatus.PREPARING
        }
    ) {
        OrderStatus.CONFIRMED
    } else {
        OrderStatus.PENDING
    }
}

internal fun mergeShippingStatus(
    creatorGroups: List<OrderCreatorGroupResponse>
): ShippingStatus {
    if (creatorGroups.isEmpty()) return ShippingStatus.PREPARING

    val activeGroups = creatorGroups.filter { it.status != OrderStatus.CANCELLED }
    if (activeGroups.isEmpty()) return ShippingStatus.PREPARING

    return when {
        activeGroups.all { it.shippingStatus == ShippingStatus.DELIVERED } -> ShippingStatus.DELIVERED
        activeGroups.any { it.shippingStatus == ShippingStatus.OUT_FOR_DELIVERY } -> ShippingStatus.OUT_FOR_DELIVERY
        activeGroups.any { it.shippingStatus == ShippingStatus.IN_TRANSIT } -> ShippingStatus.IN_TRANSIT
        activeGroups.any { it.shippingStatus == ShippingStatus.SHIPPED } -> ShippingStatus.SHIPPED
        else -> ShippingStatus.PREPARING
    }
}

// 주문 생성 보조
internal fun deserializeSubscriptionTiers(json: String?): Map<Int, SubscriptionPlanTier> {
    if (json.isNullOrBlank()) return emptyMap()
    return try {
        Json.decodeFromString<Map<Int, SubscriptionPlanTier>>(json)
    } catch (_: Exception) {
        emptyMap()
    }
}

/** 날짜8 + 난수8 주문번호 */
internal fun generateOrderNumber(): String {
    val now = nowUtc()  // 충돌 시 DB unique 제약 가드
    val dateStr = now.date.format(LocalDate.Formats.ISO_BASIC)
    val randomStr = (10_000_000..99_999_999).random()
    return "$dateStr$randomStr"
}

internal fun calcEstimatedDeliveryDate(carrier: String): LocalDateTime {
    val daysToAdd = when (carrier) {
        Constants.Commerce.CARRIER_CJ -> Constants.Commerce.DELIVERY_DAYS_CJ
        Constants.Commerce.CARRIER_EPOST -> Constants.Commerce.DELIVERY_DAYS_EPOST
        Constants.Commerce.CARRIER_HANJIN -> Constants.Commerce.DELIVERY_DAYS_HANJIN
        Constants.Commerce.CARRIER_LOTTE -> Constants.Commerce.DELIVERY_DAYS_LOTTE
        Constants.Commerce.CARRIER_LOGEN -> Constants.Commerce.DELIVERY_DAYS_LOGEN
        else -> Constants.Commerce.DELIVERY_DAYS_DEFAULT
    }

    return (Clock.System.now() + daysToAdd.days).toLocalDateTime(TimeZone.UTC)
}

// 접근 판정
internal suspend fun requireCreatorRole(userRepository: UserRepository, creatorId: Int) {
    val user = userRepository.findUserById(creatorId)
        ?: throw UserNotFoundException(Errors.User.USER_INFO_NOT_FOUND)
    if (user.role != UserRole.CREATOR && user.role != UserRole.ADMIN) {
        throw ForbiddenException(Errors.Commerce.Order.CREATOR_PERMISSION_REQUIRED)
    }
}

internal suspend fun requirePaid(paymentRepository: PaymentRepository, orderId: Int) {
    if (paymentRepository.findByOrderId(orderId)?.status != PaymentStatus.COMPLETED) {
        throw ConflictException(Errors.Commerce.Order.ORDER_NOT_PAID)
    }
}
