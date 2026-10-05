package com.ninezero.service

import com.ninezero.core.common.exception.NotFoundException
import com.ninezero.features.user.data.AddressRepositoryImpl
import com.ninezero.features.user.domain.AddressService
import com.ninezero.features.user.presentation.models.request.CreateAddressRequest
import com.ninezero.features.user.presentation.models.request.UpdateAddressRequest
import com.ninezero.helper.TestDatabase
import com.ninezero.helper.TestFixtures
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.*
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AddressServiceTest {

    private lateinit var addressService: AddressService
    private lateinit var addressRepository: AddressRepositoryImpl

    @BeforeAll
    fun setup() {
        runBlocking {
            TestDatabase.init()

            addressRepository = AddressRepositoryImpl()
            addressService = AddressService(addressRepository = addressRepository)
        }
    }

    @BeforeEach
    fun beforeEach() {
        runBlocking {
            TestDatabase.clearAll()
        }
    }

    @AfterAll
    fun tearDown() {
        runBlocking {
            TestDatabase.cleanup()
        }
    }

    private fun createRequest(
        recipientName: String = "수령인",
        isDefault: Boolean = false
    ) = CreateAddressRequest(
        recipientName = recipientName,
        recipientPhone = "01012345678",
        zipCode = "06236",
        address = "서울시 강남구 테스트로 123",
        addressDetail = "101호",
        memo = null,
        isDefault = isDefault
    )

    private fun updateRequest(
        recipientName: String = "수령인",
        isDefault: Boolean = false
    ) = UpdateAddressRequest(
        recipientName = recipientName,
        recipientPhone = "01012345678",
        zipCode = "06236",
        address = "서울시 강남구 테스트로 123",
        addressDetail = "101호",
        memo = null,
        isDefault = isDefault
    )

    // ===== 기본 배송지 =====

    @Test
    fun `기본 배송지를 새로 등록하면 기존 기본이 해제된다`() {
        runBlocking {
            val user = TestFixtures.createTestUser()
            val first = addressService.createAddress(user.id.value, createRequest("첫 배송지", isDefault = true))
            val second = addressService.createAddress(user.id.value, createRequest("두 번째 배송지", isDefault = true))

            val addresses = addressService.findAddresses(user.id.value)

            assertEquals(2, addresses.size)
            assertFalse(addresses.first { it.id == first.id }.isDefault)
            assertTrue(addresses.first { it.id == second.id }.isDefault)
        }
    }

    @Test
    fun `기본이 아닌 배송지를 등록하면 기존 기본은 유지된다`() {
        runBlocking {
            val user = TestFixtures.createTestUser()
            val first = addressService.createAddress(user.id.value, createRequest("첫 배송지", isDefault = true))
            addressService.createAddress(user.id.value, createRequest("두 번째 배송지", isDefault = false))

            val addresses = addressService.findAddresses(user.id.value)

            assertEquals(1, addresses.count { it.isDefault })
            assertTrue(addresses.first { it.id == first.id }.isDefault)
        }
    }

    @Test
    fun `수정으로 기본 배송지를 옮겨도 기본은 하나만 남는다`() {
        runBlocking {
            val user = TestFixtures.createTestUser()
            val first = addressService.createAddress(user.id.value, createRequest("첫 배송지", isDefault = true))
            val second = addressService.createAddress(user.id.value, createRequest("두 번째 배송지", isDefault = false))

            addressService.updateAddress(second.id, user.id.value, updateRequest("두 번째 배송지", isDefault = true))

            val addresses = addressService.findAddresses(user.id.value)

            assertFalse(addresses.first { it.id == first.id }.isDefault)
            assertTrue(addresses.first { it.id == second.id }.isDefault)
        }
    }

    @Test
    fun `없는 배송지를 기본으로 수정하려다 실패하면 기존 기본이 그대로다`() {
        runBlocking {
            val user = TestFixtures.createTestUser()
            val existing = addressService.createAddress(user.id.value, createRequest("첫 배송지", isDefault = true))

            assertFailsWith<NotFoundException> {
                addressService.updateAddress(existing.id + 999, user.id.value, updateRequest(isDefault = true))
            }

            // 기본 해제가 먼저 실행되지만 트랜잭션이 되돌린다
            assertTrue(addressService.findAddress(existing.id, user.id.value).isDefault)
        }
    }

    // ===== 소유자 검증 =====

    @Test
    fun `남의 배송지는 조회할 수 없다`() {
        runBlocking {
            val owner = TestFixtures.createTestUser(email = "owner@example.com", username = "owner")
            val other = TestFixtures.createTestUser(email = "other@example.com", username = "other")
            val address = addressService.createAddress(owner.id.value, createRequest())

            assertFailsWith<NotFoundException> {
                addressService.findAddress(address.id, other.id.value)
            }
        }
    }

    @Test
    fun `남의 배송지는 수정할 수 없고 값도 그대로다`() {
        runBlocking {
            val owner = TestFixtures.createTestUser(email = "owner@example.com", username = "owner")
            val other = TestFixtures.createTestUser(email = "other@example.com", username = "other")
            val address = addressService.createAddress(owner.id.value, createRequest("원래 수령인"))

            assertFailsWith<NotFoundException> {
                addressService.updateAddress(address.id, other.id.value, updateRequest("바뀐 수령인"))
            }

            assertEquals("원래 수령인", addressService.findAddress(address.id, owner.id.value).recipientName)
        }
    }

    @Test
    fun `남의 배송지는 삭제할 수 없고 목록에 남는다`() {
        runBlocking {
            val owner = TestFixtures.createTestUser(email = "owner@example.com", username = "owner")
            val other = TestFixtures.createTestUser(email = "other@example.com", username = "other")
            val address = addressService.createAddress(owner.id.value, createRequest())

            assertFailsWith<NotFoundException> {
                addressService.deleteAddress(address.id, other.id.value)
            }

            assertEquals(1, addressService.findAddresses(owner.id.value).size)
        }
    }

    @Test
    fun `배송지 목록은 본인 것만 반환한다`() {
        runBlocking {
            val owner = TestFixtures.createTestUser(email = "owner@example.com", username = "owner")
            val other = TestFixtures.createTestUser(email = "other@example.com", username = "other")
            addressService.createAddress(owner.id.value, createRequest("내 배송지"))
            addressService.createAddress(other.id.value, createRequest("남의 배송지"))

            val addresses = addressService.findAddresses(owner.id.value)

            assertEquals(1, addresses.size)
            assertEquals("내 배송지", addresses.first().recipientName)
        }
    }

    // ===== 없는 배송지 =====

    @Test
    fun `없는 배송지를 조회·수정·삭제하면 실패한다`() {
        runBlocking {
            val user = TestFixtures.createTestUser()

            assertFailsWith<NotFoundException> { addressService.findAddress(99_999, user.id.value) }
            assertFailsWith<NotFoundException> { addressService.updateAddress(99_999, user.id.value, updateRequest()) }
            assertFailsWith<NotFoundException> { addressService.deleteAddress(99_999, user.id.value) }
        }
    }

    @Test
    fun `삭제한 배송지는 목록에서 빠진다`() {
        runBlocking {
            val user = TestFixtures.createTestUser()
            val address = addressService.createAddress(user.id.value, createRequest())

            addressService.deleteAddress(address.id, user.id.value)

            assertTrue(addressService.findAddresses(user.id.value).isEmpty())
        }
    }
}
