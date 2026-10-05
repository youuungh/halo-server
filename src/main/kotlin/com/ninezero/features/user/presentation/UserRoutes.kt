package com.ninezero.features.user.presentation

import com.ninezero.core.common.config.Constants
import com.ninezero.core.common.config.PostContextType
import com.ninezero.core.common.config.UserCommentSortType
import com.ninezero.core.common.config.UserRole
import com.ninezero.core.common.exception.Errors
import com.ninezero.core.common.util.*
import com.ninezero.features.social.domain.CommentService
import com.ninezero.features.social.presentation.models.response.UserCommentThreadsResponse
import com.ninezero.features.user.domain.UserService
import com.ninezero.features.user.presentation.models.response.UserListResponse
import com.ninezero.features.user.presentation.models.response.UserStatisticsResponse
import com.ninezero.features.user.presentation.models.response.UsernameAvailabilityResponse
import io.github.smiley4.ktoropenapi.get
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.koin.ktor.ext.inject

fun Route.userRoutes() {
    route(Constants.Endpoints.USERS) {
        val userService by inject<UserService>()
        val commentService by inject<CommentService>()

        authenticate("jwt", optional = true) {
            get({
                summary = "사용자 목록 조회 (공개)"
                tags("Users")
                request {
                    queryParameter<String>("role") {
                        description = "역할 필터 (USER, CREATOR, ADMIN)"
                        required = false
                        example("default") {
                            value = "USER"
                        }
                    }
                    queryParameter<Int>("page") {
                        description = "페이지 번호 (기본값: 1)"
                        required = false
                    }
                    queryParameter<Int>("limit") {
                        description = "페이지 크기 (기본값: 20)"
                        required = false
                    }
                    queryParameter<String>("search") {
                        description = "검색어 (사용자명)"
                        required = false
                        example("default") {
                            value = "rick"
                        }
                    }
                    queryParameter<String>("sortBy") {
                        description =
                            "정렬 컬럼 (username, createdAt, role, followerCount, followingCount, postCount)"
                        required = false
                        example("default") {
                            value = "createdAt"
                        }
                    }
                    queryParameter<String>("sortOrder") {
                        description = "정렬 방향 (asc, desc)"
                        required = false
                        example("default") {
                            value = "desc"
                        }
                    }
                }
                apiResponse()
                response {
                    code(HttpStatusCode.OK) {
                        description = "사용자 목록 조회 성공"
                        body<ApiResponse<UserListResponse>>()
                    }
                }
            }) {
                val page = call.getIntParam("page", 1)
                val limit = call.getIntParam("limit", Constants.DEFAULT_PAGE_LIMIT)
                val role = call.getOptionalEnumParam<UserRole>("role")
                val search = call.getOptionalStringParam("search")
                val sortBy = call.getOptionalStringParam("sortBy")
                val sortOrder = call.getOptionalStringParam("sortOrder")

                val response = userService.getAllUsers(page, limit, role, search, sortBy, sortOrder)
                call.respond(HttpStatusCode.OK, ApiResponse.success(response))
            }

            get("/search", {
                summary = "사용자 검색 (공개)"
                tags("Users")
                request {
                    queryParameter<String>("q") {
                        description = "검색 키워드"
                        required = true
                        example("default") {
                            value = "rick"
                        }
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
                    code(HttpStatusCode.OK) {
                        description = "검색 결과"
                        body<ApiResponse<UserListResponse>>()
                    }
                    code(HttpStatusCode.BadRequest) {
                        description = "검색 키워드 누락"
                    }
                }
            }) {
                val query = call.getRequiredStringParam("q", Errors.Search.SEARCH_QUERY_REQUIRED)
                    ?: return@get

                val page = call.getIntParam("page", 1)
                val limit = call.getIntParam("limit", Constants.DEFAULT_PAGE_LIMIT)
                val currentUserId = call.getUserIdOrNull()

                val response = userService.searchUsers(query, page, limit, currentUserId)
                call.respond(HttpStatusCode.OK, ApiResponse.success(response))
            }

            get("/check-username", {
                summary = "사용자명 중복 확인 (공개)"
                tags("Users")
                request {
                    queryParameter<String>("username") {
                        description = "확인할 사용자명"
                        required = true
                        example("default") {
                            value = "rickastley"
                        }
                    }
                }
                apiResponse()
                response {
                    code(HttpStatusCode.OK) {
                        description = "사용 가능 여부 응답"
                        body<ApiResponse<UsernameAvailabilityResponse>>()
                    }
                    code(HttpStatusCode.BadRequest) {
                        description = "사용자명 누락 또는 형식 오류"
                    }
                }
            }) {
                val username = call.getRequiredStringParam("username", Errors.User.USERNAME_QUERY_REQUIRED)
                    ?: return@get

                val response = userService.checkUsernameAvailability(username)
                call.respond(HttpStatusCode.OK, ApiResponse.success(response))
            }

            get("/{userId}/comments/threads", {
                summary = "사용자 댓글 스레드 조회 (공개)"
                tags("Users")
                description = "답글 탭용"
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
                    queryParameter<String>("sort") {
                        description = "정렬 방식: LATEST (최신순, 기본값), OLDEST (오래된순)"
                        required = false
                    }
                    queryParameter<String>("contextType") {
                        description = "포스트 타입 필터: GENERAL, CREATOR_FEED, COMMUNITY"
                        required = false
                    }
                }
                apiResponse()
                response {
                    code(HttpStatusCode.OK) {
                        description = "댓글 스레드 조회 성공"
                        body<ApiResponse<UserCommentThreadsResponse>>()
                    }
                    code(HttpStatusCode.NotFound) {
                        description = "사용자를 찾을 수 없음"
                    }
                }
            }) {
                val userId = call.getRequiredIntParam("userId", Errors.User.INVALID_USER_ID)
                    ?: return@get

                val cursor = call.getOptionalStringParam("cursor")
                val limit = call.getIntParam("limit", Constants.DEFAULT_PAGE_LIMIT)
                val currentUserId = call.getUserIdOrNull()
                val sort = call.getOptionalEnumParam<UserCommentSortType>("sort") ?: UserCommentSortType.LATEST
                val contextType = call.getOptionalEnumParam<PostContextType>("contextType")

                val response = commentService.getUserCommentThreads(
                    userId = userId,
                    currentUserId = currentUserId,
                    cursor = cursor,
                    limit = limit,
                    sort = sort,
                    contextType = contextType
                )
                call.respond(HttpStatusCode.OK, ApiResponse.success(response))
            }
        }

        authenticate("jwt") {
            get("/admin/statistics", {
                summary = "사용자 통계 (Admin)"
                tags("Users")
                authResponse()
                response {
                    code(HttpStatusCode.OK) {
                        description = "통계 조회 성공"
                        body<ApiResponse<UserStatisticsResponse>>()
                    }
                    code(HttpStatusCode.Forbidden) {
                        description = "권한 없음"
                    }
                }
            }) {
                call.requireAdminId() ?: return@get

                val response = userService.getUserStatistics()
                call.respond(HttpStatusCode.OK, ApiResponse.success(response))
            }
        }
    }
}
