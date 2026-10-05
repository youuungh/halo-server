package com.ninezero.core.common.util

import io.github.smiley4.ktoropenapi.config.RouteConfig
import io.ktor.http.*

fun RouteConfig.apiResponse() {
    response {
        code(HttpStatusCode.OK) {
            description = "요청 성공"
            body<ApiResponse<Any>> {
                description = "성공 응답"
            }
        }

        code(HttpStatusCode.InternalServerError) {
            description = "서버 오류"
            body<ApiResponse<Any>> {
                description = "오류 응답"
            }
        }
    }
}

fun RouteConfig.authResponse() {
    apiResponse()
    response {
        code(HttpStatusCode.Unauthorized) {
            description = "인증 필요 또는 토큰 무효"
        }
    }
}
