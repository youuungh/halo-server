package com.ninezero.core.database.entities.base

import com.ninezero.core.common.util.nowUtc
import kotlinx.datetime.LocalDateTime
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.dao.EntityBatchUpdate
import org.jetbrains.exposed.v1.dao.IntEntity
import org.jetbrains.exposed.v1.dao.IntEntityClass
import org.jetbrains.exposed.v1.datetime.datetime

abstract class BaseIntIdTable(name: String) : IntIdTable(name) {
    val createdAt: Column<LocalDateTime> = datetime("created_at")
        .clientDefault { nowUtc() }
    val updatedAt: Column<LocalDateTime> = datetime("updated_at")
        .clientDefault { nowUtc() }
}

abstract class BaseIntEntity(id: EntityID<Int>, private val table: BaseIntIdTable) : IntEntity(id) {
    val createdAt by table.createdAt
    var updatedAt by table.updatedAt

    // 수정분과 같은 UPDATE에 실어 보냄, 직접 넣은 값은 유지
    override fun flush(batch: EntityBatchUpdate?): Boolean {
        @Suppress("UNCHECKED_CAST")
        val column = table.updatedAt as Column<Any?>
        if (writeValues.isNotEmpty() && column !in writeValues) writeValues[column] = nowUtc()
        return super.flush(batch)
    }
}

abstract class BaseIntEntityClass<E : BaseIntEntity>(
    table: BaseIntIdTable
) : IntEntityClass<E>(table)
