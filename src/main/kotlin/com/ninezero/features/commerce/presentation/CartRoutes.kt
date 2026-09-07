package com.ninezero.features.commerce.presentation

import com.ninezero.core.common.config.Constants
import com.ninezero.core.common.config.Messages
import com.ninezero.core.common.exception.Errors
import com.ninezero.core.common.util.ApiResponse
import com.ninezero.core.common.util.authResponse
import com.ninezero.core.common.util.getRequiredIntParam
import com.ninezero.core.common.util.requireUserId
import com.ninezero.features.commerce.domain.CartService
import com.ninezero.features.commerce.presentation.models.request.CartRequest
import com.ninezero.features.commerce.presentation.models.request.UpdateCartRequest
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

fun Route.cartRoutes() {
    route(Constants.Endpoints.CART) {
        val cartService by inject<CartService>()

        authenticate("jwt") {
            get({
                summary = "장바구니 조회"
                tags("Cart")
                authResponse()
                response {
                    code(HttpStatusCode.Unauthorized) {
                        description = "인증 필요"
                    }
                }
            }) {
                val userId = call.requireUserId() ?: return@get

                val response = cartService.getUserCart(userId)
                call.respond(HttpStatusCode.OK, ApiResponse.success(response))
            }

            get("/count", {
                summary = "장바구니 항목 수 조회"
                tags("Cart")
                authResponse()
                response {
                    code(HttpStatusCode.Unauthorized) {
                        description = "인증 필요"
                    }
                }
            }) {
                val userId = call.requireUserId() ?: return@get

                val response = cartService.getCartItemCount(userId)
                call.respond(HttpStatusCode.OK, ApiResponse.success(response))
            }

            post({
                summary = "장바구니 담기"
                tags("Cart")
                request {
                    body<CartRequest> {
                        description = "추가할 상품 정보"
                        required = true
                        example("default") {
                            value = CartRequest(
                                productId = 1,
                                quantity = 2
                            )
                        }
                        example("single") {
                            value = CartRequest(
                                productId = 5,
                                quantity = 1
                            )
                        }
                    }
                }
                authResponse()
                response {
                    code(HttpStatusCode.Created) {
                        description = "장바구니에 추가 성공"
                    }
                    code(HttpStatusCode.BadRequest) {
                        description = "잘못된 상품 ID 또는 수량"
                    }
                    code(HttpStatusCode.NotFound) {
                        description = "상품 없음"
                    }
                    code(HttpStatusCode.Conflict) {
                        description = "이미 장바구니에 있는 상품"
                    }
                    code(HttpStatusCode.Unauthorized) {
                        description = "인증 필요"
                    }
                }
            }) {
                val userId = call.requireUserId() ?: return@post

                val request = call.receive<CartRequest>()
                val response = cartService.addToCart(userId, request)
                call.respond(HttpStatusCode.Created, ApiResponse.success(response, Messages.Commerce.CART_ADDED))
            }

            put("/{id}", {
                summary = "장바구니 수량 변경"
                tags("Cart")
                request {
                    pathParameter<Int>("id") {
                        description = "장바구니 항목 ID"
                        required = true
                    }
                    body<UpdateCartRequest> {
                        description = "변경할 수량"
                        required = true
                        example("increase") {
                            value = UpdateCartRequest(quantity = 5)
                        }
                        example("decrease") {
                            value = UpdateCartRequest(quantity = 1)
                        }
                    }
                }
                authResponse()
                response {
                    code(HttpStatusCode.BadRequest) {
                        description = "잘못된 수량 (0 이하 또는 재고 초과)"
                    }
                    code(HttpStatusCode.NotFound) {
                        description = "장바구니 항목 없음"
                    }
                    code(HttpStatusCode.Forbidden) {
                        description = "장바구니 수정 권한 없음"
                    }
                    code(HttpStatusCode.Unauthorized) {
                        description = "인증 필요"
                    }
                }
            }) {
                val userId = call.requireUserId() ?: return@put

                val cartId = call.getRequiredIntParam("id", Errors.Commerce.Cart.INVALID_CART_ID)
                    ?: return@put

                val request = call.receive<UpdateCartRequest>()
                val response = cartService.updateCartQuantity(cartId, userId, request)
                call.respond(HttpStatusCode.OK, ApiResponse.success(response))
            }

            delete("/{id}", {
                summary = "장바구니 항목 삭제"
                tags("Cart")
                request {
                    pathParameter<Int>("id") {
                        description = "장바구니 항목 ID"
                        required = true
                    }
                }
                authResponse()
                response {
                    code(HttpStatusCode.NoContent) {
                        description = "장바구니 항목 삭제 성공"
                    }
                    code(HttpStatusCode.NotFound) {
                        description = "장바구니 항목 없음"
                    }
                    code(HttpStatusCode.Forbidden) {
                        description = "장바구니 삭제 권한 없음"
                    }
                    code(HttpStatusCode.Unauthorized) {
                        description = "인증 필요"
                    }
                }
            }) {
                val userId = call.requireUserId() ?: return@delete

                val cartId = call.getRequiredIntParam("id", Errors.Commerce.Cart.INVALID_CART_ID)
                    ?: return@delete

                cartService.removeFromCart(cartId, userId)
                call.respond(HttpStatusCode.NoContent)
            }

            delete({
                summary = "장바구니 비우기"
                tags("Cart")
                authResponse()
                response {
                    code(HttpStatusCode.NoContent) {
                        description = "장바구니 비우기 성공"
                    }
                    code(HttpStatusCode.Unauthorized) {
                        description = "인증 필요"
                    }
                }
            }) {
                val userId = call.requireUserId() ?: return@delete

                cartService.clearCart(userId)
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
}
