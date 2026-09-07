package com.ninezero.features.point

import com.ninezero.features.point.data.PointHistoryRepository
import com.ninezero.features.point.data.PointHistoryRepositoryImpl
import com.ninezero.features.point.data.PointRepository
import com.ninezero.features.point.data.PointRepositoryImpl
import com.ninezero.features.point.domain.PointEarnService
import com.ninezero.features.point.domain.PointService
import org.koin.dsl.module

val pointModule = module {
    single<PointRepository> { PointRepositoryImpl() }
    single<PointHistoryRepository> { PointHistoryRepositoryImpl() }

    single { PointService(get(), get(), get(), get()) }
    single { PointEarnService(get()) }
}
