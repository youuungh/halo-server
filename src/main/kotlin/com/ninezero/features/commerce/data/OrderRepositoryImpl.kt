package com.ninezero.features.commerce.data

import com.ninezero.core.common.config.OrderStatus
import com.ninezero.core.common.config.PaymentStatus
import com.ninezero.core.common.config.ShippingStatus
import com.ninezero.core.common.exception.InsufficientStockException
import com.ninezero.core.common.exception.OrderNotFoundException
import com.ninezero.core.common.exception.ProductNotFoundException
import com.ninezero.core.common.util.decodeJsonToList
import com.ninezero.core.common.util.nowUtc
import com.ninezero.core.common.util.toOffset
import com.ninezero.core.database.entities.commerce.*
import com.ninezero.features.commerce.domain.OrderItemData
import kotlinx.datetime.LocalDateTime
import org.jetbrains.exposed.sql.*
import java.math.BigDecimal
import java.math.RoundingMode

class OrderRepositoryImpl : OrderRepository {

    /** 주문·배송그룹 생성과 재고 차감 */
    override suspend fun createOrder(
        userId: Int,
        orderNumber: String,
        totalPrice: BigDecimal,
        shippingAddress: String,
        shippingPhone: String,
        shippingName: String,
        memo: String?,
        items: List<OrderItemData>,
        pointsUsed: BigDecimal,
        couponDiscount: BigDecimal,
        shippingFee: BigDecimal,
        subscriptionTiers: String?,
        shipments: List<OrderShipmentData>
    ): OrderDao {
        val order = OrderDao.new {
            this.userId = userId
            this.orderNumber = orderNumber
            this.totalPrice = totalPrice
            this.status = OrderStatus.PENDING
            this.shippingAddress = shippingAddress
            this.shippingPhone = shippingPhone
            this.shippingName = shippingName
            this.memo = memo
            this.pointsUsed = pointsUsed
            this.couponDiscount = couponDiscount
            this.shippingFee = shippingFee
            this.subscriptionTiers = subscriptionTiers
        }

            items.forEach { itemData ->
            OrderItemDao.new {
                this.orderId = order.id.value
                this.productId = itemData.productId
                this.quantity = itemData.quantity
                this.price = BigDecimal(itemData.price)
                this.originalPrice = itemData.originalPrice?.let { BigDecimal(it) }
            }

            val product = ProductDao.findById(itemData.productId)
                ?: throw ProductNotFoundException(itemData.productId)

            if (product.stock < itemData.quantity) {
                throw InsufficientStockException("'${product.name}' 상품의 재고가 부족합니다. (현재 재고: ${product.stock}개)")  // 재고 부족
            }

            product.stock -= itemData.quantity
            ProductStatusRules.syncByStock(product)  // 재고 0 되면 SOLD_OUT
        }

        shipments.forEach { shipmentData ->
            OrderShipmentDao.new {
                this.orderId = order.id.value
                this.creatorId = shipmentData.creatorId
                this.status = OrderStatus.PENDING
                this.shippingFee = shipmentData.shippingFee
                this.couponDiscount = shipmentData.couponDiscount
                this.couponCode = shipmentData.couponCode
                this.shippingStatus = ShippingStatus.PREPARING
            }
        }

        return order
    }

    /** 주문 취소와 재고 복구 */
    override suspend fun cancelOrder(orderId: Int): OrderDao {
        val order = OrderDao.findById(orderId)
            ?: throw OrderNotFoundException(orderId)

        order.status = OrderStatus.CANCELLED
        order.deliverySubscriptionId = null
        OrderShipmentDao.find { OrderShipmentTable.orderId eq orderId }.forEach { shipment ->
            shipment.status = OrderStatus.CANCELLED
            shipment.deliverySubscriptionId = null
        }

        val orderItems = OrderItemDao.find { OrderItemTable.orderId eq orderId }.toList()
        orderItems.forEach { item ->
            val product = ProductDao.findById(item.productId)
                ?: throw ProductNotFoundException(item.productId)

            product.stock += item.quantity
            ProductStatusRules.syncByStock(product)  // SOLD_OUT 해제
        }

        return order
    }

    /** 유저 주문 목록 조회 */
    override suspend fun findUserOrdersWithItems(
        userId: Int,
        page: Int,
        limit: Int
    ): List<OrderWithItems> {
        val orders = OrderDao.find { OrderTable.userId eq userId }
            .orderBy(OrderTable.createdAt to SortOrder.DESC, OrderTable.id to SortOrder.DESC)  // 최신순
            .limit(limit).offset(page.toOffset(limit))
            .toList()

        if (orders.isEmpty()) return emptyList()

        return loadOrdersWithItems(orders)
    }

    /** 크리에이터 주문 목록 조회 */
    override suspend fun findCreatorOrdersWithItems(
        creatorId: Int,
        page: Int,
        limit: Int,
        statusFilter: String?
    ): List<OrderWithItems> {
        val orderIds = creatorFilteredOrderIds(creatorId, statusFilter)  // statusFilter null/ALL은 전체
        if (orderIds.isEmpty()) return emptyList()

        val orders = OrderDao.find { OrderTable.id inList orderIds }
            .orderBy(OrderTable.createdAt to SortOrder.DESC, OrderTable.id to SortOrder.DESC)  // 최신순
            .limit(limit).offset(page.toOffset(limit))
            .toList()

        return loadOrdersWithItems(orders)
    }

    private fun creatorFilteredOrderIds(creatorId: Int, statusFilter: String?): Set<Int> {
        val orderIds = (OrderItemTable innerJoin ProductTable)
            .select(OrderItemTable.orderId)
            .where { ProductTable.creatorId eq creatorId }
            .withDistinct()
            .map { it[OrderItemTable.orderId] }
            .toSet()

        if (orderIds.isEmpty()) return emptySet()
        if (statusFilter.isNullOrBlank() || statusFilter == "ALL") return orderIds

        return orderIds intersect creatorShipmentOrderIds(creatorId, statusFilter)  // statusFilter 있으면 버킷 추가 필터
    }

    private fun creatorShipmentOrderIds(creatorId: Int, statusFilter: String): Set<Int> {
        return OrderShipmentTable
            .select(OrderShipmentTable.orderId)
            .where {
                val bucket = when (statusFilter) {
                    "PENDING" -> (OrderShipmentTable.status eq OrderStatus.PENDING) or  // CONFIRMED+PREPARING도 PENDING에 편입
                            ((OrderShipmentTable.status eq OrderStatus.CONFIRMED) and
                                    (OrderShipmentTable.shippingStatus eq ShippingStatus.PREPARING))

                    "SHIPPED" -> OrderShipmentTable.shippingStatus inList listOf(
                        ShippingStatus.SHIPPED,
                        ShippingStatus.IN_TRANSIT,
                        ShippingStatus.OUT_FOR_DELIVERY
                    )

                    "DELIVERED" -> (OrderShipmentTable.shippingStatus eq ShippingStatus.DELIVERED) or
                            (OrderShipmentTable.status eq OrderStatus.DELIVERED)

                    "CANCELLED" -> OrderShipmentTable.status eq OrderStatus.CANCELLED

                    else -> Op.FALSE
                }
                (OrderShipmentTable.creatorId eq creatorId) and bucket
            }
            .map { it[OrderShipmentTable.orderId] }
            .toSet()
    }

    private fun loadOrdersWithItems(orders: List<OrderDao>): List<OrderWithItems> {
        val allOrderItems = OrderItemDao.find {
            OrderItemTable.orderId inList orders.map { it.id.value }
        }.toList()

        // isActive 미필터, 삭제 상품도 주문내역 유지
        val productIds = allOrderItems.map { it.productId }.distinct()
        val allProducts = if (productIds.isNotEmpty()) {
            ProductDao.find {
                ProductTable.id inList productIds
            }.associateBy { it.id.value }
        } else {
            emptyMap()
        }

        val itemsByOrderId = allOrderItems.groupBy { it.orderId }

        return orders.map { order ->
            val items = itemsByOrderId[order.id.value]?.map { item ->
                OrderItemWithProduct(
                    orderItem = item,
                    product = allProducts[item.productId]
                )
            } ?: emptyList()

            OrderWithItems(order, items)
        }
    }

    /** 주문 조회 */
    override suspend fun findOrderById(orderId: Int): OrderDao? {
        return OrderDao.findById(orderId)
    }

    /** 주문번호로 주문 조회 */
    override suspend fun findOrderByNumber(orderNumber: String): OrderDao? {
        return OrderDao.find { OrderTable.orderNumber eq orderNumber }.singleOrNull()
    }

    /** 송장번호로 주문 조회 */
    override suspend fun findOrderByTrackingNumber(trackingNumber: String): OrderDao? {
        return OrderDao.find { OrderTable.trackingNumber eq trackingNumber }.firstOrNull()
    }

    /** 송장번호로 배송그룹 조회 */
    override suspend fun findShipmentByTrackingNumber(trackingNumber: String): OrderShipmentDao? {
        return OrderShipmentDao.find { OrderShipmentTable.trackingNumber eq trackingNumber }.firstOrNull()
    }

    /** 주문의 아이템 목록 */
    override suspend fun findOrderItems(orderId: Int): List<OrderItemDao> {
        return OrderItemDao.find { OrderItemTable.orderId eq orderId }.toList()
    }

    /** 주문의 크리에이터별 배송그룹 목록 */
    override suspend fun findOrderShipments(orderId: Int): List<OrderShipmentDao> {
        return OrderShipmentDao.find { OrderShipmentTable.orderId eq orderId }.toList()
    }

    /** 여러 주문의 배송그룹 일괄 조회 */
    override suspend fun findOrderShipments(orderIds: List<Int>): Map<Int, List<OrderShipmentDao>> {
        if (orderIds.isEmpty()) return emptyMap()
        return OrderShipmentDao.find { OrderShipmentTable.orderId inList orderIds }
            .toList()
            .groupBy { it.orderId }
    }

    /** 주문 안의 특정 크리에이터 배송그룹 조회 */
    override suspend fun findCreatorShipment(orderId: Int, creatorId: Int): OrderShipmentDao? {
        return OrderShipmentDao.find {
            (OrderShipmentTable.orderId eq orderId) and (OrderShipmentTable.creatorId eq creatorId)
        }.singleOrNull()
    }

    /** 주문 소유자 확인 */
    override suspend fun isOrderOwnedBy(orderId: Int, userId: Int): Boolean {
        val order = OrderDao.findById(orderId) ?: return false
        return order.userId == userId
    }

    /** 유저 주문 수 */
    override suspend fun countTotalOrders(userId: Int): Int {
        return OrderDao.find { OrderTable.userId eq userId }.count().toInt()
    }

    /** 크리에이터 주문 수 */
    override suspend fun countCreatorOrders(creatorId: Int, statusFilter: String?): Int {
        val orderIds = creatorFilteredOrderIds(creatorId, statusFilter)
        if (orderIds.isEmpty()) return 0
        return OrderDao.find { OrderTable.id inList orderIds }.count().toInt()
    }

    /** 주문 전체 status 교체 */
    override suspend fun updateOrderStatus(orderId: Int, status: OrderStatus): OrderDao? {
        val order = OrderDao.findById(orderId) ?: return null
        order.status = status  // 배송그룹은 안 건드림
        return order
    }

    /** 크리에이터 배송그룹의 status 교체 */
    override suspend fun updateOrderShipmentStatus(
        orderId: Int,
        creatorId: Int,
        status: OrderStatus
    ): OrderShipmentDao? {
        val shipment = findCreatorShipment(orderId, creatorId) ?: return null
        shipment.status = status
        return shipment
    }

    /** 배송그룹 송장·택배사 정보 기록 */
    override suspend fun updateShippingInfo(
        orderId: Int,
        creatorId: Int,
        trackingNumber: String,
        carrier: String,
        shippingStatus: ShippingStatus,
        estimatedDeliveryDate: LocalDateTime
    ): OrderShipmentDao? {
        val shipment = findCreatorShipment(orderId, creatorId) ?: return null

        shipment.trackingNumber = trackingNumber
        shipment.carrier = carrier
        shipment.deliverySubscriptionId = null  // 기존 배송추적 구독 id 해제
        shipment.shippingStatus = shippingStatus
        shipment.estimatedDeliveryDate = estimatedDeliveryDate

        return shipment
    }

    /** 크리에이터 배송그룹의 shippingStatus 교체 */
    override suspend fun updateShippingStatus(
        orderId: Int,
        creatorId: Int,
        shippingStatus: ShippingStatus
    ): OrderShipmentDao? {
        val shipment = findCreatorShipment(orderId, creatorId) ?: return null

        shipment.shippingStatus = shippingStatus

        if (shippingStatus == ShippingStatus.DELIVERED) {
            shipment.actualDeliveryDate = nowUtc()  // DELIVERED면 현재 시각 기록
        }

        return shipment
    }

    /** 크리에이터 배송그룹에 배송추적 구독 id 기록 */
    override suspend fun setTrackingSubId(
        orderId: Int,
        creatorId: Int,
        subscriptionId: String
    ): OrderShipmentDao? {
        val shipment = findCreatorShipment(orderId, creatorId) ?: return null
        shipment.deliverySubscriptionId = subscriptionId
        return shipment
    }

    /** 크리에이터 배송그룹의 배송추적 구독 id 해제 */
    override suspend fun clearTrackingSubId(orderId: Int, creatorId: Int): OrderShipmentDao? {
        val shipment = findCreatorShipment(orderId, creatorId) ?: return null
        shipment.deliverySubscriptionId = null
        return shipment
    }

    /** 크리에이터 배송그룹의 실제 배송완료 시각 교체 */
    override suspend fun updateActualDeliveryDate(
        orderId: Int,
        creatorId: Int,
        actualDeliveryDate: LocalDateTime
    ): OrderShipmentDao? {
        val shipment = findCreatorShipment(orderId, creatorId) ?: return null
        shipment.actualDeliveryDate = actualDeliveryDate
        return shipment
    }

    // 크리에이터 매출 통계

    private fun creatorPaidOrderIds(
        creatorId: Int,
        startDate: LocalDateTime?,
        endDate: LocalDateTime?
    ): Set<Int> {
        val orderIds = (OrderItemTable innerJoin ProductTable innerJoin OrderTable)
            .select(OrderItemTable.orderId)
            .where {
                if (startDate != null && endDate != null) {  // 기간 지정 시만 적용
                    (ProductTable.creatorId eq creatorId) and
                            (OrderTable.createdAt greaterEq startDate) and
                            (OrderTable.createdAt lessEq endDate)
                } else {
                    ProductTable.creatorId eq creatorId
                }
            }
            .withDistinct()
            .map { it[OrderItemTable.orderId] }
            .toSet()

        if (orderIds.isEmpty()) return emptySet()

        return PaymentTable
            .select(PaymentTable.orderId)
            .where {
                (PaymentTable.orderId inList orderIds) and
                        (PaymentTable.status neq PaymentStatus.PENDING)  // 결제 PENDING 제외
            }
            .mapNotNull { it[PaymentTable.orderId] }
            .toSet()
    }

    private fun sumCreatorItemRevenue(creatorId: Int, orderIds: Set<Int>): BigDecimal {
        if (orderIds.isEmpty()) return BigDecimal.ZERO
        return (OrderItemTable innerJoin ProductTable)
            .select(OrderItemTable.price, OrderItemTable.quantity)
            .where {
                (OrderItemTable.orderId inList orderIds) and (ProductTable.creatorId eq creatorId)
            }
            .map { it[OrderItemTable.price].multiply(BigDecimal(it[OrderItemTable.quantity])) }
            .fold(BigDecimal.ZERO) { acc, v -> acc + v }
    }

    /** 크리에이터 매출 요약 */
    override suspend fun aggregateCreatorSales(
        creatorId: Int,
        startDate: LocalDateTime?,
        endDate: LocalDateTime?
    ): CreatorSalesAggregate {
        val paidIds = creatorPaidOrderIds(creatorId, startDate, endDate)  // 기간 null이면 전체 누적
        if (paidIds.isEmpty()) {
            return CreatorSalesAggregate(
                grossRevenue = BigDecimal.ZERO,
                refundAmount = BigDecimal.ZERO,
                shippingFees = BigDecimal.ZERO,
                couponDiscounts = BigDecimal.ZERO,
                orderCount = 0,
                quantitySold = 0,
                pendingCount = 0,
                confirmedCount = 0,
                shippingCount = 0,
                deliveredCount = 0,
                cancelledCount = 0
            )
        }

        val gross = sumCreatorItemRevenue(creatorId, paidIds)

        val quantitySold = (OrderItemTable innerJoin ProductTable)
            .select(OrderItemTable.quantity)
            .where {
                (OrderItemTable.orderId inList paidIds) and (ProductTable.creatorId eq creatorId)
            }
            .sumOf { it[OrderItemTable.quantity] }

        // 배송그룹 단위 집계
        val shipments = OrderShipmentDao.find {
            (OrderShipmentTable.orderId inList paidIds) and (OrderShipmentTable.creatorId eq creatorId)
        }.toList()

        val refund = shipments.mapNotNull { it.refundAmount }
            .fold(BigDecimal.ZERO) { acc, v -> acc + v }
        val shippingFees = shipments.sumOf { it.shippingFee }
        val couponDiscounts = shipments.sumOf { it.couponDiscount }

        var pending = 0
        var confirmed = 0
        var shipping = 0
        var delivered = 0
        var cancelled = 0
        shipments.forEach { s ->
            when {
                s.status == OrderStatus.CANCELLED -> cancelled++
                s.shippingStatus == ShippingStatus.DELIVERED || s.status == OrderStatus.DELIVERED -> delivered++
                s.shippingStatus == ShippingStatus.SHIPPED ||
                        s.shippingStatus == ShippingStatus.IN_TRANSIT ||
                        s.shippingStatus == ShippingStatus.OUT_FOR_DELIVERY -> shipping++
                s.status == OrderStatus.CONFIRMED -> confirmed++
                else -> pending++
            }
        }

        return CreatorSalesAggregate(
            grossRevenue = gross,
            refundAmount = refund,
            shippingFees = shippingFees,
            couponDiscounts = couponDiscounts,
            orderCount = shipments.size,
            quantitySold = quantitySold,
            pendingCount = pending,
            confirmedCount = confirmed,
            shippingCount = shipping,
            deliveredCount = delivered,
            cancelledCount = cancelled
        )
    }

    /** 크리에이터 grossRevenue 합산 */
    override suspend fun sumCreatorGrossRevenue(
        creatorId: Int,
        startDate: LocalDateTime?,
        endDate: LocalDateTime?
    ): BigDecimal {
        val paidIds = creatorPaidOrderIds(creatorId, startDate, endDate)
        return sumCreatorItemRevenue(creatorId, paidIds)
    }

    /** 크리에이터 상품 매출 상위 목록 */
    override suspend fun topCreatorProducts(
        creatorId: Int,
        startDate: LocalDateTime?,
        endDate: LocalDateTime?,
        limit: Int
    ): List<CreatorProductSales> {
        val paidIds = creatorPaidOrderIds(creatorId, startDate, endDate)
        if (paidIds.isEmpty()) return emptyList()

        val quantityByProduct = HashMap<Int, Int>()
        val revenueByProduct = HashMap<Int, BigDecimal>()
        (OrderItemTable innerJoin ProductTable)
            .select(OrderItemTable.productId, OrderItemTable.price, OrderItemTable.quantity)
            .where {
                (OrderItemTable.orderId inList paidIds) and (ProductTable.creatorId eq creatorId)
            }
            .forEach { row ->
                val productId = row[OrderItemTable.productId]
                val quantity = row[OrderItemTable.quantity]
                val revenue = row[OrderItemTable.price].multiply(BigDecimal(quantity))
                quantityByProduct[productId] = (quantityByProduct[productId] ?: 0) + quantity
                revenueByProduct[productId] = (revenueByProduct[productId] ?: BigDecimal.ZERO) + revenue
            }

        val topProductIds = revenueByProduct.entries
            .sortedByDescending { it.value }  // 매출 합 내림차순
            .take(limit)
            .map { it.key }
        if (topProductIds.isEmpty()) return emptyList()

        val products = ProductDao.find { ProductTable.id inList topProductIds }
            .associateBy { it.id.value }

        return topProductIds.map { productId ->
            val product = products[productId]
            CreatorProductSales(
                productId = productId,
                productName = product?.name ?: "",  // 상품 없으면 빈 문자열
                productImageUrl = product?.imageUrls?.decodeJsonToList()?.firstOrNull(),
                quantity = quantityByProduct[productId] ?: 0,
                revenue = revenueByProduct[productId] ?: BigDecimal.ZERO
            )
        }
    }

    /** 관리자 통계용 전체 주문 수 */
    override suspend fun countAllOrders(): Int {
        return OrderDao.all().count().toInt()
    }

    /** status별 주문 수 */
    override suspend fun countOrdersByStatus(status: OrderStatus): Int {
        return OrderDao.find { OrderTable.status eq status }.count().toInt()
    }

    /** 생성 시각 구간의 주문 수 */
    override suspend fun countOrdersByDateRange(startDate: LocalDateTime, endDate: LocalDateTime): Int {
        return OrderDao.find {
            (OrderTable.createdAt greaterEq startDate) and (OrderTable.createdAt lessEq endDate)  // 양끝 포함
        }.count().toInt()
    }

    /** CANCELLED 제외 매출 합계 */
    override suspend fun sumTotalRevenue(): BigDecimal {
        val sumExpr = OrderTable.totalPrice.sum()
        return OrderTable
            .select(sumExpr)
            .where { OrderTable.status neq OrderStatus.CANCELLED }
            .firstOrNull()
            ?.get(sumExpr)
            ?: BigDecimal.ZERO
    }

    /** 기간 내 CANCELLED 제외 매출 합계 */
    override suspend fun sumRevenueByDateRange(startDate: LocalDateTime, endDate: LocalDateTime): BigDecimal {
        val sumExpr = OrderTable.totalPrice.sum()
        return OrderTable
            .select(sumExpr)
            .where {
                (OrderTable.createdAt greaterEq startDate) and  // 양끝 포함
                        (OrderTable.createdAt lessEq endDate) and
                        (OrderTable.status neq OrderStatus.CANCELLED)
            }
            .firstOrNull()
            ?.get(sumExpr)
            ?: BigDecimal.ZERO
    }

    /** CANCELLED 제외 평균 주문가 */
    override suspend fun calcAvgOrderValue(): BigDecimal {
        val result = OrderTable
            .select(OrderTable.totalPrice.avg())
            .where { OrderTable.status neq OrderStatus.CANCELLED }
            .firstOrNull()

        return result?.get(OrderTable.totalPrice.avg())
            ?.setScale(2, RoundingMode.HALF_UP)  // 소수 2자리 HALF_UP
            ?: BigDecimal.ZERO
    }
}
