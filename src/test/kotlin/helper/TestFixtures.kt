package com.ninezero.helper

import com.ninezero.core.common.config.*
import com.ninezero.core.common.util.query
import com.ninezero.core.database.entities.commerce.*
import com.ninezero.core.database.entities.social.CommentDao
import com.ninezero.core.database.entities.social.FollowDao
import com.ninezero.core.database.entities.social.LikeDao
import com.ninezero.core.database.entities.social.PostDao
import com.ninezero.core.database.entities.upload.MediaDraftDao
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
import com.ninezero.features.upload.data.MediaDraftRepositoryImpl
import com.ninezero.features.user.data.AddressRepositoryImpl
import com.ninezero.features.user.data.BlockedUserRepositoryImpl
import com.ninezero.features.user.data.CreatorApplicationRepositoryImpl
import com.ninezero.features.user.domain.AccountCleanupService
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import java.math.BigDecimal
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/** 테스트용 더미 데이터 생성 */
object TestFixtures {

    // ===== UserDao =====

    /** 테스트 사용자 생성 */
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
            }
        }
    }

    /** 여러 테스트 사용자 생성 */
    suspend fun createTestUsers(count: Int = 3): List<UserDao> {
        return (1..count).map { i ->
            createTestUser(
                email = "user$i@example.com",
                username = "user$i"
            )
        }
    }

    /** 크리에이터 사용자 생성 */
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

    /** 관리자 사용자 생성 */
    suspend fun createTestAdmin(): UserDao {
        return createTestUser(
            email = "admin@example.com",
            username = "admin",
            role = UserRole.ADMIN
        )
    }

    // ===== PostDao =====

    /** 테스트 게시글 생성 */
    suspend fun createTestPost(
        userId: Int,
        creatorId: Int? = null,
        content: String = "테스트 게시글 내용입니다",
        contextType: PostContextType = PostContextType.GENERAL,
        tags: String? = null,
        isActive: Boolean = true,
        requiredTier: SubscriptionPlanTier = SubscriptionPlanTier.FREE
    ): PostDao {
        return query {
            PostDao.new {
                this.userId = userId
                this.creatorId = creatorId
                this.content = content
                this.contextType = contextType
                this.tags = tags
                this.isActive = isActive
                this.requiredTier = requiredTier
            }
        }
    }

    /** 여러 테스트 게시글 생성 */
    suspend fun createTestPosts(userId: Int, count: Int = 3): List<PostDao> {
        return (1..count).map { i ->
            createTestPost(
                userId = userId,
                content = "테스트 게시글 $i"
            )
        }
    }

    // ===== CommentDao =====

    /** 테스트 댓글 생성 */
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
            }
        }
    }

    // ===== MediaDraftDao =====

    /** 테스트 미디어 초안 생성 */
    suspend fun createTestMediaDraft(
        userId: Int,
        targetType: MediaDraftTargetType = MediaDraftTargetType.POST,
        type: MediaType = MediaType.IMAGE,
        url: String = "https://test.supabase.co/storage/v1/object/public/posts/users/$userId/media_${System.nanoTime()}.jpg"
    ): MediaDraftDao {
        return query {
            MediaDraftDao.new {
                this.userId = userId
                this.targetType = targetType
                this.type = type
                this.url = url
                this.width = 100
                this.height = 100
                this.thumbnailUrl = url
                this.expiresAt = (Clock.System.now() + 1.days).toLocalDateTime(TimeZone.UTC)
            }
        }
    }

    suspend fun createTestFollow(
        followerId: Int,
        followingId: Int
    ): FollowDao {
        return query {
            FollowDao.new {
                this.followerId = followerId
                this.followingId = followingId
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
            }
        }
    }

    // ===== ProductDao =====

    /** 테스트 상품 생성 */
    suspend fun createTestProduct(
        creatorId: Int,
        name: String = "테스트 상품",
        price: BigDecimal = BigDecimal(10000),
        originalPrice: BigDecimal? = BigDecimal(15000),
        stock: Int = 100,
        imageUrls: String? = null,
        status: ProductStatus = ProductStatus.ACTIVE,
        isActive: Boolean = true,
        requiredTier: SubscriptionPlanTier = SubscriptionPlanTier.FREE
    ): ProductDao {
        return query {
            ProductDao.new {
                this.creatorId = creatorId
                this.name = name
                this.description = "테스트용 상품입니다"
                this.price = price
                this.originalPrice = originalPrice
                this.stock = stock
                this.brandName = "테스트 브랜드"
                this.imageUrls = imageUrls
                this.status = status
                this.isActive = isActive
                this.requiredTier = requiredTier
            }
        }
    }

    /** 여러 테스트 상품 생성 */
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

    /** 장바구니에 상품 추가 */
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

    private val orderSequence = AtomicInteger()  // 같은 밀리초에 연달아 만들어도 주문번호가 겹치지 않게

    /** 테스트 주문 생성 */
    suspend fun createTestOrder(
        userId: Int,
        productId: Int,
        totalPrice: BigDecimal = BigDecimal("10000"),
        paymentStatus: PaymentStatus = PaymentStatus.PENDING,
        paymentProvider: PaymentProvider = PaymentProvider.MOCK,
        paymentKey: String? = null,
        orderNumber: String = "ORD${System.currentTimeMillis()}-${orderSequence.incrementAndGet()}"
    ): OrderDao {
        return query {
            val order = OrderDao.new {
                this.userId = userId
                this.orderNumber = orderNumber
                this.totalPrice = totalPrice
                this.shippingAddress = "서울시 강남구 테스트로 123"
                this.shippingPhone = "01012345678"
                this.shippingName = "테스트 수령인"
            }

            OrderItemDao.new {
                this.orderId = order.id.value
                this.productId = productId
                this.quantity = 1
                this.price = totalPrice
            }

            val product = ProductDao.findById(productId)
            if (product != null) {
                OrderShipmentDao.new {
                    this.orderId = order.id.value
                    this.creatorId = product.creatorId
                    this.shippingFee = Constants.Commerce.DEFAULT_SHIPPING_FEE
                }
            }

            PaymentDao.new {
                this.orderId = order.id.value
                this.provider = paymentProvider
                this.amount = totalPrice
                this.status = paymentStatus
                this.paymentKey = paymentKey
                this.transactionId = orderNumber
            }

            order
        }
    }

    // ===== AccountCleanupService =====

    /** 탈퇴 정리 */
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
        pointHistoryRepository = PointHistoryRepositoryImpl(),
        mediaDraftRepository = MediaDraftRepositoryImpl()
    )

    // ===== 시각 보조 =====

    fun LocalDateTime.plusDays(days: Int): LocalDateTime =
        (toInstant(TimeZone.UTC) + days.days).toLocalDateTime(TimeZone.UTC)

    fun LocalDateTime.minusDays(days: Int): LocalDateTime =
        (toInstant(TimeZone.UTC) - days.days).toLocalDateTime(TimeZone.UTC)

    /** DB가 초 미만을 반올림해도 바로 뒤 요청 시각보다 늦어지지 않는 현재 시각 */
    fun nowTruncatedToSeconds(): LocalDateTime =
        Instant.fromEpochSeconds(Clock.System.now().epochSeconds).toLocalDateTime(TimeZone.UTC)
}
