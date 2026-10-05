package com.ninezero.features.banner

import com.ninezero.features.banner.domain.BannerService
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module

val bannerModule = module {
    singleOf(::BannerService)
}
