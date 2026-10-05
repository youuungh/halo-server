package com.ninezero.features.point

import com.ninezero.features.point.data.PointHistoryRepository
import com.ninezero.features.point.data.PointHistoryRepositoryImpl
import com.ninezero.features.point.data.PointRepository
import com.ninezero.features.point.data.PointRepositoryImpl
import com.ninezero.features.point.domain.PointEarnService
import com.ninezero.features.point.domain.PointService
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.bind
import org.koin.dsl.module

val pointModule = module {
    // Repositories
    singleOf(::PointRepositoryImpl) bind PointRepository::class
    singleOf(::PointHistoryRepositoryImpl) bind PointHistoryRepository::class

    // Services
    singleOf(::PointService)
    singleOf(::PointEarnService)
}
