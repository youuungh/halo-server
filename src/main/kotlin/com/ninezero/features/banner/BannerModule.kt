package com.ninezero.features.banner

import com.ninezero.features.banner.domain.BannerService
import org.koin.dsl.module

val bannerModule = module {
    single { BannerService() }
}
