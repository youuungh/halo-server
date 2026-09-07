package com.ninezero.features.tag

import com.ninezero.core.database.entities.tag.TagDao
import com.ninezero.features.tag.presentation.models.response.TagResponse

fun TagDao.toTagResponse(
    postCount: Int? = null,
    productCount: Int? = null
): TagResponse {
    return TagResponse(
        id = this.id.value,
        name = this.name,
        targetType = this.targetType,
        isSectionEnabled = this.isSectionEnabled,
        postCount = postCount,
        productCount = productCount,
        createdAt = this.createdAt
    )
}
