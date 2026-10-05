package com.ninezero.features.social.presentation

import com.ninezero.core.common.config.Constants
import com.ninezero.core.common.config.Messages
import com.ninezero.core.common.exception.Errors
import com.ninezero.core.common.util.*
import com.ninezero.features.social.domain.FollowService
import com.ninezero.features.social.presentation.models.request.FollowRequest
import com.ninezero.features.social.presentation.models.response.FollowResponse
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

fun Route.followRoutes() {
    route(Constants.Endpoints.FOLLOWS) {
        val followService by inject<FollowService>()

        authenticate("jwt", optional = true) {
            get("/{userId}/followers", {
                summary = "사용자 팔로워 목록 조회 (공개)"
                tags("Follows")
                request {
                    pathParameter<Int>("userId") {
                        description = "대상 사용자 ID"
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
                    queryParameter<String>("search") {
                        description = "검색어 (username, displayName 대소문자 무시 부분 일치)"
                        required = false
                    }
                }
                apiResponse()
            }) {
                val targetUserId = call.getRequiredIntParam("userId", Errors.User.INVALID_USER_ID)
                    ?: return@get

                val cursor = call.getOptionalStringParam("cursor")
                val limit = call.getIntParam("limit", Constants.DEFAULT_PAGE_LIMIT)
                val search = call.getOptionalStringParam("search")
                val currentUserId = call.getUserIdOrNull()

                val response = followService.getFollowers(targetUserId, currentUserId, cursor, limit, search)
                call.respond(HttpStatusCode.OK, ApiResponse.success(response))
            }

            get("/{userId}/following", {
                summary = "사용자 팔로잉 목록 조회 (공개)"
                tags("Follows")
                request {
                    pathParameter<Int>("userId") {
                        description = "대상 사용자 ID"
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
                    queryParameter<String>("search") {
                        description = "검색어 (username, displayName 대소문자 무시 부분 일치)"
                        required = false
                    }
                }
                apiResponse()
            }) {
                val targetUserId = call.getRequiredIntParam("userId", Errors.User.INVALID_USER_ID)
                    ?: return@get

                val cursor = call.getOptionalStringParam("cursor")
                val limit = call.getIntParam("limit", Constants.DEFAULT_PAGE_LIMIT)
                val search = call.getOptionalStringParam("search")
                val currentUserId = call.getUserIdOrNull()

                val response = followService.getFollowing(targetUserId, currentUserId, cursor, limit, search)
                call.respond(HttpStatusCode.OK, ApiResponse.success(response))
            }
        }

        authenticate("jwt") {
            post({
                summary = "팔로우"
                tags("Follows")
                request {
                    body<FollowRequest> {
                        description = "팔로우할 사용자 정보"
                        required = true
                        example("default") {
                            value = FollowRequest(targetUserId = 5)
                        }
                    }
                }
                authResponse()
                response {
                    code(HttpStatusCode.Created) {
                        description = "팔로우 성공"
                        body<ApiResponse<FollowResponse>>()
                    }
                    code(HttpStatusCode.BadRequest) {
                        description = "본인을 팔로우할 수 없거나 이미 팔로우 중"
                    }
                }
            }) {
                val userId = call.requireUserId() ?: return@post

                val request = call.receive<FollowRequest>()
                val response = followService.followUser(userId, request.targetUserId)
                call.respond(HttpStatusCode.Created, ApiResponse.success(response, Messages.Social.FOLLOW_SUCCESS))
            }

            delete("/{userId}", {
                summary = "언팔로우"
                tags("Follows")
                request {
                    pathParameter<Int>("userId") {
                        description = "언팔로우할 사용자 ID"
                        required = true
                    }
                }
                authResponse()
            }) {
                val currentUserId = call.requireUserId() ?: return@delete

                val targetUserId = call.getRequiredIntParam("userId", Errors.User.INVALID_USER_ID)
                    ?: return@delete

                val response = followService.unfollowUser(currentUserId, targetUserId)
                call.respond(HttpStatusCode.OK, ApiResponse.success(response, Messages.Social.UNFOLLOW_SUCCESS))
            }

            get("/{userId}/mutual", {
                summary = "맞팔 여부 확인"
                tags("Follows")
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

                val response = followService.getMutualFollow(currentUserId, targetUserId)
                call.respond(HttpStatusCode.OK, ApiResponse.success(response))
            }

            get("/my/followers", {
                summary = "내 팔로워 목록 조회"
                tags("Follows")
                request {
                    queryParameter<String>("cursor") {
                        description = "다음 페이지 커서 (응답의 nextCursor, 생략 시 처음부터)"
                        required = false
                    }
                    queryParameter<Int>("limit") {
                        description = "페이지 크기 (기본값: 20)"
                        required = false
                    }
                    queryParameter<String>("search") {
                        description = "검색어 (username, displayName 대소문자 무시 부분 일치)"
                        required = false
                    }
                }
                authResponse()
            }) {
                val userId = call.requireUserId() ?: return@get

                val cursor = call.getOptionalStringParam("cursor")
                val limit = call.getIntParam("limit", Constants.DEFAULT_PAGE_LIMIT)
                val search = call.getOptionalStringParam("search")

                val response = followService.getFollowers(userId, userId, cursor, limit, search)
                call.respond(HttpStatusCode.OK, ApiResponse.success(response))
            }

            get("/my/following", {
                summary = "내 팔로잉 목록 조회"
                tags("Follows")
                request {
                    queryParameter<String>("cursor") {
                        description = "다음 페이지 커서 (응답의 nextCursor, 생략 시 처음부터)"
                        required = false
                    }
                    queryParameter<Int>("limit") {
                        description = "페이지 크기 (기본값: 20)"
                        required = false
                    }
                    queryParameter<String>("search") {
                        description = "검색어 (username, displayName 대소문자 무시 부분 일치)"
                        required = false
                    }
                }
                authResponse()
            }) {
                val userId = call.requireUserId() ?: return@get

                val cursor = call.getOptionalStringParam("cursor")
                val limit = call.getIntParam("limit", Constants.DEFAULT_PAGE_LIMIT)
                val search = call.getOptionalStringParam("search")

                val response = followService.getFollowing(userId, userId, cursor, limit, search)
                call.respond(HttpStatusCode.OK, ApiResponse.success(response))
            }

            get("/my/following/creators", {
                summary = "팔로우 크리에이터 목록 조회"
                tags("Follows")
                description = "알림 설정 포함"
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

                val response = followService.getFollowedCreators(userId, page, limit)
                call.respond(HttpStatusCode.OK, ApiResponse.success(response))
            }

            get("/recommendations", {
                summary = "팔로우 추천 조회"
                tags("Follows")
                request {
                    queryParameter<Int>("limit") {
                        description = "추천 사용자 수 (기본값: 10)"
                        required = false
                    }
                }
                authResponse()
            }) {
                val userId = call.requireUserId() ?: return@get

                val limit = call.getIntParam("limit", Constants.Social.DEFAULT_FOLLOW_LIST_LIMIT)

                val response = followService.getFollowRecommendations(userId, limit)
                call.respond(HttpStatusCode.OK, ApiResponse.success(response))
            }

            put("/{userId}/notifications/posts", {
                summary = "새 글 알림 설정 토글"
                tags("Follows")
                request {
                    pathParameter<Int>("userId") {
                        description = "크리에이터 ID (팔로우 대상)"
                        required = true
                    }
                    queryParameter<Boolean>("enabled") {
                        description = "알림 활성화 여부 (true: 켜기, false: 끄기)"
                        required = true
                    }
                }
                authResponse()
            }) {
                val currentUserId = call.requireUserId() ?: return@put

                val creatorId = call.getRequiredIntParam("userId", Errors.User.INVALID_USER_ID)
                    ?: return@put

                val enabled = call.getRequiredBooleanParam("enabled", Errors.Social.Follow.ENABLED_PARAM_REQUIRED)
                    ?: return@put

                val response = followService.updateNewPostNotification(currentUserId, creatorId, enabled)
                call.respond(HttpStatusCode.OK, ApiResponse.success(response))
            }

            put("/{userId}/notifications/products", {
                summary = "새 상품 알림 설정 토글"
                tags("Follows")
                request {
                    pathParameter<Int>("userId") {
                        description = "크리에이터 ID (팔로우 대상)"
                        required = true
                    }
                    queryParameter<Boolean>("enabled") {
                        description = "알림 활성화 여부 (true: 켜기, false: 끄기)"
                        required = true
                    }
                }
                authResponse()
            }) {
                val currentUserId = call.requireUserId() ?: return@put

                val creatorId = call.getRequiredIntParam("userId", Errors.User.INVALID_USER_ID)
                    ?: return@put

                val enabled = call.getRequiredBooleanParam("enabled", Errors.Social.Follow.ENABLED_PARAM_REQUIRED)
                    ?: return@put

                val response = followService.updateNewProductNotification(currentUserId, creatorId, enabled)
                call.respond(HttpStatusCode.OK, ApiResponse.success(response))
            }
        }
    }
}
