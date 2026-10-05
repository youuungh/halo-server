package com.ninezero.features.user

import com.ninezero.features.user.data.*
import com.ninezero.features.user.domain.*
import com.ninezero.features.user.domain.social.GoogleTokenVerifier
import com.ninezero.features.user.domain.social.KakaoTokenVerifier
import com.ninezero.features.user.domain.social.NaverTokenVerifier
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.bind
import org.koin.dsl.module

val userModule = module {
    // Repositories
    singleOf(::UserRepositoryImpl) bind UserRepository::class
    singleOf(::CreatorApplicationRepositoryImpl) bind CreatorApplicationRepository::class
    singleOf(::UserSessionRepositoryImpl) bind UserSessionRepository::class
    singleOf(::RefreshTokenRepositoryImpl) bind RefreshTokenRepository::class
    singleOf(::BlockedUserRepositoryImpl) bind BlockedUserRepository::class
    singleOf(::AddressRepositoryImpl) bind AddressRepository::class
    singleOf(::SocialAccountRepositoryImpl) bind SocialAccountRepository::class

    // Services
    singleOf(::AccountCleanupService)
    single {
        AuthService(
            get(), get(), get(), get(), get(), get(), get(), get(),
            socialTokenVerifiers = listOf(GoogleTokenVerifier(), KakaoTokenVerifier(), NaverTokenVerifier()),
            fileUploadService = get(),
            cacheService = get(),
            webSocketManager = get()
        )
    }
    singleOf(::ProfileService)
    singleOf(::UserService)
    singleOf(::CreatorApplicationService)
    singleOf(::UserSessionService)
    singleOf(::BlockedUserService)
    singleOf(::AddressService)
}
