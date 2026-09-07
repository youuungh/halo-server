package com.ninezero.features.notification.data

import com.ninezero.core.database.entities.notification.NotificationPreferenceDao

interface NotificationPreferenceRepository {

    // 알림 설정 조회
    suspend fun findByUserId(userId: Int): NotificationPreferenceDao?

    suspend fun findByUserIds(userIds: List<Int>): List<NotificationPreferenceDao>

    // 알림 설정 생성/수정
    suspend fun createDefault(userId: Int): NotificationPreferenceDao
    suspend fun update(
        userId: Int,
        socialEnabled: Boolean?,
        creatorActivityEnabled: Boolean?,
        commerceEnabled: Boolean?,
        chatEnabled: Boolean?,
        systemEnabled: Boolean?
    ): NotificationPreferenceDao?
}
