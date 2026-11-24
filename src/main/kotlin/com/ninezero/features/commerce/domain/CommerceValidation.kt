package com.ninezero.features.commerce.domain

import com.ninezero.core.common.config.Constants
import com.ninezero.core.common.exception.Errors
import com.ninezero.core.common.exception.InsufficientStockException
import com.ninezero.core.common.exception.InvalidInputException
import com.ninezero.core.common.exception.InvalidPriceException
import com.ninezero.core.common.exception.InvalidProductNameException
import com.ninezero.core.common.exception.InvalidStockException
import com.ninezero.core.common.util.CommonValidation
import java.math.BigDecimal

/** 상품·장바구니·배송·리뷰·결제 입력 검증 */
object CommerceValidation {
    private val TRACKING_NUMBER_REGEX = Regex("^[0-9]+$")

    fun validateProductName(name: String) {
        when {
            name.isBlank() ->
                throw InvalidProductNameException("상품명은 필수입니다.")
            name.length < Constants.Commerce.MIN_PRODUCT_NAME_LENGTH ->
                throw InvalidProductNameException("상품명은 최소 ${Constants.Commerce.MIN_PRODUCT_NAME_LENGTH}자 이상이어야 합니다.")
            name.length > Constants.Commerce.MAX_PRODUCT_NAME_LENGTH ->
                throw InvalidProductNameException("상품명은 ${Constants.Commerce.MAX_PRODUCT_NAME_LENGTH}자를 초과할 수 없습니다.")
        }
    }

    fun validateProductDescription(description: String) {
        when {
            description.isBlank() ->
                throw InvalidInputException("상품 설명은 필수입니다.")
            description.length > Constants.Commerce.MAX_PRODUCT_DESCRIPTION_LENGTH ->
                throw InvalidInputException("상품 설명은 ${Constants.Commerce.MAX_PRODUCT_DESCRIPTION_LENGTH}자를 초과할 수 없습니다.")
        }
    }

    fun validatePositivePrice(price: BigDecimal, fieldName: String = "가격") {
        when {
            price <= BigDecimal.ZERO ->
                throw InvalidPriceException("${fieldName}은 0보다 커야 합니다.")
        }
    }

    fun validateOriginalPrice(originalPrice: BigDecimal?, price: BigDecimal) {
        if (originalPrice == null) return

        when {
            originalPrice < price ->
                throw InvalidPriceException("정가는 판매가보다 작을 수 없습니다.")
        }
    }

    fun validateMaxPrice(price: BigDecimal) {
        when {
            price > Constants.Commerce.MAX_PRODUCT_PRICE ->
                throw InvalidPriceException("가격은 최대 ${Constants.Commerce.MAX_PRODUCT_PRICE}원까지 설정 가능합니다.")
        }
    }

    fun validateStock(stock: Int) {
        when {
            stock < 0 ->
                throw InvalidStockException("재고는 0 이상이어야 합니다.")
        }
    }

    fun validateStockAvailability(stock: Int, requestedQuantity: Int) {
        when {
            stock < requestedQuantity ->
                throw InsufficientStockException("재고가 부족합니다. (현재 재고: ${stock}개)")
        }
    }

    fun validateProductImageCount(count: Int) {
        if (count > Constants.Commerce.MAX_PRODUCT_IMAGES) {
            throw InvalidInputException(Errors.Commerce.Product.PRODUCT_IMAGES_COUNT_EXCEEDED)
        }
    }

    fun validateProductTags(tags: List<String>?) {
        if (tags == null) return

        when {
            tags.size > Constants.Commerce.MAX_PRODUCT_TAGS ->
                throw InvalidInputException("상품 태그는 최대 ${Constants.Commerce.MAX_PRODUCT_TAGS}개까지 등록 가능합니다.")
        }
    }

    fun validateCartQuantity(quantity: Int) {
        when {
            quantity < Constants.Commerce.MIN_CART_QUANTITY ->
                throw InvalidInputException("수량은 최소 ${Constants.Commerce.MIN_CART_QUANTITY}개 이상이어야 합니다.")
            quantity > Constants.Commerce.MAX_CART_QUANTITY ->
                throw InvalidInputException("수량은 최대 ${Constants.Commerce.MAX_CART_QUANTITY}개까지 가능합니다.")
        }
    }

    fun validateShippingAddress(address: String) {
        when {
            address.isBlank() ->
                throw InvalidInputException("배송 주소는 필수입니다.")
            address.length > Constants.Commerce.MAX_SHIPPING_ADDRESS_LENGTH ->
                throw InvalidInputException("배송 주소는 ${Constants.Commerce.MAX_SHIPPING_ADDRESS_LENGTH}자를 초과할 수 없습니다.")
        }
    }

    fun validateShippingName(name: String) {
        when {
            name.isBlank() ->
                throw InvalidInputException("받는 사람 이름은 필수입니다.")
            name.length > Constants.Commerce.MAX_SHIPPING_NAME_LENGTH ->
                throw InvalidInputException("받는 사람 이름은 ${Constants.Commerce.MAX_SHIPPING_NAME_LENGTH}자를 초과할 수 없습니다.")
        }
    }

    fun validateShippingPhone(phone: String) {
        when {
            phone.isBlank() ->
                throw InvalidInputException("연락처는 필수입니다.")
            phone.length > Constants.Commerce.MAX_SHIPPING_PHONE_LENGTH ->
                throw InvalidInputException("연락처는 ${Constants.Commerce.MAX_SHIPPING_PHONE_LENGTH}자를 초과할 수 없습니다.")
            !phone.matches(CommonValidation.PHONE_REGEX) ->
                throw InvalidInputException("올바른 전화번호 형식이 아닙니다. (예: 010-1234-5678)")
        }
    }

    fun validateCarrier(carrier: String) {
        val validCarriers = listOf(
            Constants.Commerce.CARRIER_CJ,
            Constants.Commerce.CARRIER_EPOST,
            Constants.Commerce.CARRIER_HANJIN,
            Constants.Commerce.CARRIER_LOTTE,
            Constants.Commerce.CARRIER_LOGEN
        )

        if (carrier !in validCarriers) {
            throw InvalidInputException(Errors.Commerce.Order.Shipping.INVALID_CARRIER)
        }
    }

    /** 하이픈·공백을 뺀 송장번호 저장 형태 */
    fun normalizeTrackingNumber(trackingNumber: String): String =
        trackingNumber.filterNot { it == '-' || it.isWhitespace() }

    fun validateTrackingNumber(trackingNumber: String) {
        when {
            trackingNumber.length < Constants.Commerce.TRACKING_NUMBER_MIN_LENGTH ->
                throw InvalidInputException("송장번호는 최소 ${Constants.Commerce.TRACKING_NUMBER_MIN_LENGTH}자 이상이어야 합니다.")
            trackingNumber.length > Constants.Commerce.TRACKING_NUMBER_MAX_LENGTH ->
                throw InvalidInputException("송장번호는 최대 ${Constants.Commerce.TRACKING_NUMBER_MAX_LENGTH}자까지 입력 가능합니다.")
            !TRACKING_NUMBER_REGEX.matches(trackingNumber) ->
                throw InvalidInputException("송장번호는 숫자만 입력할 수 있습니다.")
        }
    }

    fun validateReviewContent(content: String) {
        when {
            content.isBlank() ->
                throw InvalidInputException("리뷰 내용은 필수입니다.")
            content.length > Constants.Commerce.MAX_REVIEW_CONTENT_LENGTH ->
                throw InvalidInputException("리뷰 내용은 ${Constants.Commerce.MAX_REVIEW_CONTENT_LENGTH}자를 초과할 수 없습니다.")
        }
    }

    fun validatePaymentAmount(amount: BigDecimal) {
        when {
            amount <= BigDecimal.ZERO ->
                throw InvalidInputException("결제 금액은 0보다 커야 합니다.")
            amount > Constants.Commerce.MAX_PAYMENT_AMOUNT ->
                throw InvalidInputException("결제 금액은 최대 ${"%,d".format(Constants.Commerce.MAX_PAYMENT_AMOUNT.toBigInteger())}원까지 가능합니다.")
        }
    }
}
