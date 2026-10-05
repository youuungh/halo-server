package com.ninezero.core.common.util

import io.ktor.http.content.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.utils.io.*
import kotlinx.io.readByteArray

/** multipart 파일 파트 수집 */
suspend fun ApplicationCall.receiveFileParts(
    maxFileBytes: Long,
    defaultContentType: String = "image/jpeg",
    onFormItem: (name: String?, value: String) -> Unit = { _, _ -> }
): Pair<List<ByteArray>, List<String>> {
    val files = mutableListOf<ByteArray>()
    val contentTypes = mutableListOf<String>()

    receiveMultipart().forEachPart { part ->
        when (part) {
            is PartData.FileItem -> {
                contentTypes.add(part.contentType?.toString() ?: defaultContentType)
                files.add(part.readCapped(maxFileBytes))
            }
            is PartData.FormItem -> onFormItem(part.name, part.value)
            else -> Unit
        }
        part.dispose()
    }

    return files to contentTypes  // 두 목록은 인덱스로 대응
}

/** 크기 검증용 한도 + 1바이트까지 읽기 */
private suspend fun PartData.FileItem.readCapped(maxBytes: Long): ByteArray =
    provider().readRemaining(maxBytes + 1).readByteArray()
