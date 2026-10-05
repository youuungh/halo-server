package com.ninezero.features.tag.data

import com.ninezero.core.common.config.TagTargetType
import com.ninezero.core.database.entities.tag.TagDao

interface TagRepository {

    // 태그 생성
    suspend fun createTag(
        creatorId: Int,
        name: String,
        targetType: TagTargetType,
        isSectionEnabled: Boolean = false
    ): TagDao

    // 태그 조회
    suspend fun findTagById(tagId: Int): TagDao?
    suspend fun findTagsByCreator(creatorId: Int, targetType: TagTargetType): List<TagDao>
    suspend fun findTagByName(creatorId: Int, name: String, targetType: TagTargetType): TagDao?
    suspend fun findSectionEnabledTags(creatorId: Int, targetType: TagTargetType): List<TagDao>

    // 태그 수정
    suspend fun updateTag(
        tagId: Int,
        name: String? = null,
        isSectionEnabled: Boolean? = null
    ): TagDao?

    // 태그 삭제
    suspend fun deleteTag(tagId: Int): Boolean

    // 태그 권한 확인
    suspend fun areTagsOwnedBy(tagIds: Collection<Int>, creatorId: Int, targetType: TagTargetType): Boolean

    // 카운트
    suspend fun countPostsByTag(tagId: Int): Int
    suspend fun countProductsByTag(tagId: Int): Int
    suspend fun countPostsByTags(tagIds: Collection<Int>): Map<Int, Int>
    suspend fun countProductsByTags(tagIds: Collection<Int>): Map<Int, Int>

    // 포스트-태그 연결
    suspend fun attachTagsToPost(
        postId: Int,
        tagIds: Collection<Int>,
        sectionTagId: Int? = null
    )

    // 상품-태그 연결/조회
    suspend fun attachTagsToProduct(
        productId: Int,
        tagIds: Collection<Int>,
        sectionTagId: Int? = null
    )

    suspend fun findTagsByProduct(productId: Int): List<TagDao>

    suspend fun findSectionTagsByProducts(productIds: Collection<Int>): Map<Int, TagDao>

    // 섹션별 포스트/상품 조회/카운트
    suspend fun findSectionPostIds(tagId: Int, page: Int, limit: Int): List<Int>
    suspend fun countSectionPosts(tagId: Int): Int
    suspend fun findSectionProductIds(tagId: Int, page: Int, limit: Int): List<Int>
    suspend fun countSectionProducts(tagId: Int): Int
}
