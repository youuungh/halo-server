package com.ninezero.helper

import com.ninezero.core.common.util.query
import com.ninezero.core.database.entities.chat.ChatRoomTable
import com.ninezero.core.database.entities.chat.MessageTable
import com.ninezero.core.database.entities.commerce.*
import com.ninezero.core.database.entities.coupon.CouponTable
import com.ninezero.core.database.entities.coupon.UserCouponTable
import com.ninezero.core.database.entities.notification.NotificationTable
import com.ninezero.core.database.entities.notification.NotificationPreferenceTable
import com.ninezero.core.database.entities.point.PointHistoryTable
import com.ninezero.core.database.entities.point.PointTable
import com.ninezero.core.database.entities.search.SearchHistoryTable
import com.ninezero.core.database.entities.social.BookmarkTable
import com.ninezero.core.database.entities.social.CommentTable
import com.ninezero.core.database.entities.social.CommentMediaTable
import com.ninezero.core.database.entities.social.FollowTable
import com.ninezero.core.database.entities.social.HiddenPostTable
import com.ninezero.core.database.entities.social.LikeTable
import com.ninezero.core.database.entities.social.PostTable
import com.ninezero.core.database.entities.social.PostMediaTable
import com.ninezero.core.database.entities.social.ReportTable
import com.ninezero.core.database.entities.user.BlockedUserTable
import com.ninezero.core.database.entities.subscription.SubscriptionPlanTable
import com.ninezero.core.database.entities.subscription.SubscriptionTable
import com.ninezero.core.database.entities.tag.PostTagTable
import com.ninezero.core.database.entities.tag.ProductTagTable
import com.ninezero.core.database.entities.tag.TagTable
import com.ninezero.core.database.entities.user.CreatorApplicationTable
import com.ninezero.core.database.entities.user.DeviceTokenTable
import com.ninezero.core.database.entities.user.PendingSignupTable
import com.ninezero.core.database.entities.user.RefreshTokenTable
import com.ninezero.core.database.entities.user.SocialAccountTable
import com.ninezero.core.database.entities.user.UserAddressTable
import com.ninezero.core.database.entities.user.UserProfileTable
import com.ninezero.core.database.entities.user.UserSessionTable
import com.ninezero.core.database.entities.user.UserTable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.deleteAll

/**
 * 테스트용 인메모리 H2 데이터베이스
 */
object TestDatabase {

    private var database: Database? = null
    private val dbLock = Mutex()

    /**
     * 테스트 DB 초기화
     */
    suspend fun init() {
        dbLock.withLock {
            if (database == null) {
                database = Database.connect(
                    url = "jdbc:h2:mem:test;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
                    driver = "org.h2.Driver"
                )
                TransactionManager.defaultDatabase = database

                query {
                    SchemaUtils.create(
                        // User
                        UserTable,
                        UserProfileTable,
                        UserSessionTable,
                        RefreshTokenTable,
                        CreatorApplicationTable,
                        BlockedUserTable,
                        SocialAccountTable,
                        UserAddressTable,
                        DeviceTokenTable,
                        PendingSignupTable,

                        // Social
                        PostTable,
                        PostMediaTable,
                        CommentTable,
                        CommentMediaTable,
                        LikeTable,
                        BookmarkTable,
                        HiddenPostTable,
                        FollowTable,
                        ReportTable,

                        // Tag System
                        TagTable,
                        PostTagTable,
                        ProductTagTable,

                        // Commerce
                        ProductTable,
                        CartTable,
                        OrderTable,
                        OrderItemTable,
                        OrderShipmentTable,
                        PaymentTable,
                        PaymentCustomerTable,
                        BillingKeyTable,
                        ReviewTable,
                        WishlistTable,

                        // Chat
                        ChatRoomTable,
                        MessageTable,

                        // Notification
                        NotificationTable,
                        NotificationPreferenceTable,

                        // Point
                        PointTable,
                        PointHistoryTable,

                        // Coupon
                        CouponTable,
                        UserCouponTable,

                        // Search
                        SearchHistoryTable,

                        // Subscription
                        SubscriptionTable,
                        SubscriptionPlanTable
                    )
                }
            }
        }
    }

    /**
     * 테스트 후 DB 정리
     */
    suspend fun cleanup() {
        dbLock.withLock {
            if (database == null) return

            query {
                SchemaUtils.drop(
                    // 역순으로 삭제
                    PendingSignupTable,
                    ReportTable,
                    DeviceTokenTable,
                    UserAddressTable,
                    SocialAccountTable,
                    SubscriptionPlanTable,
                    SubscriptionTable,
                    SearchHistoryTable,
                    UserCouponTable,
                    CouponTable,
                    PointHistoryTable,
                    PointTable,
                    NotificationPreferenceTable,
                    NotificationTable,
                    MessageTable,
                    ChatRoomTable,
                    WishlistTable,
                    ReviewTable,
                    BillingKeyTable,
                    PaymentCustomerTable,
                    PaymentTable,
                    OrderShipmentTable,
                    OrderItemTable,
                    OrderTable,
                    CartTable,
                    ProductTagTable,
                    ProductTable,
                    PostTagTable,
                    TagTable,
                    FollowTable,
                    HiddenPostTable,
                    BookmarkTable,
                    LikeTable,
                    CommentMediaTable,
                    CommentTable,
                    PostMediaTable,
                    PostTable,
                    BlockedUserTable,
                    CreatorApplicationTable,
                    RefreshTokenTable,
                    UserSessionTable,
                    UserProfileTable,
                    UserTable
                )
            }

            database = null
            TransactionManager.defaultDatabase = null
        }
    }

    /**
     * 모든 테이블 데이터 삭제
     */
    suspend fun clearAll() {
        dbLock.withLock {
            if (database == null) return

            query {
                // Chat (외래키로 ProductTable 참조하므로 먼저 삭제)
                MessageTable.deleteAll()
                ChatRoomTable.deleteAll()

                // Commerce
                ReviewTable.deleteAll()       // OrderTable를 참조하므로 먼저 삭제
                OrderShipmentTable.deleteAll()
                OrderItemTable.deleteAll()
                BillingKeyTable.deleteAll()
                PaymentCustomerTable.deleteAll()
                PaymentTable.deleteAll()
                OrderTable.deleteAll()
                CartTable.deleteAll()
                WishlistTable.deleteAll()
                ProductTagTable.deleteAll()
                ProductTable.deleteAll()

                // Social & TagTable
                PostTagTable.deleteAll()
                CommentMediaTable.deleteAll()
                CommentTable.deleteAll()
                PostMediaTable.deleteAll()
                LikeTable.deleteAll()
                BookmarkTable.deleteAll()
                ReportTable.deleteAll()
                HiddenPostTable.deleteAll()
                PostTable.deleteAll()
                TagTable.deleteAll()
                FollowTable.deleteAll()

                // Notification
                NotificationPreferenceTable.deleteAll()
                NotificationTable.deleteAll()

                // Point
                PointHistoryTable.deleteAll()
                PointTable.deleteAll()

                // Coupon
                UserCouponTable.deleteAll()
                CouponTable.deleteAll()

                // Search
                SearchHistoryTable.deleteAll()

                // Subscription
                SubscriptionTable.deleteAll()
                SubscriptionPlanTable.deleteAll()

                // User
                PendingSignupTable.deleteAll()
                DeviceTokenTable.deleteAll()
                UserAddressTable.deleteAll()
                SocialAccountTable.deleteAll()
                BlockedUserTable.deleteAll()
                CreatorApplicationTable.deleteAll()
                RefreshTokenTable.deleteAll()
                UserSessionTable.deleteAll()
                UserProfileTable.deleteAll()
                UserTable.deleteAll()
            }
        }
    }
}

