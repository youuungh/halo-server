package com.ninezero.features.user.presentation

import com.ninezero.core.common.config.Constants
import com.ninezero.core.common.config.Messages
import com.ninezero.core.common.exception.Errors
import com.ninezero.core.common.util.*
import com.ninezero.features.user.domain.AddressService
import com.ninezero.features.user.presentation.models.request.CreateAddressRequest
import com.ninezero.features.user.presentation.models.request.UpdateAddressRequest
import com.ninezero.features.user.presentation.models.response.AddressResponse
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

fun Route.addressRoutes() {
    route(Constants.Endpoints.ADDRESSES) {
        val addressService by inject<AddressService>()

        authenticate("jwt") {
            get({
                summary = "배송지 목록 조회"
                tags("Addresses")
                authResponse()
                response {
                    code(HttpStatusCode.OK) {
                        description = "배송지 목록 조회 성공"
                        body<ApiResponse<List<AddressResponse>>>()
                    }
                }
            }) {
                val userId = call.requireUserId() ?: return@get
                val response = addressService.findAddresses(userId)
                call.respond(HttpStatusCode.OK, ApiResponse.success(response))
            }

            get("/{id}", {
                summary = "배송지 상세 조회"
                tags("Addresses")
                request {
                    pathParameter<Int>("id") {
                        description = "배송지 ID"
                        required = true
                    }
                }
                authResponse()
                response {
                    code(HttpStatusCode.OK) {
                        description = "배송지 상세 조회 성공"
                        body<ApiResponse<AddressResponse>>()
                    }
                    code(HttpStatusCode.BadRequest) {
                        description = "잘못된 배송지 ID"
                    }
                    code(HttpStatusCode.NotFound) {
                        description = "배송지 없음"
                    }
                }
            }) {
                val userId = call.requireUserId() ?: return@get
                val id = call.getRequiredIntParam("id", Errors.Address.INVALID_ADDRESS_ID) ?: return@get
                val response = addressService.findAddress(id, userId)
                call.respond(HttpStatusCode.OK, ApiResponse.success(response))
            }

            post({
                summary = "배송지 추가"
                tags("Addresses")
                request {
                    body<CreateAddressRequest> {
                        description = "배송지 정보"
                        required = true
                        example("배송지 추가") {
                            value = CreateAddressRequest(
                                recipientName = "홍길동",
                                recipientPhone = "010-1234-5678",
                                zipCode = "06236",
                                address = "서울특별시 강남구 테헤란로 123",
                                addressDetail = "101동 202호",
                                memo = "부재시 문 앞에 놓아주세요"
                            )
                        }
                    }
                }
                authResponse()
                response {
                    code(HttpStatusCode.Created) {
                        description = "배송지 등록 성공"
                        body<ApiResponse<AddressResponse>>()
                    }
                }
            }) {
                val userId = call.requireUserId() ?: return@post
                val request = call.receive<CreateAddressRequest>()
                val response = addressService.createAddress(userId, request)
                call.respond(HttpStatusCode.Created, ApiResponse.success(response, Messages.Address.ADDRESS_CREATED))
            }

            put("/{id}", {
                summary = "배송지 수정"
                tags("Addresses")
                request {
                    pathParameter<Int>("id") {
                        description = "배송지 ID"
                        required = true
                    }
                    body<UpdateAddressRequest> {
                        description = "수정할 배송지 정보"
                        required = true
                        example("배송지 수정") {
                            value = UpdateAddressRequest(
                                recipientName = "홍길동",
                                recipientPhone = "010-1234-5678",
                                zipCode = "06236",
                                address = "서울특별시 강남구 테헤란로 123",
                                addressDetail = "101동 202호",
                                memo = "부재시 문 앞에 놓아주세요"
                            )
                        }
                    }
                }
                authResponse()
                response {
                    code(HttpStatusCode.OK) {
                        description = "배송지 수정 성공"
                        body<ApiResponse<AddressResponse>>()
                    }
                    code(HttpStatusCode.BadRequest) {
                        description = "잘못된 배송지 ID"
                    }
                    code(HttpStatusCode.NotFound) {
                        description = "배송지 없음"
                    }
                }
            }) {
                val userId = call.requireUserId() ?: return@put
                val id = call.getRequiredIntParam("id", Errors.Address.INVALID_ADDRESS_ID) ?: return@put
                val request = call.receive<UpdateAddressRequest>()
                val response = addressService.updateAddress(id, userId, request)
                call.respond(HttpStatusCode.OK, ApiResponse.success(response, Messages.Address.ADDRESS_UPDATED))
            }

            delete("/{id}", {
                summary = "배송지 삭제"
                tags("Addresses")
                request {
                    pathParameter<Int>("id") {
                        description = "배송지 ID"
                        required = true
                    }
                }
                authResponse()
                response {
                    code(HttpStatusCode.NoContent) {
                        description = "배송지 삭제 성공"
                    }
                    code(HttpStatusCode.BadRequest) {
                        description = "잘못된 배송지 ID"
                    }
                    code(HttpStatusCode.NotFound) {
                        description = "배송지 없음"
                    }
                }
            }) {
                val userId = call.requireUserId() ?: return@delete
                val id = call.getRequiredIntParam("id", Errors.Address.INVALID_ADDRESS_ID) ?: return@delete
                addressService.deleteAddress(id, userId)
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
}
