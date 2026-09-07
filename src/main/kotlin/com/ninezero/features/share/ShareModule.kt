package com.ninezero.features.share

import com.ninezero.features.share.domain.ShareService
import org.koin.dsl.module

val shareModule = module {
    single { ShareService(get(), get(), get(), get(), get(), get(), get(), get()) }
}
