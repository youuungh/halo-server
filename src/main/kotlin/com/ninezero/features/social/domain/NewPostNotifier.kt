package com.ninezero.features.social.domain

import com.ninezero.core.common.util.logger
import com.ninezero.core.common.util.query
import com.ninezero.features.notification.domain.NotificationService
import com.ninezero.features.social.data.FollowRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

private val newPostLogger = logger("NewPostNotifier")

/** 팔로워에게 새 글 알림 */
internal fun launchNewPostNotifications(
    coroutineScope: CoroutineScope,
    followRepository: FollowRepository,
    notificationService: NotificationService,
    creatorId: Int,
    creatorUsername: String,
    postId: Int
) {
    coroutineScope.launch {
        try {
            val followerIds = query {
                followRepository.findPostNotifyFollowers(creatorId)
            }

            if (followerIds.isNotEmpty()) {
                notificationService.sendNewPostNotifications(
                    creatorId = creatorId,
                    creatorUsername = creatorUsername,
                    postId = postId,
                    followerIds = followerIds
                )
            }
        } catch (e: Exception) {
            newPostLogger.error("새 포스트 알림 전송 실패: postId=$postId, error=${e.message}", e)
        }
    }
}
