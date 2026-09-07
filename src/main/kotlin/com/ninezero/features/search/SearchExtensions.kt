package com.ninezero.features.search

import com.ninezero.core.database.entities.search.SearchHistoryDao
import com.ninezero.core.database.entities.user.UserDao
import com.ninezero.features.search.presentation.models.response.SearchHistoryResponse
import com.ninezero.features.search.presentation.models.response.CreatorSearchResponse

fun SearchHistoryDao.toSearchHistoryResponse(): SearchHistoryResponse {
    return SearchHistoryResponse(
        id = this.id.value,
        keyword = this.keyword,
        searchType = this.searchType,
        resultCount = this.resultCount,
        createdAt = this.createdAt
    )
}

fun UserDao.toCreatorSearchResponse(
    bio: String? = null,
    followerCount: Int = 0,
    followingCount: Int = 0,
    postCount: Int = 0,
    productCount: Int = 0,
    isFollowing: Boolean = false
): CreatorSearchResponse {
    return CreatorSearchResponse(
        id = this.id.value,
        username = this.username,
        displayName = this.profile?.displayName,
        avatarUrl = this.profile?.avatarUrl,
        avatarThumbUrl = this.profile?.avatarThumbUrl,
        bio = bio,
        role = this.role.name,
        followerCount = followerCount,
        followingCount = followingCount,
        postCount = postCount,
        productCount = productCount,
        isFollowing = isFollowing
    )
}
