package com.ninezero.features.user.domain

import com.ninezero.core.common.config.CreatorApplicationStatus
import com.ninezero.features.commerce.data.BillingKeyRepository
import com.ninezero.features.commerce.data.CartRepository
import com.ninezero.features.commerce.data.ProductRepository
import com.ninezero.features.commerce.data.WishlistRepository
import com.ninezero.features.commerce.extractDetailContentImageUrls
import com.ninezero.features.coupon.data.UserCouponRepository
import com.ninezero.features.notification.data.NotificationRepository
import com.ninezero.features.point.data.PointHistoryRepository
import com.ninezero.features.point.data.PointRepository
import com.ninezero.features.search.data.SearchHistoryRepository
import com.ninezero.features.social.data.FollowRepository
import com.ninezero.features.social.data.PostRepository
import com.ninezero.features.subscription.data.SubscriptionRepository
import com.ninezero.features.user.data.AddressRepository
import com.ninezero.features.user.data.BlockedUserRepository
import com.ninezero.features.user.data.CreatorApplicationRepository
import java.math.BigDecimal

data class AccountCleanupResult(
    val deletedProductIds: List<Int>,
    val fileUrlsToDelete: List<String>
)

/** 탈퇴 정리 전담 */
class AccountCleanupService(
    private val subscriptionRepository: SubscriptionRepository,
    private val billingKeyRepository: BillingKeyRepository,
    private val productRepository: ProductRepository,
    private val postRepository: PostRepository,
    private val creatorApplicationRepository: CreatorApplicationRepository,
    private val addressRepository: AddressRepository,
    private val searchHistoryRepository: SearchHistoryRepository,
    private val cartRepository: CartRepository,
    private val userCouponRepository: UserCouponRepository,
    private val notificationRepository: NotificationRepository,
    private val blockedUserRepository: BlockedUserRepository,
    private val wishlistRepository: WishlistRepository,
    private val followRepository: FollowRepository,
    private val pointRepository: PointRepository,
    private val pointHistoryRepository: PointHistoryRepository
) {

    /** 탈퇴 데이터 정리 */
    suspend fun cleanupInTransaction(userId: Int): AccountCleanupResult {
        // 활성 구독 즉시 만료 + 빌링키 폐기
        subscriptionRepository.expireAllActiveByUser(userId)  // 자체 트랜잭션 없어 호출자에 합류
        billingKeyRepository.deactivateAll(userId)

        // 상세 이미지만 수거
        val deletedProducts = productRepository.softDeleteProductsByCreator(userId)
        val detailImageUrls = deletedProducts.flatMap { extractDetailContentImageUrls(it.detailContent) }

        // 파일만 수거
        val hiddenMediaUrls = postRepository.findHiddenCreatorPostMediaUrls(userId)

        // 처리 이력은 보존
        creatorApplicationRepository.findByUserId(userId)?.let { application ->
            if (application.status == CreatorApplicationStatus.PENDING) {
                creatorApplicationRepository.deleteApplication(application.id.value)
            }
        }

        // 개인 데이터
        addressRepository.deleteAllByUser(userId)
        searchHistoryRepository.clearHistories(userId)
        cartRepository.clearUserCart(userId)
        userCouponRepository.deleteAllByUser(userId)
        notificationRepository.deleteAllByUser(userId)
        blockedUserRepository.deleteAllInvolvingUser(userId)
        wishlistRepository.deactivateAllByUser(userId)
        followRepository.deleteAllInvolvingUser(userId)

        // 환불 경로 크래시 방지
        pointRepository.updateBalance(userId, BigDecimal.ZERO)
        pointHistoryRepository.deleteAllByUser(userId)

        return AccountCleanupResult(
            deletedProductIds = deletedProducts.map { it.productId },
            fileUrlsToDelete = detailImageUrls + hiddenMediaUrls
        )
    }
}
