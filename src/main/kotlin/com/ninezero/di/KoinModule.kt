package com.ninezero.di

import com.ninezero.core.cache.CacheService
import com.ninezero.core.delivery.DeliveryApiClient
import com.ninezero.core.email.EmailService
import com.ninezero.core.fcm.FcmService
import com.ninezero.core.payment.TossPaymentClient
import com.ninezero.core.storage.FileUploadService
import com.ninezero.core.storage.ImageProcessingService
import com.ninezero.core.storage.VideoProcessingService
import com.ninezero.core.websocket.WebSocketManager
import com.ninezero.features.admin.adminModule
import com.ninezero.features.banner.bannerModule
import com.ninezero.features.share.shareModule
import com.ninezero.features.chat.chatModule
import com.ninezero.features.commerce.commerceModule
import com.ninezero.features.coupon.couponModule
import com.ninezero.features.notification.notificationModule
import com.ninezero.features.point.pointModule
import com.ninezero.features.search.searchModule
import com.ninezero.features.social.socialModule
import com.ninezero.features.subscription.subscriptionModule
import com.ninezero.features.tag.tagModule
import com.ninezero.features.upload.uploadModule
import com.ninezero.features.user.userModule
import com.ninezero.plugins.appMicrometerRegistry
import io.micrometer.core.instrument.MeterRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.core.module.Module
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module

val coreModule = module {


    single<CoroutineScope> { CoroutineScope(SupervisorJob() + Dispatchers.IO) }  // 백그라운드 작업

    single { CacheService() }
    singleOf(::DeliveryApiClient)
    singleOf(::TossPaymentClient)
    singleOf(::EmailService)
    singleOf(::FcmService)
    singleOf(::FileUploadService)
    singleOf(::ImageProcessingService)
    singleOf(::VideoProcessingService)

    singleOf(::WebSocketManager)

    single<MeterRegistry> { appMicrometerRegistry }  // 모니터링 지표
}

val appModules: List<Module> = listOf(
    coreModule,
    userModule,
    socialModule,
    tagModule,
    shareModule,
    commerceModule,
    chatModule,
    notificationModule,
    subscriptionModule,
    searchModule,
    bannerModule,
    pointModule,
    couponModule,
    adminModule,
    uploadModule
)
