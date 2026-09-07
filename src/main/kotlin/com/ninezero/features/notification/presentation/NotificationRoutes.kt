package com.ninezero.features.notification.presentation

import com.ninezero.core.common.config.Constants
import com.ninezero.core.common.config.Messages
import com.ninezero.core.common.exception.Errors
import com.ninezero.core.common.util.*
import com.ninezero.features.notification.domain.NotificationService
import com.ninezero.features.notification.presentation.models.request.MarkReadByTargetRequest
import com.ninezero.features.notification.presentation.models.request.UpdateNotificationPreferenceRequest
import com.ninezero.features.notification.defaultPreferenceResponse
import com.ninezero.features.notification.toPreferenceResponse
import io.github.smiley4.ktoropenapi.delete
import io.github.smiley4.ktoropenapi.get
import io.github.smiley4.ktoropenapi.put
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.koin.ktor.ext.inject

fun Route.notificationRoutes() {
    route(Constants.Endpoints.NOTIFICATIONS) {
        val notificationService by inject<NotificationService>()

        authenticate("jwt") {
            get({
                summary = "알림 목록 조회"
                tags("Notifications")
                request {
                    queryParameter<Boolean>("isRead") {
                        description = "읽음 상태 필터 (true: 읽은 알림만, false: 안 읽은 알림만, 미지정: 전체)"
                        required = false
                        example("unread") { value = false }
                        example("read") { value = true }
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
                    code(HttpStatusCode.Unauthorized) {
                        description = "인증 필요"
                    }
                }
            }) {
                val userId = call.requireUserId() ?: return@get

                val isRead = call.getOptionalStringParam("isRead")?.toBoolean()
                val page = call.getIntParam("page", 1)
                val limit = call.getIntParam("limit", Constants.DEFAULT_PAGE_LIMIT)

                val response = notificationService.getMyNotifications(userId, isRead, page, limit)
                call.respond(HttpStatusCode.OK, ApiResponse.success(response))
            }

            get("/unread-count", {
                summary = "안읽은 알림 수 조회"
                tags("Notifications")
                authResponse()
                response {
                    code(HttpStatusCode.Unauthorized) {
                        description = "인증 필요"
                    }
                }
            }) {
                val userId = call.requireUserId() ?: return@get

                val response = notificationService.getUnreadCount(userId)
                call.respond(HttpStatusCode.OK, ApiResponse.success(response))
            }

            put("/{id}/read", {
                summary = "알림 읽음 처리"
                tags("Notifications")
                request {
                    pathParameter<Int>("id") {
                        description = "알림 ID"
                        required = true
                    }
                }
                authResponse()
                response {
                    code(HttpStatusCode.NotFound) {
                        description = "알림 없음"
                    }
                    code(HttpStatusCode.Forbidden) {
                        description = "알림 읽음 처리 권한 없음"
                    }
                    code(HttpStatusCode.Unauthorized) {
                        description = "인증 필요"
                    }
                }
            }) {
                val userId = call.requireUserId() ?: return@put

                val notificationId = call.getRequiredIntParam("id", Errors.Notification.INVALID_NOTIFICATION_ID)
                    ?: return@put

                notificationService.markAsRead(notificationId, userId)
                call.respond(
                    HttpStatusCode.OK,
                    ApiResponse.success(message = Messages.Notification.NOTIFICATION_READ)
                )
            }

            put("/read-by-target", {
                summary = "대상 기준 알림 읽음 처리"
                tags("Notifications")
                description = "알림 id 없는 경로용, type+targetId로 지목"
                request { body<MarkReadByTargetRequest>() }
                authResponse()
                response {
                    code(HttpStatusCode.Unauthorized) {
                        description = "인증 필요"
                    }
                }
            }) {
                val userId = call.requireUserId() ?: return@put
                val request = call.receive<MarkReadByTargetRequest>()

                notificationService.markAsReadByTarget(userId, request.type, request.targetId)
                call.respond(
                    HttpStatusCode.OK,
                    ApiResponse.success(message = Messages.Notification.NOTIFICATION_READ)
                )
            }

            put("/read-all", {
                summary = "전체 알림 읽음 처리"
                tags("Notifications")
                authResponse()
                response {
                    code(HttpStatusCode.Unauthorized) {
                        description = "인증 필요"
                    }
                }
            }) {
                val userId = call.requireUserId() ?: return@put

                notificationService.markAllAsRead(userId)
                call.respond(
                    HttpStatusCode.OK,
                    ApiResponse.success(message = Messages.Notification.ALL_NOTIFICATIONS_READ)
                )
            }

            delete("/{id}", {
                summary = "알림 삭제"
                tags("Notifications")
                request {
                    pathParameter<Int>("id") {
                        description = "알림 ID"
                        required = true
                    }
                }
                authResponse()
                response {
                    code(HttpStatusCode.NotFound) {
                        description = "알림 없음"
                    }
                    code(HttpStatusCode.Forbidden) {
                        description = "알림 삭제 권한 없음"
                    }
                    code(HttpStatusCode.Unauthorized) {
                        description = "인증 필요"
                    }
                }
            }) {
                val userId = call.requireUserId() ?: return@delete

                val notificationId = call.getRequiredIntParam("id", Errors.Notification.INVALID_NOTIFICATION_ID)
                    ?: return@delete

                notificationService.deleteNotification(notificationId, userId)
                call.respond(
                    HttpStatusCode.OK,
                    ApiResponse.success(message = Messages.Notification.NOTIFICATION_DELETED)
                )
            }

            get("/preferences", {
                summary = "알림 설정 조회"
                tags("Notifications")
                authResponse()
            }) {
                val userId = call.requireUserId() ?: return@get

                val response = notificationService.getPreferences(userId)?.toPreferenceResponse()
                    ?: defaultPreferenceResponse()

                call.respond(HttpStatusCode.OK, ApiResponse.success(response))
            }

            put("/preferences", {
                summary = "알림 설정 수정"
                tags("Notifications")
                authResponse()
            }) {
                val userId = call.requireUserId() ?: return@put
                val request = call.receive<UpdateNotificationPreferenceRequest>()

                val updated = notificationService.updatePreferences(
                    userId = userId,
                    socialEnabled = request.socialEnabled,
                    creatorActivityEnabled = request.creatorActivityEnabled,
                    commerceEnabled = request.commerceEnabled,
                    chatEnabled = request.chatEnabled,
                    systemEnabled = request.systemEnabled
                )?.toPreferenceResponse() ?: defaultPreferenceResponse()

                call.respond(HttpStatusCode.OK, ApiResponse.success(updated))
            }
        }
    }
}
