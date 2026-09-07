package com.ninezero.features.user.domain

import com.ninezero.core.cache.CacheKeys
import com.ninezero.core.cache.CacheService
import com.ninezero.core.common.config.Constants
import com.ninezero.core.common.config.UserRole
import com.ninezero.core.common.exception.UserNotFoundException
import com.ninezero.core.common.util.*
import com.ninezero.features.user.data.BlockedUserRepository
import com.ninezero.features.user.data.UserRepository
import com.ninezero.features.user.presentation.models.response.UserListResponse
import com.ninezero.features.user.presentation.models.response.UserStatisticsResponse
import com.ninezero.features.user.presentation.models.response.UserSummaryResponse
import com.ninezero.features.user.toSummaryResponse
import kotlin.time.Duration.Companion.hours

class UserService(
    private val userRepository: UserRepository,
    private val cacheService: CacheService,
    private val blockedUserRepository: BlockedUserRepository
) {

    suspend fun getUserById(userId: Int): UserSummaryResponse {
        val cacheKey = CacheKeys.user(userId)
        cacheService.getJson<UserSummaryResponse>(cacheKey)?.let {  // user 캐시 우선 조회
            return it
        }

        val response = query {
            val user = userRepository.findUserById(userId)
                ?: throw UserNotFoundException(userId)

            user.toSummaryResponse()
        }

        cacheService.setJson(cacheKey, response, ttl = 1.hours)

        return response
    }

    suspend fun searchUsers(
        query: String,
        page: Int = 1,
        limit: Int = Constants.DEFAULT_PAGE_LIMIT,
        currentUserId: Int? = null
    ): UserListResponse {
        ValidationUtils.validateSearchQuery(query)

        val (validPage, validLimit) = validatePaginationParams(page, limit)

        val (userSummaries, totalCount) = query {
            // 차단 ID 선조회
            val blockedUserIds = currentUserId?.let {  // 차단 유저 제외
                blockedUserRepository.findBlockRelatedUserIds(it)
            } ?: emptySet()

            val foundUsers = userRepository.searchUsersByUsername(query.trim(), validPage, validLimit, blockedUserIds)
            val count = userRepository.countSearchResults(query.trim(), blockedUserIds)

            val summaries = foundUsers.map { stats ->
                stats.user.toSummaryResponse(
                    followerCount = stats.followerCount,
                    followingCount = stats.followingCount,
                    postCount = stats.postCount
                )
            }

            Pair(summaries, count)
        }

        val pagination = PaginationInfo(validPage, validLimit, totalCount)
        return createPagedResponse(userSummaries, pagination)
    }

    suspend fun getAllUsers(
        page: Int = 1,
        limit: Int = Constants.DEFAULT_PAGE_LIMIT,
        role: UserRole? = null,
        search: String? = null,
        sortBy: String? = null,
        sortOrder: String? = null
    ): UserListResponse {
        val (validPage, validLimit) = validatePaginationParams(page, limit)

        search?.let { ValidationUtils.validateSearchQuery(it) }

        val (userSummaries, totalCount) = query {
            val foundUsers = userRepository.findAllUsers(
                page = validPage,
                limit = validLimit,
                role = role,
                search = search?.trim(),
                sortBy = sortBy,
                sortOrder = sortOrder
            )
            val count = userRepository.countAllUsers(
                role = role,
                search = search?.trim()
            )

            val summaries = foundUsers.map { stats ->
                stats.user.toSummaryResponse(
                    followerCount = stats.followerCount,
                    followingCount = stats.followingCount,
                    postCount = stats.postCount
                )
            }

            Pair(summaries, count)
        }

        val pagination = PaginationInfo(validPage, validLimit, totalCount)
        return createPagedResponse(userSummaries, pagination)
    }

    suspend fun checkUsernameAvailability(username: String): Map<String, Boolean> {
        val isAvailable = query {
            !userRepository.existsByUsername(username)
        }

        return mapOf("available" to isAvailable)
    }

    suspend fun registerDeviceToken(userId: Int, deviceId: String, fid: String) {
        query {
            userRepository.registerDeviceToken(userId, deviceId, fid)
        }
    }

    suspend fun deleteDeviceToken(deviceId: String) {
        query {
            userRepository.deleteDeviceToken(deviceId)
        }
    }

    suspend fun getUserStatistics(): UserStatisticsResponse {
        val (todayStart, todayEnd, weekStart, monthStart) = statisticsDateBoundaries()

        return query {
            val stats = userRepository.getUserStatistics(
                todayStart = todayStart,
                todayEnd = todayEnd,
                weekStart = weekStart,
                monthStart = monthStart
            )

            UserStatisticsResponse(
                totalUsers = stats.totalUsers,
                totalCreators = stats.totalCreators,
                totalAdmins = stats.totalAdmins,
                newUsersToday = stats.newUsersToday,
                newUsersThisWeek = stats.newUsersThisWeek,
                newUsersThisMonth = stats.newUsersThisMonth,
                activeUsers = stats.activeUsers
            )
        }
    }
}
