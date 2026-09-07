package com.ninezero.features.search.domain

import com.ninezero.core.cache.CacheKeys
import com.ninezero.core.cache.CacheService
import com.ninezero.core.common.config.Constants
import com.ninezero.core.common.util.PaginationInfo
import com.ninezero.core.common.util.ValidationUtils
import com.ninezero.core.common.util.createPagedResponse
import com.ninezero.core.common.util.getJson
import com.ninezero.core.common.util.query
import com.ninezero.core.common.util.setJson
import com.ninezero.core.common.util.validatePaginationParams
import com.ninezero.features.commerce.data.ProductRepository
import com.ninezero.features.search.data.CreatorSearchRepository
import com.ninezero.features.search.presentation.models.response.CreatorSearchListResponse
import com.ninezero.features.search.presentation.models.response.CreatorSearchResponse
import com.ninezero.features.search.toCreatorSearchResponse
import com.ninezero.features.social.data.FollowRepository
import com.ninezero.features.social.data.PostRepository
import com.ninezero.features.user.data.BlockedUserRepository
import kotlin.random.Random
import kotlin.time.Duration.Companion.minutes

class CreatorSearchService(
    private val creatorSearchRepository: CreatorSearchRepository,
    private val searchHistoryService: SearchHistoryService,
    private val followRepository: FollowRepository,
    private val postRepository: PostRepository,
    private val productRepository: ProductRepository,
    private val blockedUserRepository: BlockedUserRepository,
    private val cacheService: CacheService
) {

    suspend fun searchCreators(
        keyword: String,
        currentUserId: Int?,
        page: Int = 1,
        limit: Int = Constants.DEFAULT_PAGE_LIMIT,
        saveHistory: Boolean = true
    ): CreatorSearchListResponse {
        val trimmedKeyword = keyword.trim()
        ValidationUtils.validateSearchKeywordLength(trimmedKeyword)

        val (validPage, validLimit) = validatePaginationParams(page, limit)

        val (searchResponse, totalCount) = query {
            // 차단된 사용자를 쿼리/카운트에서 동일하게 제외
            val blockedUserIds = if (currentUserId != null) {
                blockedUserRepository.findBlockRelatedUserIds(currentUserId)
            } else {
                emptySet()
            }

            val filteredCreators = creatorSearchRepository.findCreatorsByKeyword(
                keyword = trimmedKeyword,
                page = validPage,
                limit = validLimit,
                excludeUserIds = blockedUserIds
            )

            val totalCount = creatorSearchRepository.countCreatorsByKeyword(trimmedKeyword, blockedUserIds).toInt()

            if (filteredCreators.isEmpty()) {
                val pagination = PaginationInfo(validPage, validLimit, totalCount)
                return@query createPagedResponse(emptyList<CreatorSearchResponse>(), pagination) to totalCount
            }

            val creatorIds = filteredCreators.map { it.id.value }

            val postCounts = postRepository.countPostsByUserIds(creatorIds)
            val followerCounts = followRepository.countFollowersByUserIds(creatorIds)
            val followingCounts = followRepository.countFollowingByUserIds(creatorIds)
            val productCounts = productRepository.countProductsByCreatorIds(creatorIds)

            val followStatusMap = if (currentUserId != null) {
                val targetIds = creatorIds.filter { it != currentUserId }
                followRepository.checkMultipleFollowStatus(currentUserId, targetIds)
            } else {
                emptyMap()
            }

            val creatorResponses = filteredCreators.map { creator ->
                val creatorId = creator.id.value

                val postCount = postCounts[creatorId] ?: 0
                val followerCount = followerCounts[creatorId] ?: 0
                val followingCount = followingCounts[creatorId] ?: 0
                val productCount = productCounts[creatorId] ?: 0
                val isFollowing = if (currentUserId != null && currentUserId != creatorId) {
                    followStatusMap[creatorId] ?: false
                } else {
                    false
                }

                creator.toCreatorSearchResponse(
                    bio = creator.profile?.bio,
                    followerCount = followerCount,
                    followingCount = followingCount,
                    postCount = postCount,
                    productCount = productCount,
                    isFollowing = isFollowing
                )
            }

            val pagination = PaginationInfo(validPage, validLimit, totalCount)
            createPagedResponse(creatorResponses, pagination) to totalCount
        }

        // 명시적 검색에서만 기록 저장
        if (saveHistory && currentUserId != null) {
            try {
                searchHistoryService.saveHistory(
                    userId = currentUserId,
                    keyword = trimmedKeyword,
                    searchType = "CREATOR",
                    resultCount = totalCount
                )
            } catch (_: Exception) {
                // 검색 기록 저장 실패는 무시
            }
        }

        return searchResponse
    }

    suspend fun getPopularCreators(
        currentUserId: Int?,
        limit: Int = Constants.Search.POPULAR_LIMIT
    ): CreatorSearchListResponse {
        val validLimit = when {
            limit <= 0 -> Constants.Search.POPULAR_LIMIT
            limit > Constants.MAX_PAGE_LIMIT -> Constants.MAX_PAGE_LIMIT
            else -> limit
        }

        val cacheKey = CacheKeys.popularCreators(validLimit, currentUserId)
        cacheService.getJson<CreatorSearchListResponse>(cacheKey)?.let {
            return it
        }

        val response = query {
            val blockedUserIds = if (currentUserId != null) {
                blockedUserRepository.findBlockRelatedUserIds(currentUserId)
            } else {
                emptySet()
            }

            val poolCreators = creatorSearchRepository.getPopularCreators(
                limit = validLimit * Constants.Search.POPULAR_POOL_MULTIPLIER,
                excludeUserIds = blockedUserIds
            ).filter { it.id.value != currentUserId }  // 본인 제외

            // 이미 팔로우한 크리에이터는 풀에서 제외
            val followingIds = if (currentUserId != null && poolCreators.isNotEmpty()) {
                followRepository.checkMultipleFollowStatus(currentUserId, poolCreators.map { it.id.value })
                    .filterValues { it }
                    .keys
            } else {
                emptySet()
            }

            // 시간 버킷 시드 셔플
            val rotationBucket =
                System.currentTimeMillis() / (Constants.Search.POPULAR_ROTATION_WINDOW_MINUTES * 60_000L)
            val shuffleSeed = (currentUserId ?: 0) * 31L + rotationBucket
            val filteredCreators = poolCreators
                .filter { it.id.value !in followingIds }
                .shuffled(Random(shuffleSeed))
                .take(validLimit)

            if (filteredCreators.isEmpty()) {
                val pagination = PaginationInfo(1, validLimit, 0)
                return@query createPagedResponse(emptyList<CreatorSearchResponse>(), pagination)
            }

            val creatorIds = filteredCreators.map { it.id.value }

            val postCounts = postRepository.countPostsByUserIds(creatorIds)
            val followerCounts = followRepository.countFollowersByUserIds(creatorIds)
            val followingCounts = followRepository.countFollowingByUserIds(creatorIds)
            val productCounts = productRepository.countProductsByCreatorIds(creatorIds)

            val creatorResponses = filteredCreators.map { creator ->
                val creatorId = creator.id.value

                val postCount = postCounts[creatorId] ?: 0
                val followerCount = followerCounts[creatorId] ?: 0
                val followingCount = followingCounts[creatorId] ?: 0
                val productCount = productCounts[creatorId] ?: 0

                creator.toCreatorSearchResponse(
                    bio = creator.profile?.bio,
                    followerCount = followerCount,
                    followingCount = followingCount,
                    postCount = postCount,
                    productCount = productCount,
                    isFollowing = false
                )
            }

            val pagination = PaginationInfo(
                page = 1,
                limit = validLimit,
                totalCount = creatorResponses.size
            )
            createPagedResponse(creatorResponses, pagination)
        }

        cacheService.setJson(cacheKey, response, ttl = Constants.Search.POPULAR_ROTATION_WINDOW_MINUTES.minutes)

        return response
    }
}