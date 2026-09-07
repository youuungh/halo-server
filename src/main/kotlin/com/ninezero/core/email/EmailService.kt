package com.ninezero.core.email

import com.ninezero.core.common.config.Constants
import com.ninezero.core.common.config.DotenvConfig
import com.ninezero.core.common.util.logger
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
private data class BrevoEmailRequest(
    val sender: BrevoSender,
    val to: List<BrevoRecipient>,
    val subject: String,
    val htmlContent: String
)

@Serializable
private data class BrevoSender(
    val name: String,
    val email: String
)

@Serializable
private data class BrevoRecipient(
    val email: String,
    val name: String? = null
)

class EmailService {
    private val logger = logger()

    private val apiKey = DotenvConfig.getRequired("BREVO_API_KEY")
    private val fromEmail = DotenvConfig.getRequired("FROM_EMAIL")
    private val fromName = DotenvConfig.getOrDefault("FROM_NAME", "Halo")
    private val logoUrl = "${DotenvConfig.getRequired("SUPABASE_URL")}/storage/v1/object/public/assets/halo_logo_mono_black.png"

    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                prettyPrint = true
            })
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 10_000
        }
    }

    init {
        logger.info("EmailService 초기화 완료 (apiKey 설정 여부=${apiKey.isNotBlank()})")
        logger.info("발신 이메일: $fromEmail")
    }

    private fun loadTemplate(templateName: String): String {
        return this::class.java.classLoader
            .getResource("email_templates/$templateName.html")
            ?.readText()
            ?: throw Exception("이메일 템플릿을 찾을 수 없습니다: $templateName")
    }

    private suspend fun sendCodeEmail(
        email: String,
        username: String,
        code: String,
        subject: String,
        expiryMinutes: Int,
        note: String
    ): Result<String> {
        return withContext(Dispatchers.IO) {
            try {
                val htmlContent = loadTemplate("code_email")
                    .replace("{{logoUrl}}", logoUrl)
                    .replace("{{username}}", username)
                    .replace("{{code}}", code)
                    .replace("{{expiryMinutes}}", expiryMinutes.toString())
                    .replace("{{note}}", note)

                val emailRequest = BrevoEmailRequest(
                    sender = BrevoSender(
                        name = fromName,
                        email = fromEmail
                    ),
                    to = listOf(BrevoRecipient(email = email, name = username)),
                    subject = subject,
                    htmlContent = htmlContent
                )

                val response = client.post("https://api.brevo.com/v3/smtp/email") {
                    header("api-key", apiKey)
                    header("accept", "application/json")
                    contentType(ContentType.Application.Json)
                    setBody(emailRequest)
                }

                if (response.status.isSuccess()) {
                    Result.success("이메일이 발송되었습니다.")
                } else {
                    val errorBody = response.bodyAsText()
                    Result.failure(Exception("이메일 발송 실패 (${response.status}): $errorBody"))
                }
            } catch (e: Exception) {
                Result.failure(Exception("이메일 발송 중 오류 발생: ${e.message}", e))
            }
        }
    }

    suspend fun sendVerificationEmail(
        email: String,
        username: String,
        code: String
    ): Result<String> = sendCodeEmail(
        email = email,
        username = username,
        code = code,
        subject = "[Halo] 회원가입 인증 코드",
        expiryMinutes = Constants.User.SIGNUP_CODE_VALIDITY_MINUTES,
        note = "가입을 시도하지 않으셨다면 이 이메일을 무시해주세요."
    )

    suspend fun sendPasswordResetEmail(
        email: String,
        username: String,
        code: String
    ): Result<String> = sendCodeEmail(
        email = email,
        username = username,
        code = code,
        subject = "[Halo] 비밀번호 재설정 인증 코드",
        expiryMinutes = Constants.User.RESET_CODE_VALIDITY_MINUTES,
        note = "본인이 요청하지 않았다면 이 이메일을 무시해주세요."
    )
}
