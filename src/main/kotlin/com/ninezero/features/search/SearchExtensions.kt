package com.ninezero.features.search

import com.ninezero.core.database.entities.search.SearchHistoryDao
import com.ninezero.core.database.entities.user.UserDao
import com.ninezero.features.search.presentation.models.response.CreatorSearchResponse
import com.ninezero.features.search.presentation.models.response.SearchHistoryResponse

fun SearchHistoryDao.toSearchHistoryResponse(): SearchHistoryResponse {
    return SearchHistoryResponse(
        id = id.value,
        keyword = keyword,
        searchType = searchType,
        resultCount = resultCount,
        createdAt = createdAt
    )
}

fun UserDao.toCreatorSearchResponse(
    followerCount: Int,
    followingCount: Int,
    postCount: Int,
    productCount: Int,
    isFollowing: Boolean
): CreatorSearchResponse {
    return CreatorSearchResponse(
        id = id.value,
        username = username,
        displayName = profile?.displayName,
        avatarUrl = profile?.avatarUrl,
        avatarThumbUrl = profile?.avatarThumbUrl,
        bio = profile?.bio,
        role = role.name,
        followerCount = followerCount,
        followingCount = followingCount,
        postCount = postCount,
        productCount = productCount,
        isFollowing = isFollowing
    )
}
