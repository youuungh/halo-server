package com.ninezero.features.user.presentation

import com.ninezero.core.common.config.Constants
import com.ninezero.core.common.exception.Errors
import com.ninezero.core.common.util.*
import com.ninezero.features.user.domain.BlockedUserService
import io.github.smiley4.ktoropenapi.get
import io.github.smiley4.ktoropenapi.post
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.koin.ktor.ext.inject

fun Route.blockedUserRoutes() {
    route(Constants.Endpoints.BLOCKED_USERS) {
        val blockedUserService by inject<BlockedUserService>()

        authenticate("jwt") {
            post("/{userId}", {
                summary = "사용자 차단/해제 토글"
                tags("BlockedUsers")
                request {
                    pathParameter<Int>("userId") {
                        description = "차단할 사용자 ID"
                        required = true
                    }
                }
                authResponse()
            }) {
                val currentUserId = call.requireUserId() ?: return@post

                val targetUserId = call.getRequiredIntParam("userId", Errors.User.INVALID_USER_ID)
                    ?: return@post

                val response = blockedUserService.toggleBlock(currentUserId, targetUserId)
                call.respond(HttpStatusCode.OK, ApiResponse.success(response))
            }

            get("/{userId}/status", {
                summary = "차단 상태 확인"
                tags("BlockedUsers")
                request {
                    pathParameter<Int>("userId") {
                        description = "확인할 사용자 ID"
                        required = true
                    }
                }
                authResponse()
            }) {
                val currentUserId = call.requireUserId() ?: return@get

                val targetUserId = call.getRequiredIntParam("userId", Errors.User.INVALID_USER_ID)
                    ?: return@get

                val response = blockedUserService.isBlocked(currentUserId, targetUserId)
                call.respond(HttpStatusCode.OK, ApiResponse.success(response))
            }

            get("/{userId}/relation", {
                summary = "양쪽 차단 관계 확인"
                tags("BlockedUsers")
                description = "isBlockedByMe·isBlockedByOther"
                request {
                    pathParameter<Int>("userId") {
                        description = "확인할 사용자 ID"
                        required = true
                    }
                }
                authResponse()
            }) {
                val currentUserId = call.requireUserId() ?: return@get

                val targetUserId = call.getRequiredIntParam("userId", Errors.User.INVALID_USER_ID)
                    ?: return@get

                val response = blockedUserService.getBlockRelation(currentUserId, targetUserId)
                call.respond(HttpStatusCode.OK, ApiResponse.success(response))
            }

            get("/my", {
                summary = "차단 사용자 목록 조회"
                tags("BlockedUsers")
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

                val response = blockedUserService.getBlockedUsers(userId, page, limit)
                call.respond(HttpStatusCode.OK, ApiResponse.success(response))
            }

            get("/my/count", {
                summary = "차단 사용자 수 조회"
                tags("BlockedUsers")
                authResponse()
            }) {
                val userId = call.requireUserId() ?: return@get

                val response = blockedUserService.getBlockedUserCount(userId)
                call.respond(HttpStatusCode.OK, ApiResponse.success(response))
            }
        }
    }
}
