package com.ninezero.features.tag

import com.ninezero.features.tag.data.TagRepository
import com.ninezero.features.tag.data.TagRepositoryImpl
import com.ninezero.features.tag.domain.TagService
import org.koin.dsl.module

val tagModule = module {
    single<TagRepository> { TagRepositoryImpl() }

    single { TagService(get(), get()) }
}
