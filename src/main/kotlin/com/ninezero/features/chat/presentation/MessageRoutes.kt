package com.ninezero.features.chat.presentation

import com.ninezero.core.common.config.ChatMessageType
import com.ninezero.core.common.config.Constants
import com.ninezero.core.common.config.Messages
import com.ninezero.core.common.exception.Errors
import com.ninezero.core.common.util.*
import com.ninezero.core.storage.StorageConfig
import com.ninezero.features.chat.domain.MessageService
import com.ninezero.features.chat.presentation.models.request.ChatUploadPrepareRequest
import com.ninezero.features.chat.presentation.models.request.SendMessageRequest
import com.ninezero.features.chat.presentation.models.response.ChatImageUploadResponse
import com.ninezero.features.upload.presentation.models.response.UploadPrepareResponse
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

fun Route.messageRoutes() {
    route(Constants.Endpoints.CHATS) {
        val messageService by inject<MessageService>()

        authenticate("jwt") {
            post("/{roomId}/messages", {
                summary = "메시지 전송"
                tags("Messages")
                request {
                    pathParameter<Int>("roomId") {
                        description = "채팅방 ID"
                        required = true
                    }
                    body<SendMessageRequest> {
                        description = "메시지 내용"
                        required = true
                        example("text") {
                            value = SendMessageRequest(
                                content = "안녕하세요!",
                                chatMessageType = ChatMessageType.TEXT,
                                mediaAttachments = null,
                                productId = null
                            )
                        }
                        example("image") {
                            value = SendMessageRequest(
                                content = "사진 보냈어요",
                                chatMessageType = ChatMessageType.IMAGE,
                                mediaAttachments = listOf("https://example.com/image.jpg"),
                                productId = null
                            )
                        }
                        example("product") {
                            value = SendMessageRequest(
                                content = "이 상품 어때요?",
                                chatMessageType = ChatMessageType.PRODUCT_LINK,
                                mediaAttachments = null,
                                productId = 5
                            )
                        }
                    }
                }
                authResponse()
                response {
                    code(HttpStatusCode.Created) {
                        description = "메시지 전송 성공"
                    }
                    code(HttpStatusCode.BadRequest) {
                        description = "잘못된 메시지 형식"
                    }
                    code(HttpStatusCode.NotFound) {
                        description = "채팅방 없음"
                    }
                    code(HttpStatusCode.Forbidden) {
                        description = "메시지 전송 권한 없음"
                    }
                }
            }) {
                val userId = call.requireUserId() ?: return@post

                val roomId = call.getRequiredIntParam("roomId", Errors.Chat.INVALID_ROOM_ID)
                    ?: return@post

                val request = call.receive<SendMessageRequest>()
                val response = messageService.sendMessage(roomId, userId, request)
                call.respond(HttpStatusCode.Created, ApiResponse.success(response, Messages.Chat.MESSAGE_SENT))
            }

            post("/{roomId}/images", {
                summary = "채팅 이미지 업로드"
                tags("Messages")
                request {
                    pathParameter<Int>("roomId") {
                        description = "채팅방 ID"
                        required = true
                    }
                    multipartBody {
                        description = "이미지 파일들"
                        required = true
                    }
                }
                authResponse()
                response {
                    code(HttpStatusCode.Created) {
                        description = "이미지 업로드 성공"
                        body<ApiResponse<ChatImageUploadResponse>>()
                    }
                    code(HttpStatusCode.BadRequest) {
                        description = "이미지 파일 없음"
                    }
                    code(HttpStatusCode.NotFound) {
                        description = "채팅방 없음"
                    }
                    code(HttpStatusCode.Forbidden) {
                        description = "채팅방 업로드 권한 없음"
                    }
                }
            }) {
                val userId = call.requireUserId() ?: return@post

                val roomId = call.getRequiredIntParam("roomId", Errors.Chat.INVALID_ROOM_ID)
                    ?: return@post

                val (images, _) = call.receiveFileParts(StorageConfig.FileSizeLimit.CHAT_IMAGE)

                if (images.isEmpty()) {
                    return@post call.respond(HttpStatusCode.BadRequest, ApiResponse.error<Unit>(Errors.File.NO_IMAGE_FILES))
                }

                val uploaded = messageService.uploadChatImages(roomId, userId, images)
                val response = ChatImageUploadResponse(
                    imageUrls = uploaded.map { it.first },
                    thumbnailUrls = uploaded.map { it.second ?: it.first }
                )
                call.respond(HttpStatusCode.Created, ApiResponse.success(response, Messages.Common.IMAGE_UPLOADED))
            }

            post("/{roomId}/uploads", {
                summary = "채팅 영상·파일 업로드 준비"
                tags("Messages")
                description = "서명된 업로드 URL 발급, 앱이 이 URL로 직접 올린 뒤 uploadId로 메시지 전송"
                request {
                    pathParameter<Int>("roomId") {
                        description = "채팅방 ID"
                        required = true
                    }
                    body<ChatUploadPrepareRequest> {
                        description = "종류(VIDEO·FILE), 형식, 크기, 파일명"
                        required = true
                    }
                }
                authResponse()
                response {
                    code(HttpStatusCode.Created) {
                        description = "업로드 URL 발급 성공"
                        body<ApiResponse<UploadPrepareResponse>>()
                    }
                    code(HttpStatusCode.BadRequest) {
                        description = "크기 초과 또는 지원하지 않는 형식"
                    }
                    code(HttpStatusCode.Forbidden) {
                        description = "채팅방 업로드 권한 없음"
                    }
                }
            }) {
                val userId = call.requireUserId() ?: return@post

                val roomId = call.getRequiredIntParam("roomId", Errors.Chat.INVALID_ROOM_ID)
                    ?: return@post

                val request = call.receive<ChatUploadPrepareRequest>()
                val response = messageService.prepareChatUpload(roomId, userId, request)
                call.respond(HttpStatusCode.Created, ApiResponse.success(response))
            }

            get("/{roomId}/messages", {
                summary = "메시지 목록 조회"
                tags("Messages")
                request {
                    pathParameter<Int>("roomId") {
                        description = "채팅방 ID"
                        required = true
                    }
                    queryParameter<Int>("limit") {
                        description = "가져올 메시지 수 (기본값: 30)"
                        required = false
                    }
                    queryParameter<Int>("beforeMessageId") {
                        description = "이 메시지 ID 이전의 메시지들을 가져옴 (커서 기반 페이지네이션)"
                        required = false
                    }
                }
                authResponse()
                response {
                    code(HttpStatusCode.NotFound) {
                        description = "채팅방 없음"
                    }
                    code(HttpStatusCode.Forbidden) {
                        description = "메시지 조회 권한 없음"
                    }
                }
            }) {
                val userId = call.requireUserId() ?: return@get

                val roomId = call.getRequiredIntParam("roomId", Errors.Chat.INVALID_ROOM_ID)
                    ?: return@get

                val limit = call.getIntParam("limit", Constants.Chat.DEFAULT_MESSAGE_LIMIT)
                val beforeMessageId = call.getOptionalIntParam("beforeMessageId")

                val response = messageService.getMessages(roomId, userId, limit, beforeMessageId)
                call.respond(HttpStatusCode.OK, ApiResponse.success(response))
            }

            put("/{roomId}/messages/read", {
                summary = "메시지 읽음 처리"
                tags("Messages")
                request {
                    pathParameter<Int>("roomId") {
                        description = "채팅방 ID"
                        required = true
                    }
                }
                authResponse()
                response {
                    code(HttpStatusCode.NotFound) {
                        description = "채팅방 없음"
                    }
                    code(HttpStatusCode.Forbidden) {
                        description = "읽음 처리 권한 없음"
                    }
                }
            }) {
                val userId = call.requireUserId() ?: return@put

                val roomId = call.getRequiredIntParam("roomId", Errors.Chat.INVALID_ROOM_ID)
                    ?: return@put

                val response = messageService.markMessagesAsRead(roomId, userId)
                call.respond(
                    HttpStatusCode.OK,
                    ApiResponse.success(response, Messages.Chat.ALL_MESSAGES_READ)
                )
            }

            delete("/messages/{messageId}", {
                summary = "메시지 삭제"
                tags("Messages")
                request {
                    pathParameter<Int>("messageId") {
                        description = "메시지 ID"
                        required = true
                    }
                }
                authResponse()
                response {
                    code(HttpStatusCode.NoContent) {
                        description = "메시지 삭제 성공"
                    }
                    code(HttpStatusCode.NotFound) {
                        description = "메시지 없음"
                    }
                    code(HttpStatusCode.Forbidden) {
                        description = "메시지 삭제 권한 없음"
                    }
                }
            }) {
                val userId = call.requireUserId() ?: return@delete

                val messageId = call.getRequiredIntParam("messageId", Errors.Chat.INVALID_MESSAGE_ID)
                    ?: return@delete

                messageService.deleteMessage(messageId, userId)
                call.respond(HttpStatusCode.NoContent)
            }

            get("/{roomId}/messages/search", {
                summary = "메시지 검색"
                tags("Messages")
                request {
                    pathParameter<Int>("roomId") {
                        description = "채팅방 ID"
                        required = true
                    }
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
                }
                authResponse()
                response {
                    code(HttpStatusCode.BadRequest) {
                        description = "검색 키워드 필요"
                    }
                    code(HttpStatusCode.NotFound) {
                        description = "채팅방 없음"
                    }
                    code(HttpStatusCode.Forbidden) {
                        description = "메시지 검색 권한 없음"
                    }
                }
            }) {
                val userId = call.requireUserId() ?: return@get

                val roomId = call.getRequiredIntParam("roomId", Errors.Chat.INVALID_ROOM_ID)
                    ?: return@get

                val query = call.getRequiredStringParam("q", Errors.Search.SEARCH_QUERY_REQUIRED)
                    ?: return@get

                val page = call.getIntParam("page", 1)
                val limit = call.getIntParam("limit", Constants.DEFAULT_PAGE_LIMIT)

                val response = messageService.searchMessages(roomId, userId, query, page, limit)
                call.respond(HttpStatusCode.OK, ApiResponse.success(response))
            }
        }
    }
}
