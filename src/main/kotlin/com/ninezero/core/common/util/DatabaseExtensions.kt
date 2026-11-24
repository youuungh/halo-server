package com.ninezero.core.common.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.transactions.inTopLevelSuspendTransaction

suspend fun <T> query(block: suspend () -> T): T {
    return withContext(Dispatchers.IO) { inTopLevelSuspendTransaction { block() } }  // 중첩·currentOrNull 직접 체크 금지
}

/** 대소문자 무시 LIKE */
private class ILikeOp(expr1: Expression<*>, expr2: Expression<*>, private val escapeChar: Char?) :
    ComparisonOp(expr1, expr2, "ILIKE") {
    override fun toQueryBuilder(queryBuilder: QueryBuilder) {
        super.toQueryBuilder(queryBuilder)
        if (escapeChar != null) {
            with(queryBuilder) {
                +" ESCAPE "
                +stringParam(escapeChar.toString())
            }
        }
    }
}

infix fun <T : String?> Expression<T>.ilike(pattern: LikePattern): Op<Boolean> {
    return ILikeOp(this, stringParam(pattern.pattern), pattern.escapeChar)
}

/** 검색어를 글자 그대로 포함하는 LIKE 패턴 */
fun containsLikePattern(text: String): LikePattern =
    LikePattern.ofLiteral(text).let { LikePattern("%${it.pattern}%", it.escapeChar) }  // %·_ 이스케이프
