package com.ninezero.core.cache

object CacheKeys {

    // User·Profile

    fun user(userId: Int) = "user:$userId"

    fun profile(userId: Int) = "profile:$userId"

    // Post

    fun post(postId: Int, userId: Int?) = "post:$postId:user:${userId ?: "guest"}"

    fun pinnedPosts(creatorId: Int, userId: Int?) =
        "pinned-posts:creator:$creatorId:user:${userId ?: "guest"}"

    fun creatorProfileSections(creatorId: Int, page: Int, limit: Int, userId: Int?) =
        "creator-profile-sections:$creatorId:page:$page:limit:$limit:user:${userId ?: "guest"}"

    // Community

    fun community(creatorId: Int, page: Int, limit: Int, userId: Int?) =
        "community:creator:$creatorId:page:$page:limit:$limit:user:${userId ?: "guest"}"

    // Feed

    fun exploreFeed(page: Int, limit: Int, userId: Int?) =
        "explore-feed:page:$page:limit:$limit:user:${userId ?: "guest"}"

    fun trendingFeed(page: Int, limit: Int, userId: Int?) =
        "trending-feed:page:$page:limit:$limit:user:${userId ?: "guest"}"

    fun feedStats(userId: Int) = "feed-stats:$userId"

    // Product

    fun product(productId: Int, userId: Int?) = "product:$productId:user:${userId ?: "guest"}"

    fun creatorStore(creatorId: Int, page: Int, limit: Int, userId: Int?) =
        "creator-store:$creatorId:page:$page:limit:$limit:user:${userId ?: "guest"}"

    fun relatedProducts(productId: Int, userId: Int?) =
        "related-products:$productId:user:${userId ?: "guest"}"

    // Review

    fun review(reviewId: Int) = "review:$reviewId"

    fun reviews(productId: Int, page: Int, limit: Int, sortBy: String) =
        "reviews:product:$productId:page:$page:limit:$limit:sort:$sortBy"

    fun reviewSummary(productId: Int) = "review-summary:$productId"

    // Subscription

    fun subscriptionPlan(planId: Int) = "subscription-plan:$planId"

    fun subscriptionPlans(creatorId: Int, page: Int, limit: Int) =
        "subscription-plans:creator:$creatorId:page:$page:limit:$limit"

    // Search

    fun popularCreators(limit: Int, userId: Int?) = "popular-creators:limit:$limit:user:${userId ?: "guest"}"

    // 패턴 기반 캐시 무효화

    object Patterns {
        // 특정 뷰어의 모든 개인화 캐시 무효화
        fun userScoped(userId: Int) = "*:user:$userId"

        // 포스트 패턴
        fun post(postId: Int) = "post:$postId:*"
        const val EXPLORE_FEED = "explore-feed:*"
        const val TRENDING_FEED = "trending-feed:*"
        const val PINNED_POSTS = "pinned-posts:*"
        const val CREATOR_PROFILE_SECTIONS = "creator-profile-sections:*"

        // 커뮤니티 패턴
        fun community(creatorId: Int) = "community:creator:$creatorId:*"

        // 상품 패턴
        fun product(productId: Int) = "product:$productId:*"
        fun creatorStore(userId: Int) = "creator-store:$userId:*"

        // 연관 상품 패턴
        fun relatedProducts(productId: Int) = "related-products:$productId:*"

        // 리뷰 패턴
        fun reviews(productId: Int) = "reviews:product:$productId:*"

        // 구독 패턴
        fun subscriptionPlans(creatorId: Int) = "subscription-plans:creator:$creatorId:*"

        // 검색 패턴
        const val POPULAR_CREATORS = "popular-creators:*"
    }
}
