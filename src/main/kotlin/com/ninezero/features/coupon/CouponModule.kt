package com.ninezero.features.coupon

import com.ninezero.features.coupon.data.CouponRepository
import com.ninezero.features.coupon.data.CouponRepositoryImpl
import com.ninezero.features.coupon.data.UserCouponRepository
import com.ninezero.features.coupon.data.UserCouponRepositoryImpl
import com.ninezero.features.coupon.domain.CouponService
import com.ninezero.features.coupon.domain.CouponRedemptionService
import org.koin.dsl.module

val couponModule = module {
    // Repositories
    single<CouponRepository> { CouponRepositoryImpl() }
    single<UserCouponRepository> { UserCouponRepositoryImpl() }

    // Services
    single { CouponService(get(), get()) }
    single { CouponRedemptionService(get(), get(), get(), get()) }
}
