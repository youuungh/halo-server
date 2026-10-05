package com.ninezero.features.tag

import com.ninezero.features.tag.data.TagRepository
import com.ninezero.features.tag.data.TagRepositoryImpl
import com.ninezero.features.tag.domain.TagService
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.bind
import org.koin.dsl.module

val tagModule = module {
    // Repositories
    singleOf(::TagRepositoryImpl) bind TagRepository::class

    // Services
    singleOf(::TagService)
}
