package com.ninezero.features.banner.presentation

import com.ninezero.core.common.util.ApiResponse
import com.ninezero.core.common.config.BannerType
import com.ninezero.core.common.config.Constants
import com.ninezero.core.common.exception.Errors
import com.ninezero.core.common.util.apiResponse
import com.ninezero.core.common.util.getOptionalIntParam
import com.ninezero.core.common.util.getRequiredIntParam
import com.ninezero.features.banner.domain.BannerService
import com.ninezero.features.banner.presentation.models.response.BannerListResponse
import com.ninezero.features.banner.presentation.models.response.BannerResponse
import io.github.smiley4.ktoropenapi.get
import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.koin.ktor.ext.inject

fun Route.bannerRoutes() {
    route(Constants.Endpoints.BANNERS) {
        val bannerService by inject<BannerService>()

        get({
            summary = "배너 목록 조회 (공개)"
            tags("Banners")
            request {
                queryParameter<Int>("limit") {
                    description = "최대 배너 개수 (미지정 시 모든 활성 배너 반환)"
                    required = false
                    example("default") {
                        value = 5
                    }
                }
            }
            apiResponse()
            response {
                code(HttpStatusCode.OK) {
                    description = "배너 목록 조회 성공"
                    body<ApiResponse<BannerListResponse>> {
                        description = "banners: 배너 목록, totalCount: 전체 개수"
                        example("success") {
                            value = ApiResponse.success(
                                BannerListResponse(
                                    banners = listOf(
                                        BannerResponse(
                                            id = 1,
                                            imageUrl = "https://example.com/banner1.jpg",
                                            title = "인기 크리에이터 특집",
                                            description = "이번 주 가장 핫한 크리에이터를 만나보세요",
                                            link = "/creators/popular",
                                            type = BannerType.CREATOR,
                                            order = 1
                                        )
                                    ),
                                    totalCount = 1
                                )
                            )
                        }
                    }
                }
                code(HttpStatusCode.InternalServerError) {
                    description = "배너 데이터 로드 실패"
                }
            }
        }) {
            val limit = call.getOptionalIntParam("limit")

            val activeBanners = bannerService.getActiveBanners(limit)
                ?: return@get call.respond(
                    HttpStatusCode.InternalServerError,
                    ApiResponse.error<Unit>(Errors.Banner.BANNER_LOAD_ERROR)
                )

            call.respond(
                HttpStatusCode.OK,
                ApiResponse.success(
                    BannerListResponse(
                        banners = activeBanners,
                        totalCount = activeBanners.size
                    )
                )
            )
        }

        get("/{id}", {
            summary = "배너 상세 조회 (공개)"
            tags("Banners")
            request {
                pathParameter<Int>("id") {
                    description = "배너 ID"
                    required = true
                    example("default") {
                        value = 1
                    }
                }
            }
            apiResponse()
            response {
                code(HttpStatusCode.OK) {
                    description = "배너 조회 성공"
                    body<ApiResponse<BannerResponse>> {
                        description = "배너 상세 정보"
                        example("success") {
                            value = ApiResponse.success(
                                BannerResponse(
                                    id = 1,
                                    imageUrl = "https://example.com/banner1.jpg",
                                    title = "인기 크리에이터 특집",
                                    description = "이번 주 가장 핫한 크리에이터를 만나보세요",
                                    link = "/creators/popular",
                                    type = BannerType.CREATOR,
                                    order = 1
                                )
                            )
                        }
                    }
                }
                code(HttpStatusCode.NotFound) {
                    description = "배너 없음"
                }
                code(HttpStatusCode.InternalServerError) {
                    description = "배너 데이터 로드 실패"
                }
            }
        }) {
            val bannerId = call.getRequiredIntParam("id", Errors.Banner.BANNER_NOT_FOUND)
                ?: return@get

            val bannerResponse = bannerService.getBannerById(bannerId)

            if (bannerResponse == null) {
                val bannersExist = bannerService.getActiveBanners() != null
                if (!bannersExist) {
                    call.respond(HttpStatusCode.InternalServerError, ApiResponse.error<Unit>(Errors.Banner.BANNER_LOAD_ERROR))
                } else {
                    call.respond(HttpStatusCode.NotFound, ApiResponse.error<Unit>(Errors.Banner.BANNER_NOT_FOUND))
                }
                return@get
            }

            call.respond(
                HttpStatusCode.OK,
                ApiResponse.success(bannerResponse)
            )
        }
    }
}
