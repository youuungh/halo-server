package com.ninezero.features.subscription.presentation.models.response

import com.ninezero.core.common.util.PaginatedResponse
import com.ninezero.features.user.presentation.models.response.UserSummaryResponse

typealias SubscriberListResponse = PaginatedResponse<UserSummaryResponse>
