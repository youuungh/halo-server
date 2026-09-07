package com.ninezero.features.user.domain.social

import com.ninezero.core.common.config.DotenvConfig
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

/** 카카오 액세스 토큰 검증 */
class KakaoTokenVerifier : SocialTokenVerifier {
    override val provider = SocialProvider.KAKAO

    private val logger = logger()

    // 미사용 환경 대비 lazy
    private val appId by lazy { DotenvConfig.getRequired("KAKAO_APP_ID").toLong() }

    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 10_000
        }
    }

    override suspend fun verify(token: String): SocialIdentity {
        // 토큰 유효성 + 발급 앱 확인
        val tokenInfo = getOrThrow<KakaoTokenInfo>("$KAKAO_API/v1/user/access_token_info", token)
        if (tokenInfo.appId != appId) {
            logger.warn("카카오 토큰 발급 앱 불일치: appId=${tokenInfo.appId}")
            throw InvalidCredentialsException(Errors.User.INVALID_SOCIAL_TOKEN)
        }

        // 프로필 조회
        val me = getOrThrow<KakaoUserMe>("$KAKAO_API/v2/user/me", token)
        val account = me.kakaoAccount

        return SocialIdentity(
            provider = SocialProvider.KAKAO,
            providerUserId = me.id.toString(),
            email = account?.email,
            emailVerified = account?.isEmailValid == true && account.isEmailVerified == true,  // 미동의면 가입 거부
            name = account?.profile?.nickname,
            picture = account?.profile?.profileImageUrl
        )
    }

    private suspend inline fun <reified T> getOrThrow(url: String, accessToken: String): T {
        val response = try {
            client.get(url) {
                header(HttpHeaders.Authorization, "Bearer $accessToken")
            }
        } catch (e: Exception) {
            logger.warn("카카오 API 호출 실패: ${e.message}")
            throw InvalidCredentialsException(Errors.User.INVALID_SOCIAL_TOKEN)
        }

        if (!response.status.isSuccess()) {
            logger.warn("카카오 API 응답 오류: ${response.status} ($url)")
            throw InvalidCredentialsException(Errors.User.INVALID_SOCIAL_TOKEN)
        }

        return response.body()
    }

    companion object {
        private const val KAKAO_API = "https://kapi.kakao.com"
    }
}

@Serializable
private data class KakaoTokenInfo(
    val id: Long? = null,
    @SerialName("app_id") val appId: Long
)

@Serializable
private data class KakaoUserMe(
    val id: Long,
    @SerialName("kakao_account") val kakaoAccount: KakaoAccount? = null
)

@Serializable
private data class KakaoAccount(
    val email: String? = null,
    @SerialName("is_email_valid") val isEmailValid: Boolean? = null,
    @SerialName("is_email_verified") val isEmailVerified: Boolean? = null,
    val profile: KakaoProfile? = null
)

@Serializable
private data class KakaoProfile(
    val nickname: String? = null,
    @SerialName("profile_image_url") val profileImageUrl: String? = null
)
