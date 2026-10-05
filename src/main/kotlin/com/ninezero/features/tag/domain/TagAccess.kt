package com.ninezero.features.tag.domain

import com.ninezero.core.common.config.TagTargetType
import com.ninezero.core.common.exception.Errors
import com.ninezero.core.common.exception.ForbiddenException
import com.ninezero.features.tag.data.TagRepository

internal suspend fun requireOwnedTags(
    tagRepository: TagRepository,
    ownerId: Int,
    tagIds: List<Int>,
    targetType: TagTargetType
) {
    if (!tagRepository.areTagsOwnedBy(tagIds, ownerId, targetType)) {
        throw ForbiddenException(Errors.Tag.TAG_NOT_OWNED)
    }
}
