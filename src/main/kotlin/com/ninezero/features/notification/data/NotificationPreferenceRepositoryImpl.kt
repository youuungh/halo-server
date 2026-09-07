package com.ninezero.features.notification.data

import com.ninezero.core.database.entities.notification.NotificationPreferenceDao
import com.ninezero.core.database.entities.notification.NotificationPreferenceTable

class NotificationPreferenceRepositoryImpl : NotificationPreferenceRepository {

    /** 알림 설정 조회 */
    override suspend fun findByUserId(userId: Int): NotificationPreferenceDao? {
        return NotificationPreferenceDao.find {
            NotificationPreferenceTable.userId eq userId
        }.firstOrNull()
    }

    /** 알림 설정 일괄 조회 */
    override suspend fun findByUserIds(userIds: List<Int>): List<NotificationPreferenceDao> {
        if (userIds.isEmpty()) return emptyList()
        return NotificationPreferenceDao.find {
            NotificationPreferenceTable.userId inList userIds
        }.toList()
    }

    /** 알림 설정 기본값 생성 */
    override suspend fun createDefault(userId: Int): NotificationPreferenceDao {
        return NotificationPreferenceDao.new {
            this.userId = userId
        }
    }

    /** 알림 카테고리별 수신 여부 부분 수정 */
    override suspend fun update(
        userId: Int,
        socialEnabled: Boolean?,
        creatorActivityEnabled: Boolean?,
        commerceEnabled: Boolean?,
        chatEnabled: Boolean?,
        systemEnabled: Boolean?
    ): NotificationPreferenceDao? {
        val pref = findByUserId(userId) ?: createDefault(userId)  // 없으면 기본값 생성 후 적용

        socialEnabled?.let { pref.socialEnabled = it }  // null은 유지
        creatorActivityEnabled?.let { pref.creatorActivityEnabled = it }
        commerceEnabled?.let { pref.commerceEnabled = it }
        chatEnabled?.let { pref.chatEnabled = it }
        systemEnabled?.let { pref.systemEnabled = it }

        return pref
    }
}
