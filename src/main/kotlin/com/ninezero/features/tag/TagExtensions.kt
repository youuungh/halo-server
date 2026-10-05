package com.ninezero.features.tag

import com.ninezero.core.common.config.TagTargetType
import com.ninezero.core.database.entities.tag.TagDao
import com.ninezero.features.tag.presentation.models.response.TagResponse

fun TagDao.toTagResponse(count: Int? = null): TagResponse {
    return TagResponse(
        id = id.value,
        name = name,
        targetType = targetType,
        isSectionEnabled = isSectionEnabled,
        postCount = count.takeIf { targetType == TagTargetType.POST },
        productCount = count.takeIf { targetType == TagTargetType.PRODUCT },
        createdAt = createdAt
    )
}
