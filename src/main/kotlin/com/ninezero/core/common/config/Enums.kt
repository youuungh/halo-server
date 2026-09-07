package com.ninezero.core.common.config

import kotlinx.serialization.Serializable

@Serializable
enum class UserRole {
    USER,       // 일반 사용자
    CREATOR,    // 크리에이터
    ADMIN       // 관리자
}

@Serializable
enum class PostType {
    TEXT,      // 텍스트 포스트
    IMAGE,     // 이미지 포스트
    VIDEO,     // 비디오 포스트
    PRODUCT    // 상품 포스트
}

@Serializable
enum class MediaType {
    IMAGE,
    VIDEO
}

@Serializable
enum class PostContextType {
    GENERAL,            // 일반 포스트
    CREATOR_FEED,       // Creator 본인 피드
    COMMUNITY           // Creator 커뮤니티
}

@Serializable
enum class TagTargetType {
    POST,
    PRODUCT
}

enum class BookmarkTargetType {
    POST,
    COMMENT
}

@Serializable
enum class PostStatus {
    ACTIVE,             // 활성
    HIDDEN              // 숨김
}

@Serializable
enum class ProductStatus {
    ACTIVE,             // 판매중
    SOLD_OUT,           // 품절
    DISCONTINUED        // 판매종료
}

@Serializable
enum class PurchaseState {
    PURCHASABLE,        // 구매 가능
    NO_ACCESS,          // 접근 권한 없음
    DISCONTINUED,       // 판매종료
    SOLD_OUT            // 품절
}

@Serializable
enum class OrderStatus {
    PENDING,            // 주문 대기
    CONFIRMED,          // 주문 확정
    DELIVERED,          // 배송완료
    CANCELLED,          // 주문취소
}

@Serializable
enum class ShippingStatus {
    PREPARING,           // 배송 준비중
    SHIPPED,             // 배송 시작
    IN_TRANSIT,          // 배송중
    OUT_FOR_DELIVERY,    // 배송 출발
    DELIVERED            // 배송 완료
}

@Serializable
enum class NotificationCategory {
    CREATOR_ACTIVITY,   // 크리에이터 활동
    SOCIAL,             // 소셜 활동
    COMMERCE,           // 쇼핑
    CHAT,               // 채팅
    SYSTEM              // 시스템
}

/** 알림 targetType 문자열 상수 */
object NotificationTargetType {     // deepLink 문자열 연산용
    const val USER = "USER"
    const val POST = "POST"
    const val COMMENT = "COMMENT"
    const val PRODUCT = "PRODUCT"
    const val ORDER = "ORDER"
    const val CHAT = "CHAT"
    const val POINT = "POINT"
    const val APPLICATION = "APPLICATION"
    const val SUBSCRIPTION = "SUBSCRIPTION"
}

@Serializable
enum class NotificationType {
    // Social 알림
    FOLLOW,                         // 팔로우
    LIKE_POST,                      // 포스트 좋아요 알림
    LIKE_COMMENT,                   // 댓글 좋아요 알림
    COMMENT,                        // 댓글
    REPLY,                          // 대댓글 작성
    MENTION,                        // 멘션
    NEW_POST,                       // 팔로우한 크리에이터의 새 포스트

    // Commerce 알림 - 주문
    ORDER_CREATED,                  // 주문 생성
    ORDER_RECEIVED,                 // 새 주문 접수
    ORDER_CONFIRMED,                // 주문 확정
    ORDER_CANCELLED,                // 주문 취소

    // Commerce 알림 - 배송
    SHIPPING_STARTED,               // 배송 시작
    SHIPPING_IN_TRANSIT,            // 배송중
    SHIPPING_OUT_FOR_DELIVERY,      // 배송 출발
    SHIPPING_DELIVERED,             // 배송 완료

    // Commerce 알림 - 기타
    PRODUCT_REVIEWED,               // 리뷰 작성
    NEW_PRODUCT,                    // 팔로우한 크리에이터의 새 상품

    // Chat 알림
    CHAT_MESSAGE,                   // 새 채팅 메시지

    // Point 알림
    POINT_EARNED,                   // 포인트 적립
    POINT_EXPIRED,                  // 포인트 만료

    // Creator 알림
    CREATOR_APPROVED,               // 크리에이터 승인
    CREATOR_REJECTED,               // 크리에이터 거절
    CREATOR_DEMOTED,                // 크리에이터 강등

    // 시스템 알림
    SYSTEM_ANNOUNCEMENT,            // 시스템 공지사항
}

fun NotificationType.getCategory(): NotificationCategory {
    return when (this) {
        NotificationType.NEW_POST,
        NotificationType.NEW_PRODUCT -> NotificationCategory.CREATOR_ACTIVITY

        NotificationType.FOLLOW,
        NotificationType.LIKE_POST,
        NotificationType.LIKE_COMMENT,
        NotificationType.COMMENT,
        NotificationType.REPLY,
        NotificationType.MENTION -> NotificationCategory.SOCIAL

        NotificationType.ORDER_CREATED,
        NotificationType.ORDER_RECEIVED,
        NotificationType.ORDER_CONFIRMED,
        NotificationType.ORDER_CANCELLED,
        NotificationType.SHIPPING_STARTED,
        NotificationType.SHIPPING_IN_TRANSIT,
        NotificationType.SHIPPING_OUT_FOR_DELIVERY,
        NotificationType.SHIPPING_DELIVERED,
        NotificationType.PRODUCT_REVIEWED -> NotificationCategory.COMMERCE

        NotificationType.CHAT_MESSAGE -> NotificationCategory.CHAT

        NotificationType.POINT_EARNED,
        NotificationType.POINT_EXPIRED,
        NotificationType.CREATOR_APPROVED,
        NotificationType.CREATOR_REJECTED,
        NotificationType.CREATOR_DEMOTED,
        NotificationType.SYSTEM_ANNOUNCEMENT -> NotificationCategory.SYSTEM
    }
}

@Serializable
enum class PaymentProvider {
    MOCK,               // Mock
    TOSS_PAYMENTS,      // 토스
    TOSS_BILLING        // 토스 빌링
}

@Serializable
enum class BillingKeyStatus {
    ACTIVE,             // 사용 가능
    DELETED             // 삭제됨
}

@Serializable
enum class PaymentStatus {
    PENDING,            // 결제 대기
    COMPLETED,          // 결제 완료
    FAILED,             // 결제 실패
    CANCELLED,          // 결제 취소
    REFUND_PENDING,     // 환불 요청됨
    REFUNDED            // 환불 완료
}

@Serializable
enum class LikeType {
    POST,               // 포스트 좋아요
    COMMENT             // 댓글 좋아요
}

@Serializable
enum class FeedType {
    HOME,               // 팔로잉한 사용자들의 포스트
    EXPLORE,            // 추천/인기 포스트
    USER_POSTS,         // 특정 사용자의 포스트
    HASHTAG,            // 해시태그 피드
    TRENDING            // 트렌딩 포스트
}

@Serializable
enum class ChatMessageType {
    TEXT,               // 일반 텍스트 메시지
    IMAGE,              // 이미지 메시지
    VIDEO,              // 비디오 메시지
    FILE,               // 파일 메시지
    PRODUCT_LINK,       // 상품 링크 공유
    POST_LINK           // 포스트 링크 공유
}

@Serializable
enum class SubscriptionStatus {
    ACTIVE,             // 활성 구독
    EXPIRED,            // 만료됨
    CANCELLED           // 취소됨
}

@Serializable
enum class SubscriptionPlanTier {
    FREE,               // 기본 티어
    TIER1,              // 1티어 구독
    TIER2               // 2티어 구독
}

@Serializable
enum class ReviewSortType {
    RECENT,             // 최신순
    RATING_HIGH,        // 별점 높은순
    RATING_LOW          // 별점 낮은순
}

@Serializable
enum class CommentSortType {
    LATEST,             // 최신순
    POPULAR             // 인기순
}

@Serializable
enum class CommunityPostSortType {
    LATEST,             // 최신순
    POPULAR,            // 좋아요 많은순
    MOST_VIEWED,        // 조회수 높은순
    OLDEST              // 오래된순
}

@Serializable
enum class UserPostSortType {
    LATEST,             // 최신순
    OLDEST              // 오래된순
}

@Serializable
enum class UserCommentSortType {
    LATEST,             // 최신순
    OLDEST              // 오래된순
}

@Serializable
enum class ProductSortType {
    LATEST,             // 최신 등록순
    PRICE_LOW,          // 낮은 가격순
    PRICE_HIGH          // 높은 가격순
}

@Serializable
enum class PointType {
    EARN,               // 적립
    USE,                // 사용
    EXPIRE,             // 만료
    REFUND              // 환불
}

@Serializable
enum class CouponType {
    PERCENTAGE,         // 비율 할인
    FIXED_AMOUNT,       // 정액 할인
    FREE_SHIPPING       // 무료 배송
}

@Serializable
enum class CouponDiscountTarget {
    ALL,                // 전체 상품
    CATEGORY,           // 특정 카테고리
    PRODUCT             // 특정 상품
}

@Serializable
enum class CouponStatus {
    ACTIVE,             // 활성
    EXPIRED,            // 만료
    DISABLED            // 비활성화
}

@Serializable
enum class UserCouponStatus {
    AVAILABLE,          // 사용 가능
    USED,               // 사용됨
    EXPIRED             // 만료됨
}

@Serializable
enum class CreatorApplicationStatus {
    PENDING,            // 대기중
    APPROVED,           // 승인됨
    REJECTED,           // 거절됨
    REVOKED             // 해제됨
}

@Serializable
enum class BannerType {
    CREATOR,            // 크리에이터 관련
    PRODUCT,            // 상품 관련
    POST,               // 포스트 관련
    EVENT,              // 이벤트
    EXTERNAL            // 외부 링크
}

@Serializable
enum class ReportTargetType {
    POST,               // 포스트
    COMMENT,            // 댓글
    USER                // 사용자
}
