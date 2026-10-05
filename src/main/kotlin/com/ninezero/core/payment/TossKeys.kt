package com.ninezero.core.payment

/** 토스 취소·빌링 요청에 쓰는 고정 문자열 */
object TossKeys {

    /** 사용자 입력과 무관한 고정 취소 사유 */
    const val CANCEL_REASON = "주문 취소"

    /** 결제 단위 취소 멱등키 */
    fun cancelKey(paymentId: Int): String = "CANCEL_$paymentId"

    /** 배송그룹 단위 취소 멱등키 */
    fun groupCancelKey(paymentId: Int, creatorId: Int): String = "CANCEL_${paymentId}_G$creatorId"

    /** 주문번호 기반 빌링 orderId */
    fun billingOrderId(orderNumber: String): String = "ORDER_$orderNumber"
}
