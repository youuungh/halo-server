package com.ninezero.features.commerce.domain

import com.ninezero.core.common.config.OrderStatus
import com.ninezero.core.common.config.ShippingStatus
import com.ninezero.core.common.config.SubscriptionPlanTier
import com.ninezero.core.common.util.TierAccessEvaluator
import com.ninezero.core.common.util.calcEarnByTier
import com.ninezero.core.common.util.decodeJsonToList
import com.ninezero.core.common.util.getEarnRateForTier
import com.ninezero.core.common.util.toAmountString
import com.ninezero.core.database.entities.commerce.OrderDao
import com.ninezero.core.database.entities.commerce.OrderItemDao
import com.ninezero.core.database.entities.commerce.OrderShipmentDao
import com.ninezero.core.database.entities.commerce.PaymentDao
import com.ninezero.core.database.entities.commerce.ProductDao
import com.ninezero.core.database.entities.subscription.SubscriptionDao
import com.ninezero.features.commerce.data.OrderItemWithProduct
import com.ninezero.features.commerce.data.OrderRepository
import com.ninezero.features.commerce.data.OrderWithItems
import com.ninezero.features.commerce.data.PaymentRepository
import com.ninezero.features.commerce.data.ProductRepository
import com.ninezero.features.commerce.data.ReviewRepository
import com.ninezero.features.commerce.presentation.models.response.OrderCreatorGroupResponse
import com.ninezero.features.commerce.presentation.models.response.OrderItemResponse
import com.ninezero.features.commerce.presentation.models.response.OrderResponse
import com.ninezero.features.commerce.toOrderItemResponse
import com.ninezero.features.commerce.toOrderResponse
import com.ninezero.features.subscription.data.SubscriptionPlanRepository
import com.ninezero.features.subscription.data.SubscriptionRepository
import com.ninezero.features.user.data.UserRepository
import java.math.BigDecimal

/** 주문 응답 조립 */
class OrderResponseAssembler(
    private val orderRepository: OrderRepository,
    private val productRepository: ProductRepository,
    private val reviewRepository: ReviewRepository,
    private val userRepository: UserRepository,
    private val paymentRepository: PaymentRepository,
    private val subscriptionRepository: SubscriptionRepository,
    private val planRepository: SubscriptionPlanRepository
) {

    data class OrderItemsWithProducts(
        val orderItems: List<OrderItemDao>,
        val products: Map<Int, ProductDao>,
        val itemsWithProducts: List<OrderItemWithProduct>
    )

    private data class CreatorInfo(
        val username: String,
        val displayName: String?,
        val avatarUrl: String?,
        val role: String
    )

    /** 삭제 상품 포함 조회 */
    suspend fun fetchOrderItemsWithProducts(orderId: Int): OrderItemsWithProducts {
        val orderItems = orderRepository.findOrderItems(orderId)
        val productIds = orderItems.map { it.productId }.distinct()
        val products = productRepository.findProductsByIdsWithDeleted(productIds).associateBy { it.id.value }  // 삭제 상품도 실명·이미지 유지
        val itemsWithProducts = orderItems.map { orderItem ->
            OrderItemWithProduct(
                orderItem = orderItem,
                product = products[orderItem.productId]
            )
        }
        return OrderItemsWithProducts(orderItems, products, itemsWithProducts)
    }

    suspend fun buildOrderResponseInTransaction(
        order: OrderDao,
        itemsWithProducts: List<OrderItemWithProduct>,
        payment: PaymentDao?,
        viewerCreatorId: Int? = null
    ): OrderResponse {
        val creatorIds = itemsWithProducts.mapNotNull { it.product?.creatorId }.distinct()
        // 구독 뱃지·상품 접근은 현재 기준
        val subscriptions = subscriptionRepository.findActiveSubscriptionsByCreators(order.userId, creatorIds)
        return assembleOrderResponse(
            order = order,
            itemsWithProducts = itemsWithProducts,
            payment = payment,
            viewerCreatorId = viewerCreatorId,
            creatorInfoMap = findCreatorInfoMap(creatorIds),
            reviewIdByProduct = reviewRepository.findReviewIdsByOrder(order.id.value),
            shipments = orderRepository.findOrderShipments(order.id.value),
            subscribedCreatorIds = subscriptions.map { it.creatorId }.toSet(),
            creatorTierMap = creatorTierMapOf(subscriptions, findPlanTierMap(subscriptions))
        )
    }

    /** 주문 목록 응답, 관련 행은 주문마다가 아니라 페이지 단위로 한 번씩 조회 */
    suspend fun buildOrderListResponsesInTransaction(
        ordersWithItems: List<OrderWithItems>,
        viewerCreatorId: Int? = null
    ): List<OrderResponse> {
        val orderIds = ordersWithItems.map { it.order.id.value }
        val creatorIds = ordersWithItems.flatMap { it.items }.mapNotNull { it.product?.creatorId }.distinct()
        val paymentsMap = paymentRepository.findByOrderIds(orderIds)
        val creatorInfoMap = findCreatorInfoMap(creatorIds)
        val reviewIdsByOrder = reviewRepository.findReviewIdsByOrders(orderIds)
        val shipmentsByOrder = orderRepository.findOrderShipments(orderIds)
        // 구독 뱃지·상품 접근은 현재 기준, 판매자 목록은 주문마다 구매자가 다름
        val subscriptions = subscriptionRepository
            .findActiveSubscriptionsByCreators(ordersWithItems.map { it.order.userId }.distinct(), creatorIds)
        val subscriptionsByUser = subscriptions.groupBy { it.userId }
        val planTierMap = findPlanTierMap(subscriptions)

        return ordersWithItems.map { (order, itemsWithProducts) ->
            val orderId = order.id.value
            val buyerSubscriptions = subscriptionsByUser[order.userId].orEmpty()
            assembleOrderResponse(
                order = order,
                itemsWithProducts = itemsWithProducts,
                payment = paymentsMap[orderId],
                viewerCreatorId = viewerCreatorId,
                creatorInfoMap = creatorInfoMap,
                reviewIdByProduct = reviewIdsByOrder[orderId].orEmpty(),
                shipments = shipmentsByOrder[orderId].orEmpty(),
                subscribedCreatorIds = buyerSubscriptions.map { it.creatorId }.toSet(),
                creatorTierMap = creatorTierMapOf(buyerSubscriptions, planTierMap)
            )
        }
    }

    private suspend fun findCreatorInfoMap(creatorIds: List<Int>): Map<Int, CreatorInfo> {
        // 값만 추출
        return userRepository.findUsersByIds(creatorIds).associate {
            it.id.value to CreatorInfo(it.username, it.profile?.displayName, it.profile?.avatarUrl, it.role.name)
        }
    }

    // 플랜별 티어
    private suspend fun findPlanTierMap(subscriptions: List<SubscriptionDao>): Map<Int, SubscriptionPlanTier> {
        if (subscriptions.isEmpty()) return emptyMap()
        return planRepository.findPlansByIds(subscriptions.map { it.planId }.distinct())
            .associate { it.id.value to it.tier }
    }

    // 구독 중인 크리에이터별 티어
    private fun creatorTierMapOf(
        subscriptions: List<SubscriptionDao>,
        planTierMap: Map<Int, SubscriptionPlanTier>
    ): Map<Int, SubscriptionPlanTier> = subscriptions.mapNotNull { subscription ->
        planTierMap[subscription.planId]?.let { subscription.creatorId to it }
    }.toMap()

    private fun assembleOrderResponse(
        order: OrderDao,
        itemsWithProducts: List<OrderItemWithProduct>,
        payment: PaymentDao?,
        viewerCreatorId: Int?,
        creatorInfoMap: Map<Int, CreatorInfo>,
        reviewIdByProduct: Map<Int, Int>,
        shipments: List<OrderShipmentDao>,
        subscribedCreatorIds: Set<Int>,
        creatorTierMap: Map<Int, SubscriptionPlanTier>
    ): OrderResponse {
        val itemResponses = itemsWithProducts.map { itemWithProduct ->
            val imageList = itemWithProduct.product?.imageUrls?.decodeJsonToList().orEmpty()
            val reviewId = reviewIdByProduct[itemWithProduct.orderItem.productId]
            val product = itemWithProduct.product
            val creatorInfo = product?.creatorId?.let { creatorInfoMap[it] }
            // 판매자 조회는 자기 상품이라 항상 허용
            val canAccess = viewerCreatorId != null || product == null || TierAccessEvaluator.canAccess(
                viewerId = order.userId,
                ownerId = product.creatorId,
                creatorId = product.creatorId,
                requiredTier = product.requiredTier,
                isSecret = false,
                viewerTier = creatorTierMap[product.creatorId]
            )
            itemWithProduct.orderItem.toOrderItemResponse(
                // 삭제 상품도 실명 유지
                productName = itemWithProduct.product?.name ?: "삭제된 상품",
                productImageUrl = imageList.firstOrNull(),
                creatorId = itemWithProduct.product?.creatorId,
                creatorName = creatorInfo?.displayName ?: creatorInfo?.username,
                creatorAvatarUrl = creatorInfo?.avatarUrl,
                hasReview = reviewId != null,
                reviewId = reviewId,
                isProductActive = itemWithProduct.product?.isActive ?: false,
                requiredTier = product?.requiredTier ?: SubscriptionPlanTier.FREE,
                canAccess = canAccess
            )
        }

        val creatorGroups = buildCreatorGroups(order, itemResponses, creatorInfoMap, shipments, subscribedCreatorIds)
        val visibleGroups = if (viewerCreatorId == null) {
            creatorGroups
        } else {
            creatorGroups.filter { it.creatorId == viewerCreatorId }
        }
        val visibleItems = if (viewerCreatorId == null) {
            itemResponses
        } else {
            itemResponses.filter { it.creatorId == viewerCreatorId }
        }
        val primaryGroup = when {
            viewerCreatorId != null -> visibleGroups.firstOrNull()
            visibleGroups.size == 1 -> visibleGroups.first()
            else -> null
        }

        val orderCouponCode = shipments
            .mapNotNull { it.couponCode }
            .joinToString(",")
            .ifEmpty { null }

        return order.toOrderResponse(
            items = visibleItems,
            status = primaryGroup?.status ?: mergeOrderStatus(order, visibleGroups),
            shippingStatus = primaryGroup?.shippingStatus ?: mergeShippingStatus(visibleGroups),
            payment = payment,
            creatorGroups = visibleGroups,
            shippingFeeOverride = primaryGroup?.shippingFee,
            trackingNumberOverride = primaryGroup?.trackingNumber,
            carrierOverride = primaryGroup?.carrier,
            estimatedDeliveryDateOverride = primaryGroup?.estimatedDeliveryDate,
            actualDeliveryDateOverride = primaryGroup?.actualDeliveryDate,
            couponCode = orderCouponCode
        )
    }

    private fun buildCreatorGroups(
        order: OrderDao,
        itemResponses: List<OrderItemResponse>,
        creatorInfoMap: Map<Int, CreatorInfo>,
        shipments: List<OrderShipmentDao>,
        subscribedCreatorIds: Set<Int>
    ): List<OrderCreatorGroupResponse> {
        val shipmentsByCreator = shipments.associateBy { it.creatorId }
        // 적립은 주문 시점 티어 기준
        val orderTierMap = deserializeSubscriptionTiers(order.subscriptionTiers)

        return itemResponses
            .groupBy { it.creatorId }
            .map { (creatorId, items) ->
                val creatorInfo = creatorId?.let { creatorInfoMap[it] }
                val shipment = creatorId?.let { shipmentsByCreator[it] }
                val subtotal = items.sumOf { it.subtotal.toBigDecimalOrNull() ?: BigDecimal.ZERO }

                val groupStatus = shipment?.status ?: OrderStatus.PENDING
                val earnTier = creatorId?.let { orderTierMap[it] } ?: SubscriptionPlanTier.FREE
                // 취소 그룹은 적립 대상 아님
                val earnPoints = if (groupStatus == OrderStatus.CANCELLED) {
                    BigDecimal.ZERO
                } else {
                    calcEarnByTier(subtotal, earnTier)
                }

                OrderCreatorGroupResponse(
                    creatorId = creatorId,
                    creatorName = creatorInfo?.displayName ?: creatorInfo?.username,
                    creatorAvatarUrl = creatorInfo?.avatarUrl,
                    creatorRole = creatorInfo?.role,
                    status = groupStatus,
                    items = items,
                    subtotal = subtotal.toAmountString(),
                    earnTier = earnTier,
                    earnRate = getEarnRateForTier(earnTier).toPlainString(),
                    earnPoints = earnPoints.toAmountString(),
                    isSubscribed = creatorId != null && creatorId in subscribedCreatorIds,
                    shippingFee = (shipment?.shippingFee ?: BigDecimal.ZERO).toAmountString(),
                    couponDiscount = (shipment?.couponDiscount ?: BigDecimal.ZERO).toAmountString(),
                    trackingNumber = shipment?.trackingNumber,
                    carrier = shipment?.carrier,
                    shippingStatus = shipment?.shippingStatus ?: ShippingStatus.PREPARING,
                    estimatedDeliveryDate = shipment?.estimatedDeliveryDate,
                    actualDeliveryDate = shipment?.actualDeliveryDate,
                    refundAmount = shipment?.refundAmount?.toAmountString(),
                    refundReason = shipment?.refundReason,
                    refundedAt = shipment?.refundCompletedAt
                )
            }
    }
}
