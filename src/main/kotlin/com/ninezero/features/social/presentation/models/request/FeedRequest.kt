package com.ninezero.features.social.presentation.models.request

import com.ninezero.core.common.config.Constants

data class FeedRequest(
    val page: Int = 1,
    val limit: Int = Constants.DEFAULT_PAGE_LIMIT
)
