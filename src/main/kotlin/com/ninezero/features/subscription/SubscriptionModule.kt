package com.ninezero.features.subscription

import com.ninezero.features.subscription.data.SubscriptionPlanRepository
import com.ninezero.features.subscription.data.SubscriptionPlanRepositoryImpl
import com.ninezero.features.subscription.data.SubscriptionRepository
import com.ninezero.features.subscription.data.SubscriptionRepositoryImpl
import com.ninezero.features.subscription.domain.SubscriptionPaymentService
import com.ninezero.features.subscription.domain.SubscriptionPlanService
import com.ninezero.features.subscription.domain.SubscriptionService
import org.koin.dsl.module

val subscriptionModule = module {
    // Repositories
    single<SubscriptionPlanRepository> { SubscriptionPlanRepositoryImpl() }
    single<SubscriptionRepository> { SubscriptionRepositoryImpl() }

    // Services
    single { SubscriptionPlanService(get(), get(), get(), get()) }
    single { SubscriptionService(get(), get(), get(), get(), get(), get(), get(), get()) }
    single { SubscriptionPaymentService(get(), get(), get(), get(), get()) }
}
