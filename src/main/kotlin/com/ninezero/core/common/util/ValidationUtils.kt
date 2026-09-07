package com.ninezero.core.common.util

import com.ninezero.core.common.config.Constants
import com.ninezero.core.common.config.CouponType
import com.ninezero.core.common.exception.Errors
import com.ninezero.core.common.exception.InsufficientStockException
import com.ninezero.core.common.exception.InvalidBioException
import com.ninezero.core.common.exception.InvalidCommentContentException
import com.ninezero.core.common.exception.InvalidCouponCodeException
import com.ninezero.core.common.exception.InvalidDisplayNameException
import com.ninezero.core.common.exception.InvalidEmailException
import com.ninezero.core.common.exception.InvalidFileException
import com.ninezero.core.common.exception.InvalidInputException
import com.ninezero.core.common.exception.InvalidPasswordException
import com.ninezero.core.common.exception.InvalidPointAmountException
import com.ninezero.core.common.exception.InvalidPostContentException
import com.ninezero.core.common.exception.InvalidPriceException
import com.ninezero.core.common.exception.InvalidProductNameException
import com.ninezero.core.common.exception.InvalidSearchQueryException
import com.ninezero.core.common.exception.InvalidStockException
import com.ninezero.core.common.exception.InvalidUsernameException
import kotlinx.datetime.LocalDateTime
import java.math.BigDecimal

object ValidationUtils {

    private val EMAIL_REGEX = Regex("^[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(\\.[A-Za-z0-9-]+)*\\.[A-Za-z]{2,}$")

    fun sanitizeHtml(input: String?): String {
        return input?.trim().orEmpty()  // null/blank는 빈 문자열로 정규화
    }

    /** http·https·// URL만 통과 */
    fun sanitizeUrl(url: String?): String? {
        if (url.isNullOrBlank()) return null

        val sanitized = url.trim()

        val safeProtocols = listOf("http://", "https://", "//")
        val isSafe = safeProtocols.any { sanitized.startsWith(it, ignoreCase = true) }

        return if (isSafe) sanitized else null  // javascript: 등 나머지는 null
    }

    // User 관련 검증

    fun validateEmail(email: String) {
        when {
            email.isBlank() ->
                throw InvalidEmailException("이메일은 필수입니다.")
            !EMAIL_REGEX.matches(email) ->
                throw InvalidEmailException("유효한 이메일 형식이 아닙니다.")
            email.length > Constants.User.MAX_EMAIL_LENGTH ->
                throw InvalidEmailException("이메일은 ${Constants.User.MAX_EMAIL_LENGTH}자를 초과할 수 없습니다.")
        }
    }

    fun validatePassword(password: String, fieldName: String = "비밀번호") {
        when {
            password.isBlank() ->
                throw InvalidPasswordException("${fieldName}는 필수입니다.")
            password.length < Constants.User.MIN_PASSWORD_LENGTH ->
                throw InvalidPasswordException("${fieldName}는 최소 ${Constants.User.MIN_PASSWORD_LENGTH}자 이상이어야 합니다.")
            password.length > Constants.User.MAX_PASSWORD_LENGTH ->
                throw InvalidPasswordException("${fieldName}는 ${Constants.User.MAX_PASSWORD_LENGTH}자를 초과할 수 없습니다.")
            password.contains(' ') ->
                throw InvalidPasswordException("${fieldName}에 공백을 포함할 수 없습니다.")
        }

        val hasLetter = password.any { it.isLetter() }
        val hasDigit = password.any { it.isDigit() }
        val hasSpecial = password.any { !it.isLetterOrDigit() && it != ' ' }

        if (listOf(hasLetter, hasDigit, hasSpecial).count { it } < 2) {
            throw InvalidPasswordException("${fieldName}는 영문, 숫자, 특수문자 중 2가지 이상을 조합해야 합니다.")
        }
    }

    fun validateUsername(username: String) {
        when {
            username.isBlank() ->
                throw InvalidUsernameException("사용자명은 필수입니다.")
            username.length < Constants.User.MIN_USERNAME_LENGTH ->
                throw InvalidUsernameException("사용자명은 최소 ${Constants.User.MIN_USERNAME_LENGTH}자 이상이어야 합니다.")
            username.length > Constants.User.MAX_USERNAME_LENGTH ->
                throw InvalidUsernameException("사용자명은 ${Constants.User.MAX_USERNAME_LENGTH}자를 초과할 수 없습니다.")
            !Regex("^[A-Za-z0-9_]+$").matches(username) ->
                throw InvalidUsernameException("사용자명은 영문, 숫자, 밑줄(_)만 사용할 수 있습니다.")
        }
    }

    fun validateDisplayName(displayName: String?) {
        if (displayName == null) return

        when {
            displayName.length > Constants.User.MAX_DISPLAY_NAME_LENGTH ->
                throw InvalidDisplayNameException("표시 이름은 ${Constants.User.MAX_DISPLAY_NAME_LENGTH}자를 초과할 수 없습니다.")
        }
    }

    fun validateBio(bio: String?) {
        if (bio == null) return

        when {
            bio.length > Constants.User.MAX_BIO_LENGTH ->
                throw InvalidBioException("소개는 ${Constants.User.MAX_BIO_LENGTH}자를 초과할 수 없습니다.")
        }
    }

    fun validateLocation(location: String?) {
        if (location == null) return

        when {
            location.length > Constants.User.MAX_LOCATION_LENGTH ->
                throw InvalidInputException("위치는 ${Constants.User.MAX_LOCATION_LENGTH}자를 초과할 수 없습니다.")
        }
    }

    fun validateWebsite(website: String?) {
        if (website == null) return

        when {
            website.length > Constants.User.MAX_WEBSITE_LENGTH ->
                throw InvalidInputException("웹사이트 URL은 ${Constants.User.MAX_WEBSITE_LENGTH}자를 초과할 수 없습니다.")
            !website.startsWith("http://") && !website.startsWith("https://") ->
                throw InvalidInputException("웹사이트 URL은 http:// 또는 https://로 시작해야 합니다.")
        }
    }

    fun validateCreatorApplicationReason(reason: String) {
        when {
            reason.isBlank() ->
                throw InvalidInputException("신청 사유는 필수입니다.")
            reason.length < Constants.User.MIN_CREATOR_APPLICATION_REASON_LENGTH ->
                throw InvalidInputException("신청 사유는 최소 ${Constants.User.MIN_CREATOR_APPLICATION_REASON_LENGTH}자 이상 입력해주세요.")
            reason.length > Constants.User.MAX_CREATOR_APPLICATION_REASON_LENGTH ->
                throw InvalidInputException("신청 사유는 최대 ${Constants.User.MAX_CREATOR_APPLICATION_REASON_LENGTH}자까지 입력 가능합니다.")
        }
    }

    fun validateRejectionReason(reason: String) {
        when {
            reason.isBlank() ->
                throw InvalidInputException("거절 사유를 입력해주세요.")
            reason.length < Constants.User.MIN_REJECTION_REASON_LENGTH ->
                throw InvalidInputException("거절 사유는 최소 ${Constants.User.MIN_REJECTION_REASON_LENGTH}자 이상 입력해주세요.")
            reason.length > Constants.User.MAX_REJECTION_REASON_LENGTH ->
                throw InvalidInputException("거절 사유는 최대 ${Constants.User.MAX_REJECTION_REASON_LENGTH}자까지 입력 가능합니다.")
        }
    }

    // Address 관련 검증

    fun validateRecipientName(name: String) {
        when {
            name.isBlank() ->
                throw InvalidInputException("수취인 이름은 필수입니다.")
            name.length > Constants.Address.MAX_RECIPIENT_NAME_LENGTH ->
                throw InvalidInputException("수취인 이름은 ${Constants.Address.MAX_RECIPIENT_NAME_LENGTH}자를 초과할 수 없습니다.")
        }
    }

    fun validateRecipientPhone(phone: String) {
        when {
            phone.isBlank() ->
                throw InvalidInputException("연락처는 필수입니다.")
            phone.length > Constants.Address.MAX_RECIPIENT_PHONE_LENGTH ->
                throw InvalidInputException("연락처는 ${Constants.Address.MAX_RECIPIENT_PHONE_LENGTH}자를 초과할 수 없습니다.")
            !phone.matches(Regex("^\\d{2,3}-\\d{3,4}-\\d{4}$")) ->
                throw InvalidInputException("올바른 전화번호 형식이 아닙니다. (예: 010-1234-5678)")
        }
    }

    fun validateZipCode(zipCode: String) {
        when {
            zipCode.isBlank() ->
                throw InvalidInputException("우편번호는 필수입니다.")
            zipCode.length > Constants.Address.MAX_ZIP_CODE_LENGTH ->
                throw InvalidInputException("우편번호는 ${Constants.Address.MAX_ZIP_CODE_LENGTH}자를 초과할 수 없습니다.")
        }
    }

    fun validateAddress(address: String) {
        when {
            address.isBlank() ->
                throw InvalidInputException("주소는 필수입니다.")
            address.length > Constants.Address.MAX_ADDRESS_LENGTH ->
                throw InvalidInputException("주소는 ${Constants.Address.MAX_ADDRESS_LENGTH}자를 초과할 수 없습니다.")
        }
    }

    fun validateAddressDetail(detail: String?) {
        if (detail.isNullOrBlank()) return

        when {
            detail.length > Constants.Address.MAX_ADDRESS_DETAIL_LENGTH ->
                throw InvalidInputException("상세 주소는 ${Constants.Address.MAX_ADDRESS_DETAIL_LENGTH}자를 초과할 수 없습니다.")
        }
    }

    fun validateAddressMemo(memo: String?) {
        if (memo.isNullOrBlank()) return

        when {
            memo.length > Constants.Address.MAX_MEMO_LENGTH ->
                throw InvalidInputException("배송 메모는 ${Constants.Address.MAX_MEMO_LENGTH}자를 초과할 수 없습니다.")
        }
    }

    // Social 관련 검증

    fun validatePostContent(content: String) {
        when {
            content.isBlank() ->
                throw InvalidPostContentException("포스트 내용은 필수입니다.")
            content.length > Constants.Social.MAX_POST_CONTENT_LENGTH ->
                throw InvalidPostContentException("포스트 내용은 ${Constants.Social.MAX_POST_CONTENT_LENGTH}자를 초과할 수 없습니다.")
        }
    }

    fun validateCommentContent(content: String) {
        when {
            content.isBlank() ->
                throw InvalidCommentContentException("댓글 내용은 필수입니다.")
            content.length > Constants.Social.MAX_COMMENT_CONTENT_LENGTH ->
                throw InvalidCommentContentException("댓글 내용은 ${Constants.Social.MAX_COMMENT_CONTENT_LENGTH}자를 초과할 수 없습니다.")
        }
    }

    fun validateTagName(tagName: String?) {
        when {
            tagName.isNullOrBlank() ->
                throw InvalidInputException(Errors.Social.Tag.TAG_NAME_REQUIRED)
            tagName.length > Constants.Social.MAX_TAG_NAME_LENGTH ->
                throw InvalidInputException(Errors.Social.Tag.TAG_NAME_TOO_LONG)
            !tagName.matches(Regex("^[가-힣a-zA-Z0-9\\s]+$")) ->
                throw InvalidInputException(Errors.Social.Tag.TAG_NAME_INVALID)
        }
    }

    // Commerce 관련 검증

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

    fun validatePrice(price: String, fieldName: String = "가격") {
        when {
            price.isBlank() ->
                throw InvalidPriceException("${fieldName}은 필수입니다.")
        }
    }

    fun validateStock(stock: Int) {
        when {
            stock < 0 ->
                throw InvalidStockException("재고는 0 이상이어야 합니다.")
        }
    }

    fun validateProductImages(imageUrls: List<String>?) {
        if (imageUrls == null) return

        when {
            imageUrls.size > Constants.Commerce.MAX_PRODUCT_IMAGES ->
                throw InvalidInputException("상품 이미지는 최대 ${Constants.Commerce.MAX_PRODUCT_IMAGES}개까지 등록 가능합니다.")
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
            !phone.matches(Regex("^\\d{2,3}-\\d{3,4}-\\d{4}$")) ->
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

    fun validateReviewContent(content: String) {
        when {
            content.isBlank() ->
                throw InvalidInputException("리뷰 내용은 필수입니다.")
            content.length > Constants.Commerce.MAX_REVIEW_CONTENT_LENGTH ->
                throw InvalidInputException("리뷰 내용은 ${Constants.Commerce.MAX_REVIEW_CONTENT_LENGTH}자를 초과할 수 없습니다.")
        }
    }

    // Chat 관련 검증

    fun validateChatMessage(content: String?, mediaAttachments: List<String>? = null) {
        if (content.isNullOrBlank() && mediaAttachments.isNullOrEmpty()) {
            throw InvalidInputException("메시지 내용 또는 미디어가 필요합니다.")
        }
        content?.let {
            if (it.length > Constants.Chat.MAX_MESSAGE_LENGTH) {
                throw InvalidInputException("메시지는 ${Constants.Chat.MAX_MESSAGE_LENGTH}자를 초과할 수 없습니다.")
            }
        }
        mediaAttachments?.let {
            if (it.size > Constants.Chat.MAX_MEDIA_PER_MESSAGE) {
                throw InvalidInputException("미디어는 최대 ${Constants.Chat.MAX_MEDIA_PER_MESSAGE}개까지 첨부 가능합니다.")
            }
        }
    }

    // Subscription 관련 검증

    fun validatePlanName(name: String) {
        when {
            name.isBlank() ->
                throw InvalidInputException("플랜명은 필수입니다.")
            name.length > Constants.Subscription.MAX_PLAN_NAME_LENGTH ->
                throw InvalidInputException("플랜명은 ${Constants.Subscription.MAX_PLAN_NAME_LENGTH}자를 초과할 수 없습니다.")
        }
    }

    fun validatePlanDescription(description: String) {
        when {
            description.isBlank() ->
                throw InvalidInputException("플랜 설명은 필수입니다.")
            description.length > Constants.Subscription.MAX_PLAN_DESCRIPTION_LENGTH ->
                throw InvalidInputException("플랜 설명은 ${Constants.Subscription.MAX_PLAN_DESCRIPTION_LENGTH}자를 초과할 수 없습니다.")
        }
    }

    fun validatePlanBenefits(benefits: List<String>) {
        when {
            benefits.isEmpty() ->
                throw InvalidInputException("최소 하나 이상의 혜택을 입력해야 합니다.")
            benefits.size > Constants.Subscription.MAX_BENEFITS_PER_PLAN ->
                throw InvalidInputException("혜택은 최대 ${Constants.Subscription.MAX_BENEFITS_PER_PLAN}개까지 입력 가능합니다.")
        }
    }

    fun validatePlanId(planId: Int) {
        when {
            planId <= 0 ->
                throw InvalidInputException(Errors.Common.INVALID_ID)
        }
    }

    // Coupon 관련 검증

    fun validateCouponCode(code: String?) {
        when {
            code.isNullOrBlank() ->
                throw InvalidCouponCodeException("쿠폰 코드는 필수입니다.")
            code.length < Constants.Coupon.MIN_CODE_LENGTH || code.length > Constants.Coupon.MAX_CODE_LENGTH ->
                throw InvalidCouponCodeException("쿠폰 코드는 ${Constants.Coupon.MIN_CODE_LENGTH}-${Constants.Coupon.MAX_CODE_LENGTH}자여야 합니다.")
            !code.matches("[A-Z0-9]+".toRegex()) ->
                throw InvalidCouponCodeException("쿠폰 코드는 영문 대문자와 숫자만 사용 가능합니다.")
        }
    }

    fun validateManualCouponCode(code: String?) {
        if (code.isNullOrBlank()) return  // null이면 자동생성

        when {
            code.length < Constants.Coupon.MIN_CODE_LENGTH ->
                throw InvalidCouponCodeException("쿠폰 코드는 최소 ${Constants.Coupon.MIN_CODE_LENGTH}자 이상이어야 합니다.")
            code.length > Constants.Coupon.MAX_CODE_LENGTH ->
                throw InvalidCouponCodeException("쿠폰 코드는 최대 ${Constants.Coupon.MAX_CODE_LENGTH}자까지 입력 가능합니다.")
            !code.matches("[A-Z0-9]+".toRegex()) ->
                throw InvalidCouponCodeException("쿠폰 코드는 영문 대문자와 숫자만 사용 가능합니다.")
        }
    }

    fun validateCouponName(name: String?) {
        when {
            name.isNullOrBlank() ->
                throw InvalidInputException("쿠폰명은 필수입니다.")
            name.length < Constants.Coupon.MIN_NAME_LENGTH ->
                throw InvalidInputException("쿠폰명은 최소 ${Constants.Coupon.MIN_NAME_LENGTH}자 이상이어야 합니다.")
            name.length > Constants.Coupon.MAX_NAME_LENGTH ->
                throw InvalidInputException("쿠폰명은 최대 ${Constants.Coupon.MAX_NAME_LENGTH}자까지 입력 가능합니다.")
        }
    }

    fun validateCouponDescription(description: String?) {
        if (description.isNullOrBlank()) return

        when {
            description.length > Constants.Coupon.MAX_DESCRIPTION_LENGTH ->
                throw InvalidInputException("쿠폰 설명은 최대 ${Constants.Coupon.MAX_DESCRIPTION_LENGTH}자까지 입력 가능합니다.")
        }
    }

    fun validateCouponDiscountRate(rate: Int) {
        when {
            rate < Constants.Coupon.MIN_DISCOUNT_RATE ->
                throw InvalidInputException("할인율은 최소 ${Constants.Coupon.MIN_DISCOUNT_RATE}% 이상이어야 합니다.")
            rate > Constants.Coupon.MAX_DISCOUNT_RATE ->
                throw InvalidInputException("할인율은 최대 ${Constants.Coupon.MAX_DISCOUNT_RATE}%까지 설정 가능합니다.")
        }
    }

    fun validateCouponDiscountAmount(amount: String?) {
        if (amount.isNullOrBlank()) {
            throw InvalidInputException("할인 금액은 필수입니다.")
        }

        try {
            val value = BigDecimal(amount)
            when {
                value < Constants.Coupon.MIN_DISCOUNT_AMOUNT ->
                    throw InvalidInputException("할인 금액은 최소 ${Constants.Coupon.MIN_DISCOUNT_AMOUNT}원 이상이어야 합니다.")
                value > Constants.Coupon.MAX_DISCOUNT_AMOUNT ->
                    throw InvalidInputException("할인 금액은 최대 ${Constants.Coupon.MAX_DISCOUNT_AMOUNT}원까지 설정 가능합니다.")
            }
        } catch (_: NumberFormatException) {
            throw InvalidInputException("올바른 금액 형식이 아닙니다.")
        }
    }

    fun validateCouponMinOrderAmount(amount: String?) {
        if (amount.isNullOrBlank()) return

        try {
            val value = BigDecimal(amount)
            when {
                value < Constants.Coupon.MIN_ORDER_AMOUNT ->
                    throw InvalidInputException("최소 주문 금액은 ${Constants.Coupon.MIN_ORDER_AMOUNT}원 이상이어야 합니다.")
            }
        } catch (_: NumberFormatException) {
            throw InvalidInputException("올바른 금액 형식이 아닙니다.")
        }
    }

    fun validateCouponQuantity(quantity: Int) {
        when {
            quantity < Constants.Coupon.MIN_QUANTITY ->
                throw InvalidInputException("발급 수량은 최소 ${Constants.Coupon.MIN_QUANTITY}개 이상이어야 합니다.")
            quantity > Constants.Coupon.MAX_QUANTITY ->
                throw InvalidInputException("발급 수량은 최대 ${Constants.Coupon.MAX_QUANTITY}개까지 설정 가능합니다.")
        }
    }

    fun validateCouponUseCount(useCount: Int) {
        when {
            useCount < 1 ->
                throw InvalidInputException("사용 횟수는 최소 1회 이상이어야 합니다.")
            useCount > Constants.Coupon.MAX_USE_COUNT_PER_USER ->
                throw InvalidInputException("사용 횟수는 최대 ${Constants.Coupon.MAX_USE_COUNT_PER_USER}회까지 설정 가능합니다.")
        }
    }

    fun validateCouponDates(startDate: String?, endDate: String?) {
        if (startDate.isNullOrBlank()) {
            throw InvalidInputException("시작일은 필수입니다.")
        }
        if (endDate.isNullOrBlank()) {
            throw InvalidInputException("종료일은 필수입니다.")
        }

        try {
            val start = LocalDateTime.parse(startDate)
            val end = LocalDateTime.parse(endDate)

            when {
                start >= end ->
                    throw InvalidInputException("종료일은 시작일보다 이후여야 합니다.")
            }
        } catch (_: Exception) {
            throw InvalidInputException("올바른 날짜 형식이 아닙니다. (ISO 8601 형식)")
        }
    }

    // 추가 Commerce 관련 검증

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

    fun validateProductTags(tags: List<String>?) {
        if (tags == null) return

        when {
            tags.size > Constants.Commerce.MAX_PRODUCT_TAGS ->
                throw InvalidInputException("상품 태그는 최대 ${Constants.Commerce.MAX_PRODUCT_TAGS}개까지 등록 가능합니다.")
        }
    }

    fun validateStockAvailability(stock: Int, requestedQuantity: Int) {
        when {
            stock < requestedQuantity ->
                throw InsufficientStockException("재고가 부족합니다. (현재 재고: ${stock}개)")
        }
    }

    // 추가 Social 관련 검증

    fun validateUploadedMediaFiles(mediaFiles: List<Any>, contentTypes: List<String>) {
        when {
            mediaFiles.isEmpty() ->
                throw InvalidFileException("최소 1개의 미디어를 업로드해야 합니다.")
            mediaFiles.size != contentTypes.size ->
                throw InvalidFileException("미디어 파일과 콘텐츠 타입의 개수가 일치하지 않습니다.")
            mediaFiles.size > Constants.Social.MAX_MEDIA_ATTACHMENTS_PER_POST ->
                throw InvalidFileException("미디어는 최대 ${Constants.Social.MAX_MEDIA_ATTACHMENTS_PER_POST}개까지 첨부 가능합니다.")
        }
    }

    // 추가 공통 검증

    fun validateSearchQuery(query: String) {
        when {
            query.trim().isBlank() ->
                throw InvalidSearchQueryException("검색어를 입력해주세요.")
        }
    }

    fun validateMessageSearchQuery(query: String) {
        when {
            query.isBlank() || query.length < 2 ->
                throw InvalidSearchQueryException("검색어는 최소 2자 이상이어야 합니다.")
        }
    }

    fun validateSearchKeywordLength(keyword: String) {
        when {
            keyword.isBlank() ->
                throw InvalidSearchQueryException("검색어를 입력해주세요.")
            keyword.length < Constants.Search.MIN_KEYWORD_LENGTH ->
                throw InvalidSearchQueryException("검색어는 최소 ${Constants.Search.MIN_KEYWORD_LENGTH}자 이상이어야 합니다.")
            keyword.length > Constants.Search.MAX_KEYWORD_LENGTH ->
                throw InvalidSearchQueryException("검색어는 최대 ${Constants.Search.MAX_KEYWORD_LENGTH}자까지 입력 가능합니다.")
        }
    }

    fun validateTrackingNumber(trackingNumber: String) {
        when {
            trackingNumber.length < Constants.Commerce.TRACKING_NUMBER_MIN_LENGTH ->
                throw InvalidInputException("송장번호는 최소 ${Constants.Commerce.TRACKING_NUMBER_MIN_LENGTH}자 이상이어야 합니다.")
            trackingNumber.length > Constants.Commerce.TRACKING_NUMBER_MAX_LENGTH ->
                throw InvalidInputException("송장번호는 최대 ${Constants.Commerce.TRACKING_NUMBER_MAX_LENGTH}자까지 입력 가능합니다.")
        }
    }

    // Point 관련 검증

    fun validatePointAmount(amount: BigDecimal) {
        when {
            amount < BigDecimal.ZERO ->
                throw InvalidPointAmountException("포인트 금액은 0 이상이어야 합니다.")
            amount > BigDecimal("1000000") ->
                throw InvalidPointAmountException("포인트 금액은 최대 1,000,000원까지 가능합니다.")
        }
    }

    fun validatePointReason(reason: String) {
        when {
            reason.isBlank() ->
                throw InvalidInputException("포인트 사유는 필수입니다.")
            reason.length > 200 ->
                throw InvalidInputException("포인트 사유는 최대 200자까지 입력 가능합니다.")
        }
    }

    // 날짜/기간 관련 검증

    fun validateDateRange(startDate: LocalDateTime, endDate: LocalDateTime) {
        when {
            startDate >= endDate ->
                throw InvalidInputException("종료일은 시작일보다 이후여야 합니다.")
        }
    }


    // Coupon 추가 검증

    fun validateCouponTypeFields(
        type: CouponType,
        discountRate: Int?,
        discountAmount: String?
    ) {
        when (type) {
            CouponType.PERCENTAGE -> {
                if (discountRate == null) {
                    throw InvalidInputException("할인율 쿠폰은 할인율이 필수입니다.")
                }
                validateCouponDiscountRate(discountRate)
            }
            CouponType.FIXED_AMOUNT -> {
                if (discountAmount.isNullOrBlank()) {
                    throw InvalidInputException("정액 할인 쿠폰은 할인 금액이 필수입니다.")
                }
                validateCouponDiscountAmount(discountAmount)
            }
            else -> {}
        }
    }

    fun validateTargetIds(targetIds: List<Int>?) {
        if (targetIds == null) return

        when {
            targetIds.isEmpty() ->
                throw InvalidInputException("대상 ID 목록이 비어있습니다.")
            targetIds.any { it <= 0 } ->
                throw InvalidInputException("잘못된 대상 ID가 포함되어 있습니다.")
            targetIds.size > 100 ->
                throw InvalidInputException("대상 ID는 최대 100개까지 지정 가능합니다.")
        }
    }

    // 결제 관련 검증

    fun validatePaymentAmount(amount: BigDecimal) {
        when {
            amount <= BigDecimal.ZERO ->
                throw InvalidInputException("결제 금액은 0보다 커야 합니다.")
            amount > BigDecimal("10000000") ->
                throw InvalidInputException("결제 금액은 최대 10,000,000원까지 가능합니다.")
        }
    }
}
