package com.ninezero.features.social.data

import com.ninezero.core.database.entities.social.FollowDao

interface FollowRepository {

    // 팔로우 생성/삭제
    suspend fun createFollow(followerId: Int, followingId: Int): FollowDao?
    suspend fun deleteFollow(followerId: Int, followingId: Int): Boolean

    // 탈퇴 정리
    suspend fun deleteAllInvolvingUser(userId: Int): Int

    // 팔로우 조회
    suspend fun isFollowing(followerId: Int, followingId: Int): Boolean
    suspend fun checkMultipleFollowStatus(followerId: Int, targetUserIds: List<Int>): Map<Int, Boolean>
    suspend fun findFollowers(userId: Int, page: Int, limit: Int): List<FollowDao>
    suspend fun findFollowing(userId: Int, page: Int, limit: Int): List<FollowDao>
    suspend fun findFollowingIds(userId: Int): List<Int>
    suspend fun findFollowingIdsByUserIds(userIds: List<Int>, limitPerUser: Int): Map<Int, List<Int>>

    // 검색 포함 팔로우 조회/카운트
    suspend fun findFollowersWithSearch(userId: Int, search: String, page: Int, limit: Int): List<Int>
    suspend fun findFollowingWithSearch(userId: Int, search: String, page: Int, limit: Int): List<Int>
    suspend fun countFollowersWithSearch(userId: Int, search: String): Int
    suspend fun countFollowingWithSearch(userId: Int, search: String): Int

    // 카운트
    suspend fun countFollowers(userId: Int): Int
    suspend fun countFollowing(userId: Int): Int
    suspend fun countFollowersByUserIds(userIds: List<Int>): Map<Int, Int>
    suspend fun countFollowingByUserIds(userIds: List<Int>): Map<Int, Int>

    // 알림 설정 조회
    suspend fun findPostNotifyFollowers(creatorId: Int): List<Int>
    suspend fun findProductNotifyFollowers(creatorId: Int): List<Int>

    suspend fun findNotifySettings(followerId: Int, followingId: Int): Pair<Boolean, Boolean>?

    suspend fun findFollowedCreators(userId: Int, page: Int, limit: Int): List<FollowDao>
    suspend fun countFollowedCreators(userId: Int): Int

    // 알림 설정 수정
    suspend fun updateNewPostNotification(followerId: Int, followingId: Int, enabled: Boolean): Boolean
    suspend fun updateNewProductNotification(followerId: Int, followingId: Int, enabled: Boolean): Boolean
}
