package com.ninezero.features.share.presentation

import com.ninezero.core.common.config.Constants
import com.ninezero.core.common.util.ApiResponse
import com.ninezero.core.common.util.authResponse
import com.ninezero.core.common.util.requireUserId
import com.ninezero.features.share.domain.ShareService
import com.ninezero.features.share.presentation.models.request.SharePostRequest
import com.ninezero.features.share.presentation.models.request.ShareProductRequest
import io.github.smiley4.ktoropenapi.post
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.koin.ktor.ext.inject

fun Route.shareRoutes() {
    route(Constants.Endpoints.SHARE) {
        val shareService by inject<ShareService>()

        authenticate("jwt") {
            post("/post", {
                summary = "포스트 공유"
                tags("Share")
                description = "팔로잉 유저에게 채팅으로 전송"
                request {
                    body<SharePostRequest>()
                }
                authResponse()
            }) {
                val userId = call.requireUserId() ?: return@post
                val request = call.receive<SharePostRequest>()
                val result = shareService.sharePost(userId, request)
                call.respond(HttpStatusCode.OK, ApiResponse.success(result))
            }

            post("/product", {
                summary = "상품 공유"
                tags("Share")
                description = "팔로잉 유저에게 채팅으로 전송"
                request {
                    body<ShareProductRequest>()
                }
                authResponse()
            }) {
                val userId = call.requireUserId() ?: return@post
                val request = call.receive<ShareProductRequest>()
                val result = shareService.shareProduct(userId, request)
                call.respond(HttpStatusCode.OK, ApiResponse.success(result))
            }
        }
    }
}
