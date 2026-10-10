package com.ninezero.features.upload.presentation

import com.ninezero.core.common.config.Constants
import com.ninezero.core.common.config.MediaDraftTargetType
import com.ninezero.core.common.exception.Errors
import com.ninezero.core.common.util.ApiResponse
import com.ninezero.core.common.util.authResponse
import com.ninezero.core.common.util.getOptionalEnumParam
import com.ninezero.core.common.util.getRequiredIntParam
import com.ninezero.core.common.util.receiveFileParts
import com.ninezero.core.common.util.requireUserId
import com.ninezero.core.storage.StorageConfig
import com.ninezero.features.upload.domain.MediaDraftService
import com.ninezero.features.upload.presentation.models.request.VideoDraftRequest
import com.ninezero.features.upload.presentation.models.request.VideoUploadPrepareRequest
import com.ninezero.features.upload.presentation.models.response.MediaDraftResponse
import com.ninezero.features.upload.presentation.models.response.UploadPrepareResponse
import io.github.smiley4.ktoropenapi.get
import io.github.smiley4.ktoropenapi.post
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.plugins.ratelimit.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.koin.ktor.ext.inject

fun Route.mediaDraftRoutes() {
    route(Constants.Endpoints.MEDIA_DRAFTS) {
        val mediaDraftService by inject<MediaDraftService>()

        authenticate("jwt") {
            get("/{id}", {
                summary = "초안 조회"
                tags("Media Drafts")
                description = "영상 초안의 가공 상태 확인용, READY가 되면 게시 가능"
                request {
                    pathParameter<Int>("id") {
                        description = "초안 ID"
                        required = true
                    }
                }
                authResponse()
                response {
                    code(HttpStatusCode.OK) {
                        description = "초안 조회 성공"
                        body<ApiResponse<MediaDraftResponse>>()
                    }
                    code(HttpStatusCode.NotFound) {
                        description = "초안 없음"
                    }
                }
            }) {
                val userId = call.requireUserId() ?: return@get

                val draftId = call.getRequiredIntParam("id", Errors.File.INVALID_MEDIA_DRAFT_ID)
                    ?: return@get

                val response = mediaDraftService.getDraft(userId, draftId)
                call.respond(HttpStatusCode.OK, ApiResponse.success(response))
            }

            rateLimit(RateLimitName("upload")) {
                post("/images", {
                    summary = "이미지 초안 생성"
                    tags("Media Drafts")
                    description = "글·댓글에 붙이기 전에 이미지를 가공해 초안으로 보관, 2시간 안에 게시하지 않으면 삭제"
                    request {
                        queryParameter<String>("targetType") {
                            description = "초안이 붙을 대상: POST (글·커뮤니티), COMMENT (댓글), PRODUCT (상품)"
                            required = true
                        }
                        body<ByteArray> {
                            description = "Multipart form data - 이미지 파일 파트, 순서대로 초안 생성"
                        }
                    }
                    authResponse()
                    response {
                        code(HttpStatusCode.Created) {
                            description = "초안 생성 성공"
                            body<ApiResponse<List<MediaDraftResponse>>>()
                        }
                        code(HttpStatusCode.BadRequest) {
                            description = "파일이 없거나 잘못된 이미지, 개수 초과"
                        }
                    }
                }) {
                    val userId = call.requireUserId() ?: return@post
                    val targetType = call.requireDraftTargetType() ?: return@post

                    val (files, contentTypes) = call.receiveFileParts(StorageConfig.FileSizeLimit.POST)  // 댓글도 같은 한도
                    if (files.isEmpty()) {
                        return@post call.respond(
                            HttpStatusCode.BadRequest,
                            ApiResponse.error<Unit>(Errors.File.NO_IMAGE_FILES)
                        )
                    }

                    val response = mediaDraftService.createImageDrafts(userId, targetType, files, contentTypes)
                    call.respond(HttpStatusCode.Created, ApiResponse.success(response))
                }

                post("/videos/uploads", {
                    summary = "초안 영상 업로드 준비"
                    tags("Media Drafts")
                    description = "서명된 업로드 URL 발급, 앱이 이 URL로 직접 올린 뒤 uploadId로 영상 초안 생성"
                    request {
                        queryParameter<String>("targetType") {
                            description = "초안이 붙을 대상: POST (글·커뮤니티), COMMENT (댓글), PRODUCT (상품)"
                            required = true
                        }
                        body<VideoUploadPrepareRequest> {
                            description = "영상 형식, 크기"
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
                    }
                }) {
                    val userId = call.requireUserId() ?: return@post
                    val targetType = call.requireDraftTargetType() ?: return@post

                    val request = call.receive<VideoUploadPrepareRequest>()
                    val response = mediaDraftService.prepareVideoUpload(userId, targetType, request)
                    call.respond(HttpStatusCode.Created, ApiResponse.success(response))
                }
            }

            post("/videos", {
                summary = "영상 초안 생성"
                tags("Media Drafts")
                description = "직접 올린 영상을 PROCESSING 초안으로 받고 가공은 이어서 진행, 조회로 READY 확인. 2시간 안에 게시하지 않으면 삭제"
                request {
                    queryParameter<String>("targetType") {
                        description = "초안이 붙을 대상: POST (글·커뮤니티), COMMENT (댓글), PRODUCT (상품)"
                        required = true
                    }
                    body<VideoDraftRequest> {
                        description = "업로드 준비에서 받은 uploadId"
                        required = true
                    }
                }
                authResponse()
                response {
                    code(HttpStatusCode.Accepted) {
                        description = "초안 생성 성공, 가공 중"
                        body<ApiResponse<MediaDraftResponse>>()
                    }
                    code(HttpStatusCode.BadRequest) {
                        description = "업로드가 끝나지 않았거나 만료, 지원하지 않는 형식"
                    }
                    code(HttpStatusCode.NotFound) {
                        description = "업로드 정보 없음"
                    }
                    code(HttpStatusCode.Conflict) {
                        description = "이미 사용된 업로드"
                    }
                }
            }) {
                val userId = call.requireUserId() ?: return@post
                val targetType = call.requireDraftTargetType() ?: return@post

                val request = call.receive<VideoDraftRequest>()
                val response = mediaDraftService.createVideoDraft(userId, targetType, request.uploadId)
                call.respond(HttpStatusCode.Accepted, ApiResponse.success(response))
            }
        }
    }
}

/** targetType 쿼리, 없거나 모르는 값이면 400 후 null */
private suspend fun ApplicationCall.requireDraftTargetType(): MediaDraftTargetType? {
    val targetType = getOptionalEnumParam<MediaDraftTargetType>("targetType")
    if (targetType == null) {
        respond(HttpStatusCode.BadRequest, ApiResponse.error<Unit>(Errors.File.INVALID_MEDIA_DRAFT_TARGET_TYPE))
    }
    return targetType
}
