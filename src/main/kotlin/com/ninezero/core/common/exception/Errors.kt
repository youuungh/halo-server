package com.ninezero.core.common.exception

import com.ninezero.core.common.config.Constants
import com.ninezero.core.storage.StorageConfig

object Errors {

    object Banner {
        const val BANNER_LOAD_ERROR = "배너 데이터를 불러올 수 없습니다."
        const val BANNER_NOT_FOUND = "배너를 찾을 수 없습니다."
    }

    object Common {
        // 인증·권한
        const val AUTH_REQUIRED = "인증이 필요합니다."
        const val UNAUTHORIZED = "권한이 없습니다."
        const val PERMISSION_DENIED = "접근 권한이 없습니다."

        // 역할별 권한
        const val ADMIN_ONLY = "관리자 권한이 필요합니다."
        const val CREATOR_OR_ADMIN_ONLY = "크리에이터 또는 관리자만 가능합니다."

        // 일반 에러
        const val INTERNAL_ERROR = "서버 오류가 발생했습니다."
        const val NOT_FOUND = "요청한 리소스를 찾을 수 없습니다."
        const val INVALID_REQUEST = "잘못된 요청 형식입니다."
        const val INVALID_STATE = "처리할 수 없는 상태입니다."

        // ID 및 형식 검증 에러
        const val INVALID_ID = "유효하지 않은 ID입니다."
        const val INVALID_CREATOR_ID = "유효하지 않은 크리에이터 ID입니다."
        const val INVALID_NUMBER_FORMAT = "올바르지 않은 숫자 형식입니다."
        const val USER_IDS_EMPTY = "사용자 ID 목록이 비어있습니다."
    }

    object File {
        // 일반 업로드
        const val NO_FILE_UPLOADED = "업로드할 파일이 없습니다."
        const val IMAGE_CONTENT_TYPE_MISMATCH = "이미지와 콘텐츠 타입의 개수가 일치하지 않습니다."

        // 이미지 검증
        const val NO_IMAGE_FILES = "업로드할 이미지가 없습니다."
        const val INVALID_IMAGE_FILE = "유효하지 않은 이미지 파일입니다."
        const val MIN_IMAGE_REQUIRED = "최소 1개의 이미지를 업로드해야 합니다."
        const val IMAGE_VALIDATION_FAILED = "유효하지 않은 이미지 파일이 포함되어 있습니다. (최대 10MB, JPEG/PNG/GIF/WEBP만 가능)"
        const val IMAGE_VALIDATION_FAILED_INDEX = "일부 이미지 파일이 유효하지 않습니다. (최대 10MB)"
        const val IMAGE_NOT_FOUND = "이미지가 존재하지 않습니다."
        const val IMAGE_LIMIT_EXCEEDED = "이미지는 최대 ${Constants.Chat.MAX_MEDIA_PER_MESSAGE}개까지 업로드 가능합니다."

        // 비디오 검증
        const val VIDEO_SIZE_EXCEEDED = "비디오 파일은 최대 ${StorageConfig.FileSizeLimit.CHAT_VIDEO / 1024 / 1024}MB까지 업로드 가능합니다."
        const val VIDEO_UNSUPPORTED_FORMAT = "지원하지 않는 비디오 형식입니다."

        // 파일 검증
        const val FILE_SIZE_EXCEEDED = "파일은 최대 ${StorageConfig.FileSizeLimit.CHAT_FILE / 1024 / 1024}MB까지 업로드 가능합니다."

        // 미디어 검증
        const val NO_MEDIA_FILES = "업로드할 미디어 파일이 없습니다."
        const val INVALID_MEDIA_FILE = "유효하지 않은 미디어 파일입니다."
        const val MEDIA_VALIDATION_FAILED = "유효하지 않은 미디어 파일입니다. (최대 10MB, 이미지: JPEG/PNG/GIF/WEBP, 비디오: MP4/MOV/AVI)"
    }

    object User {
        // 사용자 엔티티·계정
        const val USER_NOT_FOUND = "존재하지 않는 사용자입니다."
        const val INVALID_USER_ID = "유효하지 않은 사용자 ID입니다."
        const val DELETED_USER = "탈퇴한 사용자입니다."
        const val USER_INFO_NOT_FOUND = "사용자 정보를 찾을 수 없습니다."
        const val SESSION_NOT_FOUND = "세션을 찾을 수 없습니다."
        const val ACCOUNT_DISABLED = "비활성화된 계정입니다."

        // 기기·세션
        const val DEVICE_LOGOUT_CURRENT = "현재 디바이스는 로그아웃할 수 없습니다. 일반 로그아웃을 사용하세요."
        const val SESSION_ID_REQUIRED = "세션 ID가 필요합니다."

        // Email
        const val EMAIL_SEND_FAILED = "이메일 발송에 실패했습니다."

        // 회원가입 인증 코드
        const val SIGNUP_CODE_RESEND_TOO_SOON = "인증 코드를 너무 많이 요청했습니다. 잠시 후 다시 시도해주세요."
        const val SIGNUP_CODE_SEND_LIMIT_EXCEEDED = "인증 코드를 너무 많이 전송했습니다. 나중에 다시 시도해주세요."
        const val SIGNUP_NOT_FOUND = "진행 중인 가입 요청이 없습니다. 처음부터 다시 시도해주세요."
        const val SIGNUP_CODE_EXPIRED = "인증 코드가 만료되었습니다. 재전송해주세요."
        const val SIGNUP_CODE_ATTEMPTS_EXCEEDED = "시도 횟수를 초과했습니다. 처음부터 다시 시도해주세요."
        const val SIGNUP_CODE_INVALID = "인증 코드가 올바르지 않습니다."

        // 비밀번호 재설정 코드
        const val RESET_CODE_RESEND_TOO_SOON = "인증 코드를 너무 많이 요청했습니다. 잠시 후 다시 시도해주세요."
        const val RESET_CODE_SEND_LIMIT_EXCEEDED = "인증 코드를 너무 많이 전송했습니다. 나중에 다시 시도해주세요."
        const val RESET_CODE_NOT_FOUND = "진행 중인 인증 요청이 없습니다. 인증 코드를 다시 요청해주세요."
        const val RESET_CODE_EXPIRED = "인증 코드가 만료되었습니다. 다시 요청해주세요."
        const val RESET_CODE_ATTEMPTS_EXCEEDED = "시도 횟수를 초과했습니다. 인증 코드를 다시 요청해주세요."
        const val RESET_CODE_INVALID = "인증 코드가 올바르지 않습니다."

        // 비밀번호 재설정 토큰
        const val RESET_TOKEN_INVALID = "유효하지 않은 인증 토큰입니다."
        const val RESET_TOKEN_EXPIRED = "만료된 인증 토큰입니다."

        // 리프레시 토큰
        const val INVALID_REFRESH_TOKEN = "유효하지 않은 refresh token입니다."
        const val EXPIRED_REFRESH_TOKEN = "만료된 refresh token입니다."
        const val REVOKED_REFRESH_TOKEN = "취소된 refresh token입니다."

        // 비밀번호 변경·회원 탈퇴
        const val CURRENT_PASSWORD_MISMATCH = "현재 비밀번호가 올바르지 않습니다."
        const val NEW_PASSWORD_SAME_AS_CURRENT = "현재 비밀번호와 다른 비밀번호를 입력해주세요."
        const val CREATOR_CANNOT_DELETE = "크리에이터 계정은 탈퇴할 수 없습니다."

        // 소셜 로그인
        const val UNSUPPORTED_SOCIAL_PROVIDER = "지원하지 않는 로그인 방식입니다."
        const val INVALID_SOCIAL_TOKEN = "소셜 로그인 인증에 실패했습니다. 다시 시도해주세요."
        const val SOCIAL_EMAIL_REQUIRED = "소셜 계정에서 이메일 정보를 가져올 수 없습니다."
        const val SOCIAL_EMAIL_NOT_VERIFIED = "이메일이 확인되지 않은 소셜 계정입니다. 이메일 가입을 이용해주세요."
        const val SOCIAL_ALREADY_REGISTERED = "이미 가입이 완료된 계정입니다. 다시 로그인해주세요."
        const val SOCIAL_ONLY_ACCOUNT = "간편로그인으로 가입된 계정입니다. 소셜 로그인을 이용해주세요."
        const val INVALID_SIGNUP_TOKEN = "가입 세션이 만료되었습니다. 처음부터 다시 시도해주세요."

        // 크리에이터 권한·신청
        const val ALREADY_CREATOR = "이미 크리에이터입니다."
        const val ADMIN_CANNOT_BE_CREATOR = "관리자는 크리에이터 신청을 할 수 없습니다."
        const val PENDING_APPLICATION_EXISTS = "이미 대기 중인 신청이 있습니다."
        const val APPLICATION_ALREADY_PROCESSED = "이미 처리된 신청입니다."
        const val NOT_CREATOR = "크리에이터가 아닙니다."
        const val NO_APPLICATION_FOUND = "신청 내역이 없습니다."
        const val PENDING_APPLICATION_ONLY = "대기 중인 신청만 취소할 수 있습니다."
        const val USERNAME_QUERY_REQUIRED = "사용자명을 입력해주세요."

        object Block {
            const val BLOCK_FAILED = "사용자 차단에 실패했습니다."
            const val UNBLOCK_FAILED = "차단 해제에 실패했습니다."
            const val CANNOT_BLOCK_SELF = "자기 자신을 차단할 수 없습니다."
            const val USER_BLOCKED = "차단된 사용자입니다."
        }
    }

    object Address {
        const val ADDRESS_NOT_FOUND = "배송지를 찾을 수 없습니다."
        const val INVALID_ADDRESS_ID = "유효하지 않은 배송지 ID입니다."
    }

    object Social {
        // 미디어 첨부 공용 문구
        object Media {
            const val ATTACHMENT_LIMIT_EXCEEDED =
                "미디어는 최대 ${Constants.Social.MAX_MEDIA_ATTACHMENTS_PER_POST}개까지 첨부 가능합니다."
            const val INVALID_KEEP_MEDIA_IDS = "유지할 미디어 ID에 잘못된 값이 포함되어 있습니다."
            const val REORDER_SET_MISMATCH = "reorder 값은 keepMediaIds와 동일한 ID 집합이어야 합니다."
            const val INVALID_REORDER_MEDIA_ID = "reorder에 잘못된 미디어 ID가 포함되어 있습니다."
            const val KEEP_MEDIA_NOT_FOUND = "유지 대상 미디어를 찾을 수 없습니다."
        }

        object Post {
            const val POST_NOT_FOUND = "존재하지 않는 포스트입니다."
            const val POST_UNAVAILABLE = "유효하지 않은 게시물입니다."
            const val INVALID_POST_ID = "유효하지 않은 포스트 ID입니다."
            const val POST_UPDATE_PERMISSION_DENIED = "포스트를 수정할 권한이 없습니다."
            const val POST_DELETE_PERMISSION_DENIED = "포스트를 삭제할 권한이 없습니다."
            const val CREATOR_ONLY_CONTENT = "구독자 전용 콘텐츠는 CREATOR만 작성할 수 있습니다."
            const val SUBSCRIPTION_REQUIRED = "구독이 필요한 콘텐츠입니다."
            const val SECRET_POST_CANNOT_SHARE = "비밀글은 공유할 수 없습니다."
            const val AUTHOR_NOT_FOUND = "작성자 정보를 찾을 수 없습니다."
            const val POST_UPDATE_FAILED = "포스트 수정에 실패했습니다."
            const val POST_DELETE_FAILED = "포스트 삭제에 실패했습니다."

            // 검증
            const val SEARCH_CONDITIONS_REQUIRED = "검색 조건을 지정해주세요. (q, hashtag, userId 중 하나)"
            const val HASHTAG_REQUIRED = "해시태그를 입력해주세요."
            const val INVALID_HASHTAG = "유효하지 않은 해시태그입니다."
            const val INVALID_MEDIA_TYPE = "유효하지 않은 미디어 타입입니다. (IMAGE 또는 VIDEO)"
        }

        object Comment {
            const val COMMENT_NOT_FOUND = "존재하지 않는 댓글입니다."
            const val INVALID_COMMENT_ID = "유효하지 않은 댓글 ID입니다."
            const val COMMENT_UPDATE_PERMISSION_DENIED = "댓글을 수정할 권한이 없습니다."
            const val COMMENT_DELETE_PERMISSION_DENIED = "댓글을 삭제할 권한이 없습니다."
            const val PARENT_COMMENT_NOT_FOUND = "존재하지 않는 부모 댓글입니다."
            const val COMMENT_UPDATE_FAILED = "댓글 수정에 실패했습니다."
            const val INVALID_PARENT_COMMENT = "잘못된 부모 댓글입니다."
            const val LOCKED_POST_COMMENT_DENIED = "잠긴 게시물에는 댓글을 남길 수 없습니다."
        }

        object Report {
            const val ALREADY_REPORTED_POST = "이미 신고한 게시물입니다."
            const val ALREADY_REPORTED_COMMENT = "이미 신고한 댓글입니다."
            const val ALREADY_REPORTED_USER = "이미 신고한 사용자입니다."
            const val CANNOT_REPORT_OWN_POST = "자신의 게시물은 신고할 수 없습니다."
            const val CANNOT_REPORT_OWN_COMMENT = "자신의 댓글은 신고할 수 없습니다."
            const val CANNOT_REPORT_SELF = "자기 자신을 신고할 수 없습니다."
            const val INVALID_REPORT_TARGET = "신고할 수 없는 대상입니다."
            const val USER_CANNOT_BE_BLINDED = "사용자는 블라인드 대상이 아닙니다."
            const val NOT_BLINDED = "블라인드 상태가 아닙니다."
        }

        object Follow {
            const val CANNOT_FOLLOW_SELF = "자기 자신을 팔로우할 수 없습니다."
            const val ALREADY_FOLLOWING = "이미 팔로우 중인 사용자입니다."
            const val NOT_FOLLOWING = "팔로우 관계가 존재하지 않습니다."
            const val FOLLOWER_NOT_FOUND = "팔로워를 찾을 수 없습니다."
            const val FOLLOW_FAILED = "팔로우에 실패했습니다."
            const val UNFOLLOW_FAILED = "언팔로우에 실패했습니다."
            const val NOTIFICATION_UPDATE_FAILED = "알림 설정 업데이트에 실패했습니다."
        }

        object Like {
            const val LIKE_FAILED = "좋아요에 실패했습니다."
            const val UNLIKE_FAILED = "좋아요 취소에 실패했습니다."
            const val COMMENT_LIKE_FAILED = "댓글 좋아요에 실패했습니다."
            const val COMMENT_UNLIKE_FAILED = "댓글 좋아요 취소에 실패했습니다."
        }

        object Bookmark {
            const val BOOKMARK_FAILED = "북마크에 실패했습니다."
            const val UNBOOKMARK_FAILED = "북마크 취소에 실패했습니다."
        }

        object HiddenPost {
            const val HIDE_FAILED = "포스트 숨기기에 실패했습니다."
            const val UNHIDE_FAILED = "포스트 숨기기 취소에 실패했습니다."
        }

        object Community {
            const val ONLY_FOLLOWERS_CAN_POST = "팔로워만 커뮤니티에 글을 작성할 수 있습니다."
            const val COMMUNITY_NOT_AVAILABLE = "운영 중인 커뮤니티가 아닙니다."
        }

        object Tag {
            // 핵심 로직 오류
            const val TAG_NOT_FOUND = "태그를 찾을 수 없습니다."
            const val INVALID_TAG_ID = "유효하지 않은 태그 ID입니다."
            const val TAG_ALREADY_EXISTS = "이미 존재하는 태그명입니다."
            const val ONLY_CREATOR_CAN_MANAGE_TAGS = "크리에이터만 태그를 관리할 수 있습니다."
            const val SECTION_TAG_NOT_IN_SELECTED_TAGS = "섹션 태그는 선택한 태그 중 하나여야 합니다."

            // 검증
            const val TAG_NAME_REQUIRED = "태그명은 필수입니다."
            const val TAG_NAME_TOO_LONG = "태그명은 50자를 초과할 수 없습니다."
            const val TAG_NAME_INVALID = "태그명은 한글, 영문, 숫자만 사용 가능합니다."
            const val TAG_TARGET_TYPE_REQUIRED = "targetType 파라미터는 필수입니다."
            const val TAG_TARGET_TYPE_INVALID = "유효하지 않은 targetType입니다. (POST 또는 PRODUCT)"
        }

        object Pin {
            const val PINNED_POST_LIMIT_EXCEEDED = "고정 포스트는 최대 10개까지만 가능합니다."
            const val PINNED_COMMUNITY_LIMIT_EXCEEDED = "고정 공지는 최대 5개까지만 가능합니다."
            const val ONLY_CREATOR_CAN_PIN = "크리에이터만 포스트를 고정할 수 있습니다."
            const val SECRET_POST_CANNOT_PIN = "비밀글은 공지로 고정할 수 없습니다."
            const val PIN_ERROR = "포스트 고정 중 오류가 발생했습니다."
        }
    }

    object Commerce {
        object Product {
            // 핵심 로직 오류
            const val PRODUCT_NOT_FOUND = "존재하지 않는 상품입니다."
            const val PRODUCT_NOT_FOUND_WITH_ID = "해당 ID의 상품을 찾을 수 없습니다."
            const val PRODUCT_DISCONTINUED = "판매가 중단된 상품입니다."
            const val PRODUCT_SOLD_OUT = "품절된 상품입니다."
            const val PRODUCT_ACCESS_DENIED = "상품에 접근할 권한이 없습니다."
            const val PRODUCT_UPDATE_PERMISSION_DENIED = "상품을 수정할 권한이 없습니다."
            const val PRODUCT_DELETE_PERMISSION_DENIED = "상품을 삭제할 권한이 없습니다."
            const val PRODUCT_REGISTRATION_PERMISSION_DENIED = "상품을 등록할 권한이 없습니다."
            const val PRODUCT_STATUS_UPDATE_PERMISSION_DENIED = "상품 상태를 변경할 권한이 없습니다."
            const val PRODUCT_STATUS_UPDATE_VIA_STATUS_API_ONLY = "상품 상태는 상태 변경 API에서만 수정할 수 있습니다."
            const val CREATOR_NOT_FOUND = "판매자 정보를 찾을 수 없습니다."
            const val PRODUCT_PERMISSION_DENIED = "상품에 접근할 권한이 없습니다."
            const val PRODUCT_UPDATE_FAILED = "상품 수정에 실패했습니다."
            const val PRODUCT_DELETE_FAILED = "상품 삭제에 실패했습니다."
            const val PRODUCT_IMAGE_UPLOAD_PERMISSION_DENIED = "상품 이미지를 업로드할 권한이 없습니다."
            const val PRODUCT_IMAGE_DELETE_PERMISSION_DENIED = "상품 이미지를 삭제할 권한이 없습니다."
            const val PRODUCT_SOLD_OUT_CANNOT_TOGGLE = "품절 상태는 재고 추가로만 변경할 수 있습니다."
            const val PRODUCT_DEAL_PERMISSION_DENIED = "타임딜을 설정할 권한이 없습니다."
            const val INVALID_DEAL_FIELDS = "타임딜 설정에는 딜 가격과 기간이 모두 필요합니다."
            const val INVALID_DEAL_PRICE = "딜 가격은 판매가보다 낮아야 합니다."
            const val INVALID_DEAL_PERIOD = "딜 종료 시각은 시작 시각과 현재 시각보다 뒤여야 합니다."
            const val PRICE_BELOW_ACTIVE_DEAL = "진행 중인 타임딜 가격보다 낮은 판매가로 변경할 수 없습니다. 타임딜을 먼저 해제해주세요."

            // 검증
            const val INVALID_PRODUCT_ID = "유효하지 않은 상품 ID입니다."
            const val INVALID_PRICE_FORMAT = "올바른 가격 형식이 아닙니다."
            const val INVALID_STOCK_QUANTITY = "재고 수량은 0 이상이어야 합니다."
            const val PLAN_CREATE_REQUIRED = "%s 플랜을 먼저 생성해주세요."
            const val PRODUCT_IMAGES_COUNT_EXCEEDED = "상품 이미지는 최대 ${Constants.Commerce.MAX_PRODUCT_IMAGES}개까지 등록할 수 있습니다."
            const val PRODUCT_DETAIL_CONTENT_IMAGES_COUNT_EXCEEDED = "상품 상세 콘텐츠 이미지는 최대 ${Constants.Commerce.MAX_PRODUCT_DETAIL_CONTENT_IMAGES}개까지 등록할 수 있습니다."
            const val PRODUCT_TAGS_COUNT_EXCEEDED = "상품 태그는 최대 ${Constants.Commerce.MAX_PRODUCT_TAGS}개까지 사용할 수 있습니다."
        }

        object Cart {
            const val CART_ITEM_NOT_FOUND = "장바구니 항목을 찾을 수 없습니다."
            const val INVALID_CART_ID = " 유효하지 않은 장바구니 ID입니다."
            const val CART_UPDATE_PERMISSION_DENIED = "장바구니를 수정할 권한이 없습니다."
            const val CART_DELETE_PERMISSION_DENIED = "장바구니 항목을 삭제할 권한이 없습니다."
            const val CART_DELETE_FAILED = "장바구니 삭제에 실패했습니다."
            const val EMPTY_CART = "장바구니가 비어있습니다."
            const val CANNOT_BUY_OWN_PRODUCT = "자신이 판매하는 상품은 장바구니에 추가할 수 없습니다."
        }

        object Wishlist {
            const val WISHLIST_ITEM_NOT_FOUND = "위시리스트에서 상품을 찾을 수 없습니다."
        }

        object Order {
            // 핵심 로직 오류
            const val ORDER_NOT_FOUND = "주문을 찾을 수 없습니다."
            const val INVALID_ORDER_ID = "유효하지 않은 주문 ID입니다."
            const val ORDER_VIEW_PERMISSION_DENIED = "주문을 조회할 권한이 없습니다."
            const val ORDER_CANCEL_PERMISSION_DENIED = "주문을 취소할 권한이 없습니다."
            const val ORDER_UPDATE_PERMISSION_DENIED = "주문을 수정할 권한이 없습니다."
            const val ORDER_CANCEL_NOT_ALLOWED = "이미 배송 중이거나 완료된 주문은 취소할 수 없습니다."
            // 포인트 과다 사용 시에만 발생
            const val INVALID_FINAL_AMOUNT = "사용한 포인트가 결제 금액을 초과합니다. 포인트를 줄여주세요."
            const val CREATOR_PERMISSION_REQUIRED = "판매자 권한이 필요합니다."
            const val ORDER_STATUS_UPDATE_FAILED = "주문 상태 변경에 실패했습니다."

            object Shipping {
                const val ONLY_CREATOR_CAN_UPDATE = "판매자만 배송 정보를 수정할 수 있습니다."
                const val INVALID_CARRIER = "유효하지 않은 택배사입니다."
                const val ALREADY_SHIPPED = "이미 배송이 시작된 주문입니다."
                const val NOT_SHIPPED_YET = "아직 배송이 시작되지 않았습니다."
                const val SHIPPING_INFO_UPDATE_FAILED = "배송 정보 등록에 실패했습니다."
                const val SHIPPING_STATUS_UPDATE_FAILED = "배송 상태 변경에 실패했습니다."
            }
        }

        object Review {
            // 핵심 로직 오류
            const val REVIEW_NOT_FOUND = "리뷰를 찾을 수 없습니다."
            const val INVALID_REVIEW_ID = "유효하지 않은 리뷰 ID입니다."
            const val REVIEW_ALREADY_EXISTS = "이미 작성한 리뷰가 있습니다."
            const val REVIEW_ONLY_OWN_ORDER = "본인의 주문만 리뷰를 작성할 수 있습니다."
            const val REVIEW_ORDER_NOT_DELIVERED = "배송 완료된 주문만 리뷰를 작성할 수 있습니다."
            const val REVIEW_ORDER_CANCELLED = "취소된 주문의 상품은 리뷰를 작성할 수 없습니다."
            const val REVIEW_WRITE_DEADLINE_EXCEEDED = "리뷰 작성 기한(배송완료 후 ${Constants.Commerce.REVIEW_WRITE_DEADLINE_DAYS}일)이 지났습니다."
            const val REVIEW_UPDATE_PERMISSION_DENIED = "리뷰를 수정할 권한이 없습니다."
            const val REVIEW_DELETE_PERMISSION_DENIED = "리뷰를 삭제할 권한이 없습니다."
            const val REVIEW_IMAGE_UPLOAD_PERMISSION_DENIED = "리뷰 이미지를 업로드할 권한이 없습니다."
            const val REVIEW_IMAGE_DELETE_PERMISSION_DENIED = "리뷰 이미지를 삭제할 권한이 없습니다."
            const val REVIEW_PRODUCT_NOT_IN_ORDER = "주문 내역에 없는 상품의 리뷰는 작성할 수 없습니다."
            const val REVIEW_UPDATE_FAILED = "리뷰 업데이트에 실패했습니다."
            const val REVIEW_DELETE_FAILED = "리뷰 삭제에 실패했습니다."

            // 검증
            const val REVIEW_IMAGES_COUNT_EXCEEDED = "리뷰 이미지는 최대 ${Constants.Commerce.MAX_REVIEW_IMAGES}개까지 등록할 수 있습니다."
            const val REVIEW_RATING_INVALID = "별점은 ${Constants.Commerce.MIN_REVIEW_RATING}점에서 ${Constants.Commerce.MAX_REVIEW_RATING}점 사이여야 합니다."
        }

        object Payment {
            const val PAYMENT_PERMISSION_DENIED = "결제할 권한이 없습니다."
            const val ORDER_ALREADY_PAID = "이미 결제가 완료된 주문입니다."
            const val ORDER_CANCELLED_PAYMENT = "취소된 주문은 결제할 수 없습니다."
            const val REFUND_PERMISSION_DENIED = "환불할 권한이 없습니다."
            const val REFUND_ONLY_PAID_ORDERS = "결제가 완료된 주문만 환불할 수 있습니다."
            const val PAYMENT_STATUS_PERMISSION_DENIED = "결제 상태를 조회할 권한이 없습니다."
            const val PAYMENT_NOT_FOUND = "결제 정보를 찾을 수 없습니다."
            const val PAYMENT_AMOUNT_MISMATCH = "결제 금액이 일치하지 않습니다."
            const val PAYMENT_NOT_PREPARED = "결제 준비 정보가 없습니다. 다시 시도해주세요."
            const val TOSS_CONFIRM_FAILED = "토스 결제 승인에 실패했습니다."
            const val TOSS_ORDER_MISMATCH = "결제 주문 정보가 일치하지 않습니다."
        }
    }

    object Chat {
        const val CHAT_ROOM_NOT_FOUND = "존재하지 않는 채팅방입니다."
        const val INVALID_ROOM_ID = "유효하지 않은 채팅방 ID입니다."
        const val CHAT_PERMISSION_DENIED = "채팅방에 접근할 권한이 없습니다."
        const val CANNOT_CHAT_WITH_SELF = "자기 자신과는 채팅할 수 없습니다."
        const val MESSAGE_NOT_FOUND = "존재하지 않는 메시지입니다."
        const val INVALID_MESSAGE_ID = "유효하지 않은 메시지 ID입니다."
        const val MESSAGE_DELETE_PERMISSION_DENIED = "메시지를 삭제할 권한이 없습니다."
    }

    object Notification {
        const val INVALID_NOTIFICATION_ID = "유효하지 않은 알림 ID입니다."
        const val NOTIFICATION_NOT_FOUND_OR_NO_PERMISSION = "알림을 찾을 수 없거나 권한이 없습니다."
        const val NO_UNREAD_NOTIFICATIONS = "읽지 않은 알림이 없습니다."
        const val BUYER_NOT_FOUND = "구매자를 찾을 수 없습니다."
        const val REVIEWER_NOT_FOUND = "리뷰 작성자를 찾을 수 없습니다."
        const val SENDER_NOT_FOUND = "발신자를 찾을 수 없습니다."
    }

    object Subscription {
        // 플랜 핵심 로직 오류
        const val INVALID_PLAN_ID = "유효하지 않은 플랜 ID입니다."
        const val PLAN_NOT_ACTIVE = "비활성화된 구독 플랜입니다."
        const val PLAN_UPDATE_PERMISSION_DENIED = "구독 플랜을 수정할 권한이 없습니다."
        const val PLAN_DEACTIVATE_PERMISSION_DENIED = "구독 플랜을 비활성화할 권한이 없습니다."
        const val SUBSCRIBERS_VIEW_PERMISSION_DENIED = "구독자 목록을 조회할 권한이 없습니다."
        const val ACTIVE_SUBSCRIPTIONS_EXIST = "활성 구독이 있어 구독 플랜을 비활성화할 수 없습니다."
        const val PLAN_UPDATE_FAILED = "구독 플랜 업데이트에 실패했습니다."
        const val PLAN_DEACTIVATE_FAILED = "구독 플랜 비활성화에 실패했습니다."

        // 구독 핵심 로직 오류
        const val INVALID_SUBSCRIPTION_ID = "유효하지 않은 구독 ID입니다."
        const val CANNOT_SUBSCRIBE_TO_SELF = "자신의 구독 플랜을 구독할 수 없습니다."
        const val SUBSCRIPTION_CANCEL_PERMISSION_DENIED = "구독을 취소할 권한이 없습니다."
        const val SUBSCRIPTION_ALREADY_CANCELLED = "이미 취소되었거나 만료된 구독입니다."
        const val SUBSCRIPTION_UPDATE_PERMISSION_DENIED = "구독 설정을 변경할 권한이 없습니다."
        const val SUBSCRIPTION_ONLY_ACTIVE_AUTO_RENEW = "활성 구독만 자동 갱신 설정을 변경할 수 있습니다."
        const val SUBSCRIPTION_AUTO_RENEW_UPDATE_FAILED = "자동 갱신 설정 변경에 실패했습니다."
        const val SUBSCRIPTION_AUTO_RENEW_PARAM_REQUIRED = "자동 갱신 설정 값이 필요합니다."

        // 검증
        const val FREE_TIER_CANNOT_CREATE = "FREE 티어 플랜은 생성할 수 없습니다. TIER1, TIER2만 생성 가능합니다."
        const val PLAN_PRICE_RANGE_INVALID = "가격은 ${Constants.Subscription.MIN_PLAN_PRICE}원 이상 ${Constants.Subscription.MAX_PLAN_PRICE}원 이하여야 합니다."
        const val PAID_PLAN_PRICE_INVALID = "유료 구독 플랜은 0원보다 큰 가격이어야 합니다."
        const val CANNOT_CHANGE_TO_FREE_TIER = "FREE 티어로 변경할 수 없습니다."
        const val SAME_TIER_PLAN_EXISTS = "이미 같은 등급의 활성화된 플랜이 존재합니다."
    }

    object Search {
        const val SEARCH_HISTORY_NOT_FOUND = "존재하지 않는 검색 기록입니다."
        const val SEARCH_HISTORY_DELETE_PERMISSION_DENIED = "검색 기록을 삭제할 권한이 없습니다."
        const val SEARCH_HISTORY_DELETE_FAILED = "검색 기록 삭제에 실패했습니다."
        const val SEARCH_HISTORY_CLEAR_FAILED = "검색 기록 전체 삭제에 실패했습니다."

        // 검증
        const val INVALID_SEARCH_HISTORY_ID = "유효하지 않은 검색 기록 ID입니다."
        const val SEARCH_QUERY_REQUIRED = "검색어를 입력해주세요."
    }

    object Point {
        const val POINT_ACCOUNT_NOT_FOUND = "포인트 계정을 찾을 수 없습니다."
        const val INSUFFICIENT_POINTS = "포인트 잔액이 부족합니다."
    }

    object Coupon {
        // 핵심 로직 오류
        const val COUPON_NOT_FOUND = "쿠폰을 찾을 수 없습니다."
        const val INVALID_COUPON_ID = "유효하지 않은 쿠폰 ID입니다."
        const val COUPON_CODE_DUPLICATE = "이미 존재하는 쿠폰 코드입니다."
        const val COUPON_UPDATE_PERMISSION_DENIED = "쿠폰을 수정할 권한이 없습니다."
        const val COUPON_UPDATE_FAILED = "쿠폰 수정에 실패했습니다."
        const val COUPON_DELETE_PERMISSION_DENIED = "쿠폰을 삭제할 권한이 없습니다."
        const val COUPON_DELETE_FAILED = "쿠폰 삭제에 실패했습니다."
        const val USER_COUPON_NOT_FOUND = "사용자의 쿠폰을 찾을 수 없습니다."

        // 검증
        const val COUPON_EXPIRED = "만료된 쿠폰입니다."
        const val COUPON_ALREADY_CLAIMED = "이미 발급받은 쿠폰입니다."
        const val COUPON_ALREADY_USED = "이미 사용한 쿠폰입니다."
        const val COUPON_DISABLED = "비활성화된 쿠폰입니다."
        const val COUPON_OUT_OF_STOCK = "발급 수량이 소진된 쿠폰입니다."
        const val COUPON_NOT_USABLE = "사용할 수 없는 쿠폰입니다."
        const val COUPON_NOT_APPLICABLE = "이 상품에는 적용할 수 없는 쿠폰입니다."
        const val COUPON_MAX_USE_EXCEEDED = "최대 사용 횟수를 초과한 쿠폰입니다."
        const val COUPON_MIN_ORDER_NOT_MET = "최소 주문 금액 조건을 충족하지 못했습니다."
        const val COUPON_DISCOUNT_RATE_EXCEEDED = "할인율은 100%를 초과할 수 없습니다."
        const val COUPON_CODE_FORMAT_INVALID = "쿠폰 코드는 영문 대문자와 숫자만 사용 가능합니다."
        const val COUPON_CODE_INVALID = "유효하지 않은 쿠폰 코드입니다."
        const val FREE_SHIPPING_COUPON_NOT_ALLOWED_FOR_SUBSCRIBERS = "멤버십 구독 중에는 이미 무료배송이 적용됩니다."
    }
}
