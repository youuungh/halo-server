package com.ninezero.features.commerce

import com.ninezero.features.commerce.data.*
import com.ninezero.features.commerce.domain.*
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.bind
import org.koin.dsl.module

val commerceModule = module {
    // Repositories
    singleOf(::ProductRepositoryImpl) bind ProductRepository::class
    singleOf(::CartRepositoryImpl) bind CartRepository::class
    singleOf(::OrderRepositoryImpl) bind OrderRepository::class
    singleOf(::WishlistRepositoryImpl) bind WishlistRepository::class
    singleOf(::ReviewRepositoryImpl) bind ReviewRepository::class
    singleOf(::PaymentRepositoryImpl) bind PaymentRepository::class
    singleOf(::PaymentCustomerRepositoryImpl) bind PaymentCustomerRepository::class
    singleOf(::BillingKeyRepositoryImpl) bind BillingKeyRepository::class

    // Services
    singleOf(::ProductService)
    singleOf(::CartService)
    singleOf(::OrderResponseAssembler)
    singleOf(::OrderService)
    singleOf(::OrderShippingService)
    singleOf(::CreatorSalesService)
    singleOf(::WishlistService)
    singleOf(::ReviewService)
    singleOf(::PaymentService)
    singleOf(::BillingService)
}
