package com.ninezero.features.user.presentation

import com.ninezero.core.common.config.Constants
import com.ninezero.core.common.config.Messages
import com.ninezero.core.common.exception.Errors
import com.ninezero.core.common.util.*
import com.ninezero.features.user.domain.ProfileService
import com.ninezero.features.user.presentation.models.request.UpdateProfileRequest
import com.ninezero.features.user.presentation.models.response.ProfileResponse
import io.github.smiley4.ktoropenapi.get
import io.github.smiley4.ktoropenapi.post
import io.github.smiley4.ktoropenapi.put
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.koin.ktor.ext.inject

fun Route.profileRoutes() {
    route(Constants.Endpoints.PROFILE) {
        val profileService by inject<ProfileService>()

        authenticate("jwt", optional = true) {
            get("/{userId}", {
                summary = "프로필 조회 (공개)"
                tags("Profile")
                request {
                    pathParameter<Int>("userId") {
                        description = "사용자 ID"
                        required = true
                        example("default") {
                            value = 1
                        }
                    }
                }
                apiResponse()
                response {
                    code(HttpStatusCode.OK) {
                        description = "프로필 조회 성공"
                        body<ApiResponse<ProfileResponse>>()
                    }
                    code(HttpStatusCode.NotFound) {
                        description = "사용자를 찾을 수 없음"
                    }
                }
            }) {
                val userId = call.getRequiredIntParam("userId", Errors.User.INVALID_USER_ID)
                    ?: return@get
                val currentUserId = call.getUserIdOrNull()

                val response = profileService.getUserProfile(userId, currentUserId)
                call.respond(HttpStatusCode.OK, ApiResponse.success(response))
            }
        }

        authenticate("jwt") {
            get("/me", {
                summary = "내 프로필 조회"
                tags("Profile")
                authResponse()
                response {
                    code(HttpStatusCode.OK) {
                        description = "내 프로필 조회 성공"
                        body<ApiResponse<ProfileResponse>>()
                    }
                }
            }) {
                val userId = call.requireUserId() ?: return@get

                val response = profileService.getUserProfile(userId)
                call.respond(HttpStatusCode.OK, ApiResponse.success(response))
            }

            put({
                summary = "프로필 수정"
                tags("Profile")
                request {
                    body<UpdateProfileRequest> {
                        description = "수정할 프로필 정보"
                        required = true
                        example("default") {
                            value = UpdateProfileRequest(
                                displayName = "홍길동",
                                bio = "안녕하세요! 반갑습니다.",
                                location = "Seoul, Korea",
                                website = "https://example.com"
                            )
                        }
                        example("partial") {
                            value = UpdateProfileRequest(
                                displayName = "Rick Astley"
                            )
                        }
                    }
                }
                authResponse()
                response {
                    code(HttpStatusCode.OK) {
                        description = "프로필 수정 성공"
                        body<ApiResponse<ProfileResponse>>()
                    }
                    code(HttpStatusCode.BadRequest) {
                        description = "잘못된 입력 데이터"
                    }
                }
            }) {
                val userId = call.requireUserId() ?: return@put

                val request = call.receive<UpdateProfileRequest>()
                val response = profileService.updateProfile(userId, request)
                call.respond(HttpStatusCode.OK, ApiResponse.success(response, Messages.Profile.PROFILE_UPDATED))
            }

            post("/avatar", {
                summary = "프로필 이미지 업로드"
                tags("Profile")
                authResponse()
                response {
                    code(HttpStatusCode.Created) {
                        description = "이미지 업로드 성공"
                        body<ApiResponse<ProfileResponse>>()
                    }
                    code(HttpStatusCode.BadRequest) {
                        description = "파일이 없거나 잘못된 형식"
                    }
                }
            }) {
                val userId = call.requireUserId() ?: return@post

                val (files, contentTypes) = call.receiveFileParts()
                val fileBytes = files.firstOrNull()
                val contentType = contentTypes.firstOrNull() ?: "image/jpeg"

                if (fileBytes == null) {
                    return@post call.respond(
                        HttpStatusCode.BadRequest,
                        ApiResponse.error<Unit>(Errors.File.NO_FILE_UPLOADED)
                    )
                }

                val response = profileService.uploadAvatar(userId, fileBytes, contentType)
                call.respond(HttpStatusCode.Created, ApiResponse.success(response, Messages.Profile.AVATAR_UPLOADED))
            }
        }
    }
}
