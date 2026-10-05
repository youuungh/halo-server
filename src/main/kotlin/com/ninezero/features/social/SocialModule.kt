package com.ninezero.features.social

import com.ninezero.features.social.data.*
import com.ninezero.features.social.domain.*
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.bind
import org.koin.dsl.module

val socialModule = module {
    // Repositories
    singleOf(::PostRepositoryImpl) bind PostRepository::class
    singleOf(::CommentRepositoryImpl) bind CommentRepository::class
    singleOf(::LikeRepositoryImpl) bind LikeRepository::class
    singleOf(::BookmarkRepositoryImpl) bind BookmarkRepository::class
    singleOf(::HiddenPostRepositoryImpl) bind HiddenPostRepository::class
    singleOf(::FollowRepositoryImpl) bind FollowRepository::class
    singleOf(::ReportRepositoryImpl) bind ReportRepository::class

    // Services
    singleOf(::PostService)
    singleOf(::PostMediaService)
    singleOf(::CommentService)
    singleOf(::LikeService)
    singleOf(::BookmarkService)
    singleOf(::HiddenPostService)
    singleOf(::FollowService)
    singleOf(::ReportService)
    singleOf(::FeedService)
    singleOf(::CommunityService)
}
