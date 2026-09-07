package com.ninezero.features.chat.presentation

import com.ninezero.core.common.config.Constants
import com.ninezero.core.common.config.Messages
import com.ninezero.core.common.exception.Errors
import com.ninezero.core.common.util.*
import com.ninezero.features.chat.domain.ChatService
import com.ninezero.features.chat.presentation.models.request.CreateChatRoomRequest
import io.github.smiley4.ktoropenapi.get
import io.github.smiley4.ktoropenapi.post
import io.github.smiley4.ktoropenapi.put
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.koin.ktor.ext.inject

fun Route.chatRoutes() {
    val chatService by inject<ChatService>()

    route(Constants.Endpoints.CHATS) {
        authenticate("jwt") {
            post({
                summary = "채팅방 조회 또는 생성"
                tags("Chat")
                request {
                    body<CreateChatRoomRequest> {
                        description = "채팅 상대방 ID"
                        required = true
                        example("default") {
                            value = CreateChatRoomRequest(otherUserId = 2)
                        }
                    }
                }
                authResponse()
                response {
                    code(HttpStatusCode.Created) {
                        description = "채팅방 생성 성공 (기존 채팅방이 있으면 해당 채팅방 반환)"
                    }
                    code(HttpStatusCode.NotFound) {
                        description = "상대 사용자 없음"
                    }
                    code(HttpStatusCode.BadRequest) {
                        description = "자기 자신과 채팅방 불가"
                    }
                    code(HttpStatusCode.Unauthorized) {
                        description = "인증 필요"
                    }
                }
            }) {
                val userId = call.requireUserId() ?: return@post

                val request = call.receive<CreateChatRoomRequest>()
                val response = chatService.getOrCreateChatRoom(userId, request.otherUserId)
                call.respond(HttpStatusCode.Created, ApiResponse.success(response, Messages.Chat.CHAT_CREATED))
            }

            get({
                summary = "내 채팅방 목록 조회"
                tags("Chat")
                request {
                    queryParameter<Int>("page") {
                        description = "페이지 번호 (기본값: 1)"
                        required = false
                    }
                    queryParameter<Int>("limit") {
                        description = "페이지 크기 (기본값: 20)"
                        required = false
                    }
                    queryParameter<Boolean>("includeArchived") {
                        description = "보관된 채팅방 포함 여부 (기본값: false)"
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
                val includeArchived = call.getOptionalStringParam("includeArchived")?.toBoolean() ?: false

                val response = chatService.getMyChatRooms(userId, includeArchived, page, limit)
                call.respond(HttpStatusCode.OK, ApiResponse.success(response))
            }

            get("/{roomId}", {
                summary = "채팅방 상세 조회"
                tags("Chat")
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
                        description = "채팅방 접근 권한 없음"
                    }
                    code(HttpStatusCode.Unauthorized) {
                        description = "인증 필요"
                    }
                }
            }) {
                val userId = call.requireUserId() ?: return@get

                val roomId = call.getRequiredIntParam("roomId", Errors.Chat.INVALID_ROOM_ID)
                    ?: return@get

                val response = chatService.getChatRoomById(roomId, userId)
                call.respond(HttpStatusCode.OK, ApiResponse.success(response))
            }

            get("/with/{userId}", {
                summary = "상대와의 채팅방 조회"
                tags("Chat")
                description = "없어도 생성하지 않음"
                request {
                    pathParameter<Int>("userId") {
                        description = "상대방 사용자 ID"
                        required = true
                    }
                }
                authResponse()
                response {
                    code(HttpStatusCode.OK) {
                        description = "기존 채팅방 조회 성공"
                    }
                    code(HttpStatusCode.NotFound) {
                        description = "채팅방이 존재하지 않거나 상대방을 찾을 수 없음"
                    }
                    code(HttpStatusCode.BadRequest) {
                        description = "자기 자신과는 조회할 수 없음"
                    }
                    code(HttpStatusCode.Unauthorized) {
                        description = "인증 필요"
                    }
                }
            }) {
                val userId = call.requireUserId() ?: return@get

                val otherUserId = call.getRequiredIntParam("userId", Errors.User.INVALID_USER_ID)
                    ?: return@get

                val response = chatService.findChatRoomWithUser(userId, otherUserId)
                if (response == null) {
                    call.respond(
                        HttpStatusCode.NotFound,
                        ApiResponse.error<Unit>(Errors.Chat.CHAT_ROOM_NOT_FOUND)
                    )
                } else {
                    call.respond(HttpStatusCode.OK, ApiResponse.success(response))
                }
            }

            get("/unread", {
                summary = "안읽은 메시지 수 조회"
                tags("Chat")
                authResponse()
                response {
                    code(HttpStatusCode.Unauthorized) {
                        description = "인증 필요"
                    }
                }
            }) {
                val userId = call.requireUserId() ?: return@get

                val response = chatService.getUnreadCount(userId)
                call.respond(HttpStatusCode.OK, ApiResponse.success(response))
            }

            put("/{roomId}/archive", {
                summary = "채팅방 보관/해제"
                tags("Chat")
                request {
                    pathParameter<Int>("roomId") {
                        description = "채팅방 ID"
                        required = true
                    }
                    queryParameter<Boolean>("archived") {
                        description = "보관 여부 (true: 보관, false: 보관 해제, 기본값: true)"
                        required = false
                        example("archive") { value = true }
                        example("unarchive") { value = false }
                    }
                }
                authResponse()
                response {
                    code(HttpStatusCode.NotFound) {
                        description = "채팅방 없음"
                    }
                    code(HttpStatusCode.Forbidden) {
                        description = "채팅방 보관 권한 없음"
                    }
                    code(HttpStatusCode.Unauthorized) {
                        description = "인증 필요"
                    }
                }
            }) {
                val userId = call.requireUserId() ?: return@put

                val roomId = call.getRequiredIntParam("roomId", Errors.Chat.INVALID_ROOM_ID)
                    ?: return@put

                val archived = call.getOptionalStringParam("archived")?.toBoolean() ?: true

                chatService.toggleArchive(roomId, userId, archived)
                val response = if (archived) Messages.Chat.CHAT_ARCHIVED else Messages.Chat.CHAT_UNARCHIVED
                call.respond(HttpStatusCode.OK, ApiResponse.success(message = response))
            }

            put("/{roomId}/clear", {
                summary = "채팅방 삭제"
                tags("Chat")
                description = "본인 기록만 가림, 상대에겐 영향 없음"
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
                        description = "채팅방 접근 권한 없음"
                    }
                    code(HttpStatusCode.Unauthorized) {
                        description = "인증 필요"
                    }
                }
            }) {
                val userId = call.requireUserId() ?: return@put

                val roomId = call.getRequiredIntParam("roomId", Errors.Chat.INVALID_ROOM_ID)
                    ?: return@put

                chatService.clearChatRoom(roomId, userId)
                call.respond(HttpStatusCode.OK, ApiResponse.success(message = Messages.Chat.CHAT_CLEARED))
            }
        }
    }
}
