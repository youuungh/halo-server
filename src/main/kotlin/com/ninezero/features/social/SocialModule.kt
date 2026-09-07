package com.ninezero.features.social

import com.ninezero.features.social.data.*
import com.ninezero.features.social.domain.*
import org.koin.dsl.module

val socialModule = module {
    // Repositories
    single<PostRepository> { PostRepositoryImpl() }
    single<CommentRepository> { CommentRepositoryImpl() }
    single<LikeRepository> { LikeRepositoryImpl() }
    single<BookmarkRepository> { BookmarkRepositoryImpl() }
    single<HiddenPostRepository> { HiddenPostRepositoryImpl() }
    single<FollowRepository> { FollowRepositoryImpl() }
    single<ReportRepository> { ReportRepositoryImpl() }

    // Services
    single { PostService(get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get()) }
    single { CommentService(get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get()) }
    single { LikeService(get(), get(), get(), get(), get(), get(), get(), get(), get(), get()) }
    single { BookmarkService(get(), get(), get(), get(), get(), get(), get()) }
    single { HiddenPostService(get(), get()) }
    single { ReportService(get(), get()) }
    single { FollowService(get(), get(), get(), get(), get(), get()) }
    single { FeedService(get(), get(), get(), get(), get(), get(), get(), get(), get()) }
    single { CommunityService(get(), get(), get(), get(), get(), get(), get(), get(), get(), get()) }
}
