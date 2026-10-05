package com.ninezero.features.tag.domain

import com.ninezero.core.common.config.TagTargetType
import com.ninezero.core.common.config.UserRole
import com.ninezero.core.common.exception.ConflictException
import com.ninezero.core.common.exception.CreatorOnlyException
import com.ninezero.core.common.exception.Errors
import com.ninezero.core.common.exception.TagNotFoundException
import com.ninezero.core.common.util.ValidationUtils
import com.ninezero.core.common.util.query
import com.ninezero.core.database.entities.tag.TagDao
import com.ninezero.features.tag.data.TagRepository
import com.ninezero.features.tag.presentation.models.request.TagRequest
import com.ninezero.features.tag.presentation.models.request.UpdateTagRequest
import com.ninezero.features.tag.presentation.models.response.TagListResponse
import com.ninezero.features.tag.presentation.models.response.TagResponse
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
                throw CreatorOnlyException(Errors.Tag.ONLY_CREATOR_CAN_MANAGE_TAGS)
            }

            val existing = tagRepository.findTagByName(
                creatorId = creatorId,
                name = request.name,
                targetType = request.targetType
            )
            if (existing != null) {
                throw ConflictException(Errors.Tag.TAG_ALREADY_EXISTS)
            }

            tagRepository.createTag(
                creatorId = creatorId,
                name = request.name,
                targetType = request.targetType,
                isSectionEnabled = request.isSectionEnabled
            )
        }

        return tag.toTagResponse(count = 0)  // 새로 생성된 태그는 연결된 콘텐츠가 없음
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
                tag.toTagResponse(count = countMap[tag.id.value] ?: 0)
            }
        }

        return TagListResponse(tags = tagResponses)
    }

    suspend fun updateTag(tagId: Int, creatorId: Int, request: UpdateTagRequest): TagResponse {
        request.name?.let { newName ->
            ValidationUtils.validateTagName(newName)
        }

        val (updatedTag, count) = query {
            val tag = verifyTagOwnership(tagId, creatorId)

            request.name?.let { newName ->
                val existing = tagRepository.findTagByName(creatorId, newName, tag.targetType)
                if (existing != null && existing.id.value != tagId) {  // 이름 변경 시 중복 검사
                    throw ConflictException(Errors.Tag.TAG_ALREADY_EXISTS)
                }
            }

            val updated = tagRepository.updateTag(
                tagId = tagId,
                name = request.name,
                isSectionEnabled = request.isSectionEnabled
            ) ?: throw TagNotFoundException(Errors.Tag.TAG_NOT_FOUND)

            val tagCount = when (updated.targetType) {
                TagTargetType.POST -> tagRepository.countPostsByTag(tagId)
                TagTargetType.PRODUCT -> tagRepository.countProductsByTag(tagId)
            }

            updated to tagCount
        }

        return updatedTag.toTagResponse(count = count)
    }

    suspend fun deleteTag(tagId: Int, creatorId: Int) {
        val deleted = query {
            verifyTagOwnership(tagId, creatorId)

            if (tagRepository.countPostsByTag(tagId) > 0 || tagRepository.countProductsByTag(tagId) > 0) {
                throw ConflictException(Errors.Tag.TAG_IN_USE)
            }

            tagRepository.deleteTag(tagId)  // 소유자 확인 후 삭제
        }

        if (!deleted) {
            throw TagNotFoundException(Errors.Tag.TAG_NOT_FOUND)
        }
    }

    private suspend fun verifyTagOwnership(tagId: Int, creatorId: Int): TagDao {
        val tag = tagRepository.findTagById(tagId)
            ?: throw TagNotFoundException(Errors.Tag.TAG_NOT_FOUND)
        if (tag.creatorId != creatorId) {
            throw CreatorOnlyException(Errors.Tag.ONLY_CREATOR_CAN_MANAGE_TAGS)
        }
        return tag
    }
}
