package com.ninezero.features.commerce.presentation

import com.ninezero.core.common.config.Constants
import com.ninezero.core.common.config.Messages
import com.ninezero.core.common.config.ReviewSortType
import com.ninezero.core.common.exception.Errors
import com.ninezero.core.common.util.ApiResponse
import com.ninezero.core.common.util.apiResponse
import com.ninezero.core.common.util.authResponse
import com.ninezero.core.common.util.getIntParam
import com.ninezero.core.common.util.getOptionalEnumParam
import com.ninezero.core.common.util.getOptionalStringParam
import com.ninezero.core.common.util.getRequiredIntParam
import com.ninezero.core.common.util.receiveFileParts
import com.ninezero.core.common.util.requireUserId
import com.ninezero.core.storage.StorageConfig
import com.ninezero.features.commerce.domain.ReviewService
import io.github.smiley4.ktoropenapi.delete
import io.github.smiley4.ktoropenapi.get
import io.github.smiley4.ktoropenapi.post
import io.github.smiley4.ktoropenapi.put
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.Json
import org.koin.ktor.ext.inject

fun Route.reviewRoutes() {
    route(Constants.Endpoints.REVIEWS) {
        val reviewService by inject<ReviewService>()

        get("/products/{productId}", {
            summary = "상품 리뷰 목록 조회 (공개)"
            tags("Reviews")
            request {
                pathParameter<Int>("productId") {
                    description = "상품 ID"
                    required = true
                }
                queryParameter<String>("cursor") {
                    description = "다음 페이지 커서 (응답의 nextCursor, 생략 시 처음부터)"
                    required = false
                }
                queryParameter<Int>("limit") {
                    description = "페이지 크기 (기본값: 20)"
                    required = false
                }
                queryParameter<String>("sortBy") {
                    description = "정렬 방식 (RECENT, RATING_HIGH, RATING_LOW)"
                    required = false
                    example("recent") { value = "RECENT" }
                    example("rating_high") { value = "RATING_HIGH" }
                    example("rating_low") { value = "RATING_LOW" }
                }
            }
            apiResponse()
            response {
                code(HttpStatusCode.NotFound) {
                    description = "상품 없음"
                }
            }
        }) {
            val productId = call.getRequiredIntParam("productId", Errors.Commerce.Product.INVALID_PRODUCT_ID)
                ?: return@get

            val cursor = call.getOptionalStringParam("cursor")
            val limit = call.getIntParam("limit", Constants.DEFAULT_PAGE_LIMIT)

            val sortBy = call.getOptionalEnumParam<ReviewSortType>("sortBy") ?: ReviewSortType.RECENT

            val response = reviewService.getReviewsByProduct(productId, cursor, limit, sortBy)
            call.respond(HttpStatusCode.OK, ApiResponse.success(response))
        }

        get("/products/{productId}/summary", {
            summary = "리뷰 요약 조회 (공개)"
            tags("Reviews")
            request {
                pathParameter<Int>("productId") {
                    description = "상품 ID"
                    required = true
                }
            }
            apiResponse()
            response {
                code(HttpStatusCode.NotFound) {
                    description = "상품 없음"
                }
            }
        }) {
            val productId = call.getRequiredIntParam("productId", Errors.Commerce.Product.INVALID_PRODUCT_ID)
                ?: return@get

            val response = reviewService.getReviewSummary(productId)
            call.respond(HttpStatusCode.OK, ApiResponse.success(response))
        }

        authenticate("jwt") {
            post("/products/{productId}", {
                summary = "리뷰 작성"
                tags("Reviews")
                request {
                    pathParameter<Int>("productId") {
                        description = "상품 ID"
                        required = true
                    }
                    multipartBody {
                        description = "rating·content·orderId와 이미지 파일"
                        required = true
                    }
                }
                authResponse()
                response {
                    code(HttpStatusCode.Created) {
                        description = "리뷰 작성 성공"
                    }
                    code(HttpStatusCode.BadRequest) {
                        description = "잘못된 입력 데이터 (별점 범위 초과, 내용 누락 등)"
                    }
                    code(HttpStatusCode.UnsupportedMediaType) {
                        description = "multipart가 아닌 요청"
                    }
                    code(HttpStatusCode.NotFound) {
                        description = "상품 또는 주문 없음"
                    }
                    code(HttpStatusCode.Conflict) {
                        description = "이미 리뷰를 작성한 주문"
                    }
                }
            }) {
                val userId = call.requireUserId() ?: return@post

                val productId = call.getRequiredIntParam("productId", Errors.Commerce.Product.INVALID_PRODUCT_ID)
                    ?: return@post

                if (!call.request.contentType().match(ContentType.MultiPart.FormData)) {
                    return@post call.respond(
                        HttpStatusCode.UnsupportedMediaType,
                        ApiResponse.error<Unit>(Errors.Common.MULTIPART_REQUIRED)
                    )
                }

                var rating: Int? = null
                var content: String? = null
                var orderId: Int? = null
                val (files, contentTypes) = call.receiveFileParts(StorageConfig.FileSizeLimit.REVIEW_IMAGE) { name, value ->
                    when (name) {
                        "rating" -> rating = value.toIntOrNull()
                        "content" -> content = value
                        "orderId" -> orderId = value.toIntOrNull()
                    }
                }

                val response = reviewService.createReviewWithImages(
                    userId = userId,
                    productId = productId,
                    orderId = orderId,
                    rating = rating,
                    content = content,
                    files = files,
                    contentTypes = contentTypes
                )

                call.respond(HttpStatusCode.Created, ApiResponse.success(response, Messages.Commerce.REVIEW_CREATED))
            }

            get("/{id}", {
                summary = "리뷰 상세 조회"
                tags("Reviews")
                request {
                    pathParameter<Int>("id") {
                        description = "리뷰 ID"
                        required = true
                    }
                }
                authResponse()
                response {
                    code(HttpStatusCode.NotFound) {
                        description = "리뷰 없음"
                    }
                }
            }) {
                val reviewId = call.getRequiredIntParam("id", Errors.Commerce.Review.INVALID_REVIEW_ID)
                    ?: return@get

                val response = reviewService.getReviewById(reviewId)
                call.respond(HttpStatusCode.OK, ApiResponse.success(response))
            }

            put("/{id}", {
                summary = "리뷰 수정"
                tags("Reviews")
                request {
                    pathParameter<Int>("id") {
                        description = "리뷰 ID"
                        required = true
                    }
                    multipartBody {
                        description = "rating·content·keepImages(JSON 배열, 없으면 기존 유지)와 새 이미지 파일, 모두 선택"
                        required = true
                    }
                }
                authResponse()
                response {
                    code(HttpStatusCode.BadRequest) {
                        description = "잘못된 입력 데이터"
                    }
                    code(HttpStatusCode.UnsupportedMediaType) {
                        description = "multipart가 아닌 요청"
                    }
                    code(HttpStatusCode.NotFound) {
                        description = "리뷰 없음"
                    }
                    code(HttpStatusCode.Forbidden) {
                        description = "리뷰 수정 권한 없음"
                    }
                }
            }) {
                val userId = call.requireUserId() ?: return@put

                val reviewId = call.getRequiredIntParam("id", Errors.Commerce.Review.INVALID_REVIEW_ID)
                    ?: return@put

                if (!call.request.contentType().match(ContentType.MultiPart.FormData)) {
                    return@put call.respond(
                        HttpStatusCode.UnsupportedMediaType,
                        ApiResponse.error<Unit>(Errors.Common.MULTIPART_REQUIRED)
                    )
                }

                var rating: Int? = null
                var content: String? = null
                var keepImages: List<String>? = null
                val (newFiles, newContentTypes) = call.receiveFileParts(StorageConfig.FileSizeLimit.REVIEW_IMAGE) { name, value ->
                    when (name) {
                        "rating" -> rating = value.toIntOrNull()
                        "content" -> content = value
                        // 쉼표 충돌 방지로 JSON 배열 사용
                        // 파싱 실패만 null 폴백
                        "keepImages" -> keepImages = runCatching {
                            Json.decodeFromString<List<String>>(value)
                        }.getOrNull()
                    }
                }

                val response = reviewService.updateReviewWithImages(
                    reviewId = reviewId,
                    userId = userId,
                    rating = rating,
                    content = content,
                    keepImages = keepImages,
                    newFiles = newFiles.ifEmpty { null },
                    newContentTypes = newContentTypes.ifEmpty { null }
                )

                call.respond(HttpStatusCode.OK, ApiResponse.success(response, Messages.Commerce.REVIEW_UPDATED))
            }

            delete("/{id}", {
                summary = "리뷰 삭제"
                tags("Reviews")
                request {
                    pathParameter<Int>("id") {
                        description = "리뷰 ID"
                        required = true
                    }
                }
                authResponse()
                response {
                    code(HttpStatusCode.NoContent) {
                        description = "리뷰 삭제 성공"
                    }
                    code(HttpStatusCode.NotFound) {
                        description = "리뷰 없음"
                    }
                    code(HttpStatusCode.Forbidden) {
                        description = "리뷰 삭제 권한 없음"
                    }
                }
            }) {
                val userId = call.requireUserId() ?: return@delete

                val reviewId = call.getRequiredIntParam("id", Errors.Commerce.Review.INVALID_REVIEW_ID)
                    ?: return@delete

                reviewService.deleteReview(reviewId, userId)
                call.respond(HttpStatusCode.NoContent)
            }

            get("/users/{userId}", {
                summary = "사용자 리뷰 목록 조회"
                tags("Reviews")
                request {
                    pathParameter<Int>("userId") {
                        description = "사용자 ID"
                        required = true
                    }
                    queryParameter<String>("cursor") {
                        description = "다음 페이지 커서 (응답의 nextCursor, 생략 시 처음부터)"
                        required = false
                    }
                    queryParameter<Int>("limit") {
                        description = "페이지 크기 (기본값: 20)"
                        required = false
                    }
                }
                authResponse()
                response {
                    code(HttpStatusCode.NotFound) {
                        description = "사용자 없음"
                    }
                }
            }) {
                val currentUserId = call.requireUserId() ?: return@get

                val userId = call.getRequiredIntParam("userId", Errors.User.INVALID_USER_ID)
                    ?: return@get

                val cursor = call.getOptionalStringParam("cursor")
                val limit = call.getIntParam("limit", Constants.DEFAULT_PAGE_LIMIT)

                val response = reviewService.getUserReviews(userId, currentUserId, cursor, limit)
                call.respond(HttpStatusCode.OK, ApiResponse.success(response))
            }
        }
    }
}
