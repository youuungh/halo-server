package com.ninezero.features.commerce.data

import com.ninezero.core.common.config.CreatorOrderFilter
import com.ninezero.core.common.config.OrderSource
import com.ninezero.core.common.config.OrderStatus
import com.ninezero.core.common.config.ShippingStatus
import com.ninezero.core.common.util.PageCursor
import com.ninezero.core.database.entities.commerce.OrderDao
import com.ninezero.core.database.entities.commerce.OrderItemDao
import com.ninezero.core.database.entities.commerce.OrderShipmentDao
import com.ninezero.core.database.entities.commerce.ProductDao
import kotlinx.datetime.LocalDateTime
import java.math.BigDecimal

data class OrderWithItems(
    val order: OrderDao,
    val items: List<OrderItemWithProduct>
)

data class OrderItemWithProduct(
    val orderItem: OrderItemDao,
    val product: ProductDao?
)

data class OrderItemData(
    val productId: Int,
    val quantity: Int,
    val price: BigDecimal,
    val originalPrice: BigDecimal? = null  // 할인 전 원가
)

data class OrderShipmentData(
    val creatorId: Int,
    val shippingFee: BigDecimal,
    val couponDiscount: BigDecimal = BigDecimal.ZERO,
    val couponCode: String? = null
)

/** 크리에이터 매출 집계 */
data class CreatorSalesAggregate(
    val grossRevenue: BigDecimal,
    val refundAmount: BigDecimal,
    val shippingFees: BigDecimal,
    val couponDiscounts: BigDecimal,
    val orderCount: Int,
    val quantitySold: Int,
    val pendingCount: Int,
    val confirmedCount: Int,
    val shippingCount: Int,
    val deliveredCount: Int,
    val cancelledCount: Int
)

/** 베스트셀러 집계 */
data class CreatorProductSales(
    val productId: Int,
    val productName: String,
    val productImageUrl: String?,
    val quantity: Int,
    val revenue: BigDecimal
)

interface OrderRepository {

    // 주문 생성/취소
    suspend fun createOrder(
        userId: Int,
        orderNumber: String,
        totalPrice: BigDecimal,
        shippingAddress: String,
        shippingPhone: String,
        shippingName: String,
        memo: String?,
        items: List<OrderItemData>,
        pointsUsed: BigDecimal = BigDecimal.ZERO,
        couponDiscount: BigDecimal = BigDecimal.ZERO,
        shippingFee: BigDecimal = BigDecimal.ZERO,
        subscriptionTiers: String? = null,
        shipments: List<OrderShipmentData>,
        source: OrderSource = OrderSource.CART
    ): OrderDao

    suspend fun cancelOrder(orderId: Int): OrderDao
    suspend fun restoreStock(items: List<OrderItemDao>)

    // 아이템 포함 주문 목록 조회
    suspend fun findUserOrdersWithItems(userId: Int, cursor: PageCursor?, limit: Int): List<OrderWithItems>
    suspend fun findCreatorOrdersWithItems(
        creatorId: Int,
        cursor: PageCursor?,
        limit: Int,
        statusFilter: CreatorOrderFilter = CreatorOrderFilter.ALL
    ): List<OrderWithItems>

    // 주문 조회
    suspend fun findOrderById(orderId: Int): OrderDao?
    suspend fun findOrderItems(orderId: Int): List<OrderItemDao>
    suspend fun findOrderShipments(orderId: Int): List<OrderShipmentDao>
    suspend fun findOrderShipments(orderIds: Collection<Int>): Map<Int, List<OrderShipmentDao>>
    suspend fun findCreatorShipment(orderId: Int, creatorId: Int): OrderShipmentDao?

    // 카운트
    suspend fun countCreatorOrders(
        creatorId: Int,
        statusFilter: CreatorOrderFilter = CreatorOrderFilter.ALL
    ): Int

    // 주문 상태 수정
    suspend fun updateOrderShipmentStatus(orderId: Int, creatorId: Int, status: OrderStatus): OrderShipmentDao?

    // 배송 정보 수정
    suspend fun updateShippingInfo(
        orderId: Int,
        creatorId: Int,
        trackingNumber: String,
        carrier: String,
        shippingStatus: ShippingStatus,
        estimatedDeliveryDate: LocalDateTime
    ): OrderShipmentDao?

    suspend fun updateShippingStatus(
        orderId: Int,
        creatorId: Int,
        shippingStatus: ShippingStatus
    ): OrderShipmentDao?
    suspend fun setTrackingSubId(orderId: Int, creatorId: Int, subscriptionId: String): OrderShipmentDao?
    suspend fun clearTrackingSubId(orderId: Int, creatorId: Int): OrderShipmentDao?

    // 배송 추적 조회
    suspend fun findShipmentsMissingTrackingSubscription(since: LocalDateTime): List<OrderShipmentDao>
    suspend fun findShipmentsInTracking(since: LocalDateTime): List<OrderShipmentDao>

    // 배송완료 시각 수정
    suspend fun updateActualDeliveryDate(
        orderId: Int,
        creatorId: Int,
        actualDeliveryDate: LocalDateTime
    ): OrderShipmentDao?

    // 크리에이터 매출 통계
    suspend fun aggregateCreatorSales(
        creatorId: Int,
        startDate: LocalDateTime?,
        endDate: LocalDateTime?
    ): CreatorSalesAggregate

    suspend fun sumCreatorGrossRevenue(
        creatorId: Int,
        startDate: LocalDateTime?,
        endDate: LocalDateTime?
    ): BigDecimal

    suspend fun sumCreatorMonthlyGrossRevenue(
        creatorId: Int,
        monthRanges: List<Pair<LocalDateTime, LocalDateTime>>
    ): List<BigDecimal>

    suspend fun topCreatorProducts(
        creatorId: Int,
        startDate: LocalDateTime?,
        endDate: LocalDateTime?,
        limit: Int
    ): List<CreatorProductSales>

    // 관리자 통계
    suspend fun countAllOrders(): Int
    suspend fun countOrdersByStatus(status: OrderStatus): Int
    suspend fun countOrdersByDateRange(startDate: LocalDateTime, endDate: LocalDateTime): Int
    suspend fun sumTotalRevenue(): BigDecimal
    suspend fun sumRevenueByDateRange(startDate: LocalDateTime, endDate: LocalDateTime): BigDecimal
    suspend fun calcAvgOrderValue(): BigDecimal
}
