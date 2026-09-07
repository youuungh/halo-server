package com.ninezero.features.commerce.presentation

import com.ninezero.core.common.config.Constants
import com.ninezero.core.common.exception.Errors
import com.ninezero.core.common.util.*
import com.ninezero.features.commerce.domain.WishlistService
import io.github.smiley4.ktoropenapi.get
import io.github.smiley4.ktoropenapi.post
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.koin.ktor.ext.inject

fun Route.wishlistRoutes() {
    route(Constants.Endpoints.WISHLISTS) {
        val wishlistService by inject<WishlistService>()

        authenticate("jwt") {
            post("/products/{productId}", {
                summary = "위시리스트 토글"
                tags("Wishlist")
                request {
                    pathParameter<Int>("productId") {
                        description = "상품 ID"
                        required = true
                    }
                }
                authResponse()
                response {
                    code(HttpStatusCode.NotFound) {
                        description = "상품 없음"
                    }
                    code(HttpStatusCode.Unauthorized) {
                        description = "인증 필요"
                    }
                }
            }) {
                val userId = call.requireUserId() ?: return@post

                val productId = call.getRequiredIntParam("productId", Errors.Commerce.Product.INVALID_PRODUCT_ID)
                    ?: return@post

                val response = wishlistService.toggleWishlist(userId, productId)
                call.respond(HttpStatusCode.OK, ApiResponse.success(response))
            }

            post("/products/{productId}/move-to-cart", {
                summary = "위시리스트에서 장바구니로 이동"
                tags("Wishlist")
                request {
                    pathParameter<Int>("productId") {
                        description = "상품 ID"
                        required = true
                    }
                }
                authResponse()
                response {
                    code(HttpStatusCode.Forbidden) {
                        description = "상품 접근 권한 없음"
                    }
                    code(HttpStatusCode.NotFound) {
                        description = "상품 또는 위시리스트 항목 없음"
                    }
                    code(HttpStatusCode.UnprocessableEntity) {
                        description = "판매종료 또는 품절 상품"
                    }
                    code(HttpStatusCode.Unauthorized) {
                        description = "인증 필요"
                    }
                }
            }) {
                val userId = call.requireUserId() ?: return@post

                val productId = call.getRequiredIntParam("productId", Errors.Commerce.Product.INVALID_PRODUCT_ID)
                    ?: return@post

                val response = wishlistService.moveToCart(userId, productId)
                call.respond(HttpStatusCode.OK, ApiResponse.success(response))
            }

            get({
                summary = "위시리스트 목록 조회"
                tags("Wishlist")
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

                val response = wishlistService.getWishlist(userId, page, limit)
                call.respond(HttpStatusCode.OK, ApiResponse.success(response))
            }

            get("/products/{productId}/status", {
                summary = "위시리스트 상태 확인"
                tags("Wishlist")
                request {
                    pathParameter<Int>("productId") {
                        description = "상품 ID"
                        required = true
                    }
                }
                authResponse()
                response {
                    code(HttpStatusCode.NotFound) {
                        description = "상품 없음"
                    }
                    code(HttpStatusCode.Unauthorized) {
                        description = "인증 필요"
                    }
                }
            }) {
                val userId = call.requireUserId() ?: return@get

                val productId = call.getRequiredIntParam("productId", Errors.Commerce.Product.INVALID_PRODUCT_ID)
                    ?: return@get

                val response = wishlistService.checkWishlistStatus(userId, productId)
                call.respond(HttpStatusCode.OK, ApiResponse.success(response))
            }

            get("/count", {
                summary = "위시리스트 수 조회"
                tags("Wishlist")
                authResponse()
                response {
                    code(HttpStatusCode.Unauthorized) {
                        description = "인증 필요"
                    }
                }
            }) {
                val userId = call.requireUserId() ?: return@get

                val response = wishlistService.getWishlistCount(userId)
                call.respond(HttpStatusCode.OK, ApiResponse.success(response))
            }

            get("/products", {
                summary = "찜한 상품 ID 목록 조회"
                tags("Wishlist")
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

                val response = wishlistService.getWishlistProductIds(userId, page, limit)
                call.respond(HttpStatusCode.OK, ApiResponse.success(response))
            }
        }
    }
}
