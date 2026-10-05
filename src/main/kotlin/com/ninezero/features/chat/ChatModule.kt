package com.ninezero.features.chat

import com.ninezero.features.chat.data.ChatRepository
import com.ninezero.features.chat.data.ChatRepositoryImpl
import com.ninezero.features.chat.data.MessageRepository
import com.ninezero.features.chat.data.MessageRepositoryImpl
import com.ninezero.features.chat.domain.ChatService
import com.ninezero.features.chat.domain.MessageService
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.bind
import org.koin.dsl.module

val chatModule = module {
    // Repositories
    singleOf(::ChatRepositoryImpl) bind ChatRepository::class
    singleOf(::MessageRepositoryImpl) bind MessageRepository::class

    // Services
    singleOf(::ChatService)
    singleOf(::MessageService)
}
