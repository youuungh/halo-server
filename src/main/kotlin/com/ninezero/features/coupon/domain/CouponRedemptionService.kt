package com.ninezero.features.coupon.domain

import com.ninezero.core.common.config.CouponDiscountTarget
import com.ninezero.core.common.config.CouponType
import com.ninezero.core.common.config.SubscriptionPlanTier
import com.ninezero.core.common.config.UserCouponStatus
import com.ninezero.core.common.exception.ConflictException
import com.ninezero.core.common.exception.Errors
import com.ninezero.core.common.exception.NotFoundException
import com.ninezero.core.common.util.logger
import com.ninezero.core.common.util.nowUtc
import com.ninezero.core.common.util.query
import com.ninezero.core.common.util.toAmountString
import com.ninezero.core.database.entities.coupon.CouponDao
import com.ninezero.core.database.entities.coupon.UserCouponDao
import com.ninezero.features.coupon.calcCouponDiscount
import com.ninezero.features.coupon.data.CouponRepository
import com.ninezero.features.coupon.data.UserCouponRepository
import com.ninezero.features.coupon.decodeToTargetIds
import com.ninezero.features.subscription.data.SubscriptionPlanRepository
import com.ninezero.features.subscription.data.SubscriptionRepository
import kotlinx.datetime.LocalDateTime
import java.math.BigDecimal

/** 쿠폰 검증 결과 */
sealed interface CouponValidationResult {
    data class Valid(val discount: BigDecimal, val creatorId: Int, val type: CouponType) : CouponValidationResult
    data class Invalid(val errorMessage: String) : CouponValidationResult
}

/** 쿠폰 사용·환불 처리 */
class CouponRedemptionService(
    private val couponRepository: CouponRepository,
    private val userCouponRepository: UserCouponRepository,
    private val subscriptionRepository: SubscriptionRepository,
    private val subscriptionPlanRepository: SubscriptionPlanRepository
) {
    private val logger = logger()

    suspend fun calcDiscountInTransaction(
        userId: Int,
        couponCode: String,
        orderAmount: BigDecimal,
        productIds: List<Int>,
        shippingFeeByCreator: Map<Int, BigDecimal> = emptyMap(),
        productCreatorMap: Map<Int, Int> = emptyMap(),
        creatorAmountMap: Map<Int, BigDecimal> = emptyMap()
    ): CouponValidationResult {
        val now = nowUtc()

        val coupon = couponRepository.findByCode(couponCode)
            ?: return CouponValidationResult.Invalid(Errors.Coupon.COUPON_NOT_FOUND)

        val userCoupon = userCouponRepository.findByUserIdAndCouponId(userId, coupon.id.value)

        // creatorId 상품 금액만 계산
        val creatorOrderAmount = if (creatorAmountMap.isNotEmpty()) {
            creatorAmountMap[coupon.creatorId] ?: BigDecimal.ZERO
        } else {
            orderAmount
        }

        validateCouponUsage(coupon, userCoupon, creatorOrderAmount, now)?.let {
            return CouponValidationResult.Invalid(it)
        }

        val isApplicable = checkCouponApplies(coupon, productIds, productCreatorMap)
        if (!isApplicable) {
            return CouponValidationResult.Invalid(Errors.Coupon.COUPON_NOT_APPLICABLE)
        }

        // FREE_SHIPPING 쿠폰 구독자 차단
        if (coupon.type == CouponType.FREE_SHIPPING) {
            val subs =
                subscriptionRepository.findActiveSubscriptionsByCreators(userId, listOf(coupon.creatorId))
            if (subs.isNotEmpty()) {
                val subPlans = subscriptionPlanRepository.findPlansByIds(subs.map { it.planId })
                if (subPlans.any { it.tier != SubscriptionPlanTier.FREE }) {
                    return CouponValidationResult.Invalid(Errors.Coupon.FREE_SHIPPING_COUPON_NOT_ALLOWED_FOR_SUBSCRIBERS)
                }
            }
        }

        // FREE_SHIPPING은 배송비 조회
        val creatorShippingFee = shippingFeeByCreator[coupon.creatorId] ?: BigDecimal.ZERO

        val discount = calcCouponDiscount(
            orderAmount = creatorOrderAmount,
            couponType = coupon.type,
            discountValue = coupon.discountValue,
            maxDiscountAmount = coupon.maxDiscountAmount,
            shippingFee = creatorShippingFee
        )

        return CouponValidationResult.Valid(discount, coupon.creatorId, coupon.type)
    }

    private fun validateCouponUsage(
        coupon: CouponDao,
        userCoupon: UserCouponDao?,
        orderAmount: BigDecimal,
        now: LocalDateTime
    ): String? {
        if (userCoupon == null) {
            return Errors.Coupon.USER_COUPON_NOT_FOUND
        }

        // 쿠폰 상태·기간은 발급에만, 받은 쿠폰은 발급 때 만료일로
        if (userCoupon.status != UserCouponStatus.AVAILABLE) {
            return when (userCoupon.status) {
                UserCouponStatus.USED -> Errors.Coupon.COUPON_ALREADY_USED
                UserCouponStatus.EXPIRED -> Errors.Coupon.COUPON_EXPIRED
                else -> Errors.Coupon.COUPON_NOT_USABLE
            }
        }

        if (now > userCoupon.expiresAt) {
            return Errors.Coupon.COUPON_EXPIRED
        }

        if (userCoupon.useCount >= coupon.maxUseCount) {
            return Errors.Coupon.COUPON_MAX_USE_EXCEEDED
        }

        if (orderAmount < coupon.minOrderAmount) {
            return "${Errors.Coupon.COUPON_MIN_ORDER_NOT_MET} (최소 ${coupon.minOrderAmount.toAmountString()}원)"
        }

        return null
    }

    /** 쿠폰 적용 대상 검증 */
    private fun checkCouponApplies(
        coupon: CouponDao,
        productIds: List<Int>,
        productCreatorMap: Map<Int, Int> = emptyMap()
    ): Boolean {
        if (productCreatorMap.isNotEmpty()) {
            val hasCreatorProduct = productCreatorMap.values.any { it == coupon.creatorId }
            if (!hasCreatorProduct) return false
        }

        return when (coupon.discountTarget) {
            CouponDiscountTarget.ALL -> true
            CouponDiscountTarget.PRODUCT -> {
                val targetIds = coupon.targetIds.decodeToTargetIds()
                productIds.any { it in targetIds }
            }

            CouponDiscountTarget.CATEGORY -> {
                // TODO
                true
            }
        }
    }

    suspend fun useCouponInTransaction(userId: Int, couponCode: String, orderId: Int) {
        val coupon = couponRepository.findByCode(couponCode)
            ?: throw NotFoundException(Errors.Coupon.COUPON_NOT_FOUND)

        val userCoupon = userCouponRepository.findByUserIdAndCouponIdForUpdate(userId, coupon.id.value)
            ?: throw NotFoundException(Errors.Coupon.USER_COUPON_NOT_FOUND)

        // 소비 시점 재검증
        if (userCoupon.status != UserCouponStatus.AVAILABLE) {
            throw ConflictException(Errors.Coupon.COUPON_ALREADY_USED)
        }
        if (userCoupon.useCount >= coupon.maxUseCount) {
            throw ConflictException(Errors.Coupon.COUPON_MAX_USE_EXCEEDED)
        }

        val useCountAfter = userCoupon.useCount + 1
        userCouponRepository.incrementUseCount(userCoupon.id.value)

        if (useCountAfter >= coupon.maxUseCount) {
            userCouponRepository.markAsUsed(userCoupon.id.value, orderId)
        }
    }

    suspend fun refundCouponInTransaction(userId: Int, couponCode: String) {
        val coupon = couponRepository.findByCode(couponCode)
        val userCoupon = coupon?.let { userCouponRepository.findByUserIdAndCouponIdForUpdate(userId, it.id.value) }

        // 탈퇴로 지워진 쿠폰은 복구 없이 취소 진행
        if (coupon == null || userCoupon == null) {
            logger.warn("쿠폰 복구 대상 없음: userId={}, couponCode={}", userId, couponCode)
            return
        }

        userCouponRepository.refund(userCoupon.id.value, coupon.maxUseCount)
    }

    suspend fun expireUserCoupons(): Int {
        val now = nowUtc()

        return query {
            userCouponRepository.expireUserCoupons(now)
        }
    }
}
