package com.ninezero.features.commerce

import com.ninezero.core.common.config.Constants
import com.ninezero.core.common.config.OrderStatus
import com.ninezero.core.common.config.ShippingStatus
import com.ninezero.core.common.config.SubscriptionPlanTier
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
    val detailContentList = detailContent.decodeDetailContent()
    val tagList = tags.decodeJsonToList()

    // 타임딜 활성 시 딜가로 override
    val pricing = dealPricingAt(nowUtc())

    return ProductResponse(
        id = id.value,
        creator = creator,
        name = name,
        description = description,
        price = pricing.price.toAmountString(),
        originalPrice = pricing.originalPrice?.toAmountString(),
        discountRate = pricing.discountRate,
        dealPrice = dealPrice?.toAmountString(),
        dealStartAt = dealStartAt,
        dealEndAt = dealEndAt,
        isDealActive = pricing.isDealActive,
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

/** 상품·캐시·주문 청구 공용 타임딜 가격 */
data class DealPricing(
    val price: BigDecimal,
    val originalPrice: BigDecimal?,
    val discountRate: Int?,
    val isDealActive: Boolean
)

fun computeDealPricing(
    now: LocalDateTime,
    price: BigDecimal,
    originalPrice: BigDecimal?,
    dealPrice: BigDecimal?,
    dealStartAt: LocalDateTime?,
    dealEndAt: LocalDateTime?
): DealPricing {
    // 딜가 > 0이고 시작 이상 종료 미만 구간
    val active = dealPrice != null && dealStartAt != null && dealEndAt != null &&
        dealPrice > BigDecimal.ZERO && now >= dealStartAt && now < dealEndAt
    val effPrice = if (active) dealPrice else price  // 타임딜 활성 시 딜가, 아니면 판매가
    val effOriginal = if (active) (originalPrice ?: price) else originalPrice  // 타임딜 활성 시 원가 또는 판매가, 아니면 원가

    // 저장 자릿수로 맞춰 문자열 복원값도 같은 할인율
    val discountRate = if (effOriginal != null && effOriginal > BigDecimal.ZERO) {
        val original = effOriginal.setScale(2, RoundingMode.HALF_UP)
        val discount = ((original - effPrice.setScale(2, RoundingMode.HALF_UP)) / original) * BigDecimal(100)
        discount.setScale(0, RoundingMode.HALF_UP).toInt()
    } else {
        null
    }
    return DealPricing(effPrice, effOriginal, discountRate, active)
}

fun ProductDao.dealPricingAt(now: LocalDateTime = nowUtc()): DealPricing =
    computeDealPricing(now, price, originalPrice, dealPrice, dealStartAt, dealEndAt)

/** 캐시된 응답의 딜 반영 가격을 지금 기준으로 다시 계산 */
fun ProductResponse.withCurrentDeal(now: LocalDateTime = nowUtc()): ProductResponse {
    val pricing = computeDealPricing(
        now = now,
        price = BigDecimal(basePrice),
        originalPrice = baseOriginalPrice?.let { BigDecimal(it) },
        dealPrice = dealPrice?.let { BigDecimal(it) },
        dealStartAt = dealStartAt,
        dealEndAt = dealEndAt
    )
    return copy(
        price = pricing.price.toAmountString(),
        originalPrice = pricing.originalPrice?.toAmountString(),
        discountRate = pricing.discountRate,
        isDealActive = pricing.isDealActive
    )
}

/** 상세 블록 JSON 디코드, 비었거나 깨졌으면 빈 목록 */
fun String?.decodeDetailContent(): List<DetailContentBlock> {
    if (isNullOrBlank()) return emptyList()
    return try {
        Json.decodeFromString<List<DetailContentBlock>>(this)
    } catch (_: Exception) {
        emptyList()
    }
}

fun extractDetailContentImageUrls(raw: String?): List<String> {
    return raw.decodeDetailContent()
        .filter { it.type == "image" }
        .mapNotNull { it.url }
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
    status: OrderStatus,
    shippingStatus: ShippingStatus,
    payment: PaymentDao? = null,
    creatorGroups: List<OrderCreatorGroupResponse> = emptyList(),
    shippingFeeOverride: String? = null,
    trackingNumberOverride: String? = null,
    carrierOverride: String? = null,
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
        status = status,
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
            .sumOf { it.earnPoints.toBigDecimalOrNull() ?: BigDecimal.ZERO }
            .toAmountString(),
        trackingNumber = trackingNumberOverride ?: trackingNumber,
        carrier = carrierOverride ?: carrier,
        shippingStatus = shippingStatus,
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

fun BillingKeyDao.toBillingKeyResponse(): BillingKeyResponse {
    return BillingKeyResponse(
        registered = true,
        cardCompany = cardCompany,
        cardNumberMasked = cardNumberMasked,
        cardType = cardType
    )
}

fun List<String>.normalizeProductImages(): List<String> {
    return this
        .filter { it.isNotBlank() }
        .distinct()
}

fun SubscriptionPlanTier.toDisplayName(): String {
    return when (this) {
        SubscriptionPlanTier.FREE -> "무료"
        SubscriptionPlanTier.TIER1 -> "1티어"
        SubscriptionPlanTier.TIER2 -> "2티어"
    }
}
