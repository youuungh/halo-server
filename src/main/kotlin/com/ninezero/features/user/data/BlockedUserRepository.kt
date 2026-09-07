package com.ninezero.features.user.data

import com.ninezero.core.database.entities.user.BlockedUserDao

interface BlockedUserRepository {

    // 차단 생성/삭제
    suspend fun createBlock(userId: Int, blockedUserId: Int): BlockedUserDao?
    suspend fun deleteBlock(userId: Int, blockedUserId: Int): Boolean

    // 탈퇴 정리
    suspend fun deleteAllInvolvingUser(userId: Int): Int

    // 차단 조회
    suspend fun isBlocked(userId: Int, blockedUserId: Int): Boolean
    suspend fun isBlockedEither(userId1: Int, userId2: Int): Boolean

    // 차단 목록 조회
    suspend fun findBlockRelatedUserIds(userId: Int): Set<Int>
    suspend fun findBlockedUserIds(userId: Int, page: Int, limit: Int): List<Int>

    // 카운트
    suspend fun countBlockedUsers(userId: Int): Int
}
