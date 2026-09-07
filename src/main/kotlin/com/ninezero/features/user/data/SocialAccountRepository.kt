package com.ninezero.features.user.data

import com.ninezero.core.common.config.SocialProvider
import com.ninezero.core.database.entities.user.SocialAccountDao

interface SocialAccountRepository {
    // 소셜 연결 조회
    suspend fun findUserIdByProvider(provider: SocialProvider, providerUserId: String): Int?

    // 소셜 연결 생성
    suspend fun create(userId: Int, provider: SocialProvider, providerUserId: String): SocialAccountDao

    // 탈퇴 정리
    suspend fun deleteAllByUserId(userId: Int): Int
}
