package com.ninezero.features.search

import com.ninezero.features.search.data.CreatorSearchRepository
import com.ninezero.features.search.data.CreatorSearchRepositoryImpl
import com.ninezero.features.search.data.SearchHistoryRepository
import com.ninezero.features.search.data.SearchHistoryRepositoryImpl
import com.ninezero.features.search.domain.CreatorSearchService
import com.ninezero.features.search.domain.SearchHistoryService
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.bind
import org.koin.dsl.module

val searchModule = module {
    // Repositories
    singleOf(::SearchHistoryRepositoryImpl) bind SearchHistoryRepository::class
    singleOf(::CreatorSearchRepositoryImpl) bind CreatorSearchRepository::class

    // Services
    singleOf(::SearchHistoryService)
    singleOf(::CreatorSearchService)
}
