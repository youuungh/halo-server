package com.ninezero.features.social.presentation.models.response

import com.ninezero.core.common.util.PaginatedResponse
import com.ninezero.features.user.presentation.models.response.UserSummaryResponse

typealias FollowListResponse = PaginatedResponse<UserSummaryResponse>
