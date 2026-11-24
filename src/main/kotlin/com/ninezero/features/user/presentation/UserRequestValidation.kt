package com.ninezero.features.user.presentation

import com.ninezero.core.common.config.Constants
import com.ninezero.core.common.exception.Errors
import com.ninezero.core.common.exception.InvalidInputException
import com.ninezero.core.common.util.CommonValidation
import com.ninezero.core.common.util.validateRequest
import com.ninezero.features.user.domain.UserValidation
import com.ninezero.features.user.presentation.models.request.*
import io.ktor.server.plugins.requestvalidation.RequestValidationConfig

/** 인증·주소 요청 본문 검사 */
fun RequestValidationConfig.validateUserRequests() {
    validate<RegisterRequest> {
        validateRequest {
            CommonValidation.validateEmail(it.email)
            UserValidation.validateUsername(UserValidation.normalizeUsername(it.username))
        }
    }

    validate<ResendVerificationRequest> {
        validateRequest {
            CommonValidation.validateEmail(it.email)
        }
    }

    validate<ForgotPasswordRequest> {
        validateRequest {
            CommonValidation.validateEmail(it.email)
        }
    }

    validate<VerifyResetCodeRequest> {
        validateRequest {
            CommonValidation.validateEmail(it.email)
            if (it.code.length != Constants.User.VERIFICATION_CODE_LENGTH) {
                throw InvalidInputException(Errors.User.RESET_CODE_INVALID)
            }
        }
    }

    validate<ResetPasswordRequest> {
        validateRequest {
            if (it.token.isBlank()) throw InvalidInputException("토큰은 필수입니다.")
        }
    }

    validate<LoginRequest> {
        validateRequest {
            if (it.email.isBlank()) throw InvalidInputException("이메일은 필수입니다.")
            if (it.password.isBlank()) throw InvalidInputException("비밀번호는 필수입니다.")
        }
    }

    validate<CreateAddressRequest> {
        validateRequest {
            UserValidation.validateRecipientName(it.recipientName)
            UserValidation.validateRecipientPhone(it.recipientPhone)
            UserValidation.validateZipCode(it.zipCode)
            UserValidation.validateAddress(it.address)
            UserValidation.validateAddressDetail(it.addressDetail)
            UserValidation.validateAddressMemo(it.memo)
        }
    }

    validate<UpdateAddressRequest> {
        validateRequest {
            UserValidation.validateRecipientName(it.recipientName)
            UserValidation.validateRecipientPhone(it.recipientPhone)
            UserValidation.validateZipCode(it.zipCode)
            UserValidation.validateAddress(it.address)
            UserValidation.validateAddressDetail(it.addressDetail)
            UserValidation.validateAddressMemo(it.memo)
        }
    }
}
