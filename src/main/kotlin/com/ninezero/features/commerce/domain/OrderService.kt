package com.ninezero.features.commerce.domain

import com.ninezero.core.common.config.*
import com.ninezero.core.common.config.OrderSource
import com.ninezero.core.common.exception.*
import com.ninezero.core.common.util.*
import com.ninezero.core.database.entities.commerce.OrderShipmentDao
import com.ninezero.core.payment.TossCancelResult
import com.ninezero.core.payment.TossKeys
import com.ninezero.core.payment.TossPaymentClient
import com.ninezero.core.payment.TossPaymentResponse
import com.ninezero.features.commerce.data.CartRepository
import com.ninezero.features.commerce.data.OrderItemData
import com.ninezero.features.commerce.data.OrderRepository
import com.ninezero.features.commerce.data.OrderShipmentData
import com.ninezero.features.commerce.data.PaymentRepository
import com.ninezero.features.commerce.dealPricingAt
import com.ninezero.features.commerce.presentation.models.request.DirectOrderRequest
import com.ninezero.features.commerce.presentation.models.request.OrderCheckoutFields
import com.ninezero.features.commerce.presentation.models.request.OrderRequest
import com.ninezero.features.commerce.presentation.models.request.UpdateOrderStatusRequest
import com.ninezero.features.commerce.presentation.models.response.*
import com.ninezero.features.coupon.domain.CouponRedemptionService
import com.ninezero.features.coupon.domain.CouponValidationResult
import com.ninezero.features.notification.domain.NotificationService
import com.ninezero.features.point.data.PointRepository
import com.ninezero.features.point.domain.PointService
import com.ninezero.features.subscription.data.SubscriptionPlanRepository
import com.ninezero.features.subscription.data.SubscriptionRepository
import com.ninezero.features.user.data.UserRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.Json
import java.math.BigDecimal
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes

class OrderService(
    private val orderRepository: OrderRepository,
    private val cartRepository: CartRepository,
    private val userRepository: UserRepository,
    private val paymentRepository: PaymentRepository,
    private val pointRepository: PointRepository,
    private val subscriptionRepository: SubscriptionRepository,
    private val subscriptionPlanRepository: SubscriptionPlanRepository,
    private val notificationService: NotificationService,
    private val pointService: PointService,
    private val couponRedemptionService: CouponRedemptionService,
    private val cartService: CartService,
    private val tossPaymentClient: TossPaymentClient,
    private val orderResponseAssembler: OrderResponseAssembler,
    private val coroutineScope: CoroutineScope
) {
    private val logger = logger()

    private companion object {
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
        val orderNumber: String,
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
                    orderNumber = order.orderNumber,
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
                // 토스 결제건은 실제 결제 여부 조회
                val paidToss = tossLookupId(ref)?.let { lookupId ->
                    tossPaymentClient.findPaymentByOrderId(lookupId)?.takeIf { it.status == "DONE" }
                }

                if (paidToss != null) {
                    // confirm 커밋 누락 케이스 → 완료 처리
                    if (completeReconciledPayment(ref, paidToss)) notifyOrderPaid(ref.orderId)
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

    /** 토스 조회용 orderId */
    private fun tossLookupId(ref: StalePendingPaymentRef): String? = when (ref.provider) {
        PaymentProvider.TOSS_PAYMENTS -> ref.transactionId?.takeIf { it.isNotBlank() }
        // transactionId 기록 실패에도 조회 가능
        PaymentProvider.TOSS_BILLING -> TossKeys.billingOrderId(ref.orderNumber)
        else -> null
    }

    /** 이번 호출의 완료 처리 여부 */
    private suspend fun completeReconciledPayment(ref: StalePendingPaymentRef, toss: TossPaymentResponse): Boolean = query {
        val payment = paymentRepository.findByOrderId(ref.orderId) ?: return@query false
        if (payment.status != PaymentStatus.PENDING) return@query false  // 멱등 처리

        // 금액 불일치 시 수동 개입
        if (payment.amount.compareTo(BigDecimal(toss.totalAmount)) != 0) {
            logger.error(
                "[정리] 토스 결제금액 불일치로 완료 보류: orderId={}, server={}, toss={}",
                ref.orderId, payment.amount, toss.totalAmount
            )
            return@query false
        }

        if (ref.provider == PaymentProvider.TOSS_BILLING) {
            // 빌링은 provider 유지, 멱등키만 채움
            tossLookupId(ref)?.let { paymentRepository.updateTransactionId(payment.id.value, it) }
            paymentRepository.updatePaymentInfo(
                paymentId = payment.id.value,
                paymentKey = toss.paymentKey,
                receiptUrl = toss.receipt?.url
            )
        } else {
            paymentRepository.updateTossConfirm(
                paymentId = payment.id.value,
                paymentKey = toss.paymentKey,
                receiptUrl = toss.receipt?.url,
                method = toss.method,
                approvedAt = toss.approvedAt
            )
        }
        paymentRepository.updateStatus(payment.id.value, PaymentStatus.COMPLETED)
        clearOrderedCartItemsInTransaction(ref.orderId, ref.buyerUserId)
        true
    }

    // 환불
    private suspend fun executeTossRefundOrThrow(ctx: CancelRefundContext): Boolean {
        if (!ctx.wasPaid || ctx.refundAmount <= BigDecimal.ZERO) return false
        if (ctx.provider !in TOSS_PROVIDERS || ctx.paymentKey.isNullOrBlank()) return false  // MOCK 등 제외

        val result = tossPaymentClient.cancel(
            paymentKey = ctx.paymentKey,
            cancelReason = TossKeys.CANCEL_REASON,  // 사용자 사유는 로컬에만 기록
            cancelAmount = ctx.partialCancelAmount,
            idempotencyKey = ctx.idempotencyKey
        )
        if (result is TossCancelResult.Failure) {
            // 토스 전액 취소 뒤 로컬 반영만 실패한 재시도
            if (ctx.partialCancelAmount == null && isAlreadyFullyCancelled(ctx.paymentKey)) {
                logger.warn("토스 결제가 이미 전액 취소돼 로컬 취소를 이어서 진행: paymentId={}", ctx.paymentId)
                return true
            }
            logger.error(
                "토스 결제 취소 실패: paymentId={}, code={}, message={}",
                ctx.paymentId, result.code, result.message
            )
            throw PaymentConfirmFailedException(result.message ?: Errors.Commerce.Payment.TOSS_CONFIRM_FAILED)  // 취소 중단
        }
        return true
    }

    private suspend fun isAlreadyFullyCancelled(paymentKey: String): Boolean =
        runCatching { tossPaymentClient.findPaymentByKey(paymentKey) }
            .getOrNull()?.status == "CANCELED"

    private suspend fun recordRefund(paymentId: Int, cancelReason: String, refundAmount: BigDecimal) {
        paymentRepository.requestRefund(paymentId, cancelReason, refundAmount)  // 트랜잭션 내부에서만 호출
        paymentRepository.completeRefund(paymentId, nowUtc())
    }

    /** 취소 중 결제 완료 시 중단 */
    private suspend fun requireStillUnpaid(orderId: Int, refundCtx: CancelRefundContext?) {
        if (refundCtx == null || refundCtx.wasPaid) return  // 주문 잠금 뒤에만 호출
        if (paymentRepository.findByOrderId(orderId)?.status == PaymentStatus.COMPLETED) {
            throw ConflictException(Errors.Commerce.Order.ORDER_PAID_DURING_CANCEL)
        }
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
            val (orderItems, products, _) = orderResponseAssembler.fetchOrderItemsWithProducts(orderId)
            val shipment = orderRepository.findCreatorShipment(orderId, creatorId)
                ?: throw OrderNotFoundException(Errors.Commerce.Order.ORDER_NOT_FOUND)
            if (!shipment.isCancellableGroup()) {
                throw OrderCancelNotAllowedException(Errors.Commerce.Order.ORDER_CANCEL_NOT_ALLOWED)
            }

            val shipmentsBefore = orderRepository.findOrderShipments(orderId)
            // 마지막 남은 그룹이면 주문 전체 취소
            val willBeFullyCancelled = shipmentsBefore.count { it.status != OrderStatus.CANCELLED } <= 1

            paymentRepository.findByOrderId(orderId)?.let { payment ->
                // 결제 전엔 금액을 못 나눠 전체 취소만
                if (payment.status != PaymentStatus.COMPLETED) {
                    throw OrderCancelNotAllowedException(Errors.Commerce.Order.UNPAID_GROUP_CANCEL_NOT_ALLOWED)
                }
                val amounts = calcGroupCancelAmounts(creatorId, order.pointsUsed, orderItems, products, shipmentsBefore)

                CancelRefundContext(
                    paymentId = payment.id.value,
                    paymentKey = payment.paymentKey,
                    provider = payment.provider,
                    wasPaid = true,
                    refundAmount = amounts.cashRefund,
                    partialCancelAmount = if (willBeFullyCancelled) null else amounts.cashRefund.toLong(),
                    idempotencyKey = TossKeys.groupCancelKey(payment.id.value, creatorId)
                )
            }
        }

        // 환불 먼저, 실패 시 취소 중단
        if (refundCtx != null) executeTossRefundOrThrow(refundCtx)

        // 취소 확정
        query {
            val order = lockOrder(orderId)
            val (orderItems, products, _) = orderResponseAssembler.fetchOrderItemsWithProducts(orderId)
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
            val amounts = calcGroupCancelAmounts(creatorId, order.pointsUsed, orderItems, products, shipmentsBefore)
            val cancelledGross = amounts.gross
            val couponDiscountShare = amounts.couponShare
            val pointRefund = amounts.pointShare

            orderRepository.updateOrderShipmentStatus(orderId, creatorId, OrderStatus.CANCELLED)
                ?: throw OrderStatusUpdateFailedException(Errors.Commerce.Order.ORDER_STATUS_UPDATE_FAILED)

            shipment.deliverySubscriptionId = null
            shipment.trackingNumber = null
            shipment.carrier = null
            shipment.estimatedDeliveryDate = null
            shipment.actualDeliveryDate = null
            shipment.shippingStatus = ShippingStatus.PREPARING

            // 환불정보 기록
            val payment = refundCtx?.let { paymentRepository.findByOrderId(orderId) }
            if (refundCtx != null) {
                // 마지막 그룹은 잔여 흡수값을 환불액으로 사용
                shipment.refundAmount = if (refundCtx.partialCancelAmount == null) {
                    val priorRefunded = shipmentsBefore
                        .filter { it.status == OrderStatus.CANCELLED }
                        .sumOf { it.refundAmount ?: BigDecimal.ZERO }
                    val paidAmount = payment?.amount ?: refundCtx.refundAmount
                    (paidAmount - priorRefunded).coerceAtLeast(BigDecimal.ZERO)
                } else {
                    refundCtx.refundAmount
                }
                shipment.refundReason = cancelReason
                shipment.refundCompletedAt = nowUtc()
            }

            orderRepository.restoreStock(targetItems)

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

            // 전체 취소 확정 → 결제 전액 REFUNDED
            if (refundCtx != null && shipmentsAfter.all { it.status == OrderStatus.CANCELLED }) {
                payment?.let { recordRefund(it.id.value, cancelReason, it.amount) }
            }
        }
    }

    // 주문 생성
    /** 장바구니로 주문 생성 */
    suspend fun createOrderFromCart(userId: Int, request: OrderRequest): OrderResponse {
        val pointsUsed = validateCheckoutFields(request)
        val orderNumber = generateOrderNumber()

        val created = query {
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

            createOrderInTransaction(
                userId = userId,
                lines = cartItems.map { OrderLine(it.productId, it.quantity) },
                fields = request,
                source = OrderSource.CART,
                orderNumber = orderNumber,
                pointsUsed = pointsUsed
            )
        }

        if (created.isPaid) notifyOrderPaid(created.orderId)  // 커밋 후 알림
        return buildCreatedOrderResponse(created.orderId)
    }

    /** 장바구니를 거치지 않는 바로구매 주문 생성 */
    suspend fun createDirectOrder(userId: Int, request: DirectOrderRequest): OrderResponse {
        ValidationUtils.validateCartQuantity(request.quantity)
        val pointsUsed = validateCheckoutFields(request)
        val orderNumber = generateOrderNumber()

        val created = query {
            createOrderInTransaction(
                userId = userId,
                lines = listOf(OrderLine(request.productId, request.quantity)),
                fields = request,
                source = OrderSource.DIRECT,
                orderNumber = orderNumber,
                pointsUsed = pointsUsed
            )
        }

        if (created.isPaid) notifyOrderPaid(created.orderId)  // 커밋 후 알림
        return buildCreatedOrderResponse(created.orderId)
    }

    /** 사용 포인트를 돌려주는 배송 정보 검증 */
    private fun validateCheckoutFields(fields: OrderCheckoutFields): BigDecimal {
        ValidationUtils.validateShippingAddress(fields.shippingAddress)
        ValidationUtils.validateShippingPhone(fields.shippingPhone)
        ValidationUtils.validateShippingName(fields.shippingName)

        val pointsToUse = fields.pointsToUse
        val pointsUsed = if (!pointsToUse.isNullOrBlank()) BigDecimal(pointsToUse) else BigDecimal.ZERO
        // 음수는 잔액 검증을 건너뛰고 결제액을 늘리므로 차단
        ValidationUtils.validatePointAmount(pointsUsed)
        return pointsUsed
    }

    private data class OrderLine(val productId: Int, val quantity: Int)

    private data class CreatedOrder(val orderId: Int, val isPaid: Boolean)

    /** 0원이면 바로 완료하는 주문·결제 생성 */
    private suspend fun createOrderInTransaction(
        userId: Int,
        lines: List<OrderLine>,
        fields: OrderCheckoutFields,
        source: OrderSource,
        orderNumber: String,
        pointsUsed: BigDecimal
    ): CreatedOrder {
        val creatorIdSet = mutableSetOf<Int>()
        val orderItems = mutableListOf<OrderItemData>()
        var totalPrice = BigDecimal.ZERO

        val productCreatorMap = mutableMapOf<Int, Int>()
        val creatorAmountMap = mutableMapOf<Int, BigDecimal>()

        for ((productId, quantity) in lines) {
            val product = cartService.validatePurchasable(
                userId = userId,
                productId = productId,
                requestedQuantity = quantity
            )

            creatorIdSet.add(product.creatorId)
            productCreatorMap[product.id.value] = product.creatorId

            // 타임딜 딜가 청구
            val pricing = product.dealPricingAt()
            val effPrice = pricing.price
            val effOriginal = pricing.originalPrice

            val itemTotal = effPrice.multiply(BigDecimal(quantity))
            creatorAmountMap[product.creatorId] = (creatorAmountMap[product.creatorId] ?: BigDecimal.ZERO).add(itemTotal)

            val originalPrice = effOriginal?.takeIf { it != effPrice }

            orderItems.add(
                OrderItemData(
                    productId = product.id.value,
                    quantity = quantity,
                    price = effPrice,
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
            shippingFeeByCreator[creatorId] = when {
                tier == SubscriptionPlanTier.TIER1 || tier == SubscriptionPlanTier.TIER2 -> BigDecimal.ZERO
                amount >= Constants.Commerce.FREE_SHIPPING_THRESHOLD -> BigDecimal.ZERO
                else -> Constants.Commerce.DEFAULT_SHIPPING_FEE
            }
        }
        var totalShippingFee = shippingFeeByCreator.values.sumOf { it }

        if (pointsUsed > BigDecimal.ZERO) {
            val point = pointRepository.findByUserId(userId)
                ?: throw NotFoundException(Errors.Point.POINT_ACCOUNT_NOT_FOUND)

            pointAmountError(pointsUsed, point.balance, totalPrice)?.let { throw InvalidPointException(it) }
        }

        var couponDiscount = BigDecimal.ZERO
        val couponDiscountByCreator = mutableMapOf<Int, BigDecimal>()
        val couponCodeByCreator = mutableMapOf<Int, String>()
        val appliedCouponCodes = mutableListOf<String>()
        val freeShippingCouponCodes = mutableListOf<String>()
        val couponCodes = fields.couponCodes
        if (!couponCodes.isNullOrEmpty()) {
            val productIdList = orderItems.map { it.productId }

            // 중복 코드 제거
            for (code in couponCodes.distinct()) {
                if (code.isBlank()) continue

                val validationResult = couponRedemptionService.calcDiscountInTransaction(
                    userId = userId,
                    couponCode = code,
                    orderAmount = totalPrice,
                    productIds = productIdList,
                    shippingFeeByCreator = shippingFeeByCreator,
                    productCreatorMap = productCreatorMap,
                    creatorAmountMap = creatorAmountMap
                )

                val coupon = when (validationResult) {
                    is CouponValidationResult.Invalid ->
                        throw CouponValidationException(validationResult.errorMessage)
                    is CouponValidationResult.Valid -> validationResult
                }
                var discount = coupon.discount
                appliedCouponCodes.add(code)

                // 쿠폰은 크리에이터당 하나만
                if (couponCodeByCreator.containsKey(coupon.creatorId)) {
                    throw CouponValidationException(Errors.Coupon.COUPON_DUPLICATE_CREATOR)
                }
                couponCodeByCreator[coupon.creatorId] = code

                // FREE_SHIPPING 쿠폰인 경우 배송비 차감
                if (coupon.type == CouponType.FREE_SHIPPING && discount > BigDecimal.ZERO && discount <= totalShippingFee) {
                    freeShippingCouponCodes.add(code)
                    shippingFeeByCreator[coupon.creatorId] = BigDecimal.ZERO
                    totalShippingFee = totalShippingFee.subtract(discount)
                    discount = BigDecimal.ZERO
                }

                couponDiscount = couponDiscount.add(discount)
                // 크리에이터 그룹에 할인 귀속
                if (discount > BigDecimal.ZERO) {
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
            Json.encodeToString(creatorTierMap)
        } else null

        val createdOrder = orderRepository.createOrder(
            userId = userId,
            orderNumber = orderNumber,
            totalPrice = finalAmount,
            shippingAddress = fields.shippingAddress,
            shippingPhone = fields.shippingPhone,
            shippingName = fields.shippingName,
            memo = fields.memo,
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
            },
            source = source
        )

        val payment = paymentRepository.createPayment(
            orderId = createdOrder.id.value,
            provider = fields.paymentProvider,
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

        // 토스 0원 불가라 PG 없이 바로 완료
        val isPaid = finalAmount.signum() == 0
        if (isPaid) {
            paymentRepository.updateStatus(payment.id.value, PaymentStatus.COMPLETED)
            clearOrderedCartItemsInTransaction(createdOrder.id.value, userId)
        }

        return CreatedOrder(createdOrder.id.value, isPaid)
    }

    private suspend fun buildCreatedOrderResponse(orderId: Int): OrderResponse {
        return query {
            val order = orderRepository.findOrderById(orderId)
                ?: throw OrderNotFoundException(Errors.Commerce.Order.ORDER_NOT_FOUND)
            val itemsWithProducts = orderResponseAssembler.fetchOrderItemsWithProducts(orderId).itemsWithProducts
            orderResponseAssembler.buildOrderResponseInTransaction(order, itemsWithProducts, paymentRepository.findByOrderId(orderId))
        }
    }

    // 결제 완료 후 처리
    /** 결제 확정된 장바구니 주문의 상품 비우기 */
    suspend fun clearOrderedCartItemsInTransaction(orderId: Int, userId: Int) {
        if (orderRepository.findOrderById(orderId)?.orderSource != OrderSource.CART) return
        val productIds = orderRepository.findOrderItems(orderId).map { it.productId }.toSet()
        if (productIds.isEmpty()) return
        val cartIds = cartRepository.findUserCart(userId)
            .filter { it.productId in productIds }
            .map { it.id.value }
        if (cartIds.isNotEmpty()) {
            cartRepository.deleteCartItems(cartIds)
        }
    }

    /** 결제 완료 후 구매자·판매자 주문 알림 */
    fun notifyOrderPaid(orderId: Int) {
        coroutineScope.launch {  // 커밋 후 알림 발송
            try {
                val (order, creatorIds) = query {
                    val order = orderRepository.findOrderById(orderId) ?: return@query null
                    order to orderRepository.findOrderShipments(orderId).map { it.creatorId }.distinct()
                } ?: return@launch

                notificationService.sendOrderCreatedNotification(
                    userId = order.userId,
                    orderId = orderId,
                    orderNumber = order.orderNumber
                )

                for (creatorId in creatorIds) {
                    notificationService.sendOrderReceivedNotification(
                        creatorId = creatorId,
                        buyerId = order.userId,
                        orderId = orderId,
                        orderNumber = order.orderNumber
                    )
                }
            } catch (e: Exception) {
                logger.error("주문 결제 알림 전송 실패: orderId=$orderId, error=${e.message}", e)
            }
        }
    }

    // 주문 조회
    suspend fun getOrderById(orderId: Int, userId: Int): OrderResponse {
        return query {
            val order = requireOwnedOrder(orderRepository, orderId, userId, Errors.Commerce.Order.ORDER_VIEW_PERMISSION_DENIED)
            val itemsWithProducts = orderResponseAssembler.fetchOrderItemsWithProducts(orderId).itemsWithProducts
            orderResponseAssembler.buildOrderResponseInTransaction(order, itemsWithProducts, paymentRepository.findByOrderId(orderId))
        }
    }

    suspend fun getMyOrders(
        userId: Int,
        cursor: String? = null,
        limit: Int = Constants.DEFAULT_PAGE_LIMIT
    ): OrderListResponse {
        val validLimit = validateCursorLimit(limit)
        val pageCursor = PageCursor.decode(cursor)

        return query {
            val (ordersWithItems, nextCursor) = orderRepository.findUserOrdersWithItems(userId, pageCursor, validLimit + 1)
                .toCursorPage(validLimit) { PageCursor.of(it.order.createdAt, it.order.id.value) }

            // 결제 대기·결제 전 취소 제외는 쿼리에서
            val orderResponses = orderResponseAssembler.buildOrderListResponsesInTransaction(ordersWithItems)

            CursorResponse(orderResponses, nextCursor, hasNext = nextCursor != null)
        }
    }

    // 주문 취소
    /** 결제된 주문은 전액 환불 뒤 확정하는 전체 취소 */
    suspend fun cancelOrder(orderId: Int, userId: Int, cancelReason: String = "구매자 요청"): OrderResponse {
        // 사전 검증 + 환불 컨텍스트 캡처
        val refundCtx = query {
            requireOwnedOrder(orderRepository, orderId, userId, Errors.Commerce.Order.ORDER_CANCEL_PERMISSION_DENIED)

            if (orderRepository.findOrderShipments(orderId).any { !it.isCancellableGroup() }) {
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
                    idempotencyKey = TossKeys.cancelKey(it.id.value)
                )
            }
        }

        // 환불 먼저, 실패 시 취소 중단
        val tossRefunded = refundCtx != null && executeTossRefundOrThrow(refundCtx)

        // 취소 확정
        val (response, creatorIds) = query {
            // 동시 취소 직렬화 후 취소 가능 여부 재검사
            val lockedOrder = lockOrder(orderId)

            val lockedShipments = orderRepository.findOrderShipments(orderId)
            if (tossRefunded) {
                // 환불은 되돌릴 수 없어 송장이 있어도 취소 확정
                if (lockedOrder.status == OrderStatus.CANCELLED) {
                    throw OrderCancelNotAllowedException(Errors.Commerce.Order.ORDER_CANCEL_NOT_ALLOWED)
                }
                lockedShipments.filterNot { it.trackingNumber.isNullOrBlank() }.forEach { shipment ->
                    shipment.trackingNumber = null  // 그룹 취소와 같이 추적 해제
                    shipment.carrier = null
                    shipment.estimatedDeliveryDate = null
                    shipment.shippingStatus = ShippingStatus.PREPARING
                }
            } else if (lockedShipments.any { !it.isCancellableGroup() }) {
                throw OrderCancelNotAllowedException(Errors.Commerce.Order.ORDER_CANCEL_NOT_ALLOWED)
            }
            requireStillUnpaid(orderId, refundCtx)

            orderRepository.cancelOrder(orderId)
            mergeOrderState(lockedOrder, orderRepository.findOrderShipments(orderId))

            val payment = paymentRepository.findByOrderId(orderId)
            if (payment != null) {
                if (refundCtx != null && refundCtx.wasPaid) {
                    recordRefund(payment.id.value, cancelReason, refundCtx.refundAmount)
                } else {
                    paymentRepository.updateStatus(payment.id.value, PaymentStatus.CANCELLED)
                }
            }

            if (lockedOrder.pointsUsed > BigDecimal.ZERO) {
                pointService.refundPointsInTransaction(userId = userId, amount = lockedOrder.pointsUsed, orderId = orderId)
            }

            orderRepository.findOrderShipments(orderId)
                .mapNotNull { it.couponCode }
                .forEach { code ->
                    couponRedemptionService.refundCouponInTransaction(userId = userId, couponCode = code)
                }

            val (items, products, itemsWithProducts) = orderResponseAssembler.fetchOrderItemsWithProducts(orderId)
            val creatorIdList = items.mapNotNull { products[it.productId]?.creatorId }.distinct()

            orderResponseAssembler.buildOrderResponseInTransaction(lockedOrder, itemsWithProducts, payment) to creatorIdList
        }

        // 미결제 취소는 판매자에게 알리지 않음
        if (refundCtx?.wasPaid == true) {
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
        }

        return response
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

        return query {
            val order = orderRepository.findOrderById(orderId)
                ?: throw OrderNotFoundException(Errors.Commerce.Order.ORDER_NOT_FOUND)
            val payment = paymentRepository.findByOrderId(orderId)
            orderResponseAssembler.buildOrderResponseInTransaction(order, orderResponseAssembler.fetchOrderItemsWithProducts(orderId).itemsWithProducts, payment)
        }
    }

    private fun OrderShipmentDao.isCancellableGroup(): Boolean {
        return status in setOf(OrderStatus.PENDING, OrderStatus.CONFIRMED) &&
            trackingNumber.isNullOrBlank() &&
            shippingStatus == ShippingStatus.PREPARING
    }

    // 판매자 주문 처리
    suspend fun getCreatorOrders(
        creatorId: Int,
        cursor: String? = null,
        limit: Int = Constants.DEFAULT_PAGE_LIMIT,
        filter: CreatorOrderFilter = CreatorOrderFilter.ALL
    ): OrderListResponse {
        val validLimit = validateCursorLimit(limit)
        val pageCursor = PageCursor.decode(cursor)

        return query {
            requireCreatorRole(userRepository, creatorId)

            val (ordersWithItems, nextCursor) = orderRepository.findCreatorOrdersWithItems(creatorId, pageCursor, validLimit + 1, filter)
                .toCursorPage(validLimit) { PageCursor.of(it.order.createdAt, it.order.id.value) }

            // 결제 대기 주문은 조회 쿼리에서 제외
            val orderResponses = orderResponseAssembler.buildOrderListResponsesInTransaction(ordersWithItems, viewerCreatorId = creatorId)

            CursorResponse(orderResponses, nextCursor, hasNext = nextCursor != null)
        }
    }

    suspend fun updateOrderStatus(
        orderId: Int,
        creatorId: Int,
        request: UpdateOrderStatusRequest
    ): OrderResponse {
        // 권한 검증 + buyerUserId 확보
        val buyerUserId = query {
            requireCreatorRole(userRepository, creatorId)
            val order = orderRepository.findOrderById(orderId)
                ?: throw OrderNotFoundException(Errors.Commerce.Order.ORDER_NOT_FOUND)
            orderRepository.findCreatorShipment(orderId, creatorId)
                ?: throw ForbiddenException(Errors.Commerce.Order.ORDER_UPDATE_PERMISSION_DENIED)
            order.userId
        }

        when (request.status) {
            OrderStatus.CONFIRMED -> query {
                val order = lockOrder(orderId)  // 취소와 직렬화
                val shipment = orderRepository.findCreatorShipment(orderId, creatorId)
                    ?: throw ForbiddenException(Errors.Commerce.Order.ORDER_UPDATE_PERMISSION_DENIED)
                requirePaid(paymentRepository, orderId)
                // 취소·배송완료 그룹은 확정으로 되돌릴 수 없음
                if (shipment.status == OrderStatus.CANCELLED || shipment.status == OrderStatus.DELIVERED) {
                    throw OrderStatusUpdateFailedException(Errors.Commerce.Order.ORDER_STATUS_UPDATE_FAILED)
                }
                orderRepository.updateOrderShipmentStatus(orderId, creatorId, OrderStatus.CONFIRMED)
                    ?: throw OrderStatusUpdateFailedException(Errors.Commerce.Order.ORDER_STATUS_UPDATE_FAILED)
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

        return query {
            val order = orderRepository.findOrderById(orderId)
                ?: throw OrderNotFoundException(Errors.Commerce.Order.ORDER_NOT_FOUND)
            val payment = paymentRepository.findByOrderId(orderId)
            orderResponseAssembler.buildOrderResponseInTransaction(
                order, orderResponseAssembler.fetchOrderItemsWithProducts(orderId).itemsWithProducts, payment, viewerCreatorId = creatorId
            )
        }
    }
}
