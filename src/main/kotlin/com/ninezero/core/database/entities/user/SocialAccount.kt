package com.ninezero.core.database.entities.user

import com.ninezero.core.common.config.SocialProvider
import com.ninezero.core.database.entities.base.BaseIntEntity
import com.ninezero.core.database.entities.base.BaseIntEntityClass
import com.ninezero.core.database.entities.base.BaseIntIdTable
import org.jetbrains.exposed.dao.id.EntityID

object SocialAccountTable : BaseIntIdTable("social_accounts") {
    val userId = integer("user_id").references(UserTable.id).index()
    val provider = enumerationByName<SocialProvider>("provider", 20)
    val providerUserId = varchar("provider_user_id", 255)

    init {
        uniqueIndex(provider, providerUserId)
    }
}

class SocialAccountDao(id: EntityID<Int>) : BaseIntEntity(id, SocialAccountTable) {
    companion object : BaseIntEntityClass<SocialAccountDao>(SocialAccountTable)

    var userId by SocialAccountTable.userId
    var provider by SocialAccountTable.provider
    var providerUserId by SocialAccountTable.providerUserId

    var user by UserDao referencedOn SocialAccountTable.userId
}
