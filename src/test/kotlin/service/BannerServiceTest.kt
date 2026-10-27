package com.ninezero.service

import com.ninezero.core.common.config.BannerType
import com.ninezero.features.banner.domain.BannerItem
import com.ninezero.features.banner.domain.BannersData
import com.ninezero.features.banner.domain.visibleOn
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class BannerServiceTest {

    private fun banner(id: Int, from: String? = null, until: String? = null, isActive: Boolean = true) = BannerItem(
        id = id,
        imageUrl = "https://example.com/$id.png",
        title = "배너 $id",
        link = "https://example.com/$id",
        type = BannerType.CREATOR,
        order = id,
        isActive = isActive,
        showFrom = from?.let(LocalDate::parse),
        showUntil = until?.let(LocalDate::parse)
    )

    @Test
    fun `노출 기간은 시작일과 종료일을 포함하고 기간이 없으면 항상 보인다`() {
        val banners = listOf(
            banner(1),
            banner(2, from = "2026-11-01"),
            banner(3, until = "2026-10-31"),
            banner(4, from = "2026-10-24", until = "2026-11-08"),
            banner(5, isActive = false)
        )

        assertEquals(listOf(1, 3, 4), banners.visibleOn(LocalDate.parse("2026-10-31")).map { it.id })
        assertEquals(listOf(1, 2, 4), banners.visibleOn(LocalDate.parse("2026-11-01")).map { it.id })
        assertEquals(listOf(1, 2), banners.visibleOn(LocalDate.parse("2026-11-09")).map { it.id })
    }

    @Test
    fun `배너 파일의 노출 기간은 날짜 문자열로 읽고 없어도 된다`() {
        val data = Json.decodeFromString<BannersData>(
            """
            {"banners": [
              {"id": 1, "imageUrl": "u", "title": "t", "link": "l", "type": "CREATOR", "order": 1, "showFrom": "2026-11-01", "showUntil": "2026-11-15"},
              {"id": 2, "imageUrl": "u", "title": "t", "link": "l", "type": "CREATOR", "order": 2}
            ]}
            """
        )

        assertEquals(LocalDate.parse("2026-11-01"), data.banners[0].showFrom)
        assertEquals(LocalDate.parse("2026-11-15"), data.banners[0].showUntil)
        assertEquals(null, data.banners[1].showFrom)
    }
}
