package com.ninezero.core.database.entities.base

import com.ninezero.core.common.util.nowUtc
import kotlinx.datetime.LocalDateTime
import org.jetbrains.exposed.dao.*
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.kotlin.datetime.datetime

abstract class BaseIntIdTable(name: String) : IntIdTable(name) {
    val createdAt: Column<LocalDateTime> = datetime("created_at")
        .clientDefault { nowUtc() }
    val updatedAt: Column<LocalDateTime> = datetime("updated_at")
        .clientDefault { nowUtc() }
}

abstract class BaseIntEntity(id: EntityID<Int>, table: BaseIntIdTable) : IntEntity(id) {
    val createdAt by table.createdAt
    var updatedAt by table.updatedAt
}

/** updatedAt 자동 갱신 엔티티 베이스 */
abstract class BaseIntEntityClass<E : BaseIntEntity>(
    table: BaseIntIdTable
) : IntEntityClass<E>(table) {
    init {
        EntityHook.subscribe { action ->
            if (action.changeType == EntityChangeType.Updated) {
                try {
                    action.toEntity(this)?.updatedAt = nowUtc()
                } catch (_: Exception) {
                    // updatedAt 업데이트 실패는 무시
                }
            }
        }
    }
}
