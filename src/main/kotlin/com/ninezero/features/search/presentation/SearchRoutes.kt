package com.ninezero.features.search.presentation

import com.ninezero.core.common.config.Constants
import com.ninezero.core.common.exception.Errors
import com.ninezero.core.common.util.*
import com.ninezero.features.search.domain.CreatorSearchService
import com.ninezero.features.search.domain.SearchHistoryService
import io.github.smiley4.ktoropenapi.delete
import io.github.smiley4.ktoropenapi.get
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.koin.ktor.ext.inject

fun Route.searchRoutes() {
    route(Constants.Endpoints.SEARCH) {
        val creatorSearchService by inject<CreatorSearchService>()
        val searchHistoryService by inject<SearchHistoryService>()

        authenticate("jwt", optional = true) {
            get("/creators", {
                summary = "크리에이터 검색 (공개)"
                tags("Search")
                request {
                    queryParameter<String>("q") {
                        description = "검색 키워드"
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
                    queryParameter<Boolean>("saveHistory") {
                        description = "검색 기록 저장 여부 (기본값: true). 디바운스 라이브 검색은 false로 호출"
                        required = false
                    }
                }
                apiResponse()
                response {
                    code(HttpStatusCode.BadRequest) {
                        description = "검색 키워드 필요"
                    }
                }
            }) {
                val keyword = call.getRequiredStringParam("q", Errors.Search.SEARCH_QUERY_REQUIRED)
                    ?: return@get

                val page = call.getIntParam("page", 1)
                val limit = call.getIntParam("limit", Constants.DEFAULT_PAGE_LIMIT)
                // 명시적 커밋에서만 저장
                val saveHistory = call.request.queryParameters["saveHistory"]?.toBooleanStrictOrNull() ?: true
                val currentUserId = call.getUserIdOrNull()

                val response = creatorSearchService.searchCreators(
                    keyword = keyword,
                    currentUserId = currentUserId,
                    page = page,
                    limit = limit,
                    saveHistory = saveHistory
                )
                call.respond(HttpStatusCode.OK, ApiResponse.success(response))
            }

            get("/creators/popular", {
                summary = "추천 크리에이터 조회 (공개)"
                tags("Search")
                description = "팔로워 상위 풀에서 본인·팔로우 중 제외 후 무작위, 15분 캐시"
                request {
                    queryParameter<Int>("limit") {
                        description = "조회할 크리에이터 수 (기본값: 10)"
                        required = false
                    }
                }
                apiResponse()
            }) {
                val limit = call.getIntParam("limit", Constants.Search.POPULAR_LIMIT)
                val currentUserId = call.getUserIdOrNull()

                val response = creatorSearchService.getPopularCreators(
                    currentUserId = currentUserId,
                    limit = limit
                )
                call.respond(HttpStatusCode.OK, ApiResponse.success(response))
            }
        }

        authenticate("jwt") {
            get("/history", {
                summary = "검색 기록 조회"
                tags("Search")
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

                val response = searchHistoryService.getHistory(
                    userId = userId,
                    page = page,
                    limit = limit
                )
                call.respond(HttpStatusCode.OK, ApiResponse.success(response))
            }

            delete("/history/{id}", {
                summary = "검색 기록 삭제"
                tags("Search")
                request {
                    pathParameter<Int>("id") {
                        description = "검색 기록 ID"
                        required = true
                    }
                }
                authResponse()
                response {
                    code(HttpStatusCode.NoContent) {
                        description = "검색 기록 삭제 성공"
                    }
                    code(HttpStatusCode.NotFound) {
                        description = "검색 기록 없음"
                    }
                    code(HttpStatusCode.Forbidden) {
                        description = "검색 기록 삭제 권한 없음"
                    }
                    code(HttpStatusCode.Unauthorized) {
                        description = "인증 필요"
                    }
                }
            }) {
                val userId = call.requireUserId() ?: return@delete

                val historyId = call.getRequiredIntParam("id", Errors.Search.INVALID_SEARCH_HISTORY_ID)
                    ?: return@delete

                searchHistoryService.deleteHistory(userId, historyId)
                call.respond(HttpStatusCode.NoContent)
            }

            delete("/history/clear", {
                summary = "검색 기록 전체 삭제"
                tags("Search")
                authResponse()
                response {
                    code(HttpStatusCode.NoContent) {
                        description = "검색 기록 전체 삭제 성공"
                    }
                    code(HttpStatusCode.Unauthorized) {
                        description = "인증 필요"
                    }
                }
            }) {
                val userId = call.requireUserId() ?: return@delete

                searchHistoryService.clearHistory(userId)
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
}