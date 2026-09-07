package com.ninezero.features.tag.data

import com.ninezero.core.common.config.TagTargetType
import com.ninezero.core.database.entities.commerce.ProductTable
import com.ninezero.core.database.entities.social.PostTable
import com.ninezero.core.database.entities.tag.PostTagTable
import com.ninezero.core.database.entities.tag.ProductTagTable
import com.ninezero.core.database.entities.tag.TagDao
import com.ninezero.core.database.entities.tag.TagTable
import com.ninezero.core.common.util.toOffset
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq

class TagRepositoryImpl : TagRepository {

    /** 크리에이터의 태그 생성 */
    override suspend fun createTag(
        creatorId: Int,
        name: String,
        targetType: TagTargetType,
        isSectionEnabled: Boolean
    ): TagDao {
        return TagDao.new {
            this.creatorId = creatorId
            this.name = name
            this.targetType = targetType
            this.isSectionEnabled = isSectionEnabled
        }
    }

    /** 태그 조회 */
    override suspend fun findTagById(tagId: Int): TagDao? {
        return TagDao.findById(tagId)
    }

    /** 크리에이터의 targetType별 태그 목록 조회 */
    override suspend fun findTagsByCreator(
        creatorId: Int,
        targetType: TagTargetType
    ): List<TagDao> {
        return TagDao.find {
            (TagTable.creatorId eq creatorId) and (TagTable.targetType eq targetType)
        }.orderBy(TagTable.createdAt to SortOrder.DESC, TagTable.id to SortOrder.DESC)  // 최신순
            .toList()
    }

    /** 크리에이터·targetType안 같은 이름 태그 조회 */
    override suspend fun findTagByName(
        creatorId: Int,
        name: String,
        targetType: TagTargetType
    ): TagDao? {
        return TagDao.find {
            (TagTable.creatorId eq creatorId) and
                    (TagTable.name eq name) and
                    (TagTable.targetType eq targetType)
        }.firstOrNull()
    }

    /** 크리에이터의 섹션 노출 태그 목록 조회 */
    override suspend fun findSectionEnabledTags(
        creatorId: Int,
        targetType: TagTargetType
    ): List<TagDao> {
        return TagDao.find {
            (TagTable.creatorId eq creatorId) and
                    (TagTable.targetType eq targetType) and
                    (TagTable.isSectionEnabled eq true)
        }.orderBy(TagTable.createdAt to SortOrder.DESC, TagTable.id to SortOrder.DESC)  // 최신순
            .toList()
    }

    /** 태그 이름과 섹션 노출 여부 수정 */
    override suspend fun updateTag(
        tagId: Int,
        name: String?,
        isSectionEnabled: Boolean?
    ): TagDao? {
        val tag = TagDao.findById(tagId) ?: return null

        name?.let { tag.name = it }  // null은 유지
        isSectionEnabled?.let { tag.isSectionEnabled = it }

        return tag
    }

    /** 태그 삭제 */
    override suspend fun deleteTag(tagId: Int): Boolean {
        val tag = TagDao.findById(tagId) ?: return false
        tag.delete()
        return true
    }

    /** 태그 소유자 확인 */
    override suspend fun isTagOwnedBy(tagId: Int, creatorId: Int): Boolean {
        return TagDao.find {
            (TagTable.id eq tagId) and (TagTable.creatorId eq creatorId)
        }.count() > 0
    }

    /** 태그가 달린 포스트 수 */
    override suspend fun countPostsByTag(tagId: Int): Int {
        return PostTagTable.selectAll().where { PostTagTable.tagId eq tagId }.count().toInt()
    }

    /** 태그가 달린 상품 수 */
    override suspend fun countProductsByTag(tagId: Int): Int {
        return ProductTagTable.selectAll().where { ProductTagTable.tagId eq tagId }.count().toInt()
    }

    /** 태그별 포스트 수 일괄 조회 */
    override suspend fun countPostsByTags(tagIds: List<Int>): Map<Int, Int> {
        if (tagIds.isEmpty()) return emptyMap()

        return PostTagTable
            .select(PostTagTable.tagId, PostTagTable.tagId.count())
            .where { PostTagTable.tagId inList tagIds }
            .groupBy(PostTagTable.tagId)
            .associate { it[PostTagTable.tagId] to it[PostTagTable.tagId.count()].toInt() }
    }

    /** 태그별 상품 수 일괄 조회 */
    override suspend fun countProductsByTags(tagIds: List<Int>): Map<Int, Int> {
        if (tagIds.isEmpty()) return emptyMap()

        return ProductTagTable
            .select(ProductTagTable.tagId, ProductTagTable.tagId.count())
            .where { ProductTagTable.tagId inList tagIds }
            .groupBy(ProductTagTable.tagId)
            .associate { it[ProductTagTable.tagId] to it[ProductTagTable.tagId.count()].toInt() }
    }

    /** 포스트 태그 전체 교체 */
    override suspend fun attachTagsToPost(
        postId: Int,
        tagIds: List<Int>,
        sectionTagId: Int?
    ): Boolean {
        PostTagTable.deleteWhere { PostTagTable.postId eq postId }

        tagIds.forEach { tagId ->
            PostTagTable.insert {
                it[PostTagTable.postId] = postId
                it[PostTagTable.tagId] = tagId
                it[isSectionTag] = (tagId == sectionTagId)  // sectionTagId와 같은 태그만 섹션 태그
            }
        }
        return true
    }

    /** 상품 태그 전체 교체 */
    override suspend fun attachTagsToProduct(
        productId: Int,
        tagIds: List<Int>,
        sectionTagId: Int?
    ): Boolean {
        ProductTagTable.deleteWhere { ProductTagTable.productId eq productId }

        tagIds.forEach { tagId ->
            ProductTagTable.insert {
                it[ProductTagTable.productId] = productId
                it[ProductTagTable.tagId] = tagId
                it[isSectionTag] = (tagId == sectionTagId)  // sectionTagId와 같은 태그만 섹션 태그
            }
        }
        return true
    }

    /** 상품에 연결된 태그 목록 */
    override suspend fun findTagsByProduct(productId: Int): List<TagDao> {
        val tagIds = ProductTagTable.selectAll()
            .where { ProductTagTable.productId eq productId }
            .map { it[ProductTagTable.tagId] }

        if (tagIds.isEmpty()) return emptyList()

        return TagDao.find { TagTable.id inList tagIds }.toList()
    }

    /** 상품별 섹션 태그 일괄 조회 */
    override suspend fun findSectionTagsByProducts(productIds: List<Int>): Map<Int, TagDao> {
        if (productIds.isEmpty()) return emptyMap()

        val productToTagId = ProductTagTable.selectAll()
            .where { (ProductTagTable.productId inList productIds) and (ProductTagTable.isSectionTag eq true) }
            .associate { it[ProductTagTable.productId] to it[ProductTagTable.tagId] }
        if (productToTagId.isEmpty()) return emptyMap()

        val tagsById = TagDao.find { TagTable.id inList productToTagId.values.distinct() }
            .associateBy { it.id.value }

        return productToTagId.mapNotNull { (productId, tagId) ->
            tagsById[tagId]?.let { productId to it }
        }.toMap()
    }

    /** 섹션 태그 기준 포스트 id 조회 */
    override suspend fun findPostIdsByTag(
        tagId: Int,
        page: Int,
        limit: Int
    ): List<Int> {
        return (PostTagTable innerJoin PostTable)
            .select(PostTable.id)
            .where {
                (PostTagTable.tagId eq tagId) and
                        (PostTagTable.isSectionTag eq true) and
                        (PostTable.isActive eq true)
            }
            .orderBy(PostTable.createdAt to SortOrder.DESC, PostTable.id to SortOrder.DESC)  // 최신순
            .limit(limit)
            .offset(page.toOffset(limit))
            .map { it[PostTable.id].value }
    }

    /** 섹션 태그 기준 포스트 수 */
    override suspend fun countPostIdsByTag(tagId: Int): Int {
        return (PostTagTable innerJoin PostTable)
            .selectAll()
            .where {
                (PostTagTable.tagId eq tagId) and
                        (PostTagTable.isSectionTag eq true) and
                        (PostTable.isActive eq true)
            }
            .count()
            .toInt()
    }

    /** 섹션 태그 기준 상품 id 조회 */
    override suspend fun findProductIdsByTag(
        tagId: Int,
        page: Int,
        limit: Int
    ): List<Int> {
        return (ProductTagTable innerJoin ProductTable)
            .select(ProductTable.id)
            .where {
                (ProductTagTable.tagId eq tagId) and
                        (ProductTagTable.isSectionTag eq true) and
                        (ProductTable.isActive eq true)
            }
            .orderBy(ProductTable.createdAt to SortOrder.DESC, ProductTable.id to SortOrder.DESC)  // 최신순
            .limit(limit)
            .offset(page.toOffset(limit))
            .map { it[ProductTable.id].value }
    }

    /** 섹션 태그 기준 상품 수 */
    override suspend fun countProductIdsByTag(tagId: Int): Int {
        return (ProductTagTable innerJoin ProductTable)
            .selectAll()
            .where {
                (ProductTagTable.tagId eq tagId) and
                        (ProductTagTable.isSectionTag eq true) and
                        (ProductTable.isActive eq true)
            }
            .count()
            .toInt()
    }
}
