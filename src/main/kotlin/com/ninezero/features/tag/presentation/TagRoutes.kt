package com.ninezero.features.tag.presentation

import com.ninezero.core.common.config.Constants
import com.ninezero.core.common.config.Messages
import com.ninezero.core.common.config.TagTargetType
import com.ninezero.core.common.exception.Errors
import com.ninezero.core.common.util.ApiResponse
import com.ninezero.core.common.util.apiResponse
import com.ninezero.core.common.util.authResponse
import com.ninezero.core.common.util.getRequiredIntParam
import com.ninezero.core.common.util.getRequiredStringParam
import com.ninezero.core.common.util.requireUserId
import com.ninezero.features.tag.domain.TagService
import com.ninezero.features.tag.presentation.models.request.TagRequest
import com.ninezero.features.tag.presentation.models.request.UpdateTagRequest
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

fun Route.tagRoutes() {
    route(Constants.Endpoints.TAGS) {
        val tagService by inject<TagService>()

        get("/creator/{creatorId}", {
            summary = "크리에이터 태그 목록 조회 (공개)"
            tags("Tags")
            request {
                pathParameter<Int>("creatorId") {
                    description = "크리에이터 ID"
                    required = true
                }
                queryParameter<String>("targetType") {
                    description = "태그 대상 타입 (POST/PRODUCT)"
                    required = true
                    example("default") {
                        value = "POST"
                    }
                }
            }
            apiResponse()
            response {
                code(HttpStatusCode.BadRequest) {
                    description = "잘못된 targetType"
                }
            }
        }) {
            val creatorId = call.getRequiredIntParam("creatorId", Errors.Common.INVALID_CREATOR_ID)
                ?: return@get

            val targetTypeStr = call.getRequiredStringParam("targetType", Errors.Social.Tag.TAG_TARGET_TYPE_REQUIRED)
                ?: return@get

            val targetType = try {
                TagTargetType.valueOf(targetTypeStr.uppercase())
            } catch (_: Exception) {
                return@get call.respond(HttpStatusCode.BadRequest, ApiResponse.error<Unit>(Errors.Social.Tag.TAG_TARGET_TYPE_INVALID))
            }

            val response = tagService.getCreatorTags(creatorId, targetType)
            call.respond(HttpStatusCode.OK, ApiResponse.success(response))
        }

        authenticate("jwt") {
            post({
                summary = "태그 생성 (Creator)"
                tags("Tags")
                request {
                    body<TagRequest> {
                        description = "태그 정보"
                        required = true
                        example("포스트 태그") {
                            value = TagRequest(
                                name = "일상",
                                targetType = TagTargetType.POST,
                                isSectionEnabled = true
                            )
                        }
                        example("상품 태그") {
                            value = TagRequest(
                                name = "굿즈",
                                targetType = TagTargetType.PRODUCT,
                                isSectionEnabled = false
                            )
                        }
                    }
                }
                authResponse()
                response {
                    code(HttpStatusCode.Created) {
                        description = "태그 생성 성공"
                    }
                    code(HttpStatusCode.Forbidden) {
                        description = "크리에이터만 생성 가능"
                    }
                }
            }) {
                val userId = call.requireUserId() ?: return@post

                val request = call.receive<TagRequest>()
                val response = tagService.createTag(userId, request)
                call.respond(HttpStatusCode.Created, ApiResponse.success(response, Messages.Social.TAG_CREATED))
            }

            put("/{tagId}", {
                summary = "태그 수정 (Creator)"
                tags("Tags")
                request {
                    pathParameter<Int>("tagId") {
                        description = "태그 ID"
                        required = true
                    }
                    body<UpdateTagRequest> {
                        description = "수정할 태그 정보"
                        required = true
                        example("default") {
                            value = UpdateTagRequest(
                                name = "일상 & 브이로그",
                                isSectionEnabled = true
                            )
                        }
                    }
                }
                authResponse()
                response {
                    code(HttpStatusCode.Forbidden) {
                        description = "본인 태그만 수정 가능"
                    }
                    code(HttpStatusCode.NotFound) {
                        description = "태그 없음"
                    }
                }
            }) {
                val userId = call.requireUserId() ?: return@put

                val tagId = call.getRequiredIntParam("tagId", Errors.Social.Tag.INVALID_TAG_ID)
                    ?: return@put

                val request = call.receive<UpdateTagRequest>()
                val response = tagService.updateTag(tagId, userId, request)
                call.respond(HttpStatusCode.OK, ApiResponse.success(response, Messages.Social.TAG_UPDATED))
            }

            delete("/{tagId}", {
                summary = "태그 삭제 (Creator)"
                tags("Tags")
                request {
                    pathParameter<Int>("tagId") {
                        description = "태그 ID"
                        required = true
                    }
                }
                authResponse()
                response {
                    code(HttpStatusCode.NoContent) {
                        description = "태그 삭제 성공"
                    }
                    code(HttpStatusCode.Forbidden) {
                        description = "본인 태그만 삭제 가능"
                    }
                    code(HttpStatusCode.NotFound) {
                        description = "태그 없음"
                    }
                }
            }) {
                val userId = call.requireUserId() ?: return@delete

                val tagId = call.getRequiredIntParam("tagId", Errors.Social.Tag.INVALID_TAG_ID)
                    ?: return@delete

                tagService.deleteTag(tagId, userId)
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
}
