package com.ninezero.plugins

import com.ninezero.core.common.config.DotenvConfig
import io.github.smiley4.ktoropenapi.OpenApi
import io.github.smiley4.ktoropenapi.config.AuthScheme
import io.github.smiley4.ktoropenapi.config.AuthType
import io.github.smiley4.ktoropenapi.config.ExampleEncoder
import io.github.smiley4.ktoropenapi.config.SchemaGenerator
import io.github.smiley4.ktoropenapi.config.SchemaOverwriteModule
import io.github.smiley4.ktoropenapi.openApi
import io.github.smiley4.ktorswaggerui.swaggerUI
import io.ktor.serialization.kotlinx.json.DefaultJson
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.swagger.v3.oas.models.media.Schema
import kotlinx.datetime.LocalDateTime

fun Application.configureSwagger() {
    val baseUrl = DotenvConfig.getOrDefault("BASE_URL", "http://localhost:8080")

    install(OpenApi) {
        info {
            title = "Hub API"
            version = "1.0.0"
            description = "Hub API"
        }
        server {
            url = baseUrl
            description = "Dev Server"
        }
        schemas {
            generator = SchemaGenerator.reflection {
                overwrite(
                    SchemaOverwriteModule(
                        identifier = LocalDateTime::class.qualifiedName!!,
                        schema = {
                            Schema<Any>().also {
                                it.types = setOf("string")
                                it.format = "date-time"
                            }
                        }
                    )
                )
            }
        }
        examples {
            encoder(ExampleEncoder.kotlinx(DefaultJson))
        }
        pathFilter = { _, url ->
            val firstSegment = url.firstOrNull()
            val fullPath = url.joinToString("/")

            when {
                firstSegment == null -> false

                firstSegment.contains("static") -> false
                fullPath.contains("/static") -> false
                fullPath.contains("TailcardSelector") -> false
                fullPath.contains("io.ktor.server.http.content.") -> false
                firstSegment.startsWith("ws") -> false

                firstSegment == "swagger" -> false
                firstSegment == "api.json" -> false

                else -> true
            }
        }
        security {
            securityScheme("jwtAuth") {
                type = AuthType.HTTP
                scheme = AuthScheme.BEARER
                bearerFormat = "JWT"
            }

            defaultSecuritySchemeNames("jwtAuth")

            defaultUnauthorizedResponse {
                description = "Unauthorized - JWT token required or invalid"
            }
        }
        routing {
            route("api.json") {
                openApi()
            }

            route("swagger") {
                swaggerUI("/api.json")
            }

            get("/") {
                call.respondRedirect("/swagger/index.html")
            }
        }
    }
}
