package com.ninezero.features.coupon

import com.ninezero.features.coupon.data.CouponRepository
import com.ninezero.features.coupon.data.CouponRepositoryImpl
import com.ninezero.features.coupon.data.UserCouponRepository
import com.ninezero.features.coupon.data.UserCouponRepositoryImpl
import com.ninezero.features.coupon.domain.CouponRedemptionService
import com.ninezero.features.coupon.domain.CouponService
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.bind
import org.koin.dsl.module

val couponModule = module {
    // Repositories
    singleOf(::CouponRepositoryImpl) bind CouponRepository::class
    singleOf(::UserCouponRepositoryImpl) bind UserCouponRepository::class

    // Services
    singleOf(::CouponService)
    singleOf(::CouponRedemptionService)
}
