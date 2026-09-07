package com.ninezero.core.common.config

object Messages {

    object Common {
        const val IMAGE_UPLOADED = "이미지가 업로드되었습니다."
        const val MEDIA_UPLOADED = "미디어가 업로드되었습니다."
    }

    object Auth {
        const val SIGNUP_CODE_SENT = "인증 코드를 보냈습니다. 메일함을 확인해주세요."
        const val LOGIN_SUCCESS = "로그인되었습니다."
        const val LOGOUT_SUCCESS = "로그아웃되었습니다."
        const val DEVICE_LOGOUT_SUCCESS = "디바이스가 로그아웃되었습니다."
        const val VERIFICATION_EMAIL_SENT = "인증 코드를 다시 보냈습니다."
        const val PASSWORD_RESET_EMAIL_SENT = "비밀번호 재설정 이메일이 발송되었습니다."
        const val PASSWORD_RESET_SUCCESS = "비밀번호가 재설정되었습니다."
        const val PASSWORD_CHANGE_SUCCESS = "비밀번호가 변경되었습니다."
        const val ACCOUNT_DELETED = "탈퇴가 완료되었습니다."
        const val FCM_TOKEN_REGISTERED = "FCM 토큰이 등록되었습니다."
        const val FCM_TOKEN_DELETED = "FCM 토큰이 삭제되었습니다."
    }

    object User {
        const val APPLICATION_SUBMITTED = "크리에이터 신청이 완료되었습니다."
        const val APPLICATION_APPROVED = "크리에이터 신청이 승인되었습니다."
        const val APPLICATION_REJECTED = "크리에이터 신청이 거절되었습니다."
        const val CREATOR_DEMOTED = "크리에이터 권한이 해제되었습니다."
        const val CREATOR_SELF_REVOKED = "크리에이터가 해제되었습니다."
        const val CREATOR_SELF_REVOKE_REASON = "본인 요청으로 해제되었습니다"
    }

    object Social {
        const val POST_CREATED = "포스트가 작성되었습니다."
        const val POST_UPDATED = "포스트가 수정되었습니다."
        const val POST_DELETED = "포스트가 삭제되었습니다."

        const val COMMENT_CREATED = "댓글이 작성되었습니다."
        const val COMMENT_UPDATED = "댓글이 수정되었습니다."
        const val COMMENT_DELETED = "댓글이 삭제되었습니다."

        const val FOLLOW_SUCCESS = "팔로우했습니다."
        const val UNFOLLOW_SUCCESS = "언팔로우했습니다."

        const val LIKE_SUCCESS = "좋아요를 눌렀습니다."
        const val LIKE_CANCEL_SUCCESS = "좋아요를 취소했습니다."
        const val COMMENT_LIKE_SUCCESS = "댓글에 좋아요를 눌렀습니다."
        const val COMMENT_LIKE_CANCEL_SUCCESS = "댓글 좋아요를 취소했습니다."

        const val BOOKMARK_SUCCESS = "저장됨"
        const val BOOKMARK_CANCEL_SUCCESS = "저장 취소됨"
        const val COMMENT_BOOKMARK_SUCCESS = "댓글이 저장됨"
        const val COMMENT_BOOKMARK_CANCEL_SUCCESS = "댓글 저장 취소됨"

        const val HIDE_POST_SUCCESS = "관심없음 처리됨"
        const val UNHIDE_POST_SUCCESS = "관심없음 취소됨"

        const val BLOCK_SUCCESS = "사용자를 차단했습니다."
        const val UNBLOCK_SUCCESS = "차단을 해제했습니다."

        const val TAG_CREATED = "태그가 생성되었습니다."
        const val TAG_UPDATED = "태그가 수정되었습니다."
        const val POST_PINNED = "포스트가 고정되었습니다."
        const val POST_UNPINNED = "포스트 고정이 해제되었습니다."
        const val COMMUNITY_POST_CREATED = "커뮤니티 글이 작성되었습니다."
    }

    object Commerce {
        // Product
        const val PRODUCT_CREATED = "상품이 등록되었습니다."
        const val PRODUCT_UPDATED = "상품이 수정되었습니다."
        const val PRODUCT_IMAGE_ADDED = "상품 이미지가 추가되었습니다."
        const val PRODUCT_DETAIL_CONTENT_IMAGE_ADDED = "상품 상세 콘텐츠 이미지가 추가되었습니다."
        const val PRODUCT_DISCONTINUED = "상품이 판매 종료되었습니다."
        const val PRODUCT_RESUMED = "상품이 판매 재개되었습니다."
        const val PRODUCT_DEAL_SET = "타임딜이 설정되었습니다."
        const val PRODUCT_DEAL_CLEARED = "타임딜이 해제되었습니다."

        // Cart
        const val CART_ADDED = "장바구니에 추가되었습니다."

        // Order
        const val ORDER_CREATED = "주문이 완료되었습니다."
        const val ORDER_CANCELLED = "주문이 취소되었습니다."
        const val ORDER_STATUS_UPDATED = "주문 상태가 변경되었습니다."
        const val SHIPPING_INFO_REGISTERED = "배송 정보가 등록되었습니다."
        const val SHIPPING_STATUS_UPDATED = "배송 상태가 변경되었습니다."

        // Review
        const val REVIEW_CREATED = "리뷰가 작성되었습니다."
        const val REVIEW_UPDATED = "리뷰가 수정되었습니다."
        const val REVIEW_IMAGE_ADDED = "리뷰 이미지가 추가되었습니다."

        // Payment
        const val PAYMENT_SUCCESS = "결제가 완료되었습니다."
        const val REFUND_SUCCESS = "환불이 처리되었습니다."
        const val TOSS_PAYMENT_PREPARED = "Toss Payments 결제가 준비되었습니다."
        const val TOSS_PAYMENT_COMPLETED = "Toss Payments 결제가 완료되었습니다."

        // Wishlist
        const val WISHLIST_ADDED = "위시리스트에 추가했습니다."
        const val WISHLIST_REMOVED = "위시리스트에서 제거했습니다."
        const val WISHLIST_MOVED_TO_CART = "상품을 위시리스트에서 장바구니로 이동했습니다."
    }

    object Chat {
        const val CHAT_CREATED = "채팅방이 생성되었습니다."
        const val CHAT_ARCHIVED = "채팅방을 보관했습니다."
        const val CHAT_UNARCHIVED = "채팅방 보관을 해제했습니다."
        const val CHAT_CLEARED = "대화 기록이 삭제되었습니다."
        const val CHAT_VIDEO_UPLOADED = "비디오가 업로드되었습니다."
        const val CHAT_FILE_UPLOADED = "파일이 업로드되었습니다."
        const val MESSAGE_SENT = "메시지가 전송되었습니다."
        const val MESSAGE_DELETED = "메시지가 삭제되었습니다."
        const val ALL_MESSAGES_READ = "모든 메시지를 읽음 처리했습니다."
    }

    object Notification {
        const val NOTIFICATION_READ = "알림을 읽음 처리했습니다."
        const val ALL_NOTIFICATIONS_READ = "모든 알림을 읽음 처리했습니다."
        const val NOTIFICATION_DELETED = "알림을 삭제했습니다."
    }

    object Subscription {
        const val SUBSCRIPTION_CREATED = "구독이 완료되었습니다."
        const val SUBSCRIPTION_CANCELLED = "구독 자동 갱신이 취소되었습니다. 구독 기간 종료일까지 혜택을 이용하실 수 있습니다."
        const val SUBSCRIPTION_AUTO_RENEW_ENABLED = "자동 갱신이 설정되었습니다."
        const val SUBSCRIPTION_AUTO_RENEW_DISABLED = "자동 갱신이 해제되었습니다."
        const val PLAN_CREATED = "구독 플랜이 생성되었습니다."
        const val PLAN_UPDATED = "구독 플랜이 수정되었습니다."
        const val PLAN_DEACTIVATED = "구독 플랜이 비활성화되었습니다."
    }

    object Profile {
        const val PROFILE_UPDATED = "프로필이 수정되었습니다."
        const val AVATAR_UPLOADED = "프로필 이미지가 업로드되었습니다."
    }

    object Point {
        const val POINT_ACCOUNT_EXISTS = "이미 존재하는 포인트 계정입니다."
        const val POINT_EXPIRED_COUNT = "%d건의 포인트가 만료 처리되었습니다."
    }

    object Address {
        const val ADDRESS_CREATED = "배송지가 등록되었습니다."
        const val ADDRESS_UPDATED = "배송지가 수정되었습니다."
        const val ADDRESS_DELETED = "배송지가 삭제되었습니다."
    }

    object Coupon {
        const val COUPON_CREATED = "쿠폰이 생성되었습니다."
        const val COUPON_UPDATED = "쿠폰이 수정되었습니다."
        const val COUPON_CLAIMED = "쿠폰이 발급되었습니다."
        const val COUPON_EXPIRED_COUNT = "%d개의 사용자 쿠폰이 만료 처리되었습니다."
    }
}
