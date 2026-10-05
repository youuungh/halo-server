package com.ninezero.features.notification.data

import com.ninezero.core.database.entities.notification.NotificationPreferenceDao

interface NotificationPreferenceRepository {

    // 알림 설정 조회
    suspend fun findByUserId(userId: Int): NotificationPreferenceDao?

    suspend fun findByUserIds(userIds: Collection<Int>): List<NotificationPreferenceDao>

    // 알림 설정 수정
    suspend fun update(
        userId: Int,
        socialEnabled: Boolean?,
        creatorActivityEnabled: Boolean?,
        commerceEnabled: Boolean?,
        chatEnabled: Boolean?,
        systemEnabled: Boolean?
    ): NotificationPreferenceDao
}
