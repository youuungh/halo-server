package com.ninezero.core.common.exception

import io.ktor.http.*

// 기본 예외

sealed class AppException(
    message: String,
    val statusCode: HttpStatusCode
) : Exception(message)

// 400 잘못된 요청 예외

class InvalidInputException(message: String) : AppException(message, HttpStatusCode.BadRequest)

// User 관련
class InvalidEmailException(message: String) : AppException(message, HttpStatusCode.BadRequest)
class InvalidPasswordException(message: String) : AppException(message, HttpStatusCode.BadRequest)
class InvalidUsernameException(message: String) : AppException(message, HttpStatusCode.BadRequest)
class InvalidDisplayNameException(message: String) : AppException(message, HttpStatusCode.BadRequest)
class InvalidBioException(message: String) : AppException(message, HttpStatusCode.BadRequest)

// Commerce 관련
class InvalidProductNameException(message: String) : AppException(message, HttpStatusCode.BadRequest)
class InvalidPriceException(message: String) : AppException(message, HttpStatusCode.BadRequest)
class InvalidStockException(message: String) : AppException(message, HttpStatusCode.BadRequest)
class InvalidOrderAmountException(message: String) : AppException(message, HttpStatusCode.BadRequest)

// Social 관련
class InvalidPostContentException(message: String) : AppException(message, HttpStatusCode.BadRequest)
class InvalidCommentContentException(message: String) : AppException(message, HttpStatusCode.BadRequest)
class InvalidHashtagException(message: String) : AppException(message, HttpStatusCode.BadRequest)
class InvalidTagException(message: String) : AppException(message, HttpStatusCode.BadRequest)

// Point·Coupon 관련
class InvalidPointException(message: String) : AppException(message, HttpStatusCode.BadRequest)
class InvalidCouponCodeException(message: String) : AppException(message, HttpStatusCode.BadRequest)

// 파일 업로드 관련
class InvalidImageUrlException(message: String) : AppException(message, HttpStatusCode.BadRequest)
class InvalidFileException(message: String) : AppException(message, HttpStatusCode.BadRequest)

// Search 관련
class InvalidSearchQueryException(message: String) : AppException(message, HttpStatusCode.BadRequest)

// 401 인증 실패 예외

class InvalidCredentialsException(message: String = Errors.User.INVALID_CREDENTIALS) : AppException(message, HttpStatusCode.Unauthorized)
class TokenExpiredException(message: String) : AppException(message, HttpStatusCode.Unauthorized)
class SocialOnlyAccountException(message: String = Errors.User.SOCIAL_ONLY_ACCOUNT) : AppException(message, HttpStatusCode.Unauthorized)

// 403 권한 없음 예외

class ForbiddenException(message: String) : AppException(message, HttpStatusCode.Forbidden)
class CreatorOnlyException(message: String = Errors.User.CREATOR_ONLY) : AppException(message, HttpStatusCode.Forbidden)
class UserBlockedException(message: String) : AppException(message, HttpStatusCode.Forbidden)

// 404 리소스 없음 예외

class NotFoundException(message: String) : AppException(message, HttpStatusCode.NotFound)

// User 관련
class UserNotFoundException(message: String) : AppException(message, HttpStatusCode.NotFound) {
    constructor(userId: Int) : this("사용자 ID $userId 를 찾을 수 없습니다")
}
class UserNotFoundByEmailException(email: String) : AppException("이메일 $email 를 찾을 수 없습니다", HttpStatusCode.NotFound)
class CreatorNotFoundException(message: String) : AppException(message, HttpStatusCode.NotFound)
class CreatorApplicationNotFoundException(applicationId: Int) : AppException("크리에이터 신청 ID $applicationId 를 찾을 수 없습니다", HttpStatusCode.NotFound)

// Commerce 관련
class ProductNotFoundException(message: String) : AppException(message, HttpStatusCode.NotFound) {
    constructor(productId: Int) : this("상품 ID $productId 를 찾을 수 없습니다")
}
class OrderNotFoundException(message: String) : AppException(message, HttpStatusCode.NotFound) {
    constructor(orderId: Int) : this("주문 ID $orderId 를 찾을 수 없습니다")
}
class PaymentNotFoundException(message: String) : AppException(message, HttpStatusCode.NotFound)

// Social 관련
class PostNotFoundException(message: String) : AppException(message, HttpStatusCode.NotFound)
class CommentNotFoundException(message: String) : AppException(message, HttpStatusCode.NotFound)
class TagNotFoundException(message: String) : AppException(message, HttpStatusCode.NotFound)

// Subscription 관련
class SubscriptionNotFoundException(subscriptionId: Int) : AppException("구독 ID $subscriptionId 를 찾을 수 없습니다", HttpStatusCode.NotFound)
class SubscriptionPlanNotFoundException(planId: Int) : AppException("구독 플랜 ID $planId 를 찾을 수 없습니다", HttpStatusCode.NotFound)

// 409 충돌 예외

class ConflictException(message: String) : AppException(message, HttpStatusCode.Conflict)
class DuplicateEmailException(message: String = Errors.User.DUPLICATE_EMAIL) : AppException(message, HttpStatusCode.Conflict)
class DuplicateUsernameException(message: String = Errors.User.DUPLICATE_USERNAME) : AppException(message, HttpStatusCode.Conflict)
class EmptyCartException(message: String) : AppException(message, HttpStatusCode.Conflict)
class PostPinLimitExceededException(message: String) : AppException(message, HttpStatusCode.Conflict)

// 422 처리 불가 예외

class ValidationException(message: String) : AppException(message, HttpStatusCode.UnprocessableEntity)
class InsufficientStockException(message: String) : AppException(message, HttpStatusCode.UnprocessableEntity)
class CouponValidationException(message: String) : AppException(message, HttpStatusCode.UnprocessableEntity)
class OrderAlreadyPaidException(message: String) : AppException(message, HttpStatusCode.UnprocessableEntity)
class OrderCancelNotAllowedException(message: String) : AppException(message, HttpStatusCode.UnprocessableEntity)
class OrderCancelledException(message: String) : AppException(message, HttpStatusCode.UnprocessableEntity)
class SubscriptionAlreadyActiveException(message: String = Errors.Subscription.SUBSCRIPTION_ALREADY_ACTIVE) : AppException(message, HttpStatusCode.UnprocessableEntity)
class SubscriptionRequiredException(message: String) : AppException(message, HttpStatusCode.UnprocessableEntity)
class SubscriptionPlanRequiredException(message: String) : AppException(message, HttpStatusCode.UnprocessableEntity)
class ProductInactiveException(message: String) : AppException(message, HttpStatusCode.UnprocessableEntity)
class ProductSoldOutException(message: String) : AppException(message, HttpStatusCode.UnprocessableEntity)
class ShippingAlreadyStartedException(message: String) : AppException(message, HttpStatusCode.UnprocessableEntity)
class ShippingNotStartedException(message: String) : AppException(message, HttpStatusCode.UnprocessableEntity)
class ImageValidationFailedException(message: String) : AppException(message, HttpStatusCode.UnprocessableEntity)
class PaymentAmountMismatchException(message: String) : AppException(message, HttpStatusCode.UnprocessableEntity)
class PaymentConfirmFailedException(message: String) : AppException(message, HttpStatusCode.UnprocessableEntity)
class BillingKeyRequiredException(message: String) : AppException(message, HttpStatusCode.UnprocessableEntity)

// 429 요청 초과 예외

class TooManyRequestsException(message: String) : AppException(message, HttpStatusCode.TooManyRequests)

// 500 서버 오류 예외

class InternalServerException(message: String) : AppException(message, HttpStatusCode.InternalServerError)
class PaymentResultUnknownException(message: String = Errors.Commerce.Payment.PAYMENT_RESULT_UNKNOWN) : AppException(message, HttpStatusCode.InternalServerError)
class EmailSendException(message: String) : AppException(message, HttpStatusCode.InternalServerError)
class ProductUpdateFailedException(message: String) : AppException(message, HttpStatusCode.InternalServerError)
class ProductDeleteFailedException(message: String) : AppException(message, HttpStatusCode.InternalServerError)
class PostUpdateFailedException(message: String) : AppException(message, HttpStatusCode.InternalServerError)
class CommentUpdateFailedException(message: String) : AppException(message, HttpStatusCode.InternalServerError)
class OrderStatusUpdateFailedException(message: String) : AppException(message, HttpStatusCode.InternalServerError)
class LikeOperationFailedException(message: String) : AppException(message, HttpStatusCode.InternalServerError)
class BookmarkOperationFailedException(message: String) : AppException(message, HttpStatusCode.InternalServerError)
class BlockOperationFailedException(message: String) : AppException(message, HttpStatusCode.InternalServerError)
class ShippingInfoUpdateFailedException(message: String) : AppException(message, HttpStatusCode.InternalServerError)
class ShippingStatusUpdateFailedException(message: String) : AppException(message, HttpStatusCode.InternalServerError)
class PostDeleteFailedException(message: String) : AppException(message, HttpStatusCode.InternalServerError)
