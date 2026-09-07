package com.ninezero.helper

import com.ninezero.core.common.config.*
import com.ninezero.core.common.util.query
import com.ninezero.core.database.entities.commerce.*
import com.ninezero.core.database.entities.social.CommentDao
import com.ninezero.core.database.entities.social.FollowDao
import com.ninezero.core.database.entities.social.LikeDao
import com.ninezero.core.database.entities.social.PostDao
import com.ninezero.core.database.entities.user.UserDao
import com.ninezero.core.security.PasswordManager
import com.ninezero.features.commerce.data.BillingKeyRepositoryImpl
import com.ninezero.features.commerce.data.CartRepositoryImpl
import com.ninezero.features.commerce.data.ProductRepositoryImpl
import com.ninezero.features.commerce.data.WishlistRepositoryImpl
import com.ninezero.features.coupon.data.UserCouponRepositoryImpl
import com.ninezero.features.notification.data.NotificationRepositoryImpl
import com.ninezero.features.point.data.PointHistoryRepositoryImpl
import com.ninezero.features.point.data.PointRepositoryImpl
import com.ninezero.features.search.data.SearchHistoryRepositoryImpl
import com.ninezero.features.social.data.FollowRepositoryImpl
import com.ninezero.features.social.data.PostRepositoryImpl
import com.ninezero.features.subscription.data.SubscriptionRepositoryImpl
import com.ninezero.features.user.data.AddressRepositoryImpl
import com.ninezero.features.user.data.BlockedUserRepositoryImpl
import com.ninezero.features.user.data.CreatorApplicationRepositoryImpl
import com.ninezero.features.user.domain.AccountCleanupService
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import java.math.BigDecimal
import kotlin.time.Duration

/**
 * 테스트용 더미 데이터 생성
 */
object TestFixtures {

    // ===== UserDao =====

    /**
     * 테스트 사용자 생성
     */
    suspend fun createTestUser(
        email: String = "test@example.com",
        username: String = "testuser",
        password: String = "password123",
        role: UserRole = UserRole.USER
    ): UserDao {
        return query {
            UserDao.new {
                this.email = email
                this.username = username
                this.passwordHash = PasswordManager.hashPassword(password)
                this.role = role
                this.isActive = true
            }
        }
    }

    /**
     * 여러 테스트 사용자 생성
     */
    suspend fun createTestUsers(count: Int = 3): List<UserDao> {
        return (1..count).map { i ->
            createTestUser(
                email = "user$i@example.com",
                username = "user$i"
            )
        }
    }

    /**
     * 크리에이터 사용자 생성
     */
    suspend fun createTestCreator(
        email: String = "creator@example.com",
        username: String = "creator"
    ): UserDao {
        return createTestUser(
            email = email,
            username = username,
            role = UserRole.CREATOR
        )
    }

    /**
     * 관리자 사용자 생성
     */
    suspend fun createTestAdmin(
        email: String = "admin@example.com",
        username: String = "admin"
    ): UserDao {
        return createTestUser(
            email = email,
            username = username,
            role = UserRole.ADMIN
        )
    }

    // ===== PostDao =====

    /**
     * 테스트 게시글 생성
     */
    suspend fun createTestPost(
        userId: Int,
        creatorId: Int? = null,
        content: String = "테스트 게시글 내용입니다",
        contextType: PostContextType = PostContextType.GENERAL,
        postType: PostType = PostType.TEXT,
        status: PostStatus = PostStatus.ACTIVE,
        mediaUrls: String? = null,
        tags: String? = null,
        productId: Int? = null,
        isActive: Boolean = true,
        requiredTier: SubscriptionPlanTier = SubscriptionPlanTier.FREE
    ): PostDao {
        return query {
            PostDao.new {
                this.userId = userId
                this.creatorId = creatorId
                this.content = content
                this.contextType = contextType
                this.postType = postType
                this.status = status
                this.mediaAttachments = mediaUrls
                this.tags = tags
                this.productId = productId
                this.isActive = isActive
                this.likeCount = 0
                this.commentCount = 0
                this.viewCount = 0
                this.isPinnedInFeed = false
                this.isPinnedInCommunity = false
                this.pinnedOrderInFeed = null
                this.pinnedOrderInCommunity = null
                this.requiredTier = requiredTier
            }
        }
    }

    /**
     * 여러 테스트 게시글 생성
     */
    suspend fun createTestPosts(userId: Int, count: Int = 3): List<PostDao> {
        return (1..count).map { i ->
            createTestPost(
                userId = userId,
                content = "테스트 게시글 $i"
            )
        }
    }

    // ===== CommentDao =====

    /**
     * 테스트 댓글 생성
     */
    suspend fun createTestComment(
        userId: Int,
        postId: Int,
        content: String = "테스트 댓글입니다",
        parentCommentId: Int? = null
    ): CommentDao {
        return query {
            CommentDao.new {
                this.userId = userId
                this.postId = postId
                this.content = content
                this.parentCommentId = parentCommentId
                this.likeCount = 0
                this.replyCount = 0
                this.isActive = true
            }
        }
    }

    // ===== FollowDao =====

    suspend fun createTestFollow(
        followerId: Int,
        followingId: Int
    ): FollowDao {
        return query {
            FollowDao.new {
                this.followerId = followerId
                this.followingId = followingId
                this.isActive = true
            }
        }
    }

// ===== LikeDao =====

    suspend fun createTestLike(
        userId: Int,
        postId: Int
    ): LikeDao {
        return query {
            LikeDao.new {
                this.userId = userId
                this.targetType = LikeType.POST
                this.targetId = postId
                this.isActive = true
            }
        }
    }

    // ===== ProductDao =====

    /**
     * 테스트 상품 생성
     */
    suspend fun createTestProduct(
        creatorId: Int,
        name: String = "테스트 상품",
        description: String = "테스트용 상품입니다",
        price: BigDecimal = BigDecimal(10000),
        originalPrice: BigDecimal? = BigDecimal(15000),
        stock: Int = 100,
        categoryId: Int? = null,
        brandName: String? = "테스트 브랜드",
        imageUrls: String? = null,
        tags: String? = null,
        status: ProductStatus = ProductStatus.ACTIVE,
        isActive: Boolean = true,
        requiredTier: SubscriptionPlanTier = SubscriptionPlanTier.FREE
    ): ProductDao {
        return query {
            ProductDao.new {
                this.creatorId = creatorId
                this.name = name
                this.description = description
                this.price = price
                this.originalPrice = originalPrice
                this.stock = stock
                this.categoryId = categoryId
                this.brandName = brandName
                this.imageUrls = imageUrls
                this.tags = tags
                this.status = status
                this.viewCount = 0
                this.likeCount = 0
                this.salesCount = 0
                this.rating = BigDecimal.ZERO
                this.reviewCount = 0
                this.isActive = isActive
                this.requiredTier = requiredTier
            }
        }
    }

    /**
     * 여러 테스트 상품 생성
     */
    suspend fun createTestProducts(creatorId: Int, count: Int = 3): List<ProductDao> {
        return (1..count).map { i ->
            createTestProduct(
                creatorId = creatorId,
                name = "테스트 상품 $i",
                price = BigDecimal(10000 + i * 5000),
                stock = 50 + i * 10
            )
        }
    }

    // ===== CartDao =====

    /**
     * 장바구니에 상품 추가
     */
    suspend fun addToCart(
        userId: Int,
        productId: Int,
        quantity: Int = 1
    ): CartDao {
        return query {
            CartDao.new {
                this.userId = userId
                this.productId = productId
                this.quantity = quantity
            }
        }
    }

    // ===== OrderDao =====

    /**
     * 테스트 주문 생성 (간소화 버전)
     */
    suspend fun createTestOrder(
        userId: Int,
        productId: Int,
        totalPrice: BigDecimal = BigDecimal("10000"),
        quantity: Int = 1,
        paymentStatus: PaymentStatus = PaymentStatus.PENDING,
        // 기본값은 기존 동작(PG 거래 없는 mock 결제). 토스 취소 경로를 태우려면 둘 다 지정한다.
        paymentProvider: PaymentProvider = PaymentProvider.MOCK,
        paymentKey: String? = null
    ): OrderDao {
        return query {
            val orderNumber = "ORD${System.currentTimeMillis()}"

            // OrderDao 생성
            val order = OrderDao.new {
                this.userId = userId
                this.orderNumber = orderNumber
                this.totalPrice = totalPrice
                this.status = OrderStatus.PENDING
                this.shippingAddress = "서울시 강남구 테스트로 123"
                this.shippingPhone = "01012345678"
                this.shippingName = "테스트 수령인"
                this.memo = null
                this.pointsUsed = BigDecimal.ZERO
                this.couponDiscount = BigDecimal.ZERO
                this.couponCode = null
                this.trackingNumber = null
                this.carrier = null
                this.shippingStatus = ShippingStatus.PREPARING
                this.estimatedDeliveryDate = null
                this.actualDeliveryDate = null
            }

            // OrderItemDao 생성
            OrderItemDao.new {
                this.orderId = order.id.value
                this.productId = productId
                this.quantity = quantity
                this.price = totalPrice
            }

            val product = ProductDao.findById(productId)
            if (product != null) {
                OrderShipmentDao.new {
                    this.orderId = order.id.value
                    this.creatorId = product.creatorId
                    this.shippingFee = Constants.Commerce.DEFAULT_SHIPPING_FEE
                    this.shippingStatus = ShippingStatus.PREPARING
                }
            }

            // PaymentDao 생성
            PaymentDao.new {
                this.orderId = order.id.value
                this.subscriptionId = null
                this.provider = paymentProvider
                this.amount = totalPrice
                this.status = paymentStatus
                this.paymentKey = paymentKey
                this.transactionId = orderNumber
                this.pgProvider = null
                this.receiptUrl = null
                this.refundReason = null
                this.refundAmount = null
                this.refundRequestedAt = null
                this.refundCompletedAt = null
                this.isRenewal = false
            }

            order
        }
    }

    // ===== AccountCleanupService =====

    /** 탈퇴 정리 서비스 — 전부 무상태 real Impl로 구성(AuthService 생성 셋업 공용) */
    fun accountCleanupService(): AccountCleanupService = AccountCleanupService(
        subscriptionRepository = SubscriptionRepositoryImpl(),
        billingKeyRepository = BillingKeyRepositoryImpl(),
        productRepository = ProductRepositoryImpl(),
        postRepository = PostRepositoryImpl(),
        creatorApplicationRepository = CreatorApplicationRepositoryImpl(),
        addressRepository = AddressRepositoryImpl(),
        searchHistoryRepository = SearchHistoryRepositoryImpl(),
        cartRepository = CartRepositoryImpl(),
        userCouponRepository = UserCouponRepositoryImpl(),
        notificationRepository = NotificationRepositoryImpl(),
        blockedUserRepository = BlockedUserRepositoryImpl(),
        wishlistRepository = WishlistRepositoryImpl(),
        followRepository = FollowRepositoryImpl(),
        pointRepository = PointRepositoryImpl(),
        pointHistoryRepository = PointHistoryRepositoryImpl()
    )

    // ===== Helper Extensions =====

    /**
     * LocalDateTime에 일수 더하기
     */
    fun LocalDateTime.plusDays(days: Int): LocalDateTime {
        val instant = this.toInstant(TimeZone.UTC)
        val duration = Duration.parse("${days}d")
        return (instant + duration).toLocalDateTime(TimeZone.UTC)
    }

    fun LocalDateTime.minusDays(days: Int): LocalDateTime {
        val instant = this.toInstant(TimeZone.UTC)
        val duration = Duration.parse("${days}d")
        return (instant - duration).toLocalDateTime(TimeZone.UTC)
    }
}

