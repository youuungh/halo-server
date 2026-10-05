package com.ninezero.features.notification

import com.ninezero.features.notification.data.NotificationPreferenceRepository
import com.ninezero.features.notification.data.NotificationPreferenceRepositoryImpl
import com.ninezero.features.notification.data.NotificationRepository
import com.ninezero.features.notification.data.NotificationRepositoryImpl
import com.ninezero.features.notification.domain.NotificationService
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.bind
import org.koin.dsl.module

val notificationModule = module {
    // Repositories
    singleOf(::NotificationRepositoryImpl) bind NotificationRepository::class
    singleOf(::NotificationPreferenceRepositoryImpl) bind NotificationPreferenceRepository::class

    // Services
    singleOf(::NotificationService)
}
