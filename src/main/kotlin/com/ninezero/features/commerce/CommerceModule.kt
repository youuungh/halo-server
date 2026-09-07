package com.ninezero.features.commerce

import com.ninezero.features.commerce.data.*
import com.ninezero.features.commerce.domain.*
import org.koin.dsl.module

val commerceModule = module {
    // Repositories
    single<ProductRepository> { ProductRepositoryImpl() }
    single<CartRepository> { CartRepositoryImpl() }
    single<OrderRepository> { OrderRepositoryImpl() }
    single<WishlistRepository> { WishlistRepositoryImpl() }
    single<ReviewRepository> { ReviewRepositoryImpl() }
    single<PaymentRepository> { PaymentRepositoryImpl() }
    single<PaymentCustomerRepository> { PaymentCustomerRepositoryImpl() }
    single<BillingKeyRepository> { BillingKeyRepositoryImpl() }

    // Services
    single { ProductService(get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get()) }
    single { CartService(get(), get(), get(), get()) }
    single { OrderService(get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get()) }
    single { WishlistService(get(), get(), get(), get(), get()) }
    single { ReviewService(get(), get(), get(), get(), get(), get(), get(), get(), get(), get()) }
    single { PaymentService(get(), get(), get(), get(), get()) }
    single { BillingService(get(), get(), get()) }
}
