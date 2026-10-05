package com.ninezero.features.commerce.domain

import com.ninezero.core.common.config.OrderStatus
import com.ninezero.core.common.config.ShippingStatus
import com.ninezero.core.common.exception.*
import com.ninezero.core.common.util.ValidationUtils
import com.ninezero.core.common.util.logger
import com.ninezero.core.common.util.query
import com.ninezero.core.database.entities.commerce.OrderDao
import com.ninezero.core.database.entities.commerce.OrderItemDao
import com.ninezero.core.database.entities.commerce.OrderShipmentDao
import com.ninezero.core.database.entities.commerce.ProductDao
import com.ninezero.core.delivery.DeliveryApiClient
import com.ninezero.core.delivery.DeliveryTraceProgress
import com.ninezero.core.delivery.DeliveryTrackingItem
import com.ninezero.features.commerce.data.OrderRepository
import com.ninezero.features.commerce.data.PaymentRepository
import com.ninezero.features.commerce.presentation.models.request.UpdateShippingRequest
import com.ninezero.features.commerce.presentation.models.request.UpdateShippingStatusRequest
import com.ninezero.features.commerce.presentation.models.response.OrderResponse
import com.ninezero.features.commerce.presentation.models.response.ShippingProgressResponse
import com.ninezero.features.commerce.presentation.models.response.ShippingResponse
import com.ninezero.features.notification.domain.NotificationService
import com.ninezero.features.point.domain.PointEarnService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import java.math.BigDecimal
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days

class OrderShippingService(
    private val orderRepository: OrderRepository,
    private val paymentRepository: PaymentRepository,
    private val orderResponseAssembler: OrderResponseAssembler,
    private val notificationService: NotificationService,
    private val pointEarnService: PointEarnService,
    private val deliveryApiClient: DeliveryApiClient,
    private val coroutineScope: CoroutineScope
) {
    private val logger = logger()

    private companion object {
        /** 주문 생성 기준 배송추적 재등록·보정 기간 */
        const val TRACKING_WINDOW_DAYS = 14

        /** 추적 결과를 담은 웹훅 이벤트 */
        val TRACKING_WEBHOOK_EVENTS = setOf("tracking.polled", "tracking.completed")  // polled는 변화 없어도 매시간 옴
    }

    private data class TrackingRetryRef(
        val orderId: Int,
        val creatorId: Int,
        val carrier: String,
        val trackingNumber: String
    )

    // 배송 정보·상태
    suspend fun updateShippingInfo(
        userId: Int,
        orderId: Int,
        request: UpdateShippingRequest
    ): OrderResponse {
        ValidationUtils.validateTrackingNumber(request.trackingNumber)
        ValidationUtils.validateCarrier(request.carrier)

        val (response, buyerId) = query {
            val order = lockOrder(orderId)  // 취소와 직렬화

            val shipment = orderRepository.findCreatorShipment(orderId, userId)
                ?: throw ForbiddenException(Errors.Commerce.Order.Shipping.ONLY_CREATOR_CAN_UPDATE)
            requirePaid(paymentRepository, orderId)

            if (shipment.status == OrderStatus.CANCELLED) {
                throw ShippingInfoUpdateFailedException(Errors.Commerce.Order.Shipping.SHIPPING_INFO_UPDATE_FAILED)
            }

            if (shipment.shippingStatus != ShippingStatus.PREPARING) {  // PREPARING 상태에서만 가능
                throw ShippingAlreadyStartedException(Errors.Commerce.Order.Shipping.ALREADY_SHIPPED)
            }

            val estimatedDeliveryDate = calcEstimatedDeliveryDate(request.carrier)

            orderRepository.updateShippingInfo(
                orderId = orderId,
                creatorId = userId,
                trackingNumber = request.trackingNumber,
                carrier = request.carrier,
                shippingStatus = ShippingStatus.SHIPPED,
                estimatedDeliveryDate = estimatedDeliveryDate
            ) ?: throw ShippingInfoUpdateFailedException(Errors.Commerce.Order.Shipping.SHIPPING_INFO_UPDATE_FAILED)

            if (shipment.status == OrderStatus.PENDING) {
                orderRepository.updateOrderShipmentStatus(orderId, userId, OrderStatus.CONFIRMED)
                    ?: throw ShippingInfoUpdateFailedException(Errors.Commerce.Order.Shipping.SHIPPING_INFO_UPDATE_FAILED)
            }

            val shipmentsAfter = orderRepository.findOrderShipments(orderId)
            mergeOrderState(order, shipmentsAfter)

            val payment = paymentRepository.findByOrderId(orderId)
            orderResponseAssembler.buildOrderResponseInTransaction(
                order, orderResponseAssembler.fetchOrderItemsWithProducts(orderId).itemsWithProducts, payment, viewerCreatorId = userId
            ) to order.userId
        }

        coroutineScope.launch {  // 커밋 후 알림 발송
            try {
                notificationService.sendShippingStartedNotification(
                    userId = buyerId,
                    orderId = orderId,
                    trackingNumber = request.trackingNumber
                )
            } catch (e: Exception) {
                logger.error("배송 시작 알림 전송 실패: orderId=$orderId, error=${e.message}", e)
            }
        }

        registerTrackingSubscription(
            orderId = orderId,
            creatorId = userId,
            carrier = request.carrier,
            trackingNumber = request.trackingNumber
        )

        return response
    }

    suspend fun getShippingStatus(userId: Int, orderId: Int, creatorId: Int? = null): ShippingResponse {
        // 외부조회 전 기본 응답 구성
        val (baseResponse, orderNumber) = query {
            val order = orderRepository.findOrderById(orderId)
                ?: throw OrderNotFoundException(Errors.Commerce.Order.ORDER_NOT_FOUND)

            val (orderItems, products, _) = orderResponseAssembler.fetchOrderItemsWithProducts(orderId)

            val isCreatorOrBuyer = order.userId == userId || orderItems.any { item ->
                products[item.productId]?.creatorId == userId
            }

            if (!isCreatorOrBuyer) {
                throw ForbiddenException(Errors.Commerce.Order.ORDER_VIEW_PERMISSION_DENIED)
            }

            if (order.userId != userId && creatorId != null && creatorId != userId) {
                throw ForbiddenException(Errors.Commerce.Order.ORDER_VIEW_PERMISSION_DENIED)
            }

            val shipment = when {
                creatorId != null -> orderRepository.findCreatorShipment(orderId, creatorId)
                else -> {
                    val shipments = orderRepository.findOrderShipments(orderId)
                    shipments.firstOrNull { it.creatorId == userId } ?: shipments.firstOrNull()
                }
            }

            ShippingResponse(
                orderId = order.id.value,
                creatorId = shipment?.creatorId,
                trackingNumber = shipment?.trackingNumber,
                carrier = shipment?.carrier,
                shippingStatus = shipment?.shippingStatus ?: ShippingStatus.PREPARING,
                estimatedDeliveryDate = shipment?.estimatedDeliveryDate,
                actualDeliveryDate = shipment?.actualDeliveryDate
            ) to order.orderNumber
        }

        // 외부 HTTP
        val trace = fetchExternalTrace(
            carrier = baseResponse.carrier,
            trackingNumber = baseResponse.trackingNumber,
            orderNumber = orderNumber
        )?.data ?: return baseResponse

        val resolvedStatus = trace.deliveryStatus
            ?.let { toInternalShippingStatus(it, baseResponse.shippingStatus) }
            ?: baseResponse.shippingStatus

        return baseResponse.copy(
            shippingStatus = resolvedStatus,
            courierName = trace.courierName,
            deliveryStatusText = trace.deliveryStatusText,
            lastProgressAt = trace.dateLastProgress,
            queriedAt = trace.queriedAt,
            progresses = trace.progresses.map(::toShippingProgressResponse)
        )
    }

    suspend fun handleDeliveryWebhook(
        rawBody: String,
        signatureHeader: String?,
        timestampHeader: String?
    ): String {
        // 실패 응답 누적 시 엔드포인트 비활성화라 거절도 200
        if (!deliveryApiClient.verifyWebhookSignature(rawBody, signatureHeader, timestampHeader)) {
            logger.warn("배송 webhook 서명 검증 실패로 무시")
            return "ignored"
        }

        val payload = deliveryApiClient.parseWebhookPayload(rawBody) ?: return "ignored"
        if (payload.event !in TRACKING_WEBHOOK_EVENTS) {
            return "ignored"
        }

        return if (applyTrackingItems(payload.items) > 0) "processed" else "ignored"
    }

    private suspend fun applyTrackingItems(items: List<DeliveryTrackingItem>): Int {
        var applied = 0
        for (item in items) {
            try {
                if (applyTrackingItem(item)) applied++
            } catch (e: Exception) {
                logger.error("배송 추적 결과 반영 실패: clientId={}, error={}", item.clientId, e.message, e)
            }
        }
        return applied
    }

    /** 배송그룹에 추적 결과 전진 반영 */
    private suspend fun applyTrackingItem(item: DeliveryTrackingItem): Boolean {
        val (targetOrderId, targetCreatorId) = parseTrackingClientId(item.clientId) ?: run {
            logger.warn("배송 추적 결과의 clientId를 알 수 없어 무시: clientId={}", item.clientId)
            return false
        }
        val trackingNumber = item.trackingNumber ?: return false
        val externalStatus = item.currentStatus ?: return false

        var updatedStatus: ShippingStatus? = null
        var buyerUserId: Int? = null
        var earnedAmount = BigDecimal.ZERO

        query {
            val order = lockOrder(targetOrderId)  // 취소와 직렬화
            val shipment = orderRepository.findCreatorShipment(targetOrderId, targetCreatorId) ?: return@query

            // 취소로 송장이 지워졌거나 다른 송장으로 바뀐 그룹
            if (shipment.trackingNumber != trackingNumber) {
                logger.warn(
                    "송장번호 불일치로 추적 결과 무시: orderId={}, payloadTracking={}, shipmentTracking={}",
                    targetOrderId,
                    trackingNumber,
                    shipment.trackingNumber
                )
                return@query
            }

            val payloadCourier = item.courierCode
            if (!payloadCourier.isNullOrBlank()) {
                val expectedCourier = shipment.carrier
                    ?.let { deliveryApiClient.toCourierCode(it) }
                if (!expectedCourier.isNullOrBlank() &&
                    expectedCourier != payloadCourier.trim().lowercase()
                ) {
                    logger.warn(
                        "택배사 불일치로 추적 결과 무시: orderId={}, payloadCourier={}, orderCarrier={}",
                        targetOrderId,
                        payloadCourier,
                        shipment.carrier
                    )
                    return@query
                }
            }

            val nextStatus = toInternalShippingStatus(externalStatus, shipment.shippingStatus)

            if (nextStatus == shipment.shippingStatus) {
                return@query
            }

            // 배송 상태는 전진 업데이트만 허용
            if (nextStatus < shipment.shippingStatus) {
                logger.info(
                    "역행 배송 상태 추적 결과 무시: orderId={}, current={}, incoming={}",
                    targetOrderId,
                    shipment.shippingStatus,
                    nextStatus
                )
                return@query
            }

            orderRepository.updateShippingStatus(
                orderId = targetOrderId,
                creatorId = targetCreatorId,
                shippingStatus = nextStatus
            )
                ?: throw ShippingStatusUpdateFailedException(Errors.Commerce.Order.Shipping.SHIPPING_STATUS_UPDATE_FAILED)

            if (nextStatus == ShippingStatus.DELIVERED) {
                orderRepository.updateOrderShipmentStatus(
                    orderId = targetOrderId,
                    creatorId = targetCreatorId,
                    status = OrderStatus.DELIVERED
                ) ?: throw ShippingStatusUpdateFailedException(Errors.Commerce.Order.Shipping.SHIPPING_STATUS_UPDATE_FAILED)
            }

            val shipmentsAfter = orderRepository.findOrderShipments(targetOrderId)
            mergeOrderState(order, shipmentsAfter)

            if (nextStatus == ShippingStatus.DELIVERED) {
                val (orderItems, products, _) = orderResponseAssembler.fetchOrderItemsWithProducts(targetOrderId)
                earnedAmount = earnPointsWhenAllDelivered(order, orderItems, products, shipmentsAfter)
            }

            updatedStatus = nextStatus
            buyerUserId = order.userId
        }

        val finalStatus = updatedStatus ?: return false
        val finalBuyerUserId = buyerUserId ?: return false

        notifyShippingStatus(
            status = finalStatus,
            buyerUserId = finalBuyerUserId,
            orderId = targetOrderId,
            creatorId = targetCreatorId,
            earnedAmount = earnedAmount
        )

        return true
    }

    suspend fun updateShippingStatus(
        userId: Int,
        orderId: Int,
        request: UpdateShippingStatusRequest
    ): OrderResponse {
        val (response, buyerId, earnAmount) = query {
            val order = lockOrder(orderId)  // 취소와 직렬화

            val (orderItems, products, itemsWithProducts) = orderResponseAssembler.fetchOrderItemsWithProducts(orderId)

            val shipment = orderRepository.findCreatorShipment(orderId, userId)
                ?: throw ForbiddenException(Errors.Commerce.Order.Shipping.ONLY_CREATOR_CAN_UPDATE)

            if (shipment.status == OrderStatus.CANCELLED) {
                throw ShippingStatusUpdateFailedException(Errors.Commerce.Order.Shipping.SHIPPING_STATUS_UPDATE_FAILED)
            }

            if (shipment.trackingNumber == null) {
                throw ShippingNotStartedException(Errors.Commerce.Order.Shipping.NOT_SHIPPED_YET)
            }

            // 역행 차단
            if (request.status < shipment.shippingStatus) {
                throw ShippingStatusUpdateFailedException(Errors.Commerce.Order.Shipping.SHIPPING_STATUS_UPDATE_FAILED)
            }

            orderRepository.updateShippingStatus(
                orderId = orderId,
                creatorId = userId,
                shippingStatus = request.status
            ) ?: throw ShippingStatusUpdateFailedException(Errors.Commerce.Order.Shipping.SHIPPING_STATUS_UPDATE_FAILED)

            if (request.status == ShippingStatus.DELIVERED) {
                orderRepository.updateOrderShipmentStatus(orderId, userId, OrderStatus.DELIVERED)
                    ?: throw ShippingStatusUpdateFailedException(Errors.Commerce.Order.Shipping.SHIPPING_STATUS_UPDATE_FAILED)
            }

            val shipmentsAfter = orderRepository.findOrderShipments(orderId)
            mergeOrderState(order, shipmentsAfter)

            val earnedAmount = if (request.status == ShippingStatus.DELIVERED) {
                earnPointsWhenAllDelivered(order, orderItems, products, shipmentsAfter)
            } else {
                BigDecimal.ZERO
            }

            val payment = paymentRepository.findByOrderId(orderId)
            Triple(
                orderResponseAssembler.buildOrderResponseInTransaction(order, itemsWithProducts, payment, viewerCreatorId = userId),
                order.userId,
                earnedAmount
            )
        }

        notifyShippingStatus(
            status = request.status,
            buyerUserId = buyerId,
            orderId = orderId,
            creatorId = userId,
            earnedAmount = earnAmount
        )

        return response
    }

    /** 활성 배송그룹이 모두 배송완료면 주문 시점 티어 기준 적립 */
    private fun earnPointsWhenAllDelivered(
        order: OrderDao,
        orderItems: List<OrderItemDao>,
        products: Map<Int, ProductDao>,
        shipmentsAfter: List<OrderShipmentDao>
    ): BigDecimal {
        val cancelledCreatorIds = shipmentsAfter
            .filter { it.status == OrderStatus.CANCELLED }
            .map { it.creatorId }
            .toSet()
        val activeShipments = shipmentsAfter.filter { it.status != OrderStatus.CANCELLED }
        if (!activeShipments.all { it.status == OrderStatus.DELIVERED }) return BigDecimal.ZERO

        // 취소·환불된 그룹은 적립 제외
        val creatorAmountMap = mutableMapOf<Int, BigDecimal>()
        for (orderItem in orderItems) {
            val product = products[orderItem.productId] ?: continue
            if (product.creatorId in cancelledCreatorIds) continue
            val itemTotal = orderItem.price.multiply(BigDecimal(orderItem.quantity))
            creatorAmountMap[product.creatorId] =
                (creatorAmountMap[product.creatorId] ?: BigDecimal.ZERO).add(itemTotal)
        }

        return pointEarnService.earnPointsFromOrder(
            userId = order.userId,
            orderId = order.id.value,
            creatorAmountMap = creatorAmountMap,
            creatorTierMap = deserializeSubscriptionTiers(order.subscriptionTiers)
        )
    }

    /** 배송 상태 전이와 적립 알림 */
    private fun notifyShippingStatus(
        status: ShippingStatus,
        buyerUserId: Int,
        orderId: Int,
        creatorId: Int,
        earnedAmount: BigDecimal
    ) {
        coroutineScope.launch {  // 커밋 후 알림 발송
            try {
                when (status) {
                    ShippingStatus.IN_TRANSIT -> notificationService.sendShippingInTransitNotification(
                        userId = buyerUserId, orderId = orderId, creatorId = creatorId
                    )

                    ShippingStatus.OUT_FOR_DELIVERY -> notificationService.sendOutForDeliveryNotification(
                        userId = buyerUserId, orderId = orderId, creatorId = creatorId
                    )

                    ShippingStatus.DELIVERED -> {
                        notificationService.sendShippingDeliveredNotification(
                            userId = buyerUserId, orderId = orderId, creatorId = creatorId
                        )
                        if (earnedAmount > BigDecimal.ZERO) {
                            notificationService.sendPointEarnedNotification(userId = buyerUserId, orderId = orderId, amount = earnedAmount)
                        }
                    }

                    else -> {}
                }
            } catch (e: Exception) {
                logger.error("배송 상태 알림 전송 실패: orderId=$orderId, error=${e.message}", e)
            }
        }
    }

    // 배송 추적
    suspend fun retryMissingTrackingSubscriptions(): Int {
        // 키 없으면 등록 불가라 경고 없이 건너뜀
        if (!deliveryApiClient.isEnabled) return 0

        val since = (Clock.System.now() - TRACKING_WINDOW_DAYS.days).toLocalDateTime(TimeZone.UTC)
        val refs = query {
            orderRepository.findShipmentsMissingTrackingSubscription(since).mapNotNull { shipment ->
                TrackingRetryRef(
                    orderId = shipment.orderId,
                    creatorId = shipment.creatorId,
                    carrier = shipment.carrier ?: return@mapNotNull null,
                    trackingNumber = shipment.trackingNumber ?: return@mapNotNull null
                )
            }
        }

        var registered = 0
        for ((orderId, creatorId, carrier, trackingNumber) in refs) {
            // 매핑 없는 택배사는 다시 해도 등록 안 됨
            if (deliveryApiClient.toCourierCode(carrier) == null) continue
            try {
                if (registerTrackingSubscription(orderId, creatorId, carrier, trackingNumber)) {
                    registered++
                }
            } catch (e: Exception) {
                logger.warn("배송 추적 재등록 실패: orderId={}, error={}", orderId, e.message)
            }
        }
        return registered
    }

    /** 구독 상세로 웹훅 누락 배송그룹 보정 */
    suspend fun reconcileTrackingSubscriptions(): Int {
        if (!deliveryApiClient.isEnabled) return 0

        val since = (Clock.System.now() - TRACKING_WINDOW_DAYS.days).toLocalDateTime(TimeZone.UTC)
        val requestIds = query {
            orderRepository.findShipmentsInTracking(since).mapNotNull { it.deliverySubscriptionId }.distinct()
        }

        var applied = 0
        for (requestId in requestIds) {
            val items = deliveryApiClient.getTrackingItems(requestId) ?: continue
            applied += applyTrackingItems(items)
        }
        return applied
    }

    /** 웹훅 clientId로 돌아오는 배송그룹 추적 식별자 */
    private fun trackingClientId(orderId: Int, creatorId: Int) = "$orderId:$creatorId"

    private fun parseTrackingClientId(clientId: String?): Pair<Int, Int>? {
        val parts = clientId?.split(':')?.takeIf { it.size == 2 } ?: return null
        val orderId = parts[0].toIntOrNull() ?: return null
        val creatorId = parts[1].toIntOrNull() ?: return null
        return orderId to creatorId
    }

    private suspend fun registerTrackingSubscription(
        orderId: Int,
        creatorId: Int,
        carrier: String,
        trackingNumber: String
    ): Boolean {
        val courierCode = deliveryApiClient.toCourierCode(carrier)
        if (courierCode == null) {
            logger.warn("Delivery 구독 매핑을 지원하지 않는 택배사입니다: {}", carrier)
            return false
        }

        // 송장 공유 그룹은 clientId로 구분
        val requestId = deliveryApiClient.subscribeTracking(
            courierCode = courierCode,
            trackingNumber = trackingNumber,
            clientId = trackingClientId(orderId, creatorId)
        )

        return if (requestId == null) {
            query {
                orderRepository.clearTrackingSubId(orderId, creatorId)
            }
            logger.warn("배송 추적 구독이 생성되지 않았습니다(1시간마다 재시도): orderId={}", orderId)
            false
        } else {
            query {
                orderRepository.setTrackingSubId(orderId, creatorId, requestId)
            }
            logger.info("배송 추적 구독 생성 완료: orderId={}, requestId={}", orderId, requestId)
            true
        }
    }

    private suspend fun fetchExternalTrace(
        carrier: String?,
        trackingNumber: String?,
        orderNumber: String?
    ) = when {
        carrier.isNullOrBlank() -> null
        trackingNumber.isNullOrBlank() -> null
        else -> {
            val courierCode = deliveryApiClient.toCourierCode(carrier)
            if (courierCode == null) {
                logger.warn("Delivery API 매핑을 지원하지 않는 택배사입니다: {}", carrier)
                null
            } else {
                deliveryApiClient.traceSingle(
                    courierCode = courierCode,
                    trackingNumber = trackingNumber,
                    clientId = orderNumber
                )
            }
        }
    }

    private fun toInternalShippingStatus(
        externalStatus: String,
        fallback: ShippingStatus
    ): ShippingStatus {
        return when (externalStatus.uppercase()) {
            "PENDING", "REGISTERED", "PICKUP_READY", "PICKED_UP", "SHIPPED" -> ShippingStatus.SHIPPED
            "IN_TRANSIT" -> ShippingStatus.IN_TRANSIT
            "OUT_FOR_DELIVERY" -> ShippingStatus.OUT_FOR_DELIVERY
            "DELIVERED" -> ShippingStatus.DELIVERED
            else -> fallback
        }
    }

    private fun toShippingProgressResponse(progress: DeliveryTraceProgress): ShippingProgressResponse {
        return ShippingProgressResponse(
            dateTime = progress.dateTime,
            location = progress.location,
            status = progress.status,
            statusCode = progress.statusCode,
            description = progress.description
        )
    }
}
