package com.ninezero.features.social.presentation

import com.ninezero.core.common.config.Constants
import com.ninezero.core.common.exception.Errors
import com.ninezero.core.common.util.*
import com.ninezero.features.social.domain.FeedService
import com.ninezero.features.social.presentation.models.request.FeedRequest
import io.github.smiley4.ktoropenapi.get
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.koin.ktor.ext.inject

fun Route.feedRoutes() {
    route(Constants.Endpoints.FEEDS) {
        val feedService by inject<FeedService>()

        authenticate("jwt", optional = true) {
            get("/explore", {
                summary = "탐색 피드 (공개)"
                tags("Feed")
                description = "인기 게시글"
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
                apiResponse()
            }) {
                val page = call.getIntParam("page", 1)
                val limit = call.getIntParam("limit", Constants.DEFAULT_PAGE_LIMIT)
                val currentUserId = call.getUserIdOrNull()

                val feedRequest = FeedRequest(
                    page = page,
                    limit = limit
                )

                val response = feedService.getExploreFeed(currentUserId, feedRequest)
                call.respond(HttpStatusCode.OK, ApiResponse.success(response))
            }

            get("/trending", {
                summary = "트렌딩 피드 (공개)"
                tags("Feed")
                description = "지금 뜨는 게시글"
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
                apiResponse()
            }) {
                val page = call.getIntParam("page", 1)
                val limit = call.getIntParam("limit", Constants.DEFAULT_PAGE_LIMIT)
                val currentUserId = call.getUserIdOrNull()

                val feedRequest = FeedRequest(
                    page = page,
                    limit = limit
                )

                val response = feedService.getTrendingFeed(currentUserId, feedRequest)
                call.respond(HttpStatusCode.OK, ApiResponse.success(response))
            }

            get("/hashtag/{hashtag}", {
                summary = "해시태그 피드 (공개)"
                tags("Feed")
                request {
                    pathParameter<String>("hashtag") {
                        description = "해시태그 (# 제외)"
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
            }) {
                val hashtag = call.getRequiredStringParam("hashtag", Errors.Social.Post.HASHTAG_REQUIRED)
                    ?: return@get

                val page = call.getIntParam("page", 1)
                val limit = call.getIntParam("limit", Constants.DEFAULT_PAGE_LIMIT)
                val currentUserId = call.getUserIdOrNull()

                val feedRequest = FeedRequest(
                    page = page,
                    limit = limit
                )

                val response = feedService.getHashtagFeed(hashtag, currentUserId, feedRequest)
                call.respond(HttpStatusCode.OK, ApiResponse.success(response))
            }
        }

        authenticate("jwt") {
            get("/home", {
                summary = "홈 피드"
                tags("Feed")
                description = "팔로우한 사용자의 게시글"
                request {
                    queryParameter<String>("cursor") {
                        description = "다음 페이지 커서 (응답의 nextCursor, 생략 시 처음부터)"
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

                val cursor = call.getOptionalStringParam("cursor")
                val limit = call.getIntParam("limit", Constants.DEFAULT_PAGE_LIMIT)

                val response = feedService.getHomeFeed(userId, cursor, limit)
                call.respond(HttpStatusCode.OK, ApiResponse.success(response))
            }
        }
    }
}
