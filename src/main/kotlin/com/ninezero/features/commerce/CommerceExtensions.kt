package com.ninezero.features.commerce

import com.ninezero.core.common.config.Constants
import com.ninezero.core.common.config.OrderStatus
import com.ninezero.core.common.config.ShippingStatus
import com.ninezero.core.common.util.decodeJsonToList
import com.ninezero.core.common.util.nowUtc
import com.ninezero.core.common.util.toAmountString
import com.ninezero.core.database.entities.commerce.*
import com.ninezero.features.commerce.presentation.models.DetailContentBlock
import com.ninezero.features.commerce.presentation.models.response.*
import com.ninezero.features.tag.presentation.models.response.TagResponse
import com.ninezero.features.user.presentation.models.response.UserSummaryResponse
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.json.Json
import java.math.BigDecimal
import java.math.RoundingMode

fun ProductDao.toProductResponse(
    creator: UserSummaryResponse,
    canAccess: Boolean? = null,
    sectionTag: TagResponse? = null
): ProductResponse {
    val imageList = imageUrls.decodeJsonToList()
    val detailContentList: List<DetailContentBlock> = detailContent?.let {
        if (it.isBlank()) emptyList()
        else try { Json.decodeFromString(it) } catch (_: Exception) { emptyList() }
    } ?: emptyList()
    val tagList = tags.decodeJsonToList()

    // 타임딜 활성 시 딜가로 override
    val now = nowUtc()
    val dealActive = isDealActiveAt(now)
    val effPrice = effectivePrice(now)
    val effOriginal = effectiveOriginalPrice(now)

    val discountRate = if (effOriginal != null && effOriginal > BigDecimal.ZERO) {
        val discount = ((effOriginal - effPrice) / effOriginal) * BigDecimal(100)
        discount.setScale(0, RoundingMode.HALF_UP).toInt()
    } else {
        null
    }

    return ProductResponse(
        id = id.value,
        creator = creator,
        name = name,
        description = description,
        price = effPrice.toAmountString(),
        originalPrice = effOriginal?.toAmountString(),
        discountRate = discountRate,
        dealPrice = dealPrice?.toAmountString(),
        dealStartAt = dealStartAt,
        dealEndAt = dealEndAt,
        isDealActive = dealActive,
        // 수정화면 prefill용 원본값
        basePrice = price.toAmountString(),
        baseOriginalPrice = originalPrice?.toAmountString(),
        stock = stock,
        categoryId = categoryId,
        brandName = brandName,
        imageUrls = imageList,
        detailContent = detailContentList,
        tags = tagList,
        sectionTag = sectionTag,
        status = status,
        viewCount = viewCount,
        likeCount = likeCount,
        salesCount = salesCount,
        rating = rating.toString(),
        reviewCount = reviewCount,
        isActive = isActive,
        shippingFee = Constants.Commerce.DEFAULT_SHIPPING_FEE.toAmountString(),
        requiredTier = requiredTier,
        canAccess = canAccess,
        createdAt = createdAt,
        updatedAt = updatedAt
    )
}

fun ProductDao.isDealActiveAt(now: LocalDateTime = nowUtc()): Boolean {
    val start = dealStartAt ?: return false
    val end = dealEndAt ?: return false
    val dp = dealPrice ?: return false
    return dp > BigDecimal.ZERO && now >= start && now < end  // 딜가 > 0이고 시작 이상 종료 미만 구간
}

fun ProductDao.effectivePrice(now: LocalDateTime = nowUtc()): BigDecimal =
    if (isDealActiveAt(now)) dealPrice!! else price  // 타임딜 활성 시 딜가, 아니면 판매가

fun ProductDao.effectiveOriginalPrice(now: LocalDateTime = nowUtc()): BigDecimal? =
    if (isDealActiveAt(now)) (originalPrice ?: price) else originalPrice  // 타임딜 활성 시 원가 또는 판매가, 아니면 원가

fun extractDetailContentImageUrls(raw: String?): List<String> {
    if (raw.isNullOrBlank()) return emptyList()
    return try {
        Json.decodeFromString<List<DetailContentBlock>>(raw)
            .filter { it.type == "image" }
            .mapNotNull { it.url }
    } catch (_: Exception) {
        emptyList()
    }
}

fun CartDao.toCartItemResponse(productResponse: ProductResponse): CartItemResponse {
    val price = BigDecimal(productResponse.price)
    val subtotal = price.multiply(BigDecimal(quantity))

    return CartItemResponse(
        id = id.value,
        product = productResponse,
        quantity = quantity,
        subtotal = subtotal.toAmountString(),
        createdAt = createdAt
    )
}

fun OrderItemDao.toOrderItemResponse(
    productName: String,
    productImageUrl: String?,
    creatorId: Int? = null,
    creatorName: String? = null,
    creatorAvatarUrl: String? = null,
    hasReview: Boolean = false,
    reviewId: Int? = null,
    isProductActive: Boolean = true
): OrderItemResponse {
    val subtotal = price.multiply(BigDecimal(quantity))

    return OrderItemResponse(
        id = id.value,
        productId = productId,
        productName = productName,
        productImageUrl = productImageUrl,
        quantity = quantity,
        price = price.toAmountString(),
        originalPrice = originalPrice?.toAmountString(),
        subtotal = subtotal.toAmountString(),
        creatorId = creatorId,
        creatorName = creatorName,
        creatorAvatarUrl = creatorAvatarUrl,
        hasReview = hasReview,
        reviewId = reviewId,
        isProductActive = isProductActive
    )
}

fun OrderDao.toOrderResponse(
    items: List<OrderItemResponse>,
    payment: PaymentDao? = null,
    creatorGroups: List<OrderCreatorGroupResponse> = emptyList(),
    statusOverride: OrderStatus? = null,
    shippingFeeOverride: String? = null,
    trackingNumberOverride: String? = null,
    carrierOverride: String? = null,
    shippingStatusOverride: ShippingStatus? = null,
    estimatedDeliveryDateOverride: LocalDateTime? = null,
    actualDeliveryDateOverride: LocalDateTime? = null,
    couponCode: String? = null
): OrderResponse {
    return OrderResponse(
        id = id.value,
        userId = userId,
        orderNumber = orderNumber,
        items = items,
        creatorGroups = creatorGroups,
        totalPrice = totalPrice.toAmountString(),
        status = statusOverride ?: status,
        shippingAddress = shippingAddress,
        shippingPhone = shippingPhone,
        shippingName = shippingName,
        paymentProvider = payment?.provider,
        paymentStatus = payment?.status,
        memo = memo,
        pointsUsed = pointsUsed.toAmountString(),
        couponDiscount = couponDiscount.toAmountString(),
        couponCode = couponCode,
        shippingFee = shippingFeeOverride ?: shippingFee.toAmountString(),
        // 서버 확정값
        totalEarnPoints = creatorGroups
            .fold(BigDecimal.ZERO) { acc, g -> acc + (g.earnPoints.toBigDecimalOrNull() ?: BigDecimal.ZERO) }
            .toAmountString(),
        trackingNumber = trackingNumberOverride ?: trackingNumber,
        carrier = carrierOverride ?: carrier,
        shippingStatus = shippingStatusOverride ?: shippingStatus,
        estimatedDeliveryDate = estimatedDeliveryDateOverride ?: estimatedDeliveryDate,
        actualDeliveryDate = actualDeliveryDateOverride ?: actualDeliveryDate,
        refundAmount = payment?.refundAmount?.toAmountString(),
        refundReason = payment?.refundReason,
        refundedAt = payment?.refundCompletedAt,
        createdAt = createdAt,
        updatedAt = updatedAt
    )
}

fun WishlistDao.toWishlistResponse(productResponse: WishlistProductResponse): WishlistResponse {
    return WishlistResponse(
        id = id.value,
        userId = userId,
        product = productResponse,
        createdAt = createdAt
    )
}

fun ReviewDao.toReviewResponse(
    author: UserSummaryResponse,
    productName: String? = null,
    productImageUrl: String? = null,
    canAccess: Boolean = true
): ReviewResponse {
    val imageList = images.decodeJsonToList()

    return ReviewResponse(
        id = id.value,
        user = author,
        productId = productId,
        orderId = orderId,
        rating = rating,
        content = content,
        images = imageList,
        productName = productName,
        productImageUrl = productImageUrl,
        canAccess = canAccess,
        createdAt = createdAt,
        updatedAt = updatedAt
    )
}
