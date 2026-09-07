package com.ninezero.core.common.util

import io.ktor.http.content.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.utils.io.*
import kotlinx.io.readByteArray

/** multipart 파일 파트 수집 */
suspend fun ApplicationCall.receiveFileParts(
    defaultContentType: String = "image/jpeg"
): Pair<List<ByteArray>, List<String>> {
    val files = mutableListOf<ByteArray>()
    val contentTypes = mutableListOf<String>()

    receiveMultipart().forEachPart { part ->
        if (part is PartData.FileItem) {  // FormItem 등 나머지는 무시
            contentTypes.add(part.contentType?.toString() ?: defaultContentType)
            files.add(part.provider().readRemaining().readByteArray())
        }
        part.dispose()
    }

    return files to contentTypes  // 두 목록은 인덱스로 대응
}
