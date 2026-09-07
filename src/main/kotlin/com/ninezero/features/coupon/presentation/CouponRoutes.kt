package com.ninezero.features.coupon.presentation

import com.ninezero.core.common.config.*
import com.ninezero.core.common.exception.Errors
import com.ninezero.core.common.util.*
import com.ninezero.features.coupon.domain.CouponService
import com.ninezero.features.coupon.domain.CouponRedemptionService
import com.ninezero.features.coupon.presentation.models.request.ClaimCouponRequest
import com.ninezero.features.coupon.presentation.models.request.CouponRequest
import com.ninezero.features.coupon.presentation.models.request.UpdateCouponRequest
import io.github.smiley4.ktoropenapi.delete
import io.github.smiley4.ktoropenapi.get
import io.github.smiley4.ktoropenapi.post
import io.github.smiley4.ktoropenapi.put
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.koin.ktor.ext.inject

fun Route.couponRoutes() {
    route(Constants.Endpoints.COUPONS) {
        val couponService by inject<CouponService>()
        val couponRedemptionService by inject<CouponRedemptionService>()

        authenticate("jwt") {
            post({
                summary = "쿠폰 생성 (Creator)"
                tags("Coupons")
                request {
                    body<CouponRequest> {
                        description = "쿠폰 정보"
                        required = true
                        example("percentage") {
                            value = CouponRequest(
                                code = "WELCOME10",
                                name = "신규 회원 10% 할인",
                                description = "첫 구매 시 10% 할인",
                                type = CouponType.PERCENTAGE,
                                discountTarget = CouponDiscountTarget.ALL,
                                discountValue = "10",
                                minOrderAmount = "30000",
                                maxDiscountAmount = "10000",
                                totalQuantity = 100,
                                maxUseCount = 1,
                                targetIds = null,
                                startDate = "2025-01-01T00:00:00Z",
                                endDate = "2025-12-31T23:59:59Z"
                            )
                        }
                        example("fixed_amount") {
                            value = CouponRequest(
                                code = "SAVE5000",
                                name = "5천원 할인 쿠폰",
                                description = "5만원 이상 구매 시 5천원 할인",
                                type = CouponType.FIXED_AMOUNT,
                                discountTarget = CouponDiscountTarget.PRODUCT,
                                discountValue = "5000",
                                minOrderAmount = "50000",
                                maxDiscountAmount = null,
                                totalQuantity = 50,
                                maxUseCount = 1,
                                targetIds = listOf(1, 2, 3),
                                startDate = "2025-01-01T00:00:00Z",
                                endDate = "2025-06-30T23:59:59Z"
                            )
                        }
                        example("free_shipping") {
                            value = CouponRequest(
                                code = null,
                                name = "무료 배송 쿠폰",
                                description = "모든 상품 무료 배송",
                                type = CouponType.FREE_SHIPPING,
                                discountTarget = CouponDiscountTarget.ALL,
                                discountValue = "0",
                                minOrderAmount = null,
                                maxDiscountAmount = null,
                                totalQuantity = 200,
                                maxUseCount = 1,
                                targetIds = null,
                                startDate = "2025-01-01T00:00:00Z",
                                endDate = "2025-12-31T23:59:59Z"
                            )
                        }
                    }
                }
                authResponse()
                response {
                    code(HttpStatusCode.Created) {
                        description = "쿠폰 생성 성공"
                    }
                    code(HttpStatusCode.BadRequest) {
                        description = "잘못된 쿠폰 데이터"
                    }
                    code(HttpStatusCode.Conflict) {
                        description = "이미 존재하는 쿠폰 코드"
                    }
                    code(HttpStatusCode.Forbidden) {
                        description = "크리에이터 또는 관리자 권한 필요"
                    }
                    code(HttpStatusCode.Unauthorized) {
                        description = "인증 필요"
                    }
                }
            }) {
                val userId = call.requireUserId() ?: return@post

                val userRole = call.getUserRole()
                if (userRole != UserRole.CREATOR.name && userRole != UserRole.ADMIN.name) {
                    return@post call.respond(
                        HttpStatusCode.Forbidden,
                        ApiResponse.error<Unit>(Errors.Common.CREATOR_OR_ADMIN_ONLY)
                    )
                }

                val request = call.receive<CouponRequest>()
                val response = couponService.createCoupon(userId, request)
                call.respond(HttpStatusCode.Created, ApiResponse.success(response, Messages.Coupon.COUPON_CREATED))
            }

            get("/my-coupons", {
                summary = "내가 만든 쿠폰 목록 조회 (Creator)"
                tags("Coupons")
                request {
                    queryParameter<Int>("page") {
                        description = "페이지 번호 (기본값: 1)"
                        required = false
                    }
                    queryParameter<Int>("limit") {
                        description = "페이지 크기 (기본값: 20)"
                        required = false
                    }
                }
                authResponse()
                response {
                    code(HttpStatusCode.Unauthorized) {
                        description = "인증 필요"
                    }
                }
            }) {
                val userId = call.requireUserId() ?: return@get

                val page = call.getIntParam("page", 1)
                val limit = call.getIntParam("limit", Constants.DEFAULT_PAGE_LIMIT)

                val response = couponService.getMyCoupons(userId, page, limit)
                call.respond(HttpStatusCode.OK, ApiResponse.success(response))
            }

            get("/active", {
                summary = "활성 쿠폰 목록 조회 (공개)"
                tags("Coupons")
                request {
                    queryParameter<Int>("page") {
                        description = "페이지 번호 (기본값: 1)"
                        required = false
                    }
                    queryParameter<Int>("limit") {
                        description = "페이지 크기 (기본값: 20)"
                        required = false
                    }
                }
                apiResponse()
                response {
                    code(HttpStatusCode.Unauthorized) {
                        description = "인증 필요"
                    }
                }
            }) {
                val page = call.getIntParam("page", 1)
                val limit = call.getIntParam("limit", Constants.DEFAULT_PAGE_LIMIT)

                val response = couponService.getActiveCoupons(page, limit)
                call.respond(HttpStatusCode.OK, ApiResponse.success(response))
            }

            get("/{id}", {
                summary = "쿠폰 상세 조회 (공개)"
                tags("Coupons")
                request {
                    pathParameter<Int>("id") {
                        description = "쿠폰 ID"
                        required = true
                    }
                }
                apiResponse()
                response {
                    code(HttpStatusCode.NotFound) {
                        description = "쿠폰 없음"
                    }
                    code(HttpStatusCode.Unauthorized) {
                        description = "인증 필요"
                    }
                }
            }) {
                val couponId = call.getRequiredIntParam("id", Errors.Coupon.INVALID_COUPON_ID)
                    ?: return@get

                val response = couponService.getCouponById(couponId)
                call.respond(HttpStatusCode.OK, ApiResponse.success(response))
            }

            put("/{id}", {
                summary = "쿠폰 수정 (Creator)"
                tags("Coupons")
                request {
                    pathParameter<Int>("id") {
                        description = "쿠폰 ID"
                        required = true
                    }
                    body<UpdateCouponRequest> {
                        description = "수정할 쿠폰 정보 (모든 필드 선택사항)"
                        required = true
                        example("update_all") {
                            value = UpdateCouponRequest(
                                name = "수정된 쿠폰 이름",
                                description = "수정된 설명",
                                minOrderAmount = "40000",
                                maxDiscountAmount = "15000",
                                totalQuantity = 150,
                                maxUseCount = 2,
                                status = CouponStatus.ACTIVE,
                                startDate = "2025-02-01T00:00:00Z",
                                endDate = "2025-11-30T23:59:59Z"
                            )
                        }
                        example("status_only") {
                            value = UpdateCouponRequest(
                                name = null,
                                description = null,
                                minOrderAmount = null,
                                maxDiscountAmount = null,
                                totalQuantity = null,
                                maxUseCount = null,
                                status = CouponStatus.DISABLED,
                                startDate = null,
                                endDate = null
                            )
                        }
                    }
                }
                authResponse()
                response {
                    code(HttpStatusCode.BadRequest) {
                        description = "잘못된 쿠폰 데이터"
                    }
                    code(HttpStatusCode.NotFound) {
                        description = "쿠폰 없음"
                    }
                    code(HttpStatusCode.Forbidden) {
                        description = "쿠폰 수정 권한 없음"
                    }
                    code(HttpStatusCode.Unauthorized) {
                        description = "인증 필요"
                    }
                }
            }) {
                val userId = call.requireUserId() ?: return@put

                val couponId = call.getRequiredIntParam("id", Errors.Coupon.INVALID_COUPON_ID)
                    ?: return@put

                val request = call.receive<UpdateCouponRequest>()
                val response = couponService.updateCoupon(couponId, userId, request)
                call.respond(HttpStatusCode.OK, ApiResponse.success(response, Messages.Coupon.COUPON_UPDATED))
            }

            delete("/{id}", {
                summary = "쿠폰 삭제 (Creator)"
                tags("Coupons")
                request {
                    pathParameter<Int>("id") {
                        description = "쿠폰 ID"
                        required = true
                    }
                }
                authResponse()
                response {
                    code(HttpStatusCode.NoContent) {
                        description = "쿠폰 삭제 성공"
                    }
                    code(HttpStatusCode.NotFound) {
                        description = "쿠폰 없음"
                    }
                    code(HttpStatusCode.Forbidden) {
                        description = "쿠폰 삭제 권한 없음"
                    }
                    code(HttpStatusCode.Unauthorized) {
                        description = "인증 필요"
                    }
                }
            }) {
                val userId = call.requireUserId() ?: return@delete

                val couponId = call.getRequiredIntParam("id", Errors.Coupon.INVALID_COUPON_ID)
                    ?: return@delete

                couponService.deleteCoupon(couponId, userId)
                call.respond(HttpStatusCode.NoContent)
            }

            post("/claim", {
                summary = "쿠폰 받기"
                tags("Coupons")
                request {
                    body<ClaimCouponRequest> {
                        description = "쿠폰 코드"
                        required = true
                        example("default") {
                            value = ClaimCouponRequest(code = "WELCOME10")
                        }
                    }
                }
                authResponse()
                response {
                    code(HttpStatusCode.Created) {
                        description = "쿠폰 발급 성공"
                    }
                    code(HttpStatusCode.BadRequest) {
                        description = "잘못된 쿠폰 코드 또는 발급 불가"
                    }
                    code(HttpStatusCode.NotFound) {
                        description = "쿠폰 없음"
                    }
                    code(HttpStatusCode.Conflict) {
                        description = "이미 발급받은 쿠폰 또는 재고 소진"
                    }
                    code(HttpStatusCode.Unauthorized) {
                        description = "인증 필요"
                    }
                }
            }) {
                val userId = call.requireUserId() ?: return@post

                val request = call.receive<ClaimCouponRequest>()
                val response = couponService.claimCoupon(userId, request.code)
                call.respond(HttpStatusCode.Created, ApiResponse.success(response, Messages.Coupon.COUPON_CLAIMED))
            }

            get("/my-list", {
                summary = "내 쿠폰 목록 조회"
                tags("Coupons")
                request {
                    queryParameter<Int>("page") {
                        description = "페이지 번호 (기본값: 1)"
                        required = false
                    }
                    queryParameter<Int>("limit") {
                        description = "페이지 크기 (기본값: 20)"
                        required = false
                    }
                    queryParameter<String>("status") {
                        description = "쿠폰 상태 필터 (AVAILABLE/USED/EXPIRED, 미지정 시 전체)"
                        required = false
                    }
                }
                authResponse()
                response {
                    code(HttpStatusCode.Unauthorized) {
                        description = "인증 필요"
                    }
                }
            }) {
                val userId = call.requireUserId() ?: return@get

                val page = call.getIntParam("page", 1)
                val limit = call.getIntParam("limit", Constants.DEFAULT_PAGE_LIMIT)
                val status = call.getOptionalStringParam("status")
                    ?.let { runCatching { UserCouponStatus.valueOf(it.uppercase()) }.getOrNull() }

                val response = couponService.getMyCouponList(userId, page, limit, status)
                call.respond(HttpStatusCode.OK, ApiResponse.success(response))
            }

            post("/expire", {
                summary = "쿠폰 수동 만료 (Admin)"
                tags("Coupons")
                authResponse()
                response {
                    code(HttpStatusCode.Forbidden) {
                        description = "관리자 권한 필요"
                    }
                    code(HttpStatusCode.Unauthorized) {
                        description = "인증 필요"
                    }
                }
            }) {
                call.requireAdminId() ?: return@post

                val expiredCount = couponRedemptionService.expireUserCoupons()
                call.respond(
                    HttpStatusCode.OK,
                    ApiResponse.success(
                        mapOf(
                            "expiredCount" to expiredCount,
                            "message" to Messages.Coupon.COUPON_EXPIRED_COUNT.format(expiredCount)
                        )
                    )
                )
            }
        }
    }
}
