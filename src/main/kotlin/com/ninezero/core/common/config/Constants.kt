package com.ninezero.core.common.config

import java.math.BigDecimal

object Constants {
    const val API_VERSION = "/api/v1"

    // 페이지네이션
    const val DEFAULT_PAGE_LIMIT = 20
    const val MAX_PAGE_LIMIT = 100

    const val BCRYPT_COST = 10

    object Endpoints {
        const val ADMIN_CREATOR = "$API_VERSION/admin/creator"
        const val ADMIN_MONITORING = "$API_VERSION/admin/monitoring"
        const val ADMIN_REPORTS = "$API_VERSION/admin/reports"

        // Auth
        const val AUTH = "$API_VERSION/auth"

        // User
        const val USERS = "$API_VERSION/users"
        const val PROFILE = "$API_VERSION/profile"
        const val CREATOR = "$API_VERSION/creator"

        // Social
        const val POSTS = "$API_VERSION/posts"
        const val COMMENTS = "$API_VERSION/comments"
        const val LIKES = "$API_VERSION/likes"
        const val BOOKMARKS = "$API_VERSION/bookmarks"
        const val HIDDEN_POSTS = "$API_VERSION/posts/hidden"
        const val BLOCKED_USERS = "$API_VERSION/users/block"
        const val FOLLOWS = "$API_VERSION/follows"
        const val FEEDS = "$API_VERSION/feeds"
        const val TAGS = "$API_VERSION/tags"
        const val COMMUNITY = "$API_VERSION/community"
        const val REPORTS = "$API_VERSION/reports"

        // Commerce
        const val PRODUCTS = "$API_VERSION/products"
        const val ORDERS = "$API_VERSION/orders"
        const val CART = "$API_VERSION/cart"
        const val WISHLISTS = "$API_VERSION/wishlists"
        const val REVIEWS = "$API_VERSION/reviews"
        const val PAYMENT = "$API_VERSION/payments"
        const val BILLING = "$API_VERSION/billing"
        const val WEBHOOKS = "$API_VERSION/webhooks"

        // Notification
        const val NOTIFICATIONS = "$API_VERSION/notifications"
        const val REALTIME_WEBSOCKET = "/ws/realtime"

        // Chat
        const val CHATS = "$API_VERSION/chats"

        // Subscription
        const val SUBSCRIPTION_PLANS = "$API_VERSION/subscription-plans"
        const val SUBSCRIPTIONS = "$API_VERSION/subscriptions"

        // Search
        const val SEARCH = "$API_VERSION/search"

        // Banner
        const val BANNERS = "$API_VERSION/banners"

        // Point
        const val POINTS = "$API_VERSION/points"

        // Coupon
        const val COUPONS = "$API_VERSION/coupons"

        // Address
        const val ADDRESSES = "$API_VERSION/addresses"

        // Share
        const val SHARE = "$API_VERSION/share"
    }

    object Address {
        const val MAX_RECIPIENT_NAME_LENGTH = 50
        const val MAX_RECIPIENT_PHONE_LENGTH = 20
        const val MAX_ZIP_CODE_LENGTH = 10
        const val MAX_ADDRESS_LENGTH = 500
        const val MAX_ADDRESS_DETAIL_LENGTH = 200
        const val MAX_MEMO_LENGTH = 200
    }

    object User {
        const val MAX_EMAIL_LENGTH = 255
        const val MIN_PASSWORD_LENGTH = 8
        const val MAX_PASSWORD_LENGTH = 20
        const val MIN_USERNAME_LENGTH = 5
        const val MAX_USERNAME_LENGTH = 20
        const val MAX_DISPLAY_NAME_LENGTH = 30
        const val MAX_BIO_LENGTH = 300
        const val MAX_LOCATION_LENGTH = 100
        const val MAX_WEBSITE_LENGTH = 200

        // Session
        const val MAX_ACTIVE_SESSIONS = 5

        // Token
        const val ACCESS_TOKEN_VALIDITY_MINUTES = 15
        const val REFRESH_TOKEN_VALIDITY_DAYS = 30
        const val REFRESH_TOKEN_GRACE_SECONDS = 60
        const val SOCIAL_SIGNUP_TOKEN_VALIDITY_MINUTES = 10

        // 회원 탈퇴
        const val DELETED_USER_DISPLAY_NAME = "탈퇴한 사용자"

        // 인증 코드 형식
        const val VERIFICATION_CODE_LENGTH = 6
        const val VERIFICATION_CODE_CHARSET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"  // 혼동 문자 제외

        // 비밀번호 재설정 코드
        const val RESET_CODE_VALIDITY_MINUTES = 5
        const val RESET_CODE_MAX_ATTEMPTS = 5
        const val RESET_CODE_RESEND_COOLDOWN_SECONDS = 60
        const val RESET_CODE_MAX_SENDS_PER_HOUR = 5
        const val RESET_TOKEN_VALIDITY_MINUTES = 10

        // 회원가입 인증 코드
        const val SIGNUP_CODE_VALIDITY_MINUTES = 5
        const val SIGNUP_CODE_MAX_ATTEMPTS = 5
        const val SIGNUP_CODE_RESEND_COOLDOWN_SECONDS = 60
        const val SIGNUP_CODE_MAX_SENDS_PER_HOUR = 5

        // 대기 가입 보관 시간
        const val PENDING_SIGNUP_VALIDITY_MINUTES = 30

        // Creator
        const val MIN_CREATOR_APPLICATION_REASON_LENGTH = 10
        const val MAX_CREATOR_APPLICATION_REASON_LENGTH = 300
        const val MIN_REJECTION_REASON_LENGTH = 10
        const val MAX_REJECTION_REASON_LENGTH = 300
        const val CREATOR_REAPPLY_COOLDOWN_DAYS = 7
    }

    object Social {
        // Feed
        const val TRENDING_HOURS = 24

        // 미달 시 넓은 윈도우로 폴백
        const val TRENDING_MIN_COUNT = 10
        val TRENDING_FALLBACK_HOURS = listOf(24, 72, 168) // 24h → 3d → 7d

        // Post·Comment
        const val MAX_POST_CONTENT_LENGTH = 2000
        const val MAX_COMMENT_CONTENT_LENGTH = 500

        // Follow
        const val DEFAULT_FOLLOW_LIST_LIMIT = 10

        // Media
        const val MAX_HASHTAGS_PER_POST = 30
        const val MAX_MEDIA_ATTACHMENTS_PER_POST = 10

        // Hashtag
        const val MIN_HASHTAG_LENGTH = 1
        const val MAX_HASHTAG_LENGTH = 50

        const val MAX_PINNED_POSTS = 10
        const val MAX_PINNED_COMMUNITY_POSTS = 5
        const val MAX_TAG_SECTIONS = 3
        const val MAX_ITEMS_PER_SECTION = 10
        const val MAX_TAG_NAME_LENGTH = 50

        // Report
        const val AUTO_BLIND_REPORT_THRESHOLD = 50
    }

    object Commerce {
        // Product
        const val MIN_PRODUCT_NAME_LENGTH = 2
        const val MAX_PRODUCT_NAME_LENGTH = 255
        const val MAX_PRODUCT_DESCRIPTION_LENGTH = 5000
        const val MAX_PRODUCT_IMAGES = 10
        const val MAX_PRODUCT_DETAIL_CONTENT_IMAGES = 30
        const val MAX_PRODUCT_TAGS = 20

        // Cart
        const val MIN_CART_QUANTITY = 1
        const val MAX_CART_QUANTITY = 99

        // Order
        const val MAX_SHIPPING_ADDRESS_LENGTH = 500
        const val MAX_SHIPPING_NAME_LENGTH = 100
        const val MAX_SHIPPING_PHONE_LENGTH = 20

        // Review
        const val MIN_REVIEW_RATING = 1
        const val MAX_REVIEW_RATING = 5
        const val MAX_REVIEW_CONTENT_LENGTH = 1000
        const val MAX_REVIEW_IMAGES = 5
        const val REVIEW_WRITE_DEADLINE_DAYS = 30

        // 배송·택배사
        const val CARRIER_CJ = "CJ대한통운"
        const val CARRIER_EPOST = "우체국택배"
        const val CARRIER_HANJIN = "한진택배"
        const val CARRIER_LOTTE = "롯데택배"
        const val CARRIER_LOGEN = "로젠택배"

        // 배송·소요일
        const val DELIVERY_DAYS_CJ = 2
        const val DELIVERY_DAYS_EPOST = 3
        const val DELIVERY_DAYS_HANJIN = 2
        const val DELIVERY_DAYS_LOTTE = 2
        const val DELIVERY_DAYS_LOGEN = 3
        const val DELIVERY_DAYS_DEFAULT = 3

        // 배송·배송비
        val DEFAULT_SHIPPING_FEE = BigDecimal("3000")
        val FREE_SHIPPING_THRESHOLD = BigDecimal("50000")

        // 배송·운송장
        const val TRACKING_NUMBER_MIN_LENGTH = 10
        const val TRACKING_NUMBER_MAX_LENGTH = 20
    }

    object Chat {
        const val MAX_MESSAGE_LENGTH = 1000
        const val MAX_MEDIA_PER_MESSAGE = 10
        const val DEFAULT_MESSAGE_LIMIT = 30
    }

    object Subscription {
        const val MAX_PLAN_NAME_LENGTH = 50
        const val MAX_PLAN_DESCRIPTION_LENGTH = 500
        const val MAX_BENEFITS_PER_PLAN = 10
        const val MIN_PLAN_PRICE = 0
        const val MAX_PLAN_PRICE = 100000
        const val SUBSCRIPTION_DURATION_DAYS = 30
    }

    object Search {
        const val MIN_KEYWORD_LENGTH = 1
        const val MAX_KEYWORD_LENGTH = 100
        const val MAX_SEARCH_HISTORY = 20
        const val POPULAR_LIMIT = 20
        const val POPULAR_POOL_MULTIPLIER = 4
        const val POPULAR_ROTATION_WINDOW_MINUTES = 15
    }

    object Point {
        const val EXPIRATION_DAYS = 365

        val EARN_RATE_DECIMAL = BigDecimal("0.01")
        val TIER1_EARN_RATE_DECIMAL = BigDecimal("0.05")  // 5%
        val TIER2_EARN_RATE_DECIMAL = BigDecimal("0.10")  // 10%
        val MAX_USE_RATE_DECIMAL = BigDecimal("0.50")
        val MIN_USE_AMOUNT_DECIMAL = BigDecimal("100")
    }

    object Coupon {
        // 자동 생성 쿠폰 코드
        const val CODE_LENGTH = 12
        const val CODE_PATTERN = "[A-Z0-9]+" // 염격한 규칙: "^[A-Z0-9]{12}$"

        // 수동 입력 쿠폰 코드
        const val MIN_CODE_LENGTH = 4
        const val MAX_CODE_LENGTH = 20

        // 쿠폰명
        const val MIN_NAME_LENGTH = 2
        const val MAX_NAME_LENGTH = 50

        // 설명
        const val MAX_DESCRIPTION_LENGTH = 500

        // 할인
        const val MIN_DISCOUNT_RATE = 1
        const val MAX_DISCOUNT_RATE = 100
        val MIN_DISCOUNT_AMOUNT = BigDecimal("1000")
        val MAX_DISCOUNT_AMOUNT = BigDecimal("1000000")

        // 사용 조건
        val MIN_ORDER_AMOUNT = BigDecimal("0")

        // 발급 수량
        const val MIN_QUANTITY = 1
        const val MAX_QUANTITY = 100000

        // 사용자당 최대 사용 횟수
        const val MAX_USE_COUNT_PER_USER = 10
    }
}
