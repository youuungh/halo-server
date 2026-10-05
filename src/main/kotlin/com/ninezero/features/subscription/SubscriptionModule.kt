package com.ninezero.features.subscription

import com.ninezero.features.subscription.data.SubscriptionPlanRepository
import com.ninezero.features.subscription.data.SubscriptionPlanRepositoryImpl
import com.ninezero.features.subscription.data.SubscriptionRepository
import com.ninezero.features.subscription.data.SubscriptionRepositoryImpl
import com.ninezero.features.subscription.domain.SubscriptionPaymentService
import com.ninezero.features.subscription.domain.SubscriptionPlanService
import com.ninezero.features.subscription.domain.SubscriptionService
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.bind
import org.koin.dsl.module

val subscriptionModule = module {
    // Repositories
    singleOf(::SubscriptionPlanRepositoryImpl) bind SubscriptionPlanRepository::class
    singleOf(::SubscriptionRepositoryImpl) bind SubscriptionRepository::class

    // Services
    singleOf(::SubscriptionPlanService)
    singleOf(::SubscriptionService)
    singleOf(::SubscriptionPaymentService)
}
