package com.ninezero.features.user.data

import com.ninezero.core.common.config.SocialProvider
import com.ninezero.core.database.entities.user.SocialAccountDao
import com.ninezero.core.database.entities.user.SocialAccountTable
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere

class SocialAccountRepositoryImpl : SocialAccountRepository {

    /** 소셜 계정에 연결된 userId 조회 */
    override suspend fun findUserIdByProvider(provider: SocialProvider, providerUserId: String): Int? {
        return SocialAccountDao.find {
            (SocialAccountTable.provider eq provider) and (SocialAccountTable.providerUserId eq providerUserId)
        }.firstOrNull()?.userId
    }

    /** 유저에 소셜 계정 연결 생성 */
    override suspend fun create(userId: Int, provider: SocialProvider, providerUserId: String): SocialAccountDao {
        return SocialAccountDao.new {
            this.userId = userId
            this.provider = provider
            this.providerUserId = providerUserId
        }
    }

    /** 탈퇴 정리용 소셜 연결 삭제 */
    override suspend fun deleteAllByUserId(userId: Int): Int {
        return SocialAccountTable.deleteWhere { SocialAccountTable.userId eq userId }  // 같은 소셜 계정으로 재가입 가능
    }
}
