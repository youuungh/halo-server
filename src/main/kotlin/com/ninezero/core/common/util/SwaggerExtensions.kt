package com.ninezero.core.common.util

import io.github.smiley4.ktoropenapi.config.RequestConfig
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

/** 페이지 번호·크기 쿼리 파라미터 */
fun RequestConfig.pageParams() {
    queryParameter<Int>("page") {
        description = "페이지 번호 (기본값: 1)"
        required = false
    }
    pageSizeParam()
}

/** 커서·페이지 크기 쿼리 파라미터 */
fun RequestConfig.cursorParams() {
    queryParameter<String>("cursor") {
        description = "다음 페이지 커서 (응답의 nextCursor, 생략 시 처음부터)"
        required = false
    }
    pageSizeParam()
}

private fun RequestConfig.pageSizeParam() {
    queryParameter<Int>("limit") {
        description = "페이지 크기 (기본값: 20)"
        required = false
    }
}
