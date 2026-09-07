package com.ninezero.features.coupon.domain

import com.ninezero.core.common.config.CouponDiscountTarget
import com.ninezero.core.common.config.CouponStatus
import com.ninezero.core.common.config.CouponType
import com.ninezero.core.common.config.SubscriptionPlanTier
import com.ninezero.core.common.config.UserCouponStatus
import com.ninezero.core.common.exception.ConflictException
import com.ninezero.core.common.exception.Errors
import com.ninezero.core.common.exception.NotFoundException
import com.ninezero.core.common.util.decodeToTargetIds
import com.ninezero.core.common.util.nowUtc
import com.ninezero.core.common.util.query
import com.ninezero.core.database.entities.coupon.CouponDao
import com.ninezero.core.database.entities.coupon.UserCouponDao
import com.ninezero.features.coupon.calcCouponDiscount
import com.ninezero.features.coupon.data.CouponRepository
import com.ninezero.features.coupon.data.UserCouponRepository
import com.ninezero.features.subscription.data.SubscriptionPlanRepository
import com.ninezero.features.subscription.data.SubscriptionRepository
import kotlinx.datetime.LocalDateTime
import java.math.BigDecimal

/** 쿠폰 검증 결과 */
sealed interface CouponValidationResult {
    data class Valid(val discount: BigDecimal) : CouponValidationResult
    data class Invalid(val errorMessage: String) : CouponValidationResult
}

/** 쿠폰 사용·환불 처리 */
class CouponRedemptionService(
    private val couponRepository: CouponRepository,
    private val userCouponRepository: UserCouponRepository,
    private val subscriptionRepository: SubscriptionRepository,
    private val subscriptionPlanRepository: SubscriptionPlanRepository
) {

    suspend fun calcDiscount(
        userId: Int,
        couponCode: String,
        orderAmount: BigDecimal,
        productIds: List<Int>,
        shippingFeeByCreator: Map<Int, BigDecimal> = emptyMap(),
        productCreatorMap: Map<Int, Int> = emptyMap(),
        creatorAmountMap: Map<Int, BigDecimal> = emptyMap()
    ): CouponValidationResult {
        return try {
            val now = nowUtc()

            query {
                val coupon = couponRepository.findByCode(couponCode)
                    ?: return@query CouponValidationResult.Invalid(Errors.Coupon.COUPON_NOT_FOUND)

                val userCoupon = userCouponRepository.findByUserIdAndCouponId(userId, coupon.id.value)

                // creatorId 상품 금액만 계산
                val creatorOrderAmount = if (creatorAmountMap.isNotEmpty()) {
                    creatorAmountMap[coupon.creatorId] ?: BigDecimal.ZERO
                } else {
                    orderAmount
                }

                val (valid, error) = validateCouponUsage(coupon, userCoupon, creatorOrderAmount, now)
                if (!valid) {
                    return@query CouponValidationResult.Invalid(error ?: Errors.Coupon.COUPON_NOT_USABLE)
                }

                val isApplicable = checkCouponApplies(coupon, productIds, productCreatorMap)
                if (!isApplicable) {
                    return@query CouponValidationResult.Invalid(Errors.Coupon.COUPON_NOT_APPLICABLE)
                }

                // FREE_SHIPPING 쿠폰 구독자 차단
                if (coupon.type == CouponType.FREE_SHIPPING) {
                    val subs = subscriptionRepository.findActiveSubscriptionsByCreators(userId, listOf(coupon.creatorId))
                    if (subs.isNotEmpty()) {
                        val subPlans = subscriptionPlanRepository.findPlansByIds(subs.map { it.planId })
                        if (subPlans.any { it.tier != SubscriptionPlanTier.FREE }) {
                            return@query CouponValidationResult.Invalid(Errors.Coupon.FREE_SHIPPING_COUPON_NOT_ALLOWED_FOR_SUBSCRIBERS)
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

                CouponValidationResult.Valid(discount)
            }
        } catch (e: Exception) {
            CouponValidationResult.Invalid("쿠폰 검증 중 오류가 발생했습니다: ${e.message}")
        }
    }

    private fun validateCouponUsage(
        coupon: CouponDao,
        userCoupon: UserCouponDao?,
        orderAmount: BigDecimal,
        now: LocalDateTime
    ): Pair<Boolean, String?> {
        if (userCoupon == null) {
            return false to Errors.Coupon.USER_COUPON_NOT_FOUND
        }

        if (coupon.status != CouponStatus.ACTIVE) {
            return false to when (coupon.status) {
                CouponStatus.EXPIRED -> Errors.Coupon.COUPON_EXPIRED
                CouponStatus.DISABLED -> Errors.Coupon.COUPON_DISABLED
                else -> Errors.Coupon.COUPON_NOT_USABLE
            }
        }

        if (now < coupon.startDate || now > coupon.endDate) {
            return false to Errors.Coupon.COUPON_EXPIRED
        }

        if (userCoupon.status != UserCouponStatus.AVAILABLE) {
            return false to when (userCoupon.status) {
                UserCouponStatus.USED -> Errors.Coupon.COUPON_ALREADY_USED
                UserCouponStatus.EXPIRED -> Errors.Coupon.COUPON_EXPIRED
                else -> Errors.Coupon.COUPON_NOT_USABLE
            }
        }

        if (now > userCoupon.expiresAt) {
            return false to Errors.Coupon.COUPON_EXPIRED
        }

        if (userCoupon.useCount >= coupon.maxUseCount) {
            return false to Errors.Coupon.COUPON_MAX_USE_EXCEEDED
        }

        if (orderAmount < coupon.minOrderAmount) {
            return false to "${Errors.Coupon.COUPON_MIN_ORDER_NOT_MET} (최소 ${coupon.minOrderAmount}원)"
        }

        return true to null
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

        val userCoupon = userCouponRepository.findByUserIdAndCouponId(userId, coupon.id.value)
            ?: throw NotFoundException(Errors.Coupon.USER_COUPON_NOT_FOUND)

        // 소비 시점 재검증
        if (userCoupon.status != UserCouponStatus.AVAILABLE) {
            throw ConflictException(Errors.Coupon.COUPON_ALREADY_USED)
        }
        if (userCoupon.useCount >= coupon.maxUseCount) {
            throw ConflictException(Errors.Coupon.COUPON_MAX_USE_EXCEEDED)
        }

        userCouponRepository.incrementUseCount(userCoupon.id.value)

        if (userCoupon.useCount + 1 >= coupon.maxUseCount) {
            userCouponRepository.markAsUsed(userCoupon.id.value, orderId)
        }
    }

    suspend fun refundCouponInTransaction(userId: Int, couponCode: String) {
        val coupon = couponRepository.findByCode(couponCode)
            ?: throw NotFoundException(Errors.Coupon.COUPON_NOT_FOUND)

        val userCoupon = userCouponRepository.findByUserIdAndCouponId(userId, coupon.id.value)
            ?: throw NotFoundException(Errors.Coupon.USER_COUPON_NOT_FOUND)

        userCouponRepository.refund(userCoupon.id.value)
    }

    suspend fun expireUserCoupons(): Int {
        val now = nowUtc()

        return query {
            userCouponRepository.expireUserCoupons(now)
        }
    }
}
