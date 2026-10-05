package com.ninezero.core.database.entities.social

import com.ninezero.core.database.entities.base.BaseIntEntity
import com.ninezero.core.database.entities.base.BaseIntEntityClass
import com.ninezero.core.database.entities.base.BaseIntIdTable
import com.ninezero.core.database.entities.user.UserTable
import org.jetbrains.exposed.dao.id.EntityID

object FollowTable : BaseIntIdTable("follows") {
    val followerId = integer("follower_id").references(UserTable.id)    // 팔로우 하는 사람
    val followingId = integer("following_id").references(UserTable.id)  // 팔로우 받는 사람
    val isActive = bool("is_active").default(true)
    val notifyNewPost = bool("notify_new_post").default(false)        // 새 포스트 알림 받기
    val notifyNewProduct = bool("notify_new_product").default(false)  // 새 상품 알림 받기

    init {
        uniqueIndex(followerId, followingId)
        index(false, followingId, isActive)     // followingId 선두 조회 최적화
        index(false, followingId, createdAt)    // 팔로워 최신순 목록
        index(false, followerId, createdAt)     // 팔로잉 최신순 목록
    }
}

class FollowDao(id: EntityID<Int>) : BaseIntEntity(id, FollowTable) {
    companion object : BaseIntEntityClass<FollowDao>(FollowTable)

    var followerId by FollowTable.followerId
    var followingId by FollowTable.followingId
    var isActive by FollowTable.isActive
    var notifyNewPost by FollowTable.notifyNewPost
    var notifyNewProduct by FollowTable.notifyNewProduct
}
