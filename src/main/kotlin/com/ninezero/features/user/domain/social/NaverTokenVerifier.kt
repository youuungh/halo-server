package com.ninezero.features.user.domain.social

import com.ninezero.core.common.config.SocialProvider
import com.ninezero.core.common.exception.Errors
import com.ninezero.core.common.exception.InvalidCredentialsException
import com.ninezero.core.common.util.logger
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** 네이버 액세스 토큰 검증 */
class NaverTokenVerifier : SocialTokenVerifier {
    override val provider = SocialProvider.NAVER

    private val logger = logger()

    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 10_000
        }
    }

    override suspend fun verify(token: String): SocialIdentity {
        val result = try {
            val response = client.get("$NAVER_API/v1/nid/me") {
                header(HttpHeaders.Authorization, "Bearer $token")
            }
            if (!response.status.isSuccess()) {
                logger.warn("네이버 API 응답 오류: ${response.status}")
                throw InvalidCredentialsException(Errors.User.INVALID_SOCIAL_TOKEN)
            }
            response.body<NaverMeResponse>()
        } catch (e: InvalidCredentialsException) {
            throw e
        } catch (e: Exception) {
            logger.warn("네이버 API 호출 실패: ${e.message}")
            throw InvalidCredentialsException(Errors.User.INVALID_SOCIAL_TOKEN)
        }

        val profile = result.response
        if (result.resultcode != "00" || profile == null) {
            logger.warn("네이버 프로필 조회 실패: resultcode=${result.resultcode}")
            throw InvalidCredentialsException(Errors.User.INVALID_SOCIAL_TOKEN)
        }

        return SocialIdentity(
            provider = SocialProvider.NAVER,
            providerUserId = profile.id,
            email = profile.email,
            emailVerified = profile.email != null,  // 존재만으로 verified 간주
            name = profile.nickname ?: profile.name,
            picture = profile.profileImage
        )
    }

    companion object {
        private const val NAVER_API = "https://openapi.naver.com"
    }
}

@Serializable
private data class NaverMeResponse(
    val resultcode: String,
    val response: NaverProfile? = null
)

@Serializable
private data class NaverProfile(
    val id: String,
    val email: String? = null,
    val name: String? = null,
    val nickname: String? = null,
    @SerialName("profile_image") val profileImage: String? = null
)
