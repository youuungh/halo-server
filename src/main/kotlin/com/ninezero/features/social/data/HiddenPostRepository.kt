package com.ninezero.features.social.data

import com.ninezero.core.database.entities.social.HiddenPostDao

interface HiddenPostRepository {

    // 숨김 생성
    suspend fun createHiddenPost(userId: Int, postId: Int): HiddenPostDao?

    // 숨김 조회
    suspend fun findHiddenPostIds(userId: Int): List<Int>
}
