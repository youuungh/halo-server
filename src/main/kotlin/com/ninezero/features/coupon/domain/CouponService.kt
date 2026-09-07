package com.ninezero.features.coupon.domain

import com.ninezero.core.common.config.Constants
import com.ninezero.core.common.config.CouponType
import com.ninezero.core.common.config.UserCouponStatus
import com.ninezero.core.common.exception.*
import com.ninezero.core.common.exception.NotFoundException
import com.ninezero.core.common.util.*
import com.ninezero.core.database.entities.user.UserDao
import com.ninezero.core.database.entities.user.UserTable
import com.ninezero.features.coupon.data.CouponRepository
import com.ninezero.features.coupon.data.UserCouponRepository
import com.ninezero.features.coupon.generateCouponCode
import com.ninezero.features.coupon.presentation.models.request.CouponRequest
import com.ninezero.features.coupon.presentation.models.request.UpdateCouponRequest
import com.ninezero.features.coupon.presentation.models.response.CouponListResponse
import com.ninezero.features.coupon.presentation.models.response.CouponResponse
import com.ninezero.features.coupon.presentation.models.response.UserCouponListResponse
import com.ninezero.features.coupon.presentation.models.response.UserCouponResponse
import com.ninezero.features.coupon.toCouponResponse
import com.ninezero.features.coupon.toUserCouponResponse
import io.ktor.server.plugins.*
import kotlinx.datetime.LocalDateTime
import java.math.BigDecimal

class CouponService(
    private val couponRepository: CouponRepository,
    private val userCouponRepository: UserCouponRepository
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
        val startDate = LocalDateTime.parse(request.startDate)
        val endDate = LocalDateTime.parse(request.endDate)

        ValidationUtils.validateDateRange(startDate, endDate)
        ValidationUtils.validateCouponTypeFields(
            type = request.type,
            discountRate = if (request.type == CouponType.PERCENTAGE) discountValue.toInt() else null,
            discountAmount = if (request.type == CouponType.FIXED_AMOUNT) request.discountValue else null
        )
        ValidationUtils.validateTargetIds(request.targetIds)

        if (request.type == CouponType.PERCENTAGE && discountValue > BigDecimal(100)) {
            throw BadRequestException(Errors.Coupon.COUPON_DISCOUNT_RATE_EXCEEDED)
        }

        val name = ValidationUtils.sanitizeHtml(request.name)
        val description = request.description?.let { ValidationUtils.sanitizeHtml(it) }

        val coupon = query {
            val code = if (request.code != null) {
                val manualCode = request.code.uppercase().trim()

                ValidationUtils.validateManualCouponCode(manualCode)

                if (!manualCode.matches(Constants.Coupon.CODE_PATTERN.toRegex())) {
                    throw BadRequestException(Errors.Coupon.COUPON_CODE_FORMAT_INVALID)
                }

                if (couponRepository.existsByCode(manualCode)) {
                    throw ConflictException(Errors.Coupon.COUPON_CODE_DUPLICATE)
                }

                manualCode
            } else {
                var generatedCode = generateCouponCode()
                var attempts = 0
                val maxAttempts = 10

                while (couponRepository.existsByCode(generatedCode)) {
                    if (attempts++ >= maxAttempts) {
                        throw InternalServerException("쿠폰 코드 생성에 실패했습니다.")
                    }
                    generatedCode = generateCouponCode()
                }

                generatedCode
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
                startDate = startDate,
                endDate = endDate
            )
        }

        return query {
            val creatorName = getCreatorName(coupon.creatorId)
            coupon.toCouponResponse(creatorName)
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

    suspend fun getActiveCoupons(
        page: Int,
        limit: Int
    ): CouponListResponse {
        val (validPage, validLimit) = validatePaginationParams(page, limit)

        return query {
            val coupons = couponRepository.findActive(validPage, validLimit)
            val totalCount = couponRepository.countActive()
            val creatorNames = getCreatorNames(coupons.map { it.creatorId }.toSet())

            val pagination = PaginationInfo(
                page = validPage,
                limit = validLimit,
                totalCount = totalCount.toInt()
            )

            createPagedResponse(
                items = coupons.map { it.toCouponResponse(creatorNames[it.creatorId]) },
                pagination = pagination
            )
        }
    }

    suspend fun getCouponById(couponId: Int): CouponResponse {
        return query {
            val coupon = couponRepository.findById(couponId)
                ?: throw NotFoundException(Errors.Coupon.COUPON_NOT_FOUND)

            coupon.toCouponResponse(getCreatorName(coupon.creatorId))
        }
    }

    // 쿠폰 수정/삭제
    suspend fun updateCoupon(
        couponId: Int,
        creatorId: Int,
        request: UpdateCouponRequest
    ): CouponResponse {
        val minOrderAmount = request.minOrderAmount?.let { BigDecimal(it) }
        val maxDiscountAmount = request.maxDiscountAmount?.let { BigDecimal(it) }
        val startDate = request.startDate?.let { LocalDateTime.parse(it) }
        val endDate = request.endDate?.let { LocalDateTime.parse(it) }

        if (startDate != null && endDate != null) {
            ValidationUtils.validateDateRange(startDate, endDate)
        }

        val name = request.name?.let { ValidationUtils.sanitizeHtml(it) }
        val description = request.description?.let { ValidationUtils.sanitizeHtml(it) }

        return query {
            val coupon = couponRepository.findById(couponId)
                ?: throw NotFoundException(Errors.Coupon.COUPON_NOT_FOUND)

            if (coupon.creatorId != creatorId) {
                throw ForbiddenException(Errors.Coupon.COUPON_UPDATE_PERMISSION_DENIED)
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
                startDate = startDate,
                endDate = endDate
            )

            if (!updated) {
                throw InternalServerException(Errors.Coupon.COUPON_UPDATE_FAILED)
            }

            val updatedCoupon = couponRepository.findById(couponId)!!
            updatedCoupon.toCouponResponse(getCreatorName(updatedCoupon.creatorId))
        }
    }

    suspend fun deleteCoupon(couponId: Int, creatorId: Int) {
        query {
            val coupon = couponRepository.findById(couponId)
                ?: throw NotFoundException(Errors.Coupon.COUPON_NOT_FOUND)

            if (coupon.creatorId != creatorId) {
                throw ForbiddenException(Errors.Coupon.COUPON_DELETE_PERMISSION_DENIED)
            }

            val deleted = couponRepository.deleteCoupon(couponId)  // 실제로는 비활성화
            if (!deleted) {
                throw InternalServerException(Errors.Coupon.COUPON_DELETE_FAILED)
            }
        }
    }

    // 쿠폰 발급·지갑
    suspend fun claimCoupon(userId: Int, code: String): UserCouponResponse {
        val normalizedCode = code.uppercase().trim()

        return query {
            val (coupon, userCouponId) = couponRepository.claimByCode(userId, normalizedCode)

            val userCoupon = userCouponRepository.findById(userCouponId)
                ?: throw InternalServerException("쿠폰 발급에 실패했습니다.")

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

            val availableCount = userCouponRepository.countAvailableByUserId(userId)

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
    private fun getCreatorName(creatorId: Int): String? {
        val creator = UserDao.findById(creatorId) ?: return null
        return creator.profile?.displayName ?: creator.username
    }

    private fun getCreatorNames(creatorIds: Set<Int>): Map<Int, String> {
        if (creatorIds.isEmpty()) return emptyMap()

        return UserDao.find { UserTable.id inList creatorIds.toList() }
            .associate { user ->
                val name = user.profile?.displayName ?: user.username
                user.id.value to name
            }
    }
}
