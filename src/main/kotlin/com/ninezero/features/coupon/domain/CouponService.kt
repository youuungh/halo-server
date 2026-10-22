package com.ninezero.features.coupon.domain

import com.ninezero.core.common.config.Constants
import com.ninezero.core.common.config.CouponStatus
import com.ninezero.core.common.config.CouponType
import com.ninezero.core.common.config.UserCouponStatus
import com.ninezero.core.common.config.UserRole
import com.ninezero.core.common.exception.*
import com.ninezero.core.common.util.*
import com.ninezero.features.coupon.data.CouponRepository
import com.ninezero.features.coupon.data.UserCouponRepository
import com.ninezero.features.coupon.encodeToTargetIds
import com.ninezero.features.coupon.generateCouponCode
import com.ninezero.features.coupon.presentation.models.request.CouponRequest
import com.ninezero.features.coupon.presentation.models.request.UpdateCouponRequest
import com.ninezero.features.coupon.presentation.models.response.CouponListResponse
import com.ninezero.features.coupon.presentation.models.response.CouponResponse
import com.ninezero.features.coupon.presentation.models.response.UserCouponListResponse
import com.ninezero.features.coupon.presentation.models.response.UserCouponResponse
import com.ninezero.features.coupon.toCouponResponse
import com.ninezero.features.coupon.toUserCouponResponse
import com.ninezero.features.user.data.UserRepository
import java.math.BigDecimal

class CouponService(
    private val couponRepository: CouponRepository,
    private val userCouponRepository: UserCouponRepository,
    private val userRepository: UserRepository
) {

    // 쿠폰 생성
    suspend fun createCoupon(
        creatorId: Int,
        request: CouponRequest
    ): CouponResponse {
        val discountValue = BigDecimal(request.discountValue)
        val minOrderAmount = request.minOrderAmount?.let { BigDecimal(it) } ?: BigDecimal.ZERO
        val maxDiscountAmount = request.maxDiscountAmount?.let { BigDecimal(it) }
        val targetIds = request.targetIds?.encodeToTargetIds()

        ValidationUtils.validateDateRange(request.startDate, request.endDate)
        ValidationUtils.validateCouponTypeFields(
            type = request.type,
            discountRate = if (request.type == CouponType.PERCENTAGE) discountValue.toInt() else null,
            discountAmount = if (request.type == CouponType.FIXED_AMOUNT) request.discountValue else null
        )
        ValidationUtils.validateTargetIds(request.targetIds)
        ValidationUtils.validateCouponMaxDiscountAmount(request.maxDiscountAmount)

        if (request.type == CouponType.PERCENTAGE && discountValue > BigDecimal(Constants.Coupon.MAX_DISCOUNT_RATE)) {
            throw InvalidInputException(Errors.Coupon.COUPON_DISCOUNT_RATE_EXCEEDED)
        }

        val name = request.name.trim()
        val description = request.description?.trim()

        return query {
            // 토큰 대신 DB 역할로 해제 크리에이터 차단
            val creator = userRepository.findUserById(creatorId)
                ?.takeIf { it.role == UserRole.CREATOR || it.role == UserRole.ADMIN }
                ?: throw ForbiddenException(Errors.Common.CREATOR_OR_ADMIN_ONLY)

            val code = if (request.code != null) {
                val manualCode = request.code.uppercase().trim()

                ValidationUtils.validateManualCouponCode(manualCode)

                if (manualCode.isBlank()) {
                    throw InvalidCouponCodeException(Errors.Coupon.COUPON_CODE_FORMAT_INVALID)
                }

                if (couponRepository.existsByCode(manualCode)) {
                    throw ConflictException(Errors.Coupon.COUPON_CODE_DUPLICATE)
                }

                manualCode
            } else {
                generateCouponCode()  // 36의 12제곱 가짓수라 충돌은 유니크 인덱스에 맡김
            }

            couponRepository.create(
                creatorId = creatorId,
                code = code,
                name = name,
                description = description,
                type = request.type,
                discountTarget = request.discountTarget,
                discountValue = discountValue,
                minOrderAmount = minOrderAmount,
                maxDiscountAmount = maxDiscountAmount,
                totalQuantity = request.totalQuantity,
                maxUseCount = request.maxUseCount,
                targetIds = targetIds,
                startDate = request.startDate,
                endDate = request.endDate
            ).toCouponResponse(creatorName = creator.profile?.displayName ?: creator.username)
        }
    }

    // 쿠폰 조회
    suspend fun getMyCoupons(
        creatorId: Int,
        page: Int,
        limit: Int
    ): CouponListResponse {
        val (validPage, validLimit) = validatePaginationParams(page, limit)

        return query {
            val coupons = couponRepository.findByCreatorId(creatorId, validPage, validLimit)
            val totalCount = couponRepository.countByCreatorId(creatorId)
            val creatorName = getCreatorName(creatorId)

            val pagination = PaginationInfo(
                page = validPage,
                limit = validLimit,
                totalCount = totalCount.toInt()
            )

            createPagedResponse(
                items = coupons.map { it.toCouponResponse(creatorName) },
                pagination = pagination
            )
        }
    }

    // 쿠폰 수정/삭제
    suspend fun updateCoupon(
        couponId: Int,
        creatorId: Int,
        request: UpdateCouponRequest
    ): CouponResponse {
        ValidationUtils.validateCouponMaxDiscountAmount(request.maxDiscountAmount)

        // null이면 유지, 빈 값이면 지움
        val clearDescription = request.description?.isBlank() == true
        val clearMaxDiscountAmount = request.maxDiscountAmount?.isBlank() == true
        val minOrderAmount = request.minOrderAmount?.let { if (it.isBlank()) BigDecimal.ZERO else BigDecimal(it) }
        val maxDiscountAmount = request.maxDiscountAmount?.takeIf { it.isNotBlank() }?.let { BigDecimal(it) }

        val name = request.name?.trim()
        val description = request.description?.trim()?.takeIf { it.isNotEmpty() }

        return query {
            val coupon = couponRepository.findById(couponId)
                ?.takeUnless { it.isDeleted }  // 삭제된 쿠폰은 수정·재활성화 대상 아님
                ?: throw NotFoundException(Errors.Coupon.COUPON_NOT_FOUND)

            if (coupon.creatorId != creatorId) {
                throw ForbiddenException(Errors.Coupon.COUPON_UPDATE_PERMISSION_DENIED)
            }

            // 토큰 대신 DB 역할로 해제 크리에이터 차단
            val role = userRepository.findUserById(creatorId)?.role
            if (role != UserRole.CREATOR && role != UserRole.ADMIN) {
                throw ForbiddenException(Errors.Coupon.COUPON_UPDATE_PERMISSION_DENIED)
            }

            if (request.totalQuantity != null && request.totalQuantity < coupon.issuedQuantity) {
                throw InvalidInputException(Errors.Coupon.TOTAL_QUANTITY_BELOW_ISSUED)
            }

            // 받은 사람이 있으면 사용 조건 고정, 앱은 전체 값을 보내므로 바뀐 것만 막음
            val changesTerms = (minOrderAmount != null && !isSameAmount(minOrderAmount, coupon.minOrderAmount)) ||
                    (request.maxDiscountAmount != null && !isSameAmount(maxDiscountAmount, coupon.maxDiscountAmount)) ||
                    (request.maxUseCount != null && request.maxUseCount != coupon.maxUseCount)
            if (coupon.issuedQuantity > 0 && changesTerms) {
                throw InvalidInputException(Errors.Coupon.TERMS_LOCKED_AFTER_ISSUE)
            }

            // 한쪽만 바꿔도 저장값과 합쳐 기간 검증
            if (request.startDate != null || request.endDate != null) {
                ValidationUtils.validateDateRange(request.startDate ?: coupon.startDate, request.endDate ?: coupon.endDate)
            }

            val updated = couponRepository.updateCoupon(
                couponId = couponId,
                name = name,
                description = description,
                minOrderAmount = minOrderAmount,
                maxDiscountAmount = maxDiscountAmount,
                totalQuantity = request.totalQuantity,
                maxUseCount = request.maxUseCount,
                status = request.status,
                startDate = request.startDate,
                endDate = request.endDate,
                clearDescription = clearDescription,
                clearMaxDiscountAmount = clearMaxDiscountAmount
            )

            if (!updated) {
                throw InternalServerException(Errors.Coupon.COUPON_UPDATE_FAILED)
            }

            coupon.toCouponResponse(getCreatorName(creatorId))  // 같은 트랜잭션의 엔티티라 수정값 반영됨
        }
    }

    suspend fun deleteCoupon(couponId: Int, creatorId: Int) {
        query {
            val coupon = couponRepository.findById(couponId)
                ?.takeUnless { it.isDeleted }
                ?: throw NotFoundException(Errors.Coupon.COUPON_NOT_FOUND)

            if (coupon.creatorId != creatorId) {
                throw ForbiddenException(Errors.Coupon.COUPON_DELETE_PERMISSION_DENIED)
            }

            val deleted = couponRepository.deleteCoupon(couponId)
            if (!deleted) {
                throw InternalServerException(Errors.Coupon.COUPON_DELETE_FAILED)
            }
        }
    }

    // 쿠폰 발급·지갑
    suspend fun claimCoupon(userId: Int, code: String): UserCouponResponse {
        val normalizedCode = code.uppercase().trim()

        return query {
            val now = nowUtc()

            val coupon = couponRepository.findByCodeForUpdate(normalizedCode)
                ?: throw NotFoundException(Errors.Coupon.COUPON_CODE_INVALID)

            if (coupon.status != CouponStatus.ACTIVE) {
                throw InvalidCouponCodeException(Errors.Coupon.COUPON_DISABLED)
            }

            if (now < coupon.startDate || now > coupon.endDate) {
                throw InvalidCouponCodeException(Errors.Coupon.COUPON_EXPIRED)
            }

            if (coupon.issuedQuantity >= coupon.totalQuantity) {
                throw InvalidCouponCodeException(Errors.Coupon.COUPON_OUT_OF_STOCK)
            }

            if (userCouponRepository.findByUserIdAndCouponId(userId, coupon.id.value) != null) {
                throw ConflictException(Errors.Coupon.COUPON_ALREADY_CLAIMED)
            }

            // 만료는 쿠폰 endDate 그대로
            val userCoupon = userCouponRepository.create(userId, coupon.id.value, coupon.endDate)
            couponRepository.incrementIssuedQuantity(coupon.id.value)

            userCoupon.toUserCouponResponse(coupon, getCreatorName(coupon.creatorId))
        }
    }

    suspend fun getMyCouponList(
        userId: Int,
        page: Int,
        limit: Int,
        status: UserCouponStatus? = null
    ): UserCouponListResponse {
        val (validPage, validLimit) = validatePaginationParams(page, limit)

        return query {
            val userCoupons = when (status) {
                null -> userCouponRepository.findByUserId(userId, validPage, validLimit)
                UserCouponStatus.AVAILABLE -> userCouponRepository.findAvailableByUserId(userId, validPage, validLimit)
                else -> userCouponRepository.findByUserIdAndStatus(userId, status, validPage, validLimit)
            }

            val totalCount = when (status) {
                null -> userCouponRepository.countByUserId(userId)
                UserCouponStatus.AVAILABLE -> userCouponRepository.countAvailableByUserId(userId)
                else -> userCouponRepository.countByUserIdAndStatus(userId, status)
            }

            val availableCount = if (status == UserCouponStatus.AVAILABLE) totalCount else userCouponRepository.countAvailableByUserId(userId)

            val couponIds = userCoupons.map { it.couponId }.distinct()
            val coupons = couponRepository.findByIds(couponIds)
            val couponMap = coupons.associateBy { it.id.value }
            val creatorNames = getCreatorNames(coupons.map { it.creatorId }.toSet())

            val responses = userCoupons.mapNotNull { userCoupon ->
                val coupon = couponMap[userCoupon.couponId] ?: return@mapNotNull null
                userCoupon.toUserCouponResponse(coupon, creatorNames[coupon.creatorId])
            }

            val pagination = PaginationInfo(
                page = validPage,
                limit = validLimit,
                totalCount = totalCount.toInt()
            )

            UserCouponListResponse(
                coupons = createPagedResponse(responses, pagination),
                availableCount = availableCount.toInt()
            )
        }
    }

    // 공통 보조
    private suspend fun getCreatorName(creatorId: Int): String? {
        val creator = userRepository.findUserById(creatorId) ?: return null
        return creator.profile?.displayName ?: creator.username
    }

    private suspend fun getCreatorNames(creatorIds: Set<Int>): Map<Int, String> {
        if (creatorIds.isEmpty()) return emptyMap()

        return userRepository.findUsersByIds(creatorIds)
            .associate { user ->
                val name = user.profile?.displayName ?: user.username
                user.id.value to name
            }
    }

    // 소수 자릿수 무관 금액 비교, 둘 다 없으면 같음
    private fun isSameAmount(a: BigDecimal?, b: BigDecimal?): Boolean =
        if (a == null || b == null) a == b else a.compareTo(b) == 0
}
