package com.ninezero.features.banner.presentation

import com.ninezero.core.common.config.BannerType
import com.ninezero.core.common.config.Constants
import com.ninezero.core.common.util.ApiResponse
import com.ninezero.core.common.util.apiResponse
import com.ninezero.core.common.util.getOptionalIntParam
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
            val limit = call.getOptionalIntParam("limit")?.takeIf { it > 0 }

            val response = bannerService.getActiveBanners(limit)
            call.respond(HttpStatusCode.OK, ApiResponse.success(response))
        }
    }
}
