package com.ninezero.features.commerce.domain

import com.ninezero.core.common.config.*
import com.ninezero.core.common.exception.*
import com.ninezero.core.delivery.DeliveryApiClient
import com.ninezero.core.delivery.DeliveryTraceProgress
import com.ninezero.core.payment.TossCancelResult
import com.ninezero.core.payment.TossPaymentClient
import com.ninezero.core.payment.TossPaymentResponse
import com.ninezero.core.common.util.*
import com.ninezero.core.database.entities.commerce.OrderDao
import com.ninezero.core.database.entities.commerce.OrderItemDao
import com.ninezero.core.database.entities.commerce.OrderShipmentDao
import com.ninezero.core.database.entities.commerce.PaymentDao
import com.ninezero.core.database.entities.commerce.ProductDao
import com.ninezero.features.commerce.data.CartRepository
import com.ninezero.features.commerce.data.OrderItemWithProduct
import com.ninezero.features.commerce.data.OrderRepository
import com.ninezero.features.commerce.data.OrderShipmentData
import com.ninezero.features.commerce.data.PaymentRepository
import com.ninezero.features.commerce.data.ProductRepository
import com.ninezero.features.commerce.data.ReviewRepository
import com.ninezero.features.commerce.presentation.models.request.OrderRequest
import com.ninezero.features.commerce.presentation.models.request.UpdateOrderStatusRequest
import com.ninezero.features.commerce.presentation.models.request.UpdateShippingRequest
import com.ninezero.features.commerce.presentation.models.request.UpdateShippingStatusRequest
import com.ninezero.features.commerce.presentation.models.response.*
import com.ninezero.features.commerce.toOrderItemResponse
import com.ninezero.features.commerce.effectiveOriginalPrice
import com.ninezero.features.commerce.effectivePrice
import com.ninezero.features.commerce.toOrderResponse
import com.ninezero.features.coupon.data.CouponRepository
import com.ninezero.features.coupon.domain.CouponValidationResult
import com.ninezero.features.coupon.domain.CouponRedemptionService
import com.ninezero.features.notification.domain.NotificationService
import com.ninezero.features.point.data.PointRepository
import com.ninezero.features.point.domain.PointEarnService
import com.ninezero.features.point.domain.PointService
import com.ninezero.features.subscription.data.SubscriptionPlanRepository
import com.ninezero.features.subscription.data.SubscriptionRepository
import com.ninezero.features.user.data.UserRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.datetime.*
import kotlinx.serialization.json.Json
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes

class OrderService(
    private val orderRepository: OrderRepository,
    private val productRepository: ProductRepository,
    private val reviewRepository: ReviewRepository,
    private val cartRepository: CartRepository,
    private val userRepository: UserRepository,
    private val paymentRepository: PaymentRepository,
    private val pointRepository: PointRepository,
    private val notificationService: NotificationService,
    private val pointEarnService: PointEarnService,
    private val pointService: PointService,
    private val couponRedemptionService: CouponRedemptionService,
    private val couponRepository: CouponRepository,
    private val subscriptionRepository: SubscriptionRepository,
    private val subscriptionPlanRepository: SubscriptionPlanRepository,
    private val cartService: CartService,
    private val deliveryApiClient: DeliveryApiClient,
    private val tossPaymentClient: TossPaymentClient,
    private val coroutineScope: CoroutineScope
) {
    private val logger = logger()

    private companion object {
        const val BEST_SELLER_LIMIT = 5
        const val TREND_MONTHS = 6

        /** 토스 cancel 대상 provider */
        val TOSS_PROVIDERS = setOf(PaymentProvider.TOSS_PAYMENTS, PaymentProvider.TOSS_BILLING)
    }

    private data class CancelRefundContext(
        val paymentId: Int,
        val paymentKey: String?,
        val provider: PaymentProvider,
        val wasPaid: Boolean,
        val refundAmount: BigDecimal,
        val partialCancelAmount: Long?,
        val idempotencyKey: String
    )

    private data class StalePendingPaymentRef(
        val orderId: Int,
        val buyerUserId: Int,
        val provider: PaymentProvider,
        val transactionId: String?,
        val amount: BigDecimal
    )

    // 미결제 주문 정리
    suspend fun reconcileStalePendingOrders(olderThanMinutes: Long = 30): Int {
        val before = (Clock.System.now() - olderThanMinutes.minutes).toLocalDateTime(TimeZone.UTC)

        val refs = query {
            paymentRepository.findStalePendingPaymentOrderIds(before).mapNotNull { oid ->
                val order = orderRepository.findOrderById(oid) ?: return@mapNotNull null
                val payment = paymentRepository.findByOrderId(oid) ?: return@mapNotNull null
                StalePendingPaymentRef(
                    orderId = oid,
                    buyerUserId = order.userId,
                    provider = payment.provider,
                    transactionId = payment.transactionId,
                    amount = payment.amount
                )
            }
        }

        var processed = 0
        for (ref in refs) {
            try {
                // 토스 결제건만 실제 결제 여부 조회
                val paidToss = if (ref.provider == PaymentProvider.TOSS_PAYMENTS && !ref.transactionId.isNullOrBlank()) {
                    tossPaymentClient.findPaymentByOrderId(ref.transactionId)?.takeIf { it.status == "DONE" }
                } else {
                    null
                }

                if (paidToss != null) {
                    // confirm 커밋 누락 케이스 → 완료 처리
                    completeReconciledPayment(ref, paidToss)
                } else {
                    // 미결제 → 취소
                    cancelOrder(ref.orderId, ref.buyerUserId, "미결제 자동취소")
                }
                processed++
            } catch (e: Exception) {
                logger.error("[정리] PENDING 주문 보정 실패: orderId=${ref.orderId}, error=${e.message}", e)
            }
        }
        return processed
    }

    private suspend fun completeReconciledPayment(ref: StalePendingPaymentRef, toss: TossPaymentResponse) = query {
        val payment = paymentRepository.findByOrderId(ref.orderId) ?: return@query
        if (payment.status != PaymentStatus.PENDING) return@query // 멱등 처리

        // 금액 불일치 시 수동 개입
        if (payment.amount.compareTo(BigDecimal(toss.totalAmount)) != 0) {
            logger.error(
                "[정리] 토스 결제금액 불일치로 완료 보류: orderId={}, server={}, toss={}",
                ref.orderId, payment.amount, toss.totalAmount
            )
            return@query
        }

        paymentRepository.updateTossConfirm(
            paymentId = payment.id.value,
            paymentKey = toss.paymentKey,
            receiptUrl = toss.receipt?.url,
            method = toss.method,
            approvedAt = toss.approvedAt
        )
        paymentRepository.updateStatus(payment.id.value, PaymentStatus.COMPLETED)

        // 결제 완료 → 주문 상품에 해당하는 장바구니 비움
        val productIds = orderRepository.findOrderItems(ref.orderId).map { it.productId }.toSet()
        if (productIds.isNotEmpty()) {
            val cartIds = cartRepository.findUserCart(ref.buyerUserId)
                .filter { it.productId in productIds }
                .map { it.id.value }
            if (cartIds.isNotEmpty()) cartRepository.deleteCartItems(cartIds)
        }
    }

    // 환불

    /** 토스 PG 결제만 cancel 호출 */
    private suspend fun executeTossRefundOrThrow(ctx: CancelRefundContext, cancelReason: String) {
        if (!ctx.wasPaid || ctx.refundAmount <= BigDecimal.ZERO) return
        if (ctx.provider !in TOSS_PROVIDERS || ctx.paymentKey.isNullOrBlank()) return  // MOCK 등 제외

        val result = tossPaymentClient.cancel(
            paymentKey = ctx.paymentKey,
            cancelReason = cancelReason,
            cancelAmount = ctx.partialCancelAmount,
            idempotencyKey = ctx.idempotencyKey
        )
        if (result is TossCancelResult.Failure) {
            logger.error(
                "토스 결제 취소 실패: paymentId={}, code={}, message={}",
                ctx.paymentId, result.code, result.message
            )
            throw PaymentConfirmFailedException(result.message ?: Errors.Commerce.Payment.TOSS_CONFIRM_FAILED)  // 취소 중단
        }
    }

    private suspend fun recordRefund(paymentId: Int, cancelReason: String, refundAmount: BigDecimal) {
        paymentRepository.requestRefund(paymentId, cancelReason, refundAmount)  // 트랜잭션 내부에서만 호출
        paymentRepository.completeRefund(paymentId, nowUtc())
    }

    private suspend fun refundAndCancelCreatorGroup(
        orderId: Int,
        buyerUserId: Int,
        creatorId: Int,
        cancelReason: String
    ) {
        // 사전 검증, 환불액 계산
        val refundCtx = query {  // 권한검증·알림은 호출부 책임
            val order = orderRepository.findOrderById(orderId)
                ?: throw OrderNotFoundException(Errors.Commerce.Order.ORDER_NOT_FOUND)
            val (orderItems, products, _) = fetchOrderItemsWithProducts(orderId)
            val shipment = orderRepository.findCreatorShipment(orderId, creatorId)
                ?: throw OrderNotFoundException(Errors.Commerce.Order.ORDER_NOT_FOUND)
            val targetItems = orderItems.filter { products[it.productId]?.creatorId == creatorId }
            if (targetItems.isEmpty()) {
                throw PermissionDeniedException(Errors.Commerce.Order.ORDER_CANCEL_PERMISSION_DENIED)
            }
            if (!shipment.isCancellableGroup()) {
                throw OrderCancelNotAllowedException(Errors.Commerce.Order.ORDER_CANCEL_NOT_ALLOWED)
            }

            val shipmentsBefore = orderRepository.findOrderShipments(orderId)
            val activeBefore = shipmentsBefore.filter { it.status != OrderStatus.CANCELLED }
            // 마지막 남은 그룹이면 주문 전체 취소
            val willBeFullyCancelled = activeBefore.size <= 1

            paymentRepository.findByOrderId(orderId)?.let { payment ->
                // 현금 환불액 = 그룹 gross - 쿠폰 - 비례 포인트
                val shipmentsByCreatorBefore = shipmentsBefore.associateBy { it.creatorId }
                val cancelledGross = calcGroupGross(creatorId, orderItems, products, shipmentsByCreatorBefore)
                val couponShare = shipment.couponDiscount
                // 포인트는 순액 비율로 배분
                val groupNet = (cancelledGross - couponShare).coerceAtLeast(BigDecimal.ZERO)
                val activeNetBefore = activeBefore.sumOf {
                    (calcGroupGross(it.creatorId, orderItems, products, shipmentsByCreatorBefore) - it.couponDiscount)
                        .coerceAtLeast(BigDecimal.ZERO)
                }
                val pointShare = splitDiscount(order.pointsUsed, groupNet, activeNetBefore)
                val netCash = (groupNet - pointShare).coerceAtLeast(BigDecimal.ZERO)

                CancelRefundContext(
                    paymentId = payment.id.value,
                    paymentKey = payment.paymentKey,
                    provider = payment.provider,
                    wasPaid = payment.status == PaymentStatus.COMPLETED,
                    refundAmount = netCash,
                    partialCancelAmount = if (willBeFullyCancelled) null else netCash.toLong(),
                    idempotencyKey = "CANCEL_${payment.id.value}_G$creatorId"
                )
            }
        }

        // 환불 먼저, 실패 시 취소 중단
        if (refundCtx != null) executeTossRefundOrThrow(refundCtx, cancelReason)

        // 취소 확정
        query {
            val order = orderRepository.findOrderById(orderId)
                ?: throw OrderNotFoundException(Errors.Commerce.Order.ORDER_NOT_FOUND)
            val (orderItems, products, _) = fetchOrderItemsWithProducts(orderId)
            val shipment = orderRepository.findCreatorShipment(orderId, creatorId)
                ?: throw OrderNotFoundException(Errors.Commerce.Order.ORDER_NOT_FOUND)
            // 이미 취소된 그룹이면 재복원 생략
            if (shipment.status == OrderStatus.CANCELLED) {
                return@query
            }

            val targetItems = orderItems.filter { item ->
                products[item.productId]?.creatorId == creatorId
            }

            val shipmentsBefore = orderRepository.findOrderShipments(orderId)
            val activeShipmentsBefore = shipmentsBefore.filter { it.status != OrderStatus.CANCELLED }
            val shipmentsByCreatorBefore = shipmentsBefore.associateBy { it.creatorId }
            val cancelledGross = calcGroupGross(creatorId, orderItems, products, shipmentsByCreatorBefore)
            // 이 그룹에 귀속된 쿠폰 할인
            val couponDiscountShare = shipment.couponDiscount
            val groupNet = (cancelledGross - couponDiscountShare).coerceAtLeast(BigDecimal.ZERO)
            val activeNetBefore = activeShipmentsBefore.sumOf { activeShipment ->
                (calcGroupGross(activeShipment.creatorId, orderItems, products, shipmentsByCreatorBefore) - activeShipment.couponDiscount)
                    .coerceAtLeast(BigDecimal.ZERO)
            }
            val pointRefund = splitDiscount(order.pointsUsed, groupNet, activeNetBefore)

            orderRepository.updateOrderShipmentStatus(orderId, creatorId, OrderStatus.CANCELLED)
                ?: throw OrderStatusUpdateFailedException(Errors.Commerce.Order.ORDER_STATUS_UPDATE_FAILED)

            shipment.deliverySubscriptionId = null
            shipment.trackingNumber = null
            shipment.carrier = null
            shipment.estimatedDeliveryDate = null
            shipment.actualDeliveryDate = null
            shipment.shippingStatus = ShippingStatus.PREPARING

            // 결제완료였을 때만 환불정보 기록
            if (refundCtx != null && refundCtx.wasPaid) {
                // 마지막 그룹은 잔여 흡수값을 환불액으로 사용
                shipment.refundAmount = if (refundCtx.partialCancelAmount == null) {
                    val priorRefunded = shipmentsBefore
                        .filter { it.status == OrderStatus.CANCELLED }
                        .sumOf { it.refundAmount ?: BigDecimal.ZERO }
                    val paidAmount = paymentRepository.findByOrderId(orderId)?.amount ?: refundCtx.refundAmount
                    (paidAmount - priorRefunded).coerceAtLeast(BigDecimal.ZERO)
                } else {
                    refundCtx.refundAmount
                }
                shipment.refundReason = cancelReason
                shipment.refundCompletedAt = nowUtc()
            }

            targetItems.forEach { item ->
                val product = products[item.productId]
                    ?: throw ProductNotFoundException(item.productId)
                product.stock += item.quantity
            }

            if (pointRefund > BigDecimal.ZERO) {
                pointService.refundPointsInTransaction(userId = buyerUserId, amount = pointRefund, orderId = orderId)
            }

            order.pointsUsed = (order.pointsUsed - pointRefund).coerceAtLeast(BigDecimal.ZERO)
            order.couponDiscount = (order.couponDiscount - couponDiscountShare).coerceAtLeast(BigDecimal.ZERO)
            order.totalPrice = (order.totalPrice - cancelledGross + pointRefund + couponDiscountShare)
                .coerceAtLeast(BigDecimal.ZERO)

            val shipmentsAfter = orderRepository.findOrderShipments(orderId)
            mergeOrderState(order, shipmentsAfter)

            // 그룹 취소 즉시 쿠폰 복구
            shipment.couponCode?.let { code ->
                couponRedemptionService.refundCouponInTransaction(userId = buyerUserId, couponCode = code)
            }

            if (shipmentsAfter.all { it.status == OrderStatus.CANCELLED }) {
                val payment = paymentRepository.findByOrderId(orderId)
                if (payment != null) {
                    if (refundCtx != null && refundCtx.wasPaid) {
                        // 전체 취소 확정 → 결제 전액 REFUNDED
                        recordRefund(payment.id.value, cancelReason, payment.amount)
                    } else {
                        paymentRepository.updateStatus(payment.id.value, PaymentStatus.CANCELLED)
                    }
                }
            }
        }
    }

    /** 장바구니로 주문 생성 */
    suspend fun createOrderFromCart(userId: Int, request: OrderRequest): OrderResponse {
        ValidationUtils.validateShippingAddress(request.shippingAddress)
        ValidationUtils.validateShippingPhone(request.shippingPhone)
        ValidationUtils.validateShippingName(request.shippingName)

        val pointsUsed = if (!request.pointsToUse.isNullOrBlank()) {
            BigDecimal(request.pointsToUse)
        } else {
            BigDecimal.ZERO
        }

        val orderNumber = generateOrderNumber()

        val (order, creatorIds) = query {
            val allCartItems = cartRepository.findUserCart(userId)
            if (allCartItems.isEmpty()) {
                throw EmptyCartException(Errors.Commerce.Cart.EMPTY_CART)
            }

            // cartItemIds 지정 시 해당 항목만
            val cartItems = if (request.cartItemIds != null) {
                if (request.cartItemIds.isEmpty()) {
                    throw EmptyCartException(Errors.Commerce.Cart.EMPTY_CART)
                }
                val allCartItemIds = allCartItems.map { it.id.value }.toSet()
                val invalidIds = request.cartItemIds.filter { it !in allCartItemIds }
                if (invalidIds.isNotEmpty()) {
                    throw EmptyCartException(Errors.Commerce.Cart.CART_ITEM_NOT_FOUND)
                }
                val selectedIds = request.cartItemIds.toSet()
                allCartItems.filter { it.id.value in selectedIds }
            } else {
                allCartItems
            }

            val creatorIdSet = mutableSetOf<Int>()
            val orderItems = mutableListOf<OrderItemData>()
            var totalPrice = BigDecimal.ZERO

            val productCreatorMap = mutableMapOf<Int, Int>()
            val creatorAmountMap = mutableMapOf<Int, BigDecimal>()

            for (cartItem in cartItems) {
                val product = cartService.validatePurchasable(
                    userId = userId,
                    productId = cartItem.productId,
                    requestedQuantity = cartItem.quantity
                )

                creatorIdSet.add(product.creatorId)
                productCreatorMap[product.id.value] = product.creatorId

                // 타임딜 딜가 청구
                val effPrice = product.effectivePrice()
                val effOriginal = product.effectiveOriginalPrice()

                val itemTotal = effPrice.multiply(BigDecimal(cartItem.quantity))
                creatorAmountMap[product.creatorId] = (creatorAmountMap[product.creatorId] ?: BigDecimal.ZERO).add(itemTotal)

                val originalPrice = effOriginal?.let { op ->
                    if (op != effPrice) op.toString() else null
                }

                orderItems.add(
                    OrderItemData(
                        productId = product.id.value,
                        quantity = cartItem.quantity,
                        price = effPrice.toString(),
                        originalPrice = originalPrice
                    )
                )

                totalPrice = totalPrice.add(itemTotal)
            }

            val creatorIds = creatorIdSet.toList()
            val subscriptions = subscriptionRepository.findActiveSubscriptionsByCreators(userId, creatorIds)
            val plans = subscriptionPlanRepository.findPlansByIds(subscriptions.map { it.planId }.distinct())
                .associateBy { it.id.value }
            val creatorTierMap = subscriptions.associate { sub ->
                sub.creatorId to (plans[sub.planId]?.tier ?: SubscriptionPlanTier.FREE)
            }

            // 크리에이터별 배송비, 구독 티어는 무료
            val shippingFeeByCreator = mutableMapOf<Int, BigDecimal>()
            for ((creatorId, amount) in creatorAmountMap) {
                val tier = creatorTierMap[creatorId]
                shippingFeeByCreator[creatorId] = if (tier == SubscriptionPlanTier.TIER1 || tier == SubscriptionPlanTier.TIER2) {
                    BigDecimal.ZERO
                } else if (amount >= Constants.Commerce.FREE_SHIPPING_THRESHOLD) {
                    BigDecimal.ZERO
                } else {
                    Constants.Commerce.DEFAULT_SHIPPING_FEE
                }
            }
            var totalShippingFee = shippingFeeByCreator.values.fold(BigDecimal.ZERO) { acc, fee -> acc.add(fee) }

            if (pointsUsed > BigDecimal.ZERO) {
                val point = pointRepository.findByUserId(userId)
                    ?: throw PointAccountNotFoundException(Errors.Point.POINT_ACCOUNT_NOT_FOUND)

                val (isValid, errorMessage) = isValidPointAmount(pointsUsed, point.balance, totalPrice)
                if (!isValid) {
                    throw InvalidPointException(errorMessage!!)
                }
            }

            var couponDiscount = BigDecimal.ZERO
            val couponDiscountByCreator = mutableMapOf<Int, BigDecimal>()
            val couponCodeByCreator = mutableMapOf<Int, String>()
            val appliedCouponCodes = mutableListOf<String>()
            val freeShippingCouponCodes = mutableListOf<String>()
            if (!request.couponCodes.isNullOrEmpty()) {
                val productIdList = orderItems.map { it.productId }

                // 중복 코드 제거
                for (code in request.couponCodes.distinct()) {
                    if (code.isBlank()) continue

                    val validationResult = couponRedemptionService.calcDiscount(
                        userId = userId,
                        couponCode = code,
                        orderAmount = totalPrice,
                        productIds = productIdList,
                        shippingFeeByCreator = shippingFeeByCreator,
                        productCreatorMap = productCreatorMap,
                        creatorAmountMap = creatorAmountMap
                    )

                    var discount = when (validationResult) {
                        is CouponValidationResult.Invalid ->
                            throw CouponValidationException(validationResult.errorMessage)
                        is CouponValidationResult.Valid -> validationResult.discount
                    }
                    appliedCouponCodes.add(code)
                    val coupon = couponRepository.findByCode(code)

                    // 쿠폰은 크리에이터당 하나만
                    if (coupon != null) {
                        if (couponCodeByCreator.containsKey(coupon.creatorId)) {
                            throw CouponValidationException(Errors.Coupon.COUPON_DUPLICATE_CREATOR)
                        }
                        couponCodeByCreator[coupon.creatorId] = code
                    }

                    // FREE_SHIPPING 쿠폰인 경우 배송비 차감
                    if (discount > BigDecimal.ZERO && discount <= totalShippingFee) {
                        if (coupon != null && coupon.type == CouponType.FREE_SHIPPING) {
                            freeShippingCouponCodes.add(code)
                            shippingFeeByCreator[coupon.creatorId] = BigDecimal.ZERO
                            totalShippingFee = totalShippingFee.subtract(discount)
                            discount = BigDecimal.ZERO
                        }
                    }

                    couponDiscount = couponDiscount.add(discount)
                    // 크리에이터 그룹에 할인 귀속
                    if (discount > BigDecimal.ZERO && coupon != null) {
                        couponDiscountByCreator[coupon.creatorId] =
                            (couponDiscountByCreator[coupon.creatorId] ?: BigDecimal.ZERO).add(discount)
                    }
                }
            }
            val finalAmount = totalPrice.subtract(pointsUsed).subtract(couponDiscount).add(totalShippingFee)
            if (finalAmount < BigDecimal.ZERO) {
                throw InvalidOrderAmountException(Errors.Commerce.Order.INVALID_FINAL_AMOUNT)
            }

            val subscriptionTiersJson = if (creatorTierMap.isNotEmpty()) {
                Json.encodeToString(
                    creatorTierMap.map { (k, v) -> k.toString() to v.name }.toMap()
                )
            } else null

            val createdOrder = orderRepository.createOrder(
                userId = userId,
                orderNumber = orderNumber,
                totalPrice = finalAmount,
                shippingAddress = request.shippingAddress,
                shippingPhone = request.shippingPhone,
                shippingName = request.shippingName,
                memo = request.memo,
                items = orderItems,
                pointsUsed = pointsUsed,
                couponDiscount = couponDiscount,
                shippingFee = totalShippingFee,
                subscriptionTiers = subscriptionTiersJson,
                shipments = shippingFeeByCreator.map { (creatorId, fee) ->
                    OrderShipmentData(
                        creatorId = creatorId,
                        shippingFee = fee,
                        couponDiscount = couponDiscountByCreator[creatorId] ?: BigDecimal.ZERO,
                        couponCode = couponCodeByCreator[creatorId]
                    )
                }
            )

            paymentRepository.createPayment(
                orderId = createdOrder.id.value,
                provider = request.paymentProvider,
                amount = finalAmount
            )

            if (pointsUsed > BigDecimal.ZERO) {
                pointService.usePointsInTransaction(
                    userId = userId,
                    amount = pointsUsed,
                    orderId = createdOrder.id.value,
                    orderAmount = totalPrice
                )
            }

            for (code in appliedCouponCodes) {
                val isFreeShipping = code in freeShippingCouponCodes
                if (couponDiscount > BigDecimal.ZERO || isFreeShipping) {
                    couponRedemptionService.useCouponInTransaction(
                        userId = userId,
                        couponCode = code,
                        orderId = createdOrder.id.value
                    )
                }
            }

            Pair(createdOrder, creatorIdSet)
        }

        coroutineScope.launch {  // 커밋 후 알림 발송
            try {
                notificationService.sendOrderCreatedNotification(
                    userId = userId,
                    orderId = order.id.value,
                    orderNumber = orderNumber
                )

                for (creatorId in creatorIds) {
                    notificationService.sendOrderReceivedNotification(
                        creatorId = creatorId,
                        buyerId = userId,
                        orderId = order.id.value,
                        orderNumber = orderNumber
                    )
                }
            } catch (e: Exception) {
                logger.error("주문 생성 알림 전송 실패: orderId=${order.id.value}, error=${e.message}", e)
            }
        }

        return query {
            val createdOrderItems = orderRepository.findOrderItems(order.id.value)
            val responseProductIds = createdOrderItems.map { it.productId }.distinct()
            val responseProducts = productRepository.findProductsByIds(responseProductIds).associateBy { it.id.value }
            val itemsWithProducts = createdOrderItems.map { orderItem ->
                OrderItemWithProduct(
                    orderItem = orderItem,
                    product = responseProducts[orderItem.productId]
                )
            }
            val paymentData = paymentRepository.findByOrderId(order.id.value)
            buildOrderResponse(order, itemsWithProducts, paymentData)
        }
    }

    // 주문 조회
    suspend fun getOrderById(orderId: Int, userId: Int): OrderResponse {
        return query {
            val order = requireOwnedOrder(orderRepository, orderId, userId, Errors.Commerce.Order.ORDER_VIEW_PERMISSION_DENIED)
            val data = fetchOrderItemsWithProducts(orderId)
            val payment = paymentRepository.findByOrderId(orderId)
            buildOrderResponse(order, data.itemsWithProducts, payment)
        }
    }

    suspend fun getMyOrders(
        userId: Int,
        page: Int = 1,
        limit: Int = Constants.DEFAULT_PAGE_LIMIT
    ): OrderListResponse {
        val (validPage, validLimit) = validatePaginationParams(page, limit)

        return query {
            val ordersWithItems = orderRepository.findUserOrdersWithItems(userId, validPage, validLimit)
            val total = orderRepository.countTotalOrders(userId)

            val orderIds = ordersWithItems.map { it.order.id.value }
            val paymentsMap = paymentRepository.findByOrderIds(orderIds)

            // 결제완료·환불만 노출
            val visibleOrders = ordersWithItems.filter { orderWithItems ->
                val payment = paymentsMap[orderWithItems.order.id.value]
                payment != null &&
                    payment.status != PaymentStatus.PENDING &&
                    payment.status != PaymentStatus.CANCELLED
            }

            val orderResponses = visibleOrders.map { orderWithItems ->
                val payment = paymentsMap[orderWithItems.order.id.value]
                buildOrderResponse(orderWithItems.order, orderWithItems.items, payment)
            }

            val pagination = PaginationInfo(validPage, validLimit, total)
            createPagedResponse(orderResponses, pagination)
        }
    }

    // 주문 취소

    /** 결제 전 PENDING 전용 취소 */
    suspend fun cancelOrder(orderId: Int, userId: Int, cancelReason: String = "구매자 요청"): OrderResponse {
        // 사전 검증 + 환불 컨텍스트 캡처
        val refundCtx = query {  // 결제완료 주문은 cancelOrderGroup 사용
            val order = requireOwnedOrder(orderRepository, orderId, userId, Errors.Commerce.Order.ORDER_CANCEL_PERMISSION_DENIED)

            val shipments = orderRepository.findOrderShipments(orderId)
            if (shipments.isNotEmpty()) {
                if (shipments.any { !it.isCancellableGroup() }) {
                    throw OrderCancelNotAllowedException(Errors.Commerce.Order.ORDER_CANCEL_NOT_ALLOWED)
                }
            } else if (order.status != OrderStatus.PENDING && order.status != OrderStatus.CONFIRMED) {
                throw OrderCancelNotAllowedException(Errors.Commerce.Order.ORDER_CANCEL_NOT_ALLOWED)
            }

            paymentRepository.findByOrderId(orderId)?.let {
                CancelRefundContext(
                    paymentId = it.id.value,
                    paymentKey = it.paymentKey,
                    provider = it.provider,
                    wasPaid = it.status == PaymentStatus.COMPLETED,
                    refundAmount = it.amount,
                    partialCancelAmount = null,
                    idempotencyKey = "CANCEL_${it.id.value}"
                )
            }
        }

        // 환불 먼저, 실패 시 취소 중단
        if (refundCtx != null) executeTossRefundOrThrow(refundCtx, cancelReason)

        // 취소 확정
        val (orderData, creatorIds) = query {
            requireOwnedOrder(orderRepository, orderId, userId, Errors.Commerce.Order.ORDER_CANCEL_PERMISSION_DENIED)

            orderRepository.cancelOrder(orderId)

            val payment = paymentRepository.findByOrderId(orderId)
            if (payment != null) {
                if (refundCtx != null && refundCtx.wasPaid) {
                    recordRefund(payment.id.value, cancelReason, refundCtx.refundAmount)
                } else {
                    paymentRepository.updateStatus(payment.id.value, PaymentStatus.CANCELLED)
                }
            }

            val order = orderRepository.findOrderById(orderId)!!

            if (order.pointsUsed > BigDecimal.ZERO) {
                pointService.refundPointsInTransaction(userId = userId, amount = order.pointsUsed, orderId = orderId)
            }

            orderRepository.findOrderShipments(orderId)
                .mapNotNull { it.couponCode }
                .forEach { code ->
                    couponRedemptionService.refundCouponInTransaction(userId = userId, couponCode = code)
                }

            val (items, products, itemsWithProducts) = fetchOrderItemsWithProducts(orderId)
            val creatorIdList = items.mapNotNull { products[it.productId]?.creatorId }.distinct()

            // 취소·환불 반영된 최신 주문·결제 재조회
            val updatedOrder = orderRepository.findOrderById(orderId)!!
            val updatedPayment = paymentRepository.findByOrderId(orderId)

            Triple(updatedOrder, itemsWithProducts, updatedPayment) to creatorIdList
        }

        coroutineScope.launch {  // 커밋 후 알림 발송
            try {
                for (creatorId in creatorIds) {
                    notificationService.sendOrderCancelledNotification(
                        recipientId = creatorId,
                        cancelledById = userId,
                        orderId = orderId,
                        recipientIsSeller = true
                    )
                }
            } catch (e: Exception) {
                logger.error("주문 취소 알림 전송 실패: orderId=$orderId, error=${e.message}", e)
            }
        }

        return buildOrderResponse(orderData.first, orderData.second, orderData.third)
    }

    /** 구매자 그룹 취소 */
    suspend fun cancelOrderGroup(
        orderId: Int,
        userId: Int,
        creatorId: Int,
        cancelReason: String = "구매자 요청"
    ): OrderResponse {
        query {
            requireOwnedOrder(orderRepository, orderId, userId, Errors.Commerce.Order.ORDER_CANCEL_PERMISSION_DENIED)
        }

        refundAndCancelCreatorGroup(orderId, buyerUserId = userId, creatorId = creatorId, cancelReason = cancelReason)  // 환불 먼저 성공해야 커밋

        coroutineScope.launch {
            try {
                notificationService.sendOrderCancelledNotification(
                    recipientId = creatorId,
                    cancelledById = userId,
                    orderId = orderId,
                    recipientIsSeller = true
                )
            } catch (e: Exception) {
                logger.error("주문 그룹 취소 알림 전송 실패: orderId=$orderId, error=${e.message}", e)
            }
        }

        val (order, itemsWithProducts, payment) = fetchOrderResponseData(orderId)
        return buildOrderResponse(order, itemsWithProducts, payment)
    }

    // 판매자 주문 처리
    suspend fun getCreatorOrders(
        creatorId: Int,
        page: Int = 1,
        limit: Int = Constants.DEFAULT_PAGE_LIMIT,
        filter: String? = null
    ): OrderListResponse {
        return query {
            val user = userRepository.findUserById(creatorId)
                ?: throw UserNotFoundException(Errors.User.USER_INFO_NOT_FOUND)

            if (user.role != UserRole.CREATOR && user.role != UserRole.ADMIN) {
                throw PermissionDeniedException(Errors.Commerce.Order.CREATOR_PERMISSION_REQUIRED)
            }

            val (validPage, validLimit) = validatePaginationParams(page, limit)

            val ordersWithItems = orderRepository.findCreatorOrdersWithItems(creatorId, validPage, validLimit, filter)
            val total = orderRepository.countCreatorOrders(creatorId, filter)

            val orderIds = ordersWithItems.map { it.order.id.value }
            val paymentsMap = paymentRepository.findByOrderIds(orderIds)

            // 결제 미완료 주문은 판매자 목록에서도 제외
            val visibleOrders = ordersWithItems.filter { orderWithItems ->
                val payment = paymentsMap[orderWithItems.order.id.value]
                payment != null && payment.status != PaymentStatus.PENDING
            }

            val orderResponses = visibleOrders.map { orderWithItems ->
                val payment = paymentsMap[orderWithItems.order.id.value]
                buildOrderResponse(
                    order = orderWithItems.order,
                    itemsWithProducts = orderWithItems.items,
                    payment = payment,
                    viewerCreatorId = creatorId
                )
            }

            val pagination = PaginationInfo(validPage, validLimit, total)
            createPagedResponse(orderResponses, pagination)
        }
    }

    suspend fun updateOrderStatus(
        orderId: Int,
        creatorId: Int,
        request: UpdateOrderStatusRequest
    ): OrderResponse {
        // 권한 검증 + buyerUserId 확보
        val buyerUserId = query {
            val user = userRepository.findUserById(creatorId)
                ?: throw UserNotFoundException(Errors.User.USER_INFO_NOT_FOUND)
            if (user.role != UserRole.CREATOR && user.role != UserRole.ADMIN) {
                throw PermissionDeniedException(Errors.Commerce.Order.CREATOR_PERMISSION_REQUIRED)
            }
            val order = orderRepository.findOrderById(orderId)
                ?: throw OrderNotFoundException(Errors.Commerce.Order.ORDER_NOT_FOUND)
            val (orderItems, products, _) = fetchOrderItemsWithProducts(orderId)
            if (orderItems.none { products[it.productId]?.creatorId == creatorId }) {
                throw PermissionDeniedException(Errors.Commerce.Order.ORDER_UPDATE_PERMISSION_DENIED)
            }
            orderRepository.findCreatorShipment(orderId, creatorId)
                ?: throw PermissionDeniedException(Errors.Commerce.Order.ORDER_UPDATE_PERMISSION_DENIED)
            order.userId
        }

        when (request.status) {
            OrderStatus.CONFIRMED -> query {
                val shipment = orderRepository.findCreatorShipment(orderId, creatorId)
                    ?: throw PermissionDeniedException(Errors.Commerce.Order.ORDER_UPDATE_PERMISSION_DENIED)
                if (shipment.status == OrderStatus.CANCELLED) {
                    throw OrderStatusUpdateFailedException(Errors.Commerce.Order.ORDER_STATUS_UPDATE_FAILED)
                }
                orderRepository.updateOrderShipmentStatus(orderId, creatorId, OrderStatus.CONFIRMED)
                    ?: throw OrderStatusUpdateFailedException(Errors.Commerce.Order.ORDER_STATUS_UPDATE_FAILED)
                val order = orderRepository.findOrderById(orderId)
                    ?: throw OrderNotFoundException(Errors.Commerce.Order.ORDER_NOT_FOUND)
                mergeOrderState(order, orderRepository.findOrderShipments(orderId))
            }

            // 환불 먼저
            OrderStatus.CANCELLED ->
                refundAndCancelCreatorGroup(orderId, buyerUserId = buyerUserId, creatorId = creatorId, cancelReason = "판매자 취소")

            else -> throw OrderStatusUpdateFailedException(Errors.Commerce.Order.ORDER_STATUS_UPDATE_FAILED)  // 그 외 상태는 예외
        }

        coroutineScope.launch {  // 커밋 후 알림 발송
            try {
                when (request.status) {
                    OrderStatus.CONFIRMED -> notificationService.sendOrderConfirmedNotification(
                        userId = buyerUserId, creatorId = creatorId, orderId = orderId
                    )
                    OrderStatus.CANCELLED -> notificationService.sendOrderCancelledNotification(
                        recipientId = buyerUserId, cancelledById = creatorId, orderId = orderId
                    )
                    else -> {}
                }
            } catch (e: Exception) {
                logger.error("주문 상태 변경 알림 전송 실패: orderId=$orderId, error=${e.message}", e)
            }
        }

        val (order, itemsWithProducts, payment) = fetchOrderResponseData(orderId)
        return buildOrderResponse(order, itemsWithProducts, payment, viewerCreatorId = creatorId)
    }

    // 배송
    suspend fun updateShippingInfo(
        userId: Int,
        orderId: Int,
        request: UpdateShippingRequest
    ): OrderResponse {
        ValidationUtils.validateTrackingNumber(request.trackingNumber)
        ValidationUtils.validateCarrier(request.carrier)

        val (orderResponseData, buyerUserId) = query {
            val order = orderRepository.findOrderById(orderId)
                ?: throw OrderNotFoundException(Errors.Commerce.Order.ORDER_NOT_FOUND)

            val (orderItems, products, _) = fetchOrderItemsWithProducts(orderId)

            val isCreatorOfAnyProduct = orderItems.any { item ->
                products[item.productId]?.creatorId == userId
            }

            if (!isCreatorOfAnyProduct) {
                throw PermissionDeniedException(Errors.Commerce.Order.Shipping.ONLY_CREATOR_CAN_UPDATE)
            }

            val shipment = orderRepository.findCreatorShipment(orderId, userId)
                ?: throw ShippingInfoUpdateFailedException(Errors.Commerce.Order.Shipping.SHIPPING_INFO_UPDATE_FAILED)

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

            val paymentData = paymentRepository.findByOrderId(orderId)
            val updatedOrder = orderRepository.findOrderById(orderId)
                ?: throw OrderNotFoundException(Errors.Commerce.Order.ORDER_NOT_FOUND)
            val itemsWithProducts = orderItems.map { item ->
                OrderItemWithProduct(
                    orderItem = item,
                    product = products[item.productId]
                )
            }

            val orderNumber = updatedOrder.orderNumber

            Triple(updatedOrder, itemsWithProducts, paymentData) to Pair(order.userId, orderNumber)
        }

        val (buyerId, orderNumber) = buyerUserId

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
            orderNumber = orderNumber,
            carrier = request.carrier,
            trackingNumber = request.trackingNumber
        )

        return buildOrderResponse(
            order = orderResponseData.first,
            itemsWithProducts = orderResponseData.second,
            payment = orderResponseData.third,
            viewerCreatorId = userId
        )
    }

    suspend fun getShippingStatus(userId: Int, orderId: Int, creatorId: Int? = null): ShippingResponse {
        // 외부조회 전 기본 응답 구성
        val (baseResponse, orderNumber) = query {
            val order = orderRepository.findOrderById(orderId)
                ?: throw OrderNotFoundException(Errors.Commerce.Order.ORDER_NOT_FOUND)

            val (orderItems, products, _) = fetchOrderItemsWithProducts(orderId)

            val isCreatorOrBuyer = order.userId == userId || orderItems.any { item ->
                products[item.productId]?.creatorId == userId
            }

            if (!isCreatorOrBuyer) {
                throw PermissionDeniedException(Errors.Common.UNAUTHORIZED)
            }

            if (order.userId != userId && creatorId != null && creatorId != userId) {
                throw PermissionDeniedException(Errors.Common.UNAUTHORIZED)
            }

            val shipment = when {
                creatorId != null -> orderRepository.findCreatorShipment(orderId, creatorId)
                else -> {
                    val shipments = orderRepository.findOrderShipments(orderId)
                    when {
                        shipments.size == 1 -> shipments.first()
                        shipments.any { it.creatorId == userId } -> shipments.firstOrNull { it.creatorId == userId }
                        else -> shipments.firstOrNull()
                    }
                }
            }

            ShippingResponse(
                orderId = order.id.value,
                creatorId = shipment?.creatorId,
                trackingNumber = shipment?.trackingNumber,
                carrier = shipment?.carrier,
                shippingStatus = shipment?.shippingStatus ?: ShippingStatus.PREPARING,
                estimatedDeliveryDate = shipment?.estimatedDeliveryDate,
                actualDeliveryDate = shipment?.actualDeliveryDate,
                courierName = null,
                deliveryStatusText = null,
                lastProgressAt = null,
                queriedAt = null,
                progresses = emptyList()
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
        if (!deliveryApiClient.verifyWebhookSignature(rawBody, signatureHeader, timestampHeader)) {
            throw PermissionDeniedException(Errors.Common.UNAUTHORIZED)
        }

        val payload = deliveryApiClient.parseWebhookPayload(rawBody)
            ?: throw InvalidInputException("유효하지 않은 배송 웹훅 payload입니다.")

        if (payload.event != "tracking.status_changed") {
            return "ignored"
        }

        val webhookData = payload.data ?: return "ignored"
        val metadataOrderId = payload.metadata?.get("orderId")?.toIntOrNull()
        val metadataOrderNumber = payload.metadata?.get("orderNumber")
        val metadataCreatorId = payload.metadata?.get("creatorId")?.toIntOrNull()
        val trackingNumber = webhookData.trackingNumber
            ?: webhookData.tracking?.trackingNumber
            ?: return "ignored"

        val externalStatus = webhookData.currentStatus
            ?: webhookData.tracking?.deliveryStatus
            ?: return "ignored"

        var updatedStatus: ShippingStatus? = null
        var orderId: Int? = null
        var buyerUserId: Int? = null
        var earnedAmount = BigDecimal.ZERO

        query {
            val shipment = orderRepository.findShipmentByTrackingNumber(trackingNumber) ?: run {
                val fallbackOrder = when {
                    metadataOrderId != null -> orderRepository.findOrderById(metadataOrderId)
                    !metadataOrderNumber.isNullOrBlank() -> orderRepository.findOrderByNumber(metadataOrderNumber)
                    else -> orderRepository.findOrderByTrackingNumber(trackingNumber)
                } ?: return@query
                val shipments = orderRepository.findOrderShipments(fallbackOrder.id.value)
                if (metadataCreatorId != null) {
                    shipments.firstOrNull { it.creatorId == metadataCreatorId }
                } else {
                    shipments.firstOrNull()
                }
            } ?: return@query

            val order = orderRepository.findOrderById(shipment.orderId) ?: return@query

            if (shipment.trackingNumber != trackingNumber) {  // 송장번호 불일치 시 무시
                logger.warn(
                    "송장번호 불일치로 webhook 무시: orderId={}, payloadTracking={}, shipmentTracking={}",
                    order.id.value,
                    trackingNumber,
                    shipment.trackingNumber
                )
                return@query
            }

            val payloadCourier = webhookData.courierCode
                ?: webhookData.tracking?.courier
            if (!payloadCourier.isNullOrBlank()) {
                val expectedCourier = shipment.carrier
                    ?.let { deliveryApiClient.toCourierCode(it) }
                if (!expectedCourier.isNullOrBlank() &&
                    expectedCourier != payloadCourier.trim().lowercase()
                ) {
                    logger.warn(
                        "택배사 불일치로 webhook 무시: orderId={}, payloadCourier={}, orderCarrier={}",
                        order.id.value,
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
            if (shippingStatusRank(nextStatus) < shippingStatusRank(shipment.shippingStatus)) {
                logger.info(
                    "역행 배송 상태 webhook 무시: orderId={}, current={}, incoming={}",
                    order.id.value,
                    shipment.shippingStatus,
                    nextStatus
                )
                return@query
            }

            orderRepository.updateShippingStatus(
                orderId = order.id.value,
                creatorId = shipment.creatorId,
                shippingStatus = nextStatus
            )
                ?: throw ShippingStatusUpdateFailedException(Errors.Commerce.Order.Shipping.SHIPPING_STATUS_UPDATE_FAILED)

            if (nextStatus == ShippingStatus.DELIVERED) {
                orderRepository.updateOrderShipmentStatus(
                    orderId = order.id.value,
                    creatorId = shipment.creatorId,
                    status = OrderStatus.DELIVERED
                ) ?: throw ShippingStatusUpdateFailedException(Errors.Commerce.Order.Shipping.SHIPPING_STATUS_UPDATE_FAILED)
            }

            val shipmentsAfter = orderRepository.findOrderShipments(order.id.value)
            mergeOrderState(order, shipmentsAfter)

            if (nextStatus == ShippingStatus.DELIVERED &&  // 전체 배송완료 시에만 포인트 적립
                shipmentsAfter
                    .filter { it.status != OrderStatus.CANCELLED }
                    .all { it.status == OrderStatus.DELIVERED }
            ) {

                val orderItems = orderRepository.findOrderItems(order.id.value)
                val productIds = orderItems.map { it.productId }.distinct()
                val products = productRepository.findProductsByIds(productIds).associateBy { it.id.value }

                // 취소·환불된 그룹은 적립 제외
                val cancelledCreatorIds = shipmentsAfter
                    .filter { it.status == OrderStatus.CANCELLED }
                    .map { it.creatorId }
                    .toSet()
                val creatorAmountMap = mutableMapOf<Int, BigDecimal>()
                for (orderItem in orderItems) {
                    val product = products[orderItem.productId] ?: continue
                    if (product.creatorId in cancelledCreatorIds) continue
                    val itemTotal = orderItem.price.multiply(BigDecimal(orderItem.quantity))
                    creatorAmountMap[product.creatorId] = (creatorAmountMap[product.creatorId] ?: BigDecimal.ZERO)
                        .add(itemTotal)
                }

                val creatorTierMap = deserializeSubscriptionTiers(order.subscriptionTiers)
                earnedAmount = pointEarnService.earnPointsFromOrder(
                    userId = order.userId,
                    orderId = order.id.value,
                    creatorAmountMap = creatorAmountMap,
                    creatorTierMap = creatorTierMap
                )
            }

            updatedStatus = nextStatus
            orderId = order.id.value
            buyerUserId = order.userId
        }

        val finalStatus = updatedStatus ?: return "ignored"
        val finalOrderId = orderId ?: return "ignored"
        val finalBuyerUserId = buyerUserId ?: return "ignored"

        coroutineScope.launch {  // 커밋 후 알림 발송
            try {
                when (finalStatus) {
                    ShippingStatus.IN_TRANSIT -> {
                        notificationService.sendShippingInTransitNotification(
                            userId = finalBuyerUserId,
                            orderId = finalOrderId
                        )
                    }
                    ShippingStatus.OUT_FOR_DELIVERY -> {
                        notificationService.sendOutForDeliveryNotification(
                            userId = finalBuyerUserId,
                            orderId = finalOrderId
                        )
                    }
                    ShippingStatus.DELIVERED -> {
                        notificationService.sendShippingDeliveredNotification(
                            userId = finalBuyerUserId,
                            orderId = finalOrderId
                        )

                        if (earnedAmount > BigDecimal.ZERO) {
                            notificationService.sendPointEarnedNotification(
                                userId = finalBuyerUserId,
                                amount = earnedAmount
                            )
                        }
                    }
                    else -> {}
                }
            } catch (e: Exception) {
                logger.error("배송 상태 알림 전송 실패: orderId=$finalOrderId, error=${e.message}", e)
            }
        }

        return "processed"
    }

    suspend fun updateShippingStatus(
        userId: Int,
        orderId: Int,
        request: UpdateShippingStatusRequest
    ): OrderResponse {
        val (orderResponseData, notificationData) = query {
            val order = orderRepository.findOrderById(orderId)
                ?: throw OrderNotFoundException(Errors.Commerce.Order.ORDER_NOT_FOUND)

            val (orderItems, products, _) = fetchOrderItemsWithProducts(orderId)

            val isCreatorOfAnyProduct = orderItems.any { item ->
                products[item.productId]?.creatorId == userId
            }

            if (!isCreatorOfAnyProduct) {
                throw PermissionDeniedException(Errors.Commerce.Order.Shipping.ONLY_CREATOR_CAN_UPDATE)
            }

            val shipment = orderRepository.findCreatorShipment(orderId, userId)
                ?: throw ShippingStatusUpdateFailedException(Errors.Commerce.Order.Shipping.SHIPPING_STATUS_UPDATE_FAILED)

            if (shipment.status == OrderStatus.CANCELLED) {
                throw ShippingStatusUpdateFailedException(Errors.Commerce.Order.Shipping.SHIPPING_STATUS_UPDATE_FAILED)
            }

            if (shipment.trackingNumber == null) {
                throw ShippingNotStartedException(Errors.Commerce.Order.Shipping.NOT_SHIPPED_YET)
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

            // 배송완료 시 주문 시점 티어 기준 포인트 적립
            var earnedAmount = BigDecimal.ZERO
            if (request.status == ShippingStatus.DELIVERED &&
                shipmentsAfter
                    .filter { it.status != OrderStatus.CANCELLED }
                    .all { it.status == OrderStatus.DELIVERED }
            ) {
                // 취소·환불된 그룹은 적립 제외
                val cancelledCreatorIds = shipmentsAfter
                    .filter { it.status == OrderStatus.CANCELLED }
                    .map { it.creatorId }
                    .toSet()
                val creatorAmountMap = mutableMapOf<Int, BigDecimal>()
                for (orderItem in orderItems) {
                    val product = products[orderItem.productId] ?: continue
                    if (product.creatorId in cancelledCreatorIds) continue
                    val itemTotal = orderItem.price.multiply(BigDecimal(orderItem.quantity))
                    creatorAmountMap[product.creatorId] = (creatorAmountMap[product.creatorId] ?: BigDecimal.ZERO).add(itemTotal)
                }

                val creatorTierMap = deserializeSubscriptionTiers(order.subscriptionTiers)

                earnedAmount = pointEarnService.earnPointsFromOrder(
                    userId = order.userId,
                    orderId = orderId,
                    creatorAmountMap = creatorAmountMap,
                    creatorTierMap = creatorTierMap
                )
            }

            val paymentData = paymentRepository.findByOrderId(orderId)
            val updatedOrder = orderRepository.findOrderById(orderId)
                ?: throw OrderNotFoundException(Errors.Commerce.Order.ORDER_NOT_FOUND)
            val itemsWithProducts = orderItems.map { item ->
                OrderItemWithProduct(
                    orderItem = item,
                    product = products[item.productId]
                )
            }

            Triple(updatedOrder, itemsWithProducts, paymentData) to Triple(order.userId, order.totalPrice, earnedAmount)
        }

        coroutineScope.launch {  // 커밋 후 알림 발송
            try {
                when (request.status) {
                    ShippingStatus.IN_TRANSIT -> {
                        notificationService.sendShippingInTransitNotification(
                            userId = notificationData.first,
                            orderId = orderId
                        )
                    }
                    ShippingStatus.OUT_FOR_DELIVERY -> {
                        notificationService.sendOutForDeliveryNotification(
                            userId = notificationData.first,
                            orderId = orderId
                        )
                    }
                    ShippingStatus.DELIVERED -> {
                        notificationService.sendShippingDeliveredNotification(
                            userId = notificationData.first,
                            orderId = orderId
                        )

                        val earnAmount = notificationData.third
                        if (earnAmount > BigDecimal.ZERO) {
                            notificationService.sendPointEarnedNotification(
                                userId = notificationData.first,
                                amount = earnAmount
                            )
                        }
                    }
                    else -> {}
                }
            } catch (e: Exception) {
                logger.error("배송 상태 변경 알림 전송 실패: orderId=$orderId, error=${e.message}", e)
            }
        }

        return buildOrderResponse(
            order = orderResponseData.first,
            itemsWithProducts = orderResponseData.second,
            payment = orderResponseData.third,
            viewerCreatorId = userId
        )
    }

    private data class OrderItemsWithProducts(
        val orderItems: List<OrderItemDao>,
        val products: Map<Int, ProductDao>,
        val itemsWithProducts: List<OrderItemWithProduct>
    )

    // 주문 응답 조립

    /** 삭제 상품 포함 조회 */
    private suspend fun fetchOrderItemsWithProducts(orderId: Int): OrderItemsWithProducts {
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

    private data class CreatorInfo(val id: Int, val username: String, val displayName: String?, val avatarUrl: String?, val role: String)

    private suspend fun buildOrderResponse(
        order: OrderDao,
        itemsWithProducts: List<OrderItemWithProduct>,
        payment: PaymentDao?,
        viewerCreatorId: Int? = null
    ): OrderResponse = query {
        val creatorUserIds = itemsWithProducts.mapNotNull { it.product?.creatorId }.distinct()
        // 값만 추출
        val creatorInfoMap = userRepository.findUsersByIds(creatorUserIds).associate {
            it.id.value to CreatorInfo(it.id.value, it.username, it.profile?.displayName, it.profile?.avatarUrl, it.role.name)
        }
        // 리뷰 ID 일괄 조회
        val reviewIdByProduct = reviewRepository.findReviewIdsByOrder(order.id.value)
        val itemResponses = itemsWithProducts.map { itemWithProduct ->
            val imageList = itemWithProduct.product?.imageUrls?.decodeJsonToList().orEmpty()
            val reviewId = reviewIdByProduct[itemWithProduct.orderItem.productId]
            val creatorInfo = itemWithProduct.product?.creatorId?.let { creatorInfoMap[it] }
            itemWithProduct.orderItem.toOrderItemResponse(
                // 삭제 상품도 실명 유지
                productName = itemWithProduct.product?.name ?: "삭제된 상품",
                productImageUrl = imageList.firstOrNull(),
                creatorId = itemWithProduct.product?.creatorId,
                creatorName = creatorInfo?.displayName ?: creatorInfo?.username,
                creatorAvatarUrl = creatorInfo?.avatarUrl,
                hasReview = reviewId != null,
                reviewId = reviewId,
                isProductActive = itemWithProduct.product?.isActive ?: false
            )
        }

        val creatorGroups = buildCreatorGroups(order, itemResponses, creatorInfoMap)
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

        val orderCouponCode = orderRepository.findOrderShipments(order.id.value)
            .mapNotNull { it.couponCode }
            .joinToString(",")
            .ifEmpty { null }

        order.toOrderResponse(
            items = visibleItems,
            payment = payment,
            creatorGroups = visibleGroups,
            statusOverride = primaryGroup?.status ?: mergeOrderStatus(order, visibleGroups),
            shippingFeeOverride = primaryGroup?.shippingFee,
            trackingNumberOverride = primaryGroup?.trackingNumber,
            carrierOverride = primaryGroup?.carrier,
            shippingStatusOverride = primaryGroup?.shippingStatus ?: mergeShippingStatus(visibleGroups),
            estimatedDeliveryDateOverride = primaryGroup?.estimatedDeliveryDate,
            actualDeliveryDateOverride = primaryGroup?.actualDeliveryDate,
            couponCode = orderCouponCode
        )
    }

    private suspend fun buildCreatorGroups(
        order: OrderDao,
        itemResponses: List<OrderItemResponse>,
        creatorInfoMap: Map<Int, CreatorInfo>
    ): List<OrderCreatorGroupResponse> {
        val shipmentsByCreator = orderRepository.findOrderShipments(order.id.value)
            .associateBy { it.creatorId }
        val singleGroupFallback = itemResponses.mapNotNull { it.creatorId }.distinct().size <= 1
        // 적립은 주문 시점 티어 기준
        val orderTierMap = deserializeSubscriptionTiers(order.subscriptionTiers)
        // 구독 뱃지는 현재 기준
        val subscribedCreatorIds = subscriptionRepository
            .findActiveSubscriptionsByCreators(
                order.userId,
                itemResponses.mapNotNull { it.creatorId }.distinct()
            )
            .map { it.creatorId }
            .toSet()

        return itemResponses
            .groupBy { it.creatorId }
            .map { (creatorId, items) ->
                val creatorInfo = creatorId?.let { creatorInfoMap[it] }
                val shipment = creatorId?.let { shipmentsByCreator[it] }
                val subtotal = items.fold(BigDecimal.ZERO) { acc, item ->
                    acc + (item.subtotal.toBigDecimalOrNull() ?: BigDecimal.ZERO)
                }

                val groupStatus = shipment?.status
                    ?: if (singleGroupFallback) order.status else OrderStatus.PENDING
                val earnTier = creatorId?.let { orderTierMap[it] } ?: SubscriptionPlanTier.FREE
                // 취소 그룹은 적립 대상 아님
                val earnPoints = if (groupStatus == OrderStatus.CANCELLED) {
                    BigDecimal.ZERO
                } else {
                    calcEarnByTier(subtotal, earnTier)
                }

                OrderCreatorGroupResponse(
                    creatorId = creatorId,
                    creatorName = items.firstOrNull()?.creatorName
                        ?: creatorInfo?.displayName
                        ?: creatorInfo?.username,
                    creatorAvatarUrl = items.firstOrNull()?.creatorAvatarUrl
                        ?: creatorInfo?.avatarUrl,
                    creatorRole = creatorInfo?.role,
                    status = groupStatus,
                    items = items,
                    subtotal = subtotal.toAmountString(),
                    earnTier = earnTier,
                    earnRate = getEarnRateForTier(earnTier).toPlainString(),
                    earnPoints = earnPoints.toAmountString(),
                    isSubscribed = creatorId != null && creatorId in subscribedCreatorIds,
                    shippingFee = shipment?.shippingFee?.toAmountString()
                        ?: if (singleGroupFallback) order.shippingFee.toAmountString() else BigDecimal.ZERO.toAmountString(),
                    couponDiscount = (shipment?.couponDiscount ?: BigDecimal.ZERO).toAmountString(),
                    trackingNumber = shipment?.trackingNumber
                        ?: if (singleGroupFallback) order.trackingNumber else null,
                    carrier = shipment?.carrier
                        ?: if (singleGroupFallback) order.carrier else null,
                    shippingStatus = shipment?.shippingStatus
                        ?: if (singleGroupFallback) order.shippingStatus else ShippingStatus.PREPARING,
                    estimatedDeliveryDate = (shipment?.estimatedDeliveryDate
                        ?: if (singleGroupFallback) order.estimatedDeliveryDate else null),
                    actualDeliveryDate = (shipment?.actualDeliveryDate
                        ?: if (singleGroupFallback) order.actualDeliveryDate else null),
                    refundAmount = shipment?.refundAmount?.toAmountString(),
                    refundReason = shipment?.refundReason,
                    refundedAt = shipment?.refundCompletedAt
                )
            }
    }

    private suspend fun fetchOrderResponseData(orderId: Int): Triple<OrderDao, List<OrderItemWithProduct>, PaymentDao?> = query {
        val order = orderRepository.findOrderById(orderId)
            ?: throw OrderNotFoundException(Errors.Commerce.Order.ORDER_NOT_FOUND)
        val payment = paymentRepository.findByOrderId(orderId)
        Triple(order, fetchOrderItemsWithProducts(orderId).itemsWithProducts, payment)
    }

    // 통계
    suspend fun getOrderStatistics(): OrderStatisticsResponse {
        val (todayStart, todayEnd, weekStart, monthStart) = statisticsDateBoundaries()

        return query {
            OrderStatisticsResponse(
                totalOrders = orderRepository.countAllOrders(),
                totalRevenue = orderRepository.sumTotalRevenue().toString(),
                ordersToday = orderRepository.countOrdersByDateRange(todayStart, todayEnd),
                ordersThisWeek = orderRepository.countOrdersByDateRange(weekStart, todayEnd),
                ordersThisMonth = orderRepository.countOrdersByDateRange(monthStart, todayEnd),
                revenueToday = orderRepository.sumRevenueByDateRange(todayStart, todayEnd).toString(),
                revenueThisWeek = orderRepository.sumRevenueByDateRange(weekStart, todayEnd).toString(),
                revenueThisMonth = orderRepository.sumRevenueByDateRange(monthStart, todayEnd).toString(),
                avgOrderValue = orderRepository.calcAvgOrderValue().toString(),
                pendingOrders = orderRepository.countOrdersByStatus(OrderStatus.PENDING),
                completedOrders = orderRepository.countOrdersByStatus(OrderStatus.DELIVERED),
                cancelledOrders = orderRepository.countOrdersByStatus(OrderStatus.CANCELLED)
            )
        }
    }

    suspend fun getCreatorSalesSummary(creatorId: Int): CreatorSalesSummaryResponse {
        return query {
            requireCreatorRole(creatorId)

            val now = nowUtc()
            val monthStart = LocalDateTime(now.year, now.month.number, 1, 0, 0, 0)
            val (lastMonthStart, lastMonthEnd) = previousMonthRange(now)

            val allTime = orderRepository.aggregateCreatorSales(creatorId, null, null)
            val thisMonth = orderRepository.aggregateCreatorSales(creatorId, monthStart, now)
            val lastMonthGross = orderRepository.sumCreatorGrossRevenue(creatorId, lastMonthStart, lastMonthEnd)

            CreatorSalesSummaryResponse(
                thisMonthRevenue = thisMonth.grossRevenue.toAmountString(),
                thisMonthNetRevenue = (thisMonth.grossRevenue - thisMonth.refundAmount).toAmountString(),
                lastMonthRevenue = lastMonthGross.toAmountString(),
                cumulativeRevenue = allTime.grossRevenue.toAmountString(),
                pendingCount = allTime.pendingCount,
                confirmedCount = allTime.confirmedCount,
                shippingCount = allTime.shippingCount,
                deliveredCount = allTime.deliveredCount,
                cancelledCount = allTime.cancelledCount
            )
        }
    }

    suspend fun getCreatorMonthlySales(creatorId: Int, month: String?): CreatorMonthlySalesResponse {
        val (year, monthNum) = parseYearMonth(month)
        val (start, end) = monthRange(year, monthNum)

        return query {
            requireCreatorRole(creatorId)

            val agg = orderRepository.aggregateCreatorSales(creatorId, start, end)
            val top = orderRepository.topCreatorProducts(creatorId, start, end, BEST_SELLER_LIMIT)

            // 최근 6개월 추이
            val trend = (TREND_MONTHS - 1 downTo 0).map { offset ->
                val m = LocalDate(year, monthNum, 1).minus(DatePeriod(months = offset))
                val (ms, me) = monthRange(m.year, m.month.number)
                MonthRevenueResponse(
                    month = formatYearMonth(m.year, m.month.number),
                    revenue = orderRepository.sumCreatorGrossRevenue(creatorId, ms, me).toAmountString()
                )
            }

            val net = agg.grossRevenue - agg.refundAmount
            val avgOrderValue = if (agg.orderCount > 0) {
                agg.grossRevenue.divide(BigDecimal(agg.orderCount), 0, RoundingMode.HALF_UP)
            } else {
                BigDecimal.ZERO
            }

            CreatorMonthlySalesResponse(
                month = formatYearMonth(year, monthNum),
                grossRevenue = agg.grossRevenue.toAmountString(),
                refundAmount = agg.refundAmount.toAmountString(),
                netRevenue = net.toAmountString(),
                orderCount = agg.orderCount,
                quantitySold = agg.quantitySold,
                avgOrderValue = avgOrderValue.toAmountString(),
                shippingFees = agg.shippingFees.toAmountString(),
                couponDiscounts = agg.couponDiscounts.toAmountString(),
                statusBreakdown = SalesStatusBreakdownResponse(
                    pending = agg.pendingCount,
                    confirmed = agg.confirmedCount,
                    shipping = agg.shippingCount,
                    delivered = agg.deliveredCount,
                    cancelled = agg.cancelledCount
                ),
                monthlyTrend = trend,
                bestSellers = top.map {
                    BestSellerResponse(
                        productId = it.productId,
                        productName = it.productName,
                        productImageUrl = it.productImageUrl,
                        quantity = it.quantity,
                        revenue = it.revenue.toAmountString()
                    )
                }
            )
        }
    }

    private suspend fun requireCreatorRole(creatorId: Int) {
        val user = userRepository.findUserById(creatorId)
            ?: throw UserNotFoundException(Errors.User.USER_INFO_NOT_FOUND)
        if (user.role != UserRole.CREATOR && user.role != UserRole.ADMIN) {
            throw PermissionDeniedException(Errors.Commerce.Order.CREATOR_PERMISSION_REQUIRED)
        }
    }

    private fun parseYearMonth(month: String?): Pair<Int, Int> {
        val now = nowUtc()
        if (month.isNullOrBlank()) return now.year to now.month.number  // 없으면 이번 달로 폴백
        val parts = month.split("-")
        val year = parts.getOrNull(0)?.toIntOrNull()
        val monthNum = parts.getOrNull(1)?.toIntOrNull()
        return if (year != null && monthNum != null && monthNum in 1..12) {
            year to monthNum
        } else {
            now.year to now.month.number
        }
    }

    private fun monthRange(year: Int, month: Int): Pair<LocalDateTime, LocalDateTime> {
        val start = LocalDateTime(year, month, 1, 0, 0, 0)
        val lastDay = LocalDate(year, month, 1).plus(DatePeriod(months = 1)).minus(DatePeriod(days = 1))
        val end = LocalDateTime(lastDay.year, lastDay.month.number, lastDay.day, 23, 59, 59)
        return start to end
    }

    private fun previousMonthRange(now: LocalDateTime): Pair<LocalDateTime, LocalDateTime> {
        val lastMonth = LocalDate(now.year, now.month.number, 1).minus(DatePeriod(months = 1))
        return monthRange(lastMonth.year, lastMonth.month.number)
    }

    private fun formatYearMonth(year: Int, month: Int): String =
        "%04d-%02d".format(year, month)

    // 배송 추적
    private suspend fun registerTrackingSubscription(
        orderId: Int,
        creatorId: Int,
        orderNumber: String,
        carrier: String,
        trackingNumber: String
    ) {
        val courierCode = deliveryApiClient.toCourierCode(carrier)
        if (courierCode == null) {
            logger.warn("Delivery 구독 매핑을 지원하지 않는 택배사입니다: {}", carrier)
            return
        }

        val subscriptionId = deliveryApiClient.subscribeTracking(
            courierCode = courierCode,
            trackingNumber = trackingNumber,
            id = orderNumber,
            metadata = mapOf(
                "orderId" to orderId.toString(),
                "orderNumber" to orderNumber,
                "creatorId" to creatorId.toString()
            )
        )

        if (subscriptionId == null) {
            query {
                orderRepository.clearTrackingSubId(orderId, creatorId)
            }
            logger.warn("배송 추적 구독이 생성되지 않았습니다: orderId={}", orderId)
        } else {
            query {
                orderRepository.setTrackingSubId(orderId, creatorId, subscriptionId)
            }
            logger.info("배송 추적 구독 생성 완료: orderId={}, subscriptionId={}", orderId, subscriptionId)
        }
    }

    private fun shippingStatusRank(status: ShippingStatus): Int {
        return when (status) {
            ShippingStatus.PREPARING -> 0
            ShippingStatus.SHIPPED -> 1
            ShippingStatus.IN_TRANSIT -> 2
            ShippingStatus.OUT_FOR_DELIVERY -> 3
            ShippingStatus.DELIVERED -> 4
        }
    }

    private fun OrderShipmentDao.isCancellableGroup(): Boolean {
        return status in setOf(OrderStatus.PENDING, OrderStatus.CONFIRMED) &&
            trackingNumber.isNullOrBlank() &&
            shippingStatus == ShippingStatus.PREPARING
    }

    // 금액 계산
    private fun calcGroupGross(
        creatorId: Int,
        orderItems: List<OrderItemDao>,
        products: Map<Int, ProductDao>,
        shipmentsByCreator: Map<Int, OrderShipmentDao>
    ): BigDecimal {
        val itemSubtotal = orderItems
            .filter { item -> products[item.productId]?.creatorId == creatorId }
            .fold(BigDecimal.ZERO) { acc, item ->
                acc.add(item.price.multiply(BigDecimal(item.quantity)))
            }

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

        return totalDiscount
            .multiply(targetGross)
            .divide(totalGross, 2, RoundingMode.HALF_UP)
            .coerceAtMost(totalDiscount)
    }

    // 배송 상태 병합
    private fun mergeOrderState(
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

    private fun mergeOrderStatus(
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

    private fun mergeShippingStatus(
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

    // 주문 생성 보조
    private fun deserializeSubscriptionTiers(json: String?): Map<Int, SubscriptionPlanTier> {
        if (json.isNullOrBlank()) return emptyMap()
        return try {
            val map = Json.decodeFromString<Map<String, String>>(json)
            map.mapKeys { it.key.toInt() }.mapValues { SubscriptionPlanTier.valueOf(it.value) }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    /** 날짜8 + 난수8 주문번호 */
    private fun generateOrderNumber(): String {
        val now = nowUtc()  // 충돌 시 DB unique 제약 가드
        val dateStr = "${now.year}${now.month.number.toString().padStart(2, '0')}${now.day.toString().padStart(2, '0')}"
        val randomStr = (10_000_000..99_999_999).random()
        return "$dateStr$randomStr"
    }

    private fun calcEstimatedDeliveryDate(carrier: String): LocalDateTime {
        val daysToAdd = when (carrier) {
            Constants.Commerce.CARRIER_CJ -> Constants.Commerce.DELIVERY_DAYS_CJ
            Constants.Commerce.CARRIER_EPOST -> Constants.Commerce.DELIVERY_DAYS_EPOST
            Constants.Commerce.CARRIER_HANJIN -> Constants.Commerce.DELIVERY_DAYS_HANJIN
            Constants.Commerce.CARRIER_LOTTE -> Constants.Commerce.DELIVERY_DAYS_LOTTE
            Constants.Commerce.CARRIER_LOGEN -> Constants.Commerce.DELIVERY_DAYS_LOGEN
            else -> Constants.Commerce.DELIVERY_DAYS_DEFAULT
        }

        val now = Clock.System.now()
        val instant = now.plus(daysToAdd.days)
        return instant.toLocalDateTime(TimeZone.UTC)
    }
}
