package com.ninezero.features.commerce.presentation

import com.ninezero.core.common.config.Constants
import com.ninezero.core.common.config.Messages
import com.ninezero.core.common.config.ReviewSortType
import com.ninezero.core.common.exception.Errors
import com.ninezero.core.common.util.ApiResponse
import com.ninezero.core.common.util.apiResponse
import com.ninezero.core.common.util.getIntParam
import com.ninezero.core.common.util.getOptionalStringParam
import com.ninezero.core.common.util.getRequiredIntParam
import com.ninezero.core.common.util.receiveFileParts
import com.ninezero.core.common.util.requireUserId
import com.ninezero.core.common.util.authResponse
import com.ninezero.features.commerce.domain.ReviewService
import com.ninezero.features.commerce.presentation.models.request.DeleteReviewImageRequest
import com.ninezero.features.commerce.presentation.models.request.ReviewRequest
import com.ninezero.features.commerce.presentation.models.request.UpdateReviewRequest
import io.github.smiley4.ktoropenapi.delete
import io.github.smiley4.ktoropenapi.get
import io.github.smiley4.ktoropenapi.post
import io.github.smiley4.ktoropenapi.put
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.*
import kotlinx.io.readByteArray
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
                queryParameter<Int>("page") {
                    description = "페이지 번호 (기본값: 1)"
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

            val page = call.getIntParam("page", 1)
            val limit = call.getIntParam("limit", Constants.DEFAULT_PAGE_LIMIT)

            val sortBy = call.getOptionalStringParam("sortBy")?.let {
                try {
                    ReviewSortType.valueOf(it.uppercase())
                } catch (_: IllegalArgumentException) {
                    ReviewSortType.RECENT
                }
            } ?: ReviewSortType.RECENT

            val response = reviewService.getReviewsByProduct(productId, page, limit, sortBy)
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
                    body<ReviewRequest> {
                        description = "리뷰 정보"
                        required = true
                        example("default") {
                            value = ReviewRequest(
                                orderId = 1,
                                productId = 1,
                                rating = 5,
                                content = "정말 좋은 상품이에요! 배송도 빠르고 품질도 만족스럽습니다.",
                                images = listOf(
                                    "https://example.com/review1.jpg",
                                    "https://example.com/review2.jpg"
                                )
                            )
                        }
                        example("no_images") {
                            value = ReviewRequest(
                                orderId = 2,
                                productId = 3,
                                rating = 4,
                                content = "전반적으로 만족합니다.",
                                images = emptyList()
                            )
                        }
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
                    code(HttpStatusCode.NotFound) {
                        description = "상품 또는 주문 없음"
                    }
                    code(HttpStatusCode.Conflict) {
                        description = "이미 리뷰를 작성한 주문"
                    }
                    code(HttpStatusCode.Unauthorized) {
                        description = "인증 필요"
                    }
                }
            }) {
                val userId = call.requireUserId() ?: return@post

                val productId = call.getRequiredIntParam("productId", Errors.Commerce.Product.INVALID_PRODUCT_ID)
                    ?: return@post

                // multipart는 이미지 포함
                val response = if (call.request.contentType().match(ContentType.MultiPart.FormData)) {
                    val multipart = call.receiveMultipart()
                    var rating: Int? = null
                    var content: String? = null
                    var orderId: Int? = null
                    val files = mutableListOf<ByteArray>()
                    val contentTypes = mutableListOf<String>()

                    multipart.forEachPart { part ->
                        when (part) {
                            is PartData.FormItem -> when (part.name) {
                                "rating" -> rating = part.value.toIntOrNull()
                                "content" -> content = part.value
                                "orderId" -> orderId = part.value.toIntOrNull()
                            }

                            is PartData.FileItem -> {
                                contentTypes.add(part.contentType?.toString() ?: "image/jpeg")
                                files.add(part.provider().readRemaining().readByteArray())
                            }

                            else -> Unit
                        }
                        part.dispose()
                    }

                    reviewService.createReviewWithImages(
                        userId = userId,
                        productId = productId,
                        orderId = orderId,
                        rating = rating,
                        content = content,
                        files = files,
                        contentTypes = contentTypes
                    )
                } else {
                    reviewService.createReview(userId, call.receive<ReviewRequest>())
                }

                call.respond(HttpStatusCode.Created, ApiResponse.success(response, Messages.Commerce.REVIEW_CREATED))
            }

            get("/{id}", {
                summary = "리뷰 상세 조회 (공개)"
                tags("Reviews")
                request {
                    pathParameter<Int>("id") {
                        description = "리뷰 ID"
                        required = true
                    }
                }
                apiResponse()
                response {
                    code(HttpStatusCode.NotFound) {
                        description = "리뷰 없음"
                    }
                    code(HttpStatusCode.Unauthorized) {
                        description = "인증 필요"
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
                    body<UpdateReviewRequest> {
                        description = "수정할 리뷰 정보 (모든 필드 선택사항)"
                        required = true
                        example("update_all") {
                            value = UpdateReviewRequest(
                                rating = 4,
                                content = "다시 생각해보니 4점이 적당한 것 같아요.",
                                images = listOf("https://example.com/updated.jpg")
                            )
                        }
                        example("content_only") {
                            value = UpdateReviewRequest(
                                rating = null,
                                content = "내용만 수정합니다.",
                                images = null
                            )
                        }
                    }
                }
                authResponse()
                response {
                    code(HttpStatusCode.BadRequest) {
                        description = "잘못된 입력 데이터"
                    }
                    code(HttpStatusCode.NotFound) {
                        description = "리뷰 없음"
                    }
                    code(HttpStatusCode.Forbidden) {
                        description = "리뷰 수정 권한 없음"
                    }
                    code(HttpStatusCode.Unauthorized) {
                        description = "인증 필요"
                    }
                }
            }) {
                val userId = call.requireUserId() ?: return@put

                val reviewId = call.getRequiredIntParam("id", Errors.Commerce.Review.INVALID_REVIEW_ID)
                    ?: return@put

                // multipart는 이미지 교체 포함
                val response = if (call.request.contentType().match(ContentType.MultiPart.FormData)) {
                    val multipart = call.receiveMultipart()
                    var rating: Int? = null
                    var content: String? = null
                    var keepImages: List<String>? = null
                    val newFiles = mutableListOf<ByteArray>()
                    val newContentTypes = mutableListOf<String>()

                    multipart.forEachPart { part ->
                        when (part) {
                            is PartData.FormItem -> when (part.name) {
                                "rating" -> rating = part.value.toIntOrNull()
                                "content" -> content = part.value
                                // 쉼표 충돌 방지로 JSON 배열 사용
                                // 파싱 실패만 null 폴백
                                "keepImages" -> keepImages = runCatching {
                                    Json.decodeFromString<List<String>>(part.value)
                                }.getOrNull()
                            }

                            is PartData.FileItem -> {
                                newContentTypes.add(part.contentType?.toString() ?: "image/jpeg")
                                newFiles.add(part.provider().readRemaining().readByteArray())
                            }

                            else -> Unit
                        }
                        part.dispose()
                    }

                    reviewService.updateReviewWithImages(
                        reviewId = reviewId,
                        userId = userId,
                        rating = rating,
                        content = content,
                        keepImages = keepImages,
                        newFiles = newFiles.ifEmpty { null },
                        newContentTypes = newContentTypes.ifEmpty { null }
                    )
                } else {
                    reviewService.updateReview(reviewId, userId, call.receive<UpdateReviewRequest>())
                }

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
                    code(HttpStatusCode.Unauthorized) {
                        description = "인증 필요"
                    }
                }
            }) {
                val userId = call.requireUserId() ?: return@delete

                val reviewId = call.getRequiredIntParam("id", Errors.Commerce.Review.INVALID_REVIEW_ID)
                    ?: return@delete

                reviewService.deleteReview(reviewId, userId)
                call.respond(HttpStatusCode.NoContent)
            }

            post("/{reviewId}/images", {
                summary = "리뷰 이미지 업로드"
                tags("Reviews")
                request {
                    pathParameter<Int>("reviewId") {
                        description = "리뷰 ID"
                        required = true
                    }
                    multipartBody {
                        description = "이미지 파일들 (최대 5개)"
                        required = true
                    }
                }
                authResponse()
                response {
                    code(HttpStatusCode.Created) {
                        description = "이미지 업로드 성공"
                    }
                    code(HttpStatusCode.BadRequest) {
                        description = "이미지가 없거나 최대 개수 초과"
                    }
                    code(HttpStatusCode.NotFound) {
                        description = "리뷰 없음"
                    }
                    code(HttpStatusCode.Forbidden) {
                        description = "리뷰 이미지 추가 권한 없음"
                    }
                    code(HttpStatusCode.Unauthorized) {
                        description = "인증 필요"
                    }
                }
            }) {
                val userId = call.requireUserId() ?: return@post

                val reviewId = call.getRequiredIntParam("reviewId", Errors.Commerce.Review.INVALID_REVIEW_ID)
                    ?: return@post

                val (images, contentTypes) = call.receiveFileParts()

                if (images.isEmpty()) {
                    return@post call.respond(HttpStatusCode.BadRequest, ApiResponse.error<Unit>(Errors.File.NO_IMAGE_FILES))
                }

                val response = reviewService.uploadReviewImages(reviewId, userId, images, contentTypes)
                call.respond(
                    HttpStatusCode.Created,
                    ApiResponse.success(response, Messages.Commerce.REVIEW_IMAGE_ADDED)
                )
            }

            delete("/{reviewId}/images", {
                summary = "리뷰 이미지 삭제"
                tags("Reviews")
                request {
                    pathParameter<Int>("reviewId") {
                        description = "리뷰 ID"
                        required = true
                    }
                    body<DeleteReviewImageRequest> {
                        description = "삭제할 이미지 URL"
                        required = true
                        example("default") {
                            value = DeleteReviewImageRequest(
                                imageUrl = "https://example.com/review_image.jpg"
                            )
                        }
                    }
                }
                authResponse()
                response {
                    code(HttpStatusCode.NoContent) {
                        description = "이미지 삭제 성공"
                    }
                    code(HttpStatusCode.NotFound) {
                        description = "리뷰 또는 이미지 없음"
                    }
                    code(HttpStatusCode.Forbidden) {
                        description = "이미지 삭제 권한 없음"
                    }
                    code(HttpStatusCode.Unauthorized) {
                        description = "인증 필요"
                    }
                }
            }) {
                val userId = call.requireUserId() ?: return@delete

                val reviewId = call.getRequiredIntParam("reviewId", Errors.Commerce.Review.INVALID_REVIEW_ID)
                    ?: return@delete

                val request = call.receive<DeleteReviewImageRequest>()
                reviewService.deleteReviewImage(reviewId, userId, request.imageUrl)
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
                    code(HttpStatusCode.NotFound) {
                        description = "사용자 없음"
                    }
                    code(HttpStatusCode.Unauthorized) {
                        description = "인증 필요"
                    }
                }
            }) {
                val requestUserId = call.requireUserId() ?: return@get

                val userId = call.getRequiredIntParam("userId", Errors.User.INVALID_USER_ID)
                    ?: return@get

                val page = call.getIntParam("page", 1)
                val limit = call.getIntParam("limit", Constants.DEFAULT_PAGE_LIMIT)

                val response = reviewService.getMyReviews(userId, requestUserId, page, limit)
                call.respond(HttpStatusCode.OK, ApiResponse.success(response))
            }
        }
    }
}
