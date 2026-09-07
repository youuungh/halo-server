package com.ninezero.features.notification

import com.ninezero.features.notification.data.NotificationPreferenceRepository
import com.ninezero.features.notification.data.NotificationPreferenceRepositoryImpl
import com.ninezero.features.notification.data.NotificationRepository
import com.ninezero.features.notification.data.NotificationRepositoryImpl
import com.ninezero.features.notification.domain.NotificationService
import org.koin.dsl.module

val notificationModule = module {
    single<NotificationRepository> { NotificationRepositoryImpl() }
    single<NotificationPreferenceRepository> { NotificationPreferenceRepositoryImpl() }

    single { NotificationService(get(), get(), get(), get(), get(), get(), get()) }
}
