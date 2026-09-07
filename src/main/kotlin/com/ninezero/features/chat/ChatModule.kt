package com.ninezero.features.chat

import com.ninezero.features.chat.data.ChatRepository
import com.ninezero.features.chat.data.ChatRepositoryImpl
import com.ninezero.features.chat.data.MessageRepository
import com.ninezero.features.chat.data.MessageRepositoryImpl
import com.ninezero.features.chat.domain.ChatService
import com.ninezero.features.chat.domain.MessageService
import org.koin.dsl.module

val chatModule = module {
    // Repositories
    single<ChatRepository> { ChatRepositoryImpl() }
    single<MessageRepository> { MessageRepositoryImpl() }

    // Services
    single { ChatService(get(), get(), get()) }
    single { MessageService(get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get()) }
}
