package com.ninezero.features.user.data

import com.ninezero.core.database.entities.user.UserAddressDao
import com.ninezero.core.database.entities.user.UserAddressTable
import com.ninezero.core.database.entities.user.UserTable
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.update

class AddressRepositoryImpl : AddressRepository {

    /** 배송지 전체 목록 조회 */
    override suspend fun findAddresses(userId: Int): List<UserAddressDao> {
        return UserAddressDao.find { UserAddressTable.userId eq userId }.toList()
    }

    /** 배송지 조회 */
    override suspend fun findAddress(id: Int, userId: Int): UserAddressDao? {
        return UserAddressDao.find {
            (UserAddressTable.id eq id) and (UserAddressTable.userId eq userId)
        }.firstOrNull()
    }

    /** 배송지 생성 */
    override suspend fun createAddress(
        userId: Int,
        recipientName: String,
        recipientPhone: String,
        zipCode: String,
        address: String,
        addressDetail: String?,
        memo: String?,
        isDefault: Boolean
    ): UserAddressDao {
        return UserAddressDao.new {
            this.userId = EntityID(userId, UserTable)
            this.recipientName = recipientName
            this.recipientPhone = recipientPhone
            this.zipCode = zipCode
            this.address = address
            this.addressDetail = addressDetail
            this.memo = memo
            this.isDefault = isDefault  // 기존 기본 배송지는 해제 안 함
        }
    }

    /** 배송지 전체 수정 */
    override suspend fun updateAddress(
        id: Int,
        userId: Int,
        recipientName: String,
        recipientPhone: String,
        zipCode: String,
        address: String,
        addressDetail: String?,
        memo: String?,
        isDefault: Boolean
    ): UserAddressDao? {
        val entity = UserAddressDao.find {
            (UserAddressTable.id eq id) and (UserAddressTable.userId eq userId)
        }.firstOrNull() ?: return null

        entity.recipientName = recipientName
        entity.recipientPhone = recipientPhone
        entity.zipCode = zipCode
        entity.address = address
        entity.addressDetail = addressDetail
        entity.memo = memo
        entity.isDefault = isDefault

        return entity
    }

    /** 기본 배송지 전부 해제 */
    override suspend fun clearDefaultAddress(userId: Int) {
        UserAddressTable.update({ (UserAddressTable.userId eq userId) and (UserAddressTable.isDefault eq true) }) {
            it[isDefault] = false
        }
    }

    /** 배송지 삭제 */
    override suspend fun deleteAddress(id: Int, userId: Int): Boolean {
        val entity = UserAddressDao.find {
            (UserAddressTable.id eq id) and (UserAddressTable.userId eq userId)
        }.firstOrNull() ?: return false

        entity.delete()
        return true
    }

    /** 탈퇴 정리용 배송지 삭제 */
    override suspend fun deleteAllByUser(userId: Int): Int {
        return UserAddressTable.deleteWhere { this.userId eq userId }
    }
}
