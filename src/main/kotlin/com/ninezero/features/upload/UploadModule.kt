package com.ninezero.features.upload

import com.ninezero.features.upload.data.MediaDraftRepository
import com.ninezero.features.upload.data.MediaDraftRepositoryImpl
import com.ninezero.features.upload.data.MediaUploadRepository
import com.ninezero.features.upload.data.MediaUploadRepositoryImpl
import com.ninezero.features.upload.domain.MediaDraftService
import com.ninezero.features.upload.domain.MediaUploadService
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.bind
import org.koin.dsl.module

val uploadModule = module {
    // Repositories
    singleOf(::MediaUploadRepositoryImpl) bind MediaUploadRepository::class
    singleOf(::MediaDraftRepositoryImpl) bind MediaDraftRepository::class

    // Services
    singleOf(::MediaUploadService)
    singleOf(::MediaDraftService)
}
