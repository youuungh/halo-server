package com.ninezero.features.social.data

import com.ninezero.core.database.entities.social.HiddenPostDao

interface HiddenPostRepository {

    // 숨김 생성/삭제
    suspend fun createHiddenPost(userId: Int, postId: Int): HiddenPostDao?
    suspend fun deleteHiddenPost(userId: Int, postId: Int): Boolean

    // 숨김 조회
    suspend fun isHidden(userId: Int, postId: Int): Boolean
    suspend fun findHiddenPostIds(userId: Int): List<Int>
    suspend fun findUserHiddenPosts(userId: Int, page: Int, limit: Int): List<Int>

    // 카운트
    suspend fun countUserHiddenPosts(userId: Int): Int
}
