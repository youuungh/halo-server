package com.ninezero.features.user.data

import com.ninezero.core.database.entities.user.UserAddressDao

interface AddressRepository {

    // 배송지 조회
    suspend fun findAddresses(userId: Int): List<UserAddressDao>
    suspend fun findAddress(id: Int, userId: Int): UserAddressDao?

    // 배송지 생성/수정
    suspend fun createAddress(
        userId: Int,
        recipientName: String,
        recipientPhone: String,
        zipCode: String,
        address: String,
        addressDetail: String?,
        memo: String?,
        isDefault: Boolean
    ): UserAddressDao

    suspend fun updateAddress(
        id: Int,
        userId: Int,
        recipientName: String,
        recipientPhone: String,
        zipCode: String,
        address: String,
        addressDetail: String?,
        memo: String?,
        isDefault: Boolean
    ): UserAddressDao?

    // 기본 배송지 해제
    suspend fun clearDefaultAddress(userId: Int)

    // 배송지 삭제
    suspend fun deleteAddress(id: Int, userId: Int): Boolean

    // 탈퇴 정리
    suspend fun deleteAllByUser(userId: Int): Int
}
