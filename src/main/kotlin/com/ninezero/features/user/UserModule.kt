package com.ninezero.features.user

import com.ninezero.features.user.data.*
import com.ninezero.features.user.domain.*
import com.ninezero.features.user.domain.social.GoogleTokenVerifier
import com.ninezero.features.user.domain.social.KakaoTokenVerifier
import com.ninezero.features.user.domain.social.NaverTokenVerifier
import org.koin.dsl.module

val userModule = module {
    // Repositories
    single<UserRepository> { UserRepositoryImpl() }
    single<CreatorApplicationRepository> { CreatorApplicationRepositoryImpl() }
    single<UserSessionRepository> { UserSessionRepositoryImpl() }
    single<RefreshTokenRepository> { RefreshTokenRepositoryImpl() }
    single<BlockedUserRepository> { BlockedUserRepositoryImpl() }
    single<AddressRepository> { AddressRepositoryImpl() }
    single<SocialAccountRepository> { SocialAccountRepositoryImpl() }

    // Services
    single {
        AccountCleanupService(
            subscriptionRepository = get(),
            billingKeyRepository = get(),
            productRepository = get(),
            postRepository = get(),
            creatorApplicationRepository = get(),
            addressRepository = get(),
            searchHistoryRepository = get(),
            cartRepository = get(),
            userCouponRepository = get(),
            notificationRepository = get(),
            blockedUserRepository = get(),
            wishlistRepository = get(),
            followRepository = get(),
            pointRepository = get(),
            pointHistoryRepository = get()
        )
    }
    single {
        AuthService(
            get(), get(), get(), get(), get(), get(),
            socialAccountRepository = get(),
            socialTokenVerifiers = listOf(GoogleTokenVerifier(), KakaoTokenVerifier(), NaverTokenVerifier()),
            fileUploadService = get(),
            cacheService = get(),
            accountCleanupService = get(),
            webSocketManager = get()
        )
    }
    single { ProfileService(get(), get(), get(), get(), get(), get(), get(), get(), get()) }
    single { UserService(get(), get(), get()) }
    single {
        CreatorApplicationService(
            applicationRepository = get(),
            userRepository = get(),
            notificationService = get(),
            coroutineScope = get(),
            subscriptionRepository = get(),
            orderRepository = get(),
            subscriptionPlanRepository = get(),
            productRepository = get(),
            postRepository = get(),
            couponRepository = get(),
            cacheService = get()
        )
    }
    single { UserSessionService(get(), get()) }
    single { BlockedUserService(get(), get(), get(), get()) }
    single { AddressService(get()) }
}
