package com.ninezero.features.tag.domain

import com.ninezero.core.common.config.TagTargetType
import com.ninezero.core.common.config.UserRole
import com.ninezero.core.common.exception.ConflictException
import com.ninezero.core.common.exception.CreatorOnlyException
import com.ninezero.core.common.exception.Errors
import com.ninezero.core.common.exception.TagNotFoundException
import com.ninezero.core.common.util.ValidationUtils
import com.ninezero.core.common.util.query
import com.ninezero.features.tag.data.TagRepository
import com.ninezero.features.tag.presentation.models.response.TagListResponse
import com.ninezero.features.tag.presentation.models.request.TagRequest
import com.ninezero.features.tag.presentation.models.response.TagResponse
import com.ninezero.features.tag.presentation.models.request.UpdateTagRequest
import com.ninezero.features.tag.toTagResponse
import com.ninezero.features.user.data.UserRepository

class TagService(
    private val tagRepository: TagRepository,
    private val userRepository: UserRepository
) {

    suspend fun createTag(creatorId: Int, request: TagRequest): TagResponse {
        ValidationUtils.validateTagName(request.name)

        val tag = query {
            val user = userRepository.findUserById(creatorId)
            if (user?.role != UserRole.CREATOR && user?.role != UserRole.ADMIN) {
                throw CreatorOnlyException(Errors.Social.Tag.ONLY_CREATOR_CAN_MANAGE_TAGS)
            }

            val existing = tagRepository.findTagByName(
                creatorId = creatorId,
                name = request.name,
                targetType = request.targetType
            )
            if (existing != null) {
                throw ConflictException(Errors.Social.Tag.TAG_ALREADY_EXISTS)
            }

            tagRepository.createTag(
                creatorId = creatorId,
                name = request.name,
                targetType = request.targetType,
                isSectionEnabled = request.isSectionEnabled
            )
        }

        val count = 0 // 새로 생성된 태그는 연결된 콘텐츠가 없음

        return tag.toTagResponse(
            postCount = if (tag.targetType == TagTargetType.POST) count else null,
            productCount = if (tag.targetType == TagTargetType.PRODUCT) count else null
        )
    }

    suspend fun getCreatorTags(creatorId: Int, targetType: TagTargetType): TagListResponse {
        val tagResponses = query {
            val tags = tagRepository.findTagsByCreator(creatorId, targetType)
            val tagIds = tags.map { it.id.value }

            val countMap = when (targetType) {
                TagTargetType.POST -> tagRepository.countPostsByTags(tagIds)
                TagTargetType.PRODUCT -> tagRepository.countProductsByTags(tagIds)
            }

            tags.map { tag ->
                val count = countMap[tag.id.value] ?: 0

                tag.toTagResponse(
                    postCount = if (targetType == TagTargetType.POST) count else null,
                    productCount = if (targetType == TagTargetType.PRODUCT) count else null
                )
            }
        }

        return TagListResponse(tags = tagResponses)
    }

    suspend fun updateTag(tagId: Int, creatorId: Int, request: UpdateTagRequest): TagResponse {
        request.name?.let { newName ->
            ValidationUtils.validateTagName(newName)
        }

        val (updatedTag, count) = query {
            if (!tagRepository.isTagOwnedBy(tagId, creatorId)) {
                throw CreatorOnlyException(Errors.Social.Tag.ONLY_CREATOR_CAN_MANAGE_TAGS)
            }

            request.name?.let { newName ->
                val tag = tagRepository.findTagById(tagId)
                    ?: throw TagNotFoundException(Errors.Social.Tag.TAG_NOT_FOUND)

                val existing = tagRepository.findTagByName(creatorId, newName, tag.targetType)
                if (existing != null && existing.id.value != tagId) {  // 이름 변경 시 중복 검사
                    throw ConflictException(Errors.Social.Tag.TAG_ALREADY_EXISTS)
                }
            }

            val updated = tagRepository.updateTag(
                tagId = tagId,
                name = request.name,
                isSectionEnabled = request.isSectionEnabled
            ) ?: throw TagNotFoundException(Errors.Social.Tag.TAG_NOT_FOUND)

            val tagCount = when (updated.targetType) {
                TagTargetType.POST -> tagRepository.countPostsByTag(tagId)
                TagTargetType.PRODUCT -> tagRepository.countProductsByTag(tagId)
            }

            Pair(updated, tagCount)
        }

        return updatedTag.toTagResponse(
            postCount = if (updatedTag.targetType == TagTargetType.POST) count else null,
            productCount = if (updatedTag.targetType == TagTargetType.PRODUCT) count else null
        )
    }

    suspend fun deleteTag(tagId: Int, creatorId: Int) {
        val deleted = query {
            if (!tagRepository.isTagOwnedBy(tagId, creatorId)) {
                throw CreatorOnlyException(Errors.Social.Tag.ONLY_CREATOR_CAN_MANAGE_TAGS)
            }

            tagRepository.deleteTag(tagId)  // 소유자 확인 후 삭제
        }

        if (!deleted) {
            throw TagNotFoundException(Errors.Social.Tag.TAG_NOT_FOUND)
        }
    }
}
