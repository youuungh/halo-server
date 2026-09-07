package db.migration

import com.ninezero.core.database.entities.chat.ChatRoomTable
import com.ninezero.core.database.entities.chat.MessageTable
import com.ninezero.core.database.entities.commerce.*
import com.ninezero.core.database.entities.coupon.CouponTable
import com.ninezero.core.database.entities.coupon.UserCouponTable
import com.ninezero.core.database.entities.notification.NotificationPreferenceTable
import com.ninezero.core.database.entities.notification.NotificationTable
import com.ninezero.core.database.entities.point.PointHistoryTable
import com.ninezero.core.database.entities.point.PointTable
import com.ninezero.core.database.entities.search.SearchHistoryTable
import com.ninezero.core.database.entities.social.*
import com.ninezero.core.database.entities.subscription.SubscriptionPlanTable
import com.ninezero.core.database.entities.subscription.SubscriptionTable
import com.ninezero.core.database.entities.tag.PostTagTable
import com.ninezero.core.database.entities.tag.ProductTagTable
import com.ninezero.core.database.entities.tag.TagTable
import com.ninezero.core.database.entities.user.*
import org.flywaydb.core.api.migration.BaseJavaMigration
import org.flywaydb.core.api.migration.Context
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction

class V1__initial_schema : BaseJavaMigration() {
    override fun migrate(context: Context?) {
        transaction {
            SchemaUtils.create(
                // 기본 사용자 테이블
                UserTable, UserProfileTable, CreatorApplicationTable, UserSessionTable, RefreshTokenTable, BlockedUserTable, UserAddressTable, DeviceTokenTable, SocialAccountTable, PendingSignupTable,

                // 소셜 기능 테이블
                PostTable, PostMediaTable, CommentTable, CommentMediaTable, LikeTable, BookmarkTable, FollowTable, HiddenPostTable, ReportTable, TagTable, PostTagTable, ProductTagTable,

                // 커머스 기능 테이블
                ProductTable, CartTable, OrderTable, OrderItemTable, OrderShipmentTable, ReviewTable, WishlistTable, PaymentTable, PaymentCustomerTable, BillingKeyTable,

                // 알림 테이블
                NotificationTable, NotificationPreferenceTable,

                // 채팅 테이블
                ChatRoomTable, MessageTable,

                // 구독 테이블
                SubscriptionPlanTable, SubscriptionTable,

                // 검색 테이블
                SearchHistoryTable,

                // 포인트 테이블
                PointTable, PointHistoryTable,

                // 쿠폰 테이블
                CouponTable, UserCouponTable
            )
        }
    }
}
