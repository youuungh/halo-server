package com.ninezero.features.user.domain

import com.ninezero.core.common.exception.Errors
import com.ninezero.core.common.exception.NotFoundException
import com.ninezero.core.common.util.query
import com.ninezero.features.user.data.AddressRepository
import com.ninezero.features.user.presentation.models.request.CreateAddressRequest
import com.ninezero.features.user.presentation.models.request.UpdateAddressRequest
import com.ninezero.features.user.presentation.models.response.AddressResponse
import com.ninezero.features.user.toAddressResponse

class AddressService(
    private val addressRepository: AddressRepository
) {

    suspend fun findAddresses(userId: Int): List<AddressResponse> = query {
        addressRepository.findAddresses(userId).map { it.toAddressResponse() }
    }

    suspend fun findAddress(id: Int, userId: Int): AddressResponse = query {
        addressRepository.findAddress(id, userId)?.toAddressResponse()  // 소유자 검증
            ?: throw NotFoundException(Errors.Address.ADDRESS_NOT_FOUND)
    }

    suspend fun createAddress(userId: Int, request: CreateAddressRequest): AddressResponse = query {
        if (request.isDefault) {
            addressRepository.clearDefaultAddress(userId)  // 기본 배송지 지정 시 기존 기본 해제
        }
        addressRepository.createAddress(
            userId = userId,
            recipientName = request.recipientName,
            recipientPhone = request.recipientPhone,
            zipCode = request.zipCode,
            address = request.address,
            addressDetail = request.addressDetail,
            memo = request.memo,
            isDefault = request.isDefault
        ).toAddressResponse()
    }

    suspend fun updateAddress(id: Int, userId: Int, request: UpdateAddressRequest): AddressResponse = query {
        if (request.isDefault) {
            addressRepository.clearDefaultAddress(userId)  // 기본 배송지 지정 시 기존 기본 해제
        }
        addressRepository.updateAddress(
            id = id,
            userId = userId,
            recipientName = request.recipientName,
            recipientPhone = request.recipientPhone,
            zipCode = request.zipCode,
            address = request.address,
            addressDetail = request.addressDetail,
            memo = request.memo,
            isDefault = request.isDefault
        )?.toAddressResponse() ?: throw NotFoundException(Errors.Address.ADDRESS_NOT_FOUND)
    }

    suspend fun deleteAddress(id: Int, userId: Int) = query {
        if (!addressRepository.deleteAddress(id, userId)) {  // 소유자 검증
            throw NotFoundException(Errors.Address.ADDRESS_NOT_FOUND)
        }
    }
}
