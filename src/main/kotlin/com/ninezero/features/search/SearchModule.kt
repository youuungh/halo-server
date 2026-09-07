package com.ninezero.features.search

import com.ninezero.features.search.data.CreatorSearchRepository
import com.ninezero.features.search.data.CreatorSearchRepositoryImpl
import com.ninezero.features.search.data.SearchHistoryRepository
import com.ninezero.features.search.data.SearchHistoryRepositoryImpl
import com.ninezero.features.search.domain.CreatorSearchService
import com.ninezero.features.search.domain.SearchHistoryService
import org.koin.dsl.module

val searchModule = module {
    single<SearchHistoryRepository> { SearchHistoryRepositoryImpl() }
    single<CreatorSearchRepository> { CreatorSearchRepositoryImpl() }

    single { SearchHistoryService(get()) }
    single { CreatorSearchService(get(), get(), get(), get(), get(), get(), get()) }
}
