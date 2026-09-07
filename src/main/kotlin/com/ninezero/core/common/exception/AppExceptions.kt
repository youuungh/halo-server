package com.ninezero.core.common.exception

import io.ktor.http.*

// 기본 예외

/** StatusPages 자동 처리용 기본 예외 */
sealed class AppException(
    message: String,
    val statusCode: HttpStatusCode
) : Exception(message)

// 400 잘못된 요청 예외

class InvalidInputException(message: String) : AppException(message, HttpStatusCode.BadRequest)

// User 관련
class InvalidEmailException(message: String = "올바른 이메일 형식이 아닙니다") : AppException(message, HttpStatusCode.BadRequest)
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
class InvalidPointAmountException(message: String) : AppException(message, HttpStatusCode.BadRequest)
class InvalidPointException(message: String) : AppException(message, HttpStatusCode.BadRequest)
class InvalidCouponCodeException(message: String) : AppException(message, HttpStatusCode.BadRequest)

// 파일 업로드 관련
class InvalidImageUrlException(message: String) : AppException(message, HttpStatusCode.BadRequest)
class InvalidFileException(message: String) : AppException(message, HttpStatusCode.BadRequest)

// Search 관련
class InvalidSearchQueryException(message: String) : AppException(message, HttpStatusCode.BadRequest)

// 401 인증 실패 예외

class InvalidCredentialsException(message: String = "이메일 또는 비밀번호가 올바르지 않습니다") : AppException(message, HttpStatusCode.Unauthorized)
class TokenExpiredException(message: String = "토큰이 만료되었습니다") : AppException(message, HttpStatusCode.Unauthorized)
class SocialOnlyAccountException(message: String = Errors.User.SOCIAL_ONLY_ACCOUNT) : AppException(message, HttpStatusCode.Unauthorized)

// 403 권한 없음 예외

class ForbiddenException(message: String = "접근 권한이 없습니다") : AppException(message, HttpStatusCode.Forbidden)
class PermissionDeniedException(message: String) : AppException(message, HttpStatusCode.Forbidden)
class CreatorOnlyException(message: String = "크리에이터만 접근할 수 있습니다") : AppException(message, HttpStatusCode.Forbidden)

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
class ImageNotFoundException(message: String) : AppException(message, HttpStatusCode.NotFound)

// Social 관련
class PostNotFoundException(message: String) : AppException(message, HttpStatusCode.NotFound) {
    constructor(postId: Int) : this("게시글 ID $postId 를 찾을 수 없습니다")
}
class CommentNotFoundException(message: String) : AppException(message, HttpStatusCode.NotFound) {
    constructor(commentId: Int) : this("댓글 ID $commentId 를 찾을 수 없습니다")
}
class TagNotFoundException(message: String) : AppException(message, HttpStatusCode.NotFound)

// Point·Coupon 관련
class PointAccountNotFoundException(message: String) : AppException(message, HttpStatusCode.NotFound) {
    constructor(userId: Int) : this("포인트 계정 ID $userId 를 찾을 수 없습니다")
}

// Subscription 관련
class SubscriptionNotFoundException(subscriptionId: Int) : AppException("구독 ID $subscriptionId 를 찾을 수 없습니다", HttpStatusCode.NotFound)
class SubscriptionPlanNotFoundException(planId: Int) : AppException("구독 플랜 ID $planId 를 찾을 수 없습니다", HttpStatusCode.NotFound)

// 409 충돌 예외

class ConflictException(message: String) : AppException(message, HttpStatusCode.Conflict)
class DuplicateEmailException(email: String = "이미 사용 중인 이메일입니다") : AppException(email, HttpStatusCode.Conflict)
class DuplicateUsernameException(username: String = "이미 사용 중인 사용자명입니다") : AppException(username, HttpStatusCode.Conflict)
class EmptyCartException(message: String) : AppException(message, HttpStatusCode.Conflict)
class ProductImagesCountExceededException(message: String) : AppException(message, HttpStatusCode.Conflict)
class ProductTagsExceededException(message: String) : AppException(message, HttpStatusCode.Conflict)
class MinImageRequiredException(message: String) : AppException(message, HttpStatusCode.Conflict)
class ImageContentTypeMismatchException(message: String) : AppException(message, HttpStatusCode.Conflict)
class PostPinLimitExceededException(message: String) : AppException(message, HttpStatusCode.Conflict)

// 422 처리 불가 예외

class ValidationException(message: String) : AppException(message, HttpStatusCode.UnprocessableEntity)
class InsufficientStockException(message: String = "재고가 부족합니다") : AppException(message, HttpStatusCode.UnprocessableEntity)
class CouponValidationException(message: String) : AppException(message, HttpStatusCode.UnprocessableEntity)
class OrderAlreadyPaidException(message: String) : AppException(message, HttpStatusCode.UnprocessableEntity)
class OrderCancelNotAllowedException(message: String) : AppException(message, HttpStatusCode.UnprocessableEntity)
class OrderCancelledException(message: String) : AppException(message, HttpStatusCode.UnprocessableEntity)
class RefundNotAllowedException(message: String) : AppException(message, HttpStatusCode.UnprocessableEntity)
class SubscriptionAlreadyActiveException(message: String = "이미 활성화된 구독입니다") : AppException(message, HttpStatusCode.UnprocessableEntity)
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

class InternalServerException(message: String = "서버 내부 오류가 발생했습니다") : AppException(message, HttpStatusCode.InternalServerError)
class EmailSendException(message: String = "이메일 전송 중 오류가 발생했습니다") : AppException(message, HttpStatusCode.InternalServerError)
class ProductUpdateFailedException(message: String) : AppException(message, HttpStatusCode.InternalServerError)
class ProductDeleteFailedException(message: String) : AppException(message, HttpStatusCode.InternalServerError)
class PostUpdateFailedException(message: String) : AppException(message, HttpStatusCode.InternalServerError)
class CommentUpdateFailedException(message: String) : AppException(message, HttpStatusCode.InternalServerError)
class OrderStatusUpdateFailedException(message: String) : AppException(message, HttpStatusCode.InternalServerError)
class LikeOperationFailedException(message: String) : AppException(message, HttpStatusCode.InternalServerError)
class BookmarkOperationFailedException(message: String) : AppException(message, HttpStatusCode.InternalServerError)
class HiddenPostOperationFailedException(message: String) : AppException(message, HttpStatusCode.InternalServerError)
class BlockOperationFailedException(message: String) : AppException(message, HttpStatusCode.InternalServerError)
class UserBlockedException(message: String) : AppException(message, HttpStatusCode.Forbidden)
class ShippingInfoUpdateFailedException(message: String) : AppException(message, HttpStatusCode.InternalServerError)
class ShippingStatusUpdateFailedException(message: String) : AppException(message, HttpStatusCode.InternalServerError)
class PostPinException(message: String) : AppException(message, HttpStatusCode.InternalServerError)
class PostDeleteFailedException(message: String) : AppException(message, HttpStatusCode.InternalServerError)
