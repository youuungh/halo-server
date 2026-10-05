package com.ninezero.features.user.domain

import com.ninezero.core.common.config.Constants
import com.ninezero.core.common.config.UserRole
import com.ninezero.core.common.util.*
import com.ninezero.features.user.data.BlockedUserRepository
import com.ninezero.features.user.data.UserRepository
import com.ninezero.features.user.presentation.models.response.UserListResponse
import com.ninezero.features.user.presentation.models.response.UserStatisticsResponse
import com.ninezero.features.user.presentation.models.response.UsernameAvailabilityResponse
import com.ninezero.features.user.toUserSummaryResponse
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days

class UserService(
    private val userRepository: UserRepository,
    private val blockedUserRepository: BlockedUserRepository
) {

    suspend fun searchUsers(
        query: String,
        page: Int = 1,
        limit: Int = Constants.DEFAULT_PAGE_LIMIT,
        currentUserId: Int? = null
    ): UserListResponse {
        ValidationUtils.validateSearchQuery(query)

        val (validPage, validLimit) = validatePaginationParams(page, limit)

        val keyword = query.trim()

        val (userSummaries, totalCount) = query {
            val blockedUserIds = currentUserId?.let {  // 차단 유저 제외
                blockedUserRepository.findBlockRelatedUserIds(it)
            } ?: emptySet()

            val foundUsers = userRepository.searchUsersByUsername(keyword, validPage, validLimit, blockedUserIds)
            val count = userRepository.countSearchResults(keyword, blockedUserIds)

            val summaries = foundUsers.map { stats ->
                stats.user.toUserSummaryResponse(
                    followerCount = stats.followerCount,
                    followingCount = stats.followingCount,
                    postCount = stats.postCount
                )
            }

            summaries to count
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
        val keyword = search?.trim()

        val (userSummaries, totalCount) = query {
            val foundUsers = userRepository.findAllUsers(
                page = validPage,
                limit = validLimit,
                role = role,
                search = keyword,
                sortBy = sortBy,
                sortOrder = sortOrder
            )
            val count = userRepository.countAllUsers(
                role = role,
                search = keyword
            )

            val summaries = foundUsers.map { stats ->
                stats.user.toUserSummaryResponse(
                    followerCount = stats.followerCount,
                    followingCount = stats.followingCount,
                    postCount = stats.postCount
                )
            }

            summaries to count
        }

        val pagination = PaginationInfo(validPage, validLimit, totalCount)
        return createPagedResponse(userSummaries, pagination)
    }

    suspend fun checkUsernameAvailability(username: String): UsernameAvailabilityResponse {
        val normalizedUsername = ValidationUtils.normalizeUsername(username)
        ValidationUtils.validateUsername(normalizedUsername)  // 가입과 같은 형식 검사

        val isAvailable = query {
            !userRepository.existsByUsername(normalizedUsername)
        }

        return UsernameAvailabilityResponse(available = isAvailable)
    }

    suspend fun registerDeviceToken(userId: Int, deviceId: String, fid: String) {
        query {
            userRepository.registerDeviceToken(userId, deviceId, fid)
        }
    }

    suspend fun deleteDeviceToken(userId: Int, deviceId: String) {
        query {
            userRepository.deleteDeviceToken(userId, deviceId)
        }
    }

    suspend fun getUserStatistics(): UserStatisticsResponse {
        val (todayStart, tomorrowStart, weekStart, monthStart) = statisticsDateBoundaries()

        return query {
            val stats = userRepository.getUserStatistics(
                todayStart = todayStart,
                tomorrowStart = tomorrowStart,
                weekStart = weekStart,
                monthStart = monthStart,
                activeSince = (Clock.System.now() - Constants.User.ACTIVE_USER_WINDOW_DAYS.days).toLocalDateTime(TimeZone.UTC)
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
