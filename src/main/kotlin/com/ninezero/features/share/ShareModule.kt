package com.ninezero.features.share

import com.ninezero.features.share.domain.ShareService
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module

val shareModule = module {
    singleOf(::ShareService)
}
