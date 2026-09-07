package com.ninezero.features.commerce.presentation

import com.ninezero.core.common.config.Constants
import com.ninezero.core.common.config.Messages
import com.ninezero.core.common.config.ProductSortType
import com.ninezero.core.common.exception.Errors
import com.ninezero.core.common.util.*
import com.ninezero.features.commerce.domain.ProductService
import com.ninezero.features.commerce.presentation.models.request.*
import io.github.smiley4.ktoropenapi.*
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.koin.ktor.ext.inject

fun Route.productRoutes() {
    route(Constants.Endpoints.PRODUCTS) {
        val productService by inject<ProductService>()

        authenticate("jwt", optional = true) {
            get({
                summary = "상품 목록 조회 (공개)"
                tags("Products")
                description = "categoryId 필터 지원"
                request {
                    queryParameter<Int>("page") {
                        description = "페이지 번호 (기본값: 1)"
                        required = false
                    }
                    queryParameter<Int>("limit") {
                        description = "페이지 크기 (기본값: 20)"
                        required = false
                    }
                    queryParameter<Int>("categoryId") {
                        description = "카테고리 ID (지정 시 해당 카테고리 상품만 조회)"
                        required = false
                    }
                }
                apiResponse()
            }) {
                val page = call.getIntParam("page", 1)
                val limit = call.getIntParam("limit", Constants.DEFAULT_PAGE_LIMIT)
                val currentUserId = call.getUserIdOrNull()
                val categoryIdRaw = call.request.queryParameters["categoryId"]
                val categoryId = categoryIdRaw?.let {
                    it.toIntOrNull()
                        ?: return@get call.respond(
                            HttpStatusCode.BadRequest,
                            ApiResponse.error<Unit>(Errors.Common.INVALID_ID)
                        )
                }

                val response = if (categoryId != null) {
                    productService.searchProducts(
                        request = ProductSearchRequest(categoryId = categoryId, page = page, limit = limit),
                        currentUserId = currentUserId
                    )
                } else {
                    productService.getAllProducts(page, limit, currentUserId)
                }
                call.respond(HttpStatusCode.OK, ApiResponse.success(response))
            }

            get("/{id}", {
                summary = "상품 상세 조회 (공개)"
                tags("Products")
                request {
                    pathParameter<Int>("id") {
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
                val productId = call.getRequiredIntParam("id", Errors.Commerce.Product.INVALID_PRODUCT_ID)
                    ?: return@get
                val currentUserId = call.getUserIdOrNull()

                val response = productService.getProductById(productId, currentUserId)
                call.respond(HttpStatusCode.OK, ApiResponse.success(response))
            }

            post("/search", {
                summary = "상품 검색 (공개)"
                tags("Products")
                request {
                    body<ProductSearchRequest> {
                        description = "검색 조건"
                        required = true
                        example("키워드 검색") {
                            value = ProductSearchRequest(
                                query = "아크릴 스탠드",
                                page = 1,
                                limit = 20
                            )
                        }
                        example("카테고리 + 가격") {
                            value = ProductSearchRequest(
                                categoryId = 1,
                                minPrice = "10000",
                                maxPrice = "50000",
                                page = 1,
                                limit = 20
                            )
                        }
                    }
                }
                apiResponse()
            }) {
                val searchRequest = call.receive<ProductSearchRequest>()
                val currentUserId = call.getUserIdOrNull()

                val response = productService.searchProducts(searchRequest, currentUserId)
                call.respond(HttpStatusCode.OK, ApiResponse.success(response))
            }

            get("/popular", {
                summary = "인기 상품 조회 (공개)"
                tags("Products")
                request {
                    queryParameter<Int>("limit") {
                        description = "조회 개수 (기본값: 20)"
                        required = false
                    }
                }
                apiResponse()
            }) {
                val limit = call.getIntParam("limit", Constants.DEFAULT_PAGE_LIMIT)
                val currentUserId = call.getUserIdOrNull()

                val response = productService.getPopularProducts(limit, currentUserId)
                call.respond(HttpStatusCode.OK, ApiResponse.success(response))
            }

            get("/trending", {
                summary = "트렌딩 상품 조회 (공개)"
                tags("Products")
                request {
                    queryParameter<Int>("limit") {
                        description = "조회 개수 (기본값: 20)"
                        required = false
                    }
                }
                apiResponse()
            }) {
                val limit = call.getIntParam("limit", Constants.DEFAULT_PAGE_LIMIT)
                val currentUserId = call.getUserIdOrNull()

                val response = productService.getTrendingProducts(limit, currentUserId)
                call.respond(HttpStatusCode.OK, ApiResponse.success(response))
            }

            get("/creator/{creatorId}/sections", {
                summary = "크리에이터 스토어 섹션 조회 (공개)"
                tags("Products")
                description = "섹션별 상품 목록"
                request {
                    pathParameter<Int>("creatorId") {
                        description = "크리에이터 ID"
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
                    queryParameter<String>("sort") {
                        description = "정렬 방식: LATEST (최신 등록순, 기본값), PRICE_LOW (낮은 가격순), PRICE_HIGH (높은 가격순)"
                        required = false
                    }
                }
                apiResponse()
            }) {
                val creatorId = call.getRequiredIntParam("creatorId", Errors.Common.INVALID_CREATOR_ID)
                    ?: return@get

                val page = call.getIntParam("page", 1)
                val limit = call.getIntParam("limit", Constants.DEFAULT_PAGE_LIMIT)
                val currentUserId = call.getUserIdOrNull()
                val sort = call.request.queryParameters["sort"]?.let {
                    runCatching { ProductSortType.valueOf(it.uppercase()) }.getOrNull()
                } ?: ProductSortType.LATEST

                val response = productService.getCreatorStoreSections(
                    creatorId = creatorId,
                    currentUserId = currentUserId,
                    page = page,
                    limit = limit,
                    sort = sort
                )
                call.respond(HttpStatusCode.OK, ApiResponse.success(response))
            }

            get("/{id}/related", {
                summary = "연관 상품 조회 (공개)"
                tags("Products")
                description = "태그 매칭 후 카테고리 보충"
                request {
                    pathParameter<Int>("id") {
                        description = "기준 상품 ID"
                        required = true
                    }
                    queryParameter<Int>("limit") {
                        description = "반환 개수 (기본값: 10, 최대: 20)"
                        required = false
                    }
                }
                apiResponse()
                response {
                    code(HttpStatusCode.NotFound) {
                        description = "상품 없음"
                    }
                }
            }) {
                val productId = call.getRequiredIntParam("id", Errors.Commerce.Product.INVALID_PRODUCT_ID)
                    ?: return@get
                val limit = call.getIntParam("limit", 10)
                val currentUserId = call.getUserIdOrNull()

                val response = productService.getRelatedProducts(productId, limit, currentUserId)
                call.respond(HttpStatusCode.OK, ApiResponse.success(response))
            }

            get("/tag/{tagId}", {
                summary = "태그별 상품 조회 (공개)"
                tags("Products")
                request {
                    pathParameter<Int>("tagId") {
                        description = "태그 ID"
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
                apiResponse()
                response {
                    code(HttpStatusCode.NotFound) {
                        description = "태그 없음"
                    }
                }
            }) {
                val tagId = call.getRequiredIntParam("tagId", Errors.Social.Tag.INVALID_TAG_ID)
                    ?: return@get

                val page = call.getIntParam("page", 1)
                val limit = call.getIntParam("limit", Constants.DEFAULT_PAGE_LIMIT)
                val currentUserId = call.getUserIdOrNull()

                val response = productService.getProductsByTag(
                    tagId = tagId,
                    currentUserId = currentUserId,
                    page = page,
                    limit = limit
                )
                call.respond(HttpStatusCode.OK, ApiResponse.success(response))
            }
        }

        authenticate("jwt") {
            post({
                summary = "상품 등록 (Creator)"
                tags("Products")
                request {
                    body<ProductRequest> {
                        description = "상품 정보"
                        required = true
                        example("아크릴 스탠드") {
                            value = ProductRequest(
                                name = "한정판 아크릴 스탠드",
                                description = "15cm 크기의 프리미엄 아크릴 스탠드",
                                price = "15000",
                                stock = 100,
                                categoryId = 1,
                                imageUrls = listOf("https://example.com/image.jpg")
                            )
                        }
                    }
                }
                authResponse()
                response {
                    code(HttpStatusCode.Created) {
                        description = "상품 등록 성공"
                    }
                    code(HttpStatusCode.Forbidden) {
                        description = "크리에이터만 등록 가능"
                    }
                }
            }) {
                val userId = call.requireUserId() ?: return@post

                val request = call.receive<ProductRequest>()
                val response = productService.createProduct(userId, request)
                call.respond(HttpStatusCode.Created, ApiResponse.success(response, Messages.Commerce.PRODUCT_CREATED))
            }

            get("/my", {
                summary = "내 상품 목록 조회 (Creator)"
                tags("Products")
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
            }) {
                val userId = call.requireUserId() ?: return@get

                val page = call.getIntParam("page", 1)
                val limit = call.getIntParam("limit", Constants.DEFAULT_PAGE_LIMIT)

                val response = productService.getMyProducts(userId, page, limit)
                call.respond(HttpStatusCode.OK, ApiResponse.success(response))
            }

            post("/{id}/images", {
                summary = "상품 이미지 업로드 (Creator)"
                tags("Products")
                request {
                    pathParameter<Int>("id") {
                        description = "상품 ID"
                        required = true
                    }
                    body<ByteArray> {
                        description = "Multipart form data - 이미지 파일"
                    }
                }
                authResponse()
                response {
                    code(HttpStatusCode.Created) {
                        description = "이미지 업로드 성공"
                    }
                    code(HttpStatusCode.BadRequest) {
                        description = "파일이 없거나 최대 개수 초과"
                    }
                }
            }) {
                val userId = call.requireUserId() ?: return@post

                val productId = call.getRequiredIntParam("id", Errors.Commerce.Product.INVALID_PRODUCT_ID)
                    ?: return@post

                val (images, contentTypes) = call.receiveFileParts()

                if (images.isEmpty()) {
                    return@post call.respond(
                        HttpStatusCode.BadRequest,
                        ApiResponse.error<Unit>(Errors.File.NO_IMAGE_FILES)
                    )
                }

                val response = productService.uploadProductImages(productId, userId, images, contentTypes)
                call.respond(
                    HttpStatusCode.Created,
                    ApiResponse.success(response, Messages.Commerce.PRODUCT_IMAGE_ADDED)
                )
            }

            post("/{id}/detail-images", {
                summary = "상품 상세 이미지 업로드 (Creator)"
                tags("Products")
                request {
                    pathParameter<Int>("id") {
                        description = "상품 ID"
                        required = true
                    }
                    body<ByteArray> {
                        description = "Multipart form data - 상세 이미지 파일"
                    }
                }
                authResponse()
                response {
                    code(HttpStatusCode.Created) {
                        description = "상세 이미지 업로드 성공"
                    }
                    code(HttpStatusCode.BadRequest) {
                        description = "파일이 없거나 최대 개수 초과"
                    }
                }
            }) {
                val userId = call.requireUserId() ?: return@post

                val productId = call.getRequiredIntParam("id", Errors.Commerce.Product.INVALID_PRODUCT_ID)
                    ?: return@post

                val (images, contentTypes) = call.receiveFileParts()

                if (images.isEmpty()) {
                    return@post call.respond(
                        HttpStatusCode.BadRequest,
                        ApiResponse.error<Unit>(Errors.File.NO_IMAGE_FILES)
                    )
                }

                val response = productService.uploadDetailImages(productId, userId, images, contentTypes)
                call.respond(
                    HttpStatusCode.Created,
                    ApiResponse.success(response, Messages.Commerce.PRODUCT_DETAIL_CONTENT_IMAGE_ADDED)
                )
            }

            delete("/{id}/detail-images", {
                summary = "상품 상세 이미지 삭제 (Creator)"
                tags("Products")
                request {
                    pathParameter<Int>("id") {
                        description = "상품 ID"
                        required = true
                    }
                    body<DeleteProductImageRequest> {
                        description = "삭제할 상세 이미지 URL"
                        required = true
                    }
                }
                authResponse()
                response {
                    code(HttpStatusCode.NoContent) {
                        description = "상세 이미지 삭제 성공"
                    }
                }
            }) {
                val userId = call.requireUserId() ?: return@delete

                val productId = call.getRequiredIntParam("id", Errors.Commerce.Product.INVALID_PRODUCT_ID)
                    ?: return@delete

                val request = call.receive<DeleteProductImageRequest>()
                productService.deleteDetailImage(productId, userId, request.imageUrl)
                call.respond(HttpStatusCode.NoContent)
            }

            delete("/{id}/images", {
                summary = "상품 이미지 삭제 (Creator)"
                tags("Products")
                request {
                    pathParameter<Int>("id") {
                        description = "상품 ID"
                        required = true
                    }
                    body<DeleteProductImageRequest> {
                        description = "삭제할 이미지 URL"
                        required = true
                    }
                }
                authResponse()
                response {
                    code(HttpStatusCode.NoContent) {
                        description = "이미지 삭제 성공"
                    }
                }
            }) {
                val userId = call.requireUserId() ?: return@delete

                val productId = call.getRequiredIntParam("id", Errors.Commerce.Product.INVALID_PRODUCT_ID)
                    ?: return@delete

                val request = call.receive<DeleteProductImageRequest>()
                productService.deleteProductImage(productId, userId, request.imageUrl)
                call.respond(HttpStatusCode.NoContent)
            }

            put("/{id}", {
                summary = "상품 수정 (Creator)"
                tags("Products")
                request {
                    pathParameter<Int>("id") {
                        description = "상품 ID"
                        required = true
                    }
                    body<UpdateProductRequest> {
                        description = "수정할 상품 정보"
                        required = true
                    }
                }
                authResponse()
                response {
                    code(HttpStatusCode.Forbidden) {
                        description = "본인 상품만 수정 가능"
                    }
                    code(HttpStatusCode.NotFound) {
                        description = "상품 없음"
                    }
                }
            }) {
                val userId = call.requireUserId() ?: return@put

                val productId = call.getRequiredIntParam("id", Errors.Commerce.Product.INVALID_PRODUCT_ID)
                    ?: return@put

                val request = call.receive<UpdateProductRequest>()
                val response = productService.updateProduct(productId, userId, request)
                call.respond(HttpStatusCode.OK, ApiResponse.success(response, Messages.Commerce.PRODUCT_UPDATED))
            }

            patch("/{id}/status", {
                summary = "상품 판매 상태 토글 (Creator)"
                tags("Products")
                request {
                    pathParameter<Int>("id") {
                        description = "상품 ID"
                        required = true
                    }
                }
                authResponse()
            }) {
                val userId = call.requireUserId() ?: return@patch

                val productId = call.getRequiredIntParam("id", Errors.Commerce.Product.INVALID_PRODUCT_ID)
                    ?: return@patch

                val response = productService.toggleProductStatus(productId, userId)
                call.respond(HttpStatusCode.OK, ApiResponse.success(response))
            }

            patch("/{id}/deal", {
                summary = "타임딜 설정/해제 (Creator)"
                tags("Products")
                description = "전 필드 null이면 해제"
                request {
                    pathParameter<Int>("id") {
                        description = "상품 ID"
                        required = true
                    }
                    body<SetProductDealRequest> {
                        description = "딜 가격 + 기간(UTC). 해제 시 모두 null"
                        required = true
                    }
                }
                authResponse()
                response {
                    code(HttpStatusCode.Forbidden) {
                        description = "본인 상품만 타임딜 설정 가능"
                    }
                }
            }) {
                val userId = call.requireUserId() ?: return@patch

                val productId = call.getRequiredIntParam("id", Errors.Commerce.Product.INVALID_PRODUCT_ID)
                    ?: return@patch

                val request = call.receive<SetProductDealRequest>()
                val response = productService.setProductDeal(productId, userId, request)
                val message = if (request.dealPrice == null) {
                    Messages.Commerce.PRODUCT_DEAL_CLEARED
                } else {
                    Messages.Commerce.PRODUCT_DEAL_SET
                }
                call.respond(HttpStatusCode.OK, ApiResponse.success(response, message))
            }

            delete("/{id}", {
                summary = "상품 삭제 (Creator)"
                tags("Products")
                request {
                    pathParameter<Int>("id") {
                        description = "상품 ID"
                        required = true
                    }
                }
                authResponse()
                response {
                    code(HttpStatusCode.NoContent) {
                        description = "상품 삭제 성공"
                    }
                    code(HttpStatusCode.Forbidden) {
                        description = "본인 상품만 삭제 가능"
                    }
                }
            }) {
                val userId = call.requireUserId() ?: return@delete

                val productId = call.getRequiredIntParam("id", Errors.Commerce.Product.INVALID_PRODUCT_ID)
                    ?: return@delete

                productService.deleteProduct(productId, userId)
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
}
