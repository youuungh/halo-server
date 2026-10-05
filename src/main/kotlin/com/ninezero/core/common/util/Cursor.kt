package com.ninezero.core.common.util

import com.ninezero.core.common.config.Constants
import com.ninezero.core.common.exception.Errors
import com.ninezero.core.common.exception.InvalidInputException
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greater
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.or
import java.util.Base64

// 커서 페이지네이션

/** 커서 목록 응답 */
@Serializable
data class CursorResponse<T>(
    val items: List<T>,
    val nextCursor: String?,  // 마지막 페이지면 null
    val hasNext: Boolean
)

/** 마지막으로 읽은 행의 정렬값 */
class PageCursor private constructor(private val keys: List<String>) {

    fun encode(): String = ENCODER.encodeToString(keys.joinToString(SEPARATOR).toByteArray())

    fun int(index: Int): Int = keys.getOrNull(index)?.toIntOrNull() ?: invalid()

    fun dateTime(index: Int): LocalDateTime =
        keys.getOrNull(index)?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() } ?: invalid()

    companion object {
        private const val SEPARATOR = "|"
        private val ENCODER = Base64.getUrlEncoder().withoutPadding()

        fun of(vararg keys: Any): PageCursor = PageCursor(keys.map { it.toString() })

        /** 요청 커서, 없으면 첫 페이지 */
        fun decode(raw: String?): PageCursor? {
            if (raw.isNullOrBlank()) return null
            val text = runCatching { String(Base64.getUrlDecoder().decode(raw)) }.getOrNull() ?: invalid()
            return PageCursor(text.split(SEPARATOR))
        }

        private fun invalid(): Nothing = throw InvalidInputException(Errors.Common.INVALID_CURSOR)
    }
}

/** 정렬 키마다 넘어선 조건과 같은 조건으로 고른 커서 다음 행 */
fun afterCursor(vararg keys: Pair<Op<Boolean>, Op<Boolean>>): Op<Boolean> =
    keys.indices
        .map { i -> keys.take(i).fold(keys[i].first) { acc, key -> key.second and acc } }
        .reduce { acc, op -> acc or op }

/** 시각↓·id↓ 최신순 커서 다음 행 */
fun afterNewest(createdAt: Column<LocalDateTime>, id: Column<EntityID<Int>>, cursor: PageCursor): Op<Boolean> {
    val at = cursor.dateTime(0)
    val lastId = cursor.int(1)
    return afterCursor((createdAt less at) to (createdAt eq at), (id less lastId) to (id eq lastId))
}

/** 시각↑·id↓ 오래된순 커서 다음 행 */
fun afterOldest(createdAt: Column<LocalDateTime>, id: Column<EntityID<Int>>, cursor: PageCursor): Op<Boolean> {
    val at = cursor.dateTime(0)
    val lastId = cursor.int(1)
    return afterCursor((createdAt greater at) to (createdAt eq at), (id less lastId) to (id eq lastId))
}

/** limit보다 한 행 더 읽은 결과로 한 페이지와 다음 커서 */
fun <R> List<R>.toCursorPage(limit: Int, cursorOf: (R) -> PageCursor): Pair<List<R>, String?> {
    if (size <= limit) return this to null
    val page = take(limit)
    return page to cursorOf(page.last()).encode()
}

fun validateCursorLimit(limit: Int): Int = when {
    limit <= 0 -> Constants.DEFAULT_PAGE_LIMIT
    limit > Constants.MAX_PAGE_LIMIT -> Constants.MAX_PAGE_LIMIT
    else -> limit
}
