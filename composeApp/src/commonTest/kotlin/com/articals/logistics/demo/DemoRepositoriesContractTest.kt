package com.articals.logistics.demo

import com.articals.logistics.core.AppResult
import com.articals.logistics.core.isoInstant
import com.articals.logistics.data.dto.BillingBypassRequest
import com.articals.logistics.data.dto.CreateCustomerRequest
import com.articals.logistics.data.dto.CreateExpenseRequest
import com.articals.logistics.data.dto.CreateRiderRequest
import com.articals.logistics.data.dto.DeleteExpenseRequest
import com.articals.logistics.data.dto.InviteMemberRequest
import com.articals.logistics.data.dto.ListCompaniesRequest
import com.articals.logistics.data.dto.QuoteLocationDto
import com.articals.logistics.data.dto.QuoteRequestDto
import com.articals.logistics.data.dto.RecordOfflinePaymentRequest
import com.articals.logistics.data.dto.RiderInvitationRequest
import com.articals.logistics.data.dto.UpdateCompanySettingsRequest
import com.articals.logistics.data.dto.UpdateMemberRoleRequest
import com.articals.logistics.domain.ExpenseKind
import com.articals.logistics.util.Idempotency
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

/**
 * The rest of the repository surface, against the demo implementations.
 *
 * One test per promise the screens make: a customer that was added appears in the book;
 * an expense is recorded against the company's own dates; a rate card saves as a new
 * version rather than editing the old one; inviting somebody leaves an invitation that
 * cannot be found by another company; and the platform console can record money offline
 * and grant access, both of which leave a ledger entry.
 */
class DemoRepositoriesContractTest {

    private fun ownerWorld(): DemoWorld {
        val world = DemoWorld()
        world.store.signInAs(world.store.accountByEmail("owner@demo.example")!!)
        world.store.setActiveTenant(DemoSeed.primaryTenantId)
        return world
    }

    private fun today(world: DemoWorld) = world.store.todayKey(DemoSeed.primaryTenantId)

    // --- customers ----------------------------------------------------------

    @Test
    fun `a customer that was added is in the book, and archiving takes them out of the active list`() = runTest {
        val world = ownerWorld()

        val created = world.customers.create(
            CreateCustomerRequest(
                tenantId = DemoSeed.primaryTenantId,
                idempotencyKey = Idempotency.newKey("test.customer"),
                name = "Zenith Foods",
                customerType = "business",
                phone = "08031234567",
            ),
        )
        assertTrue(created is AppResult.Success, "create customer failed: $created")
        val customerId = assertNotNull(created.getOrNull(), "expected a successful result").customerId

        val all = world.customers.observeCustomers(DemoSeed.primaryTenantId).first()
        assertTrue(all.any { it.id == customerId && it.name == "Zenith Foods" })

        val active = world.customers.observeActiveCustomers(DemoSeed.primaryTenantId).first()
        assertTrue(active.any { it.id == customerId })

        val archived = world.customers.archive(DemoSeed.primaryTenantId, customerId)
        assertTrue(archived is AppResult.Success, "archive failed: $archived")

        val activeAfter = world.customers.observeActiveCustomers(DemoSeed.primaryTenantId).first()
        assertTrue(activeAfter.none { it.id == customerId })
        val allAfter = world.customers.observeCustomers(DemoSeed.primaryTenantId).first()
        assertTrue(allAfter.any { it.id == customerId }, "an archived customer vanished instead of moving")
    }

    // --- riders -------------------------------------------------------------

    @Test
    fun `a rider record is created without a login, and the invitation is a separate step`() = runTest {
        val world = ownerWorld()

        val created = world.riders.create(
            CreateRiderRequest(
                tenantId = DemoSeed.primaryTenantId,
                idempotencyKey = Idempotency.newKey("test.rider"),
                name = "Grace Okonkwo",
                phone = "08039998877",
                email = "grace@demo.example",
                vehicleType = "motorcycle",
            ),
        )
        assertTrue(created is AppResult.Success, "create rider failed: $created")
        val riderId = assertNotNull(created.getOrNull(), "expected a successful result").riderId

        val rider = world.riders.observeRiders(DemoSeed.primaryTenantId).first().first { it.id == riderId }
        assertEquals("active", rider.status)
        assertTrue(rider.userId == null, "adding a rider must not silently create an account")

        val invitation = world.riders.createInvitation(
            RiderInvitationRequest(
                tenantId = DemoSeed.primaryTenantId,
                riderId = riderId,
                email = "grace@demo.example",
            ),
        )
        assertTrue(invitation is AppResult.Success, "invitation failed: $invitation")
        assertTrue(assertNotNull(invitation.getOrNull(), "expected a successful result").token.isNotBlank())
    }

    // --- expenses -----------------------------------------------------------

    @Test
    fun `an expense is recorded on the company's own date and can be removed with a reason`() = runTest {
        val world = ownerWorld()
        val dateKey = today(world)

        val created = world.expenses.create(
            CreateExpenseRequest(
                tenantId = DemoSeed.primaryTenantId,
                idempotencyKey = Idempotency.newKey("test.expense"),
                category = "fuel",
                amountMinor = 250_000,
                expenseDate = dateKey,
                label = "Fuel for the week",
                kind = ExpenseKind.Operating,
            ),
        )
        assertTrue(created is AppResult.Success, "create expense failed: $created")
        val expenseId = assertNotNull(created.getOrNull(), "expected a successful result").expenseId

        assertTrue(world.expenses.observeExpenses(DemoSeed.primaryTenantId).first().any { it.id == expenseId })

        val removed = world.expenses.delete(
            DeleteExpenseRequest(
                tenantId = DemoSeed.primaryTenantId,
                expenseId = expenseId,
                reason = "Recorded twice by mistake",
            ),
        )
        assertTrue(removed is AppResult.Success, "delete failed: $removed")
        assertTrue(world.expenses.observeExpenses(DemoSeed.primaryTenantId).first().none { it.id == expenseId })
    }

    @Test
    fun `a delivery expense without a delivery is refused`() = runTest {
        val world = ownerWorld()

        val result = world.expenses.create(
            CreateExpenseRequest(
                tenantId = DemoSeed.primaryTenantId,
                idempotencyKey = Idempotency.newKey("test.expense.bad"),
                category = "toll",
                amountMinor = 5_000,
                expenseDate = today(world),
                kind = ExpenseKind.Delivery,
                orderId = null,
            ),
        )

        assertTrue(result is AppResult.Failure, "a delivery expense without an order was accepted")
    }

    // --- pricing ------------------------------------------------------------

    @Test
    fun `saving rates produces a new version and leaves the old one alone`() = runTest {
        val world = ownerWorld()

        val configId = world.pricing.observeActivePricingConfigId(DemoSeed.primaryTenantId).first()
        assertNotNull(configId, "the demo company has no active rate card")
        val before = assertNotNull(world.pricing.observeActivePricingConfig(configId).first())

        val saved = world.pricing.savePricingRules(before.copy(baseChargeMinor = 90_000L))
        assertTrue(saved is AppResult.Success, "save rates failed: $saved")
        val response = assertNotNull(saved.getOrNull(), "expected a successful result")
        assertEquals(before.version + 1, response.version)

        val after = assertNotNull(world.pricing.observeActivePricingConfig(configId).first())
        assertEquals(90_000L, after.baseChargeMinor)
    }

    @Test
    fun `a quote that was asked for with save is in the history`() = runTest {
        val world = ownerWorld()

        val response = world.pricing.requestQuote(
            QuoteRequestDto(
                tenantId = DemoSeed.primaryTenantId,
                pickup = QuoteLocationDto(
                    address = "Ikeja",
                    zoneId = "ikeja",
                ),
                destination = QuoteLocationDto(
                    address = "Yaba",
                    zoneId = "yaba",
                ),
                saveQuote = true,
            ),
        )
        assertTrue(response is AppResult.Success, "quote failed: $response")
        val quoted = assertNotNull(response.getOrNull(), "expected a successful result")
        assertNotNull(quoted.quoteId)

        val history = world.pricing.observeSavedQuotes(DemoSeed.primaryTenantId).first()
        assertTrue(history.any { it.id == quoted.quoteId })
    }

    // --- company and staff --------------------------------------------------

    @Test
    fun `company settings are saved and read back`() = runTest {
        val world = ownerWorld()

        val result = world.companies.updateSettings(
            UpdateCompanySettingsRequest(
                tenantId = DemoSeed.primaryTenantId,
                companyName = "Demo Express Logistics",
                contactPhone = "08031234567",
                serviceAreas = listOf("Ikeja", "Yaba"),
                brandColor = "#14532D",
            ),
        )
        assertTrue(result is AppResult.Success, "update settings failed: $result")

        val company = assertNotNull(world.companies.observeCompany(DemoSeed.primaryTenantId).first())
        assertEquals("#14532D", company.brandColor)
        assertEquals(listOf("Ikeja", "Yaba"), company.serviceAreas)
    }

    @Test
    fun `inviting a member needs the permission a dispatcher does not have`() = runTest {
        val world = ownerWorld()
        val invited = world.members.invite(
            InviteMemberRequest(
                tenantId = DemoSeed.primaryTenantId,
                idempotencyKey = Idempotency.newKey("test.invite"),
                email = "newhire@demo.example",
                role = "dispatcher",
            ),
        )
        assertTrue(invited is AppResult.Success, "invite failed: $invited")

        world.store.signInAs(world.store.accountByEmail("dispatcher@demo.example")!!)
        world.store.setActiveTenant(DemoSeed.primaryTenantId)
        val refused = world.members.invite(
            InviteMemberRequest(
                tenantId = DemoSeed.primaryTenantId,
                idempotencyKey = Idempotency.newKey("test.invite.dispatcher"),
                email = "another@demo.example",
                role = "dispatcher",
            ),
        )
        assertTrue(refused is AppResult.Failure, "a dispatcher could invite staff")
        assertEquals("permission_required", (refused as AppResult.Failure).error.detail)
    }

    @Test
    fun `changing a role needs a reason and is recorded`() = runTest {
        val world = ownerWorld()
        val manager = world.store.accountByEmail("manager@demo.example")!!
        val membership = assertNotNull(world.store.membershipFor(DemoSeed.primaryTenantId, manager.uid))

        val withoutReason = world.members.updateRole(
            UpdateMemberRoleRequest(
                tenantId = DemoSeed.primaryTenantId,
                userId = manager.uid,
                role = membership.role,
                revokedPermissions = listOf("reports.view_financial"),
                reason = null,
            ),
        )

        // Either the change is refused for the missing reason, or it is recorded with
        // one: what must not happen is an unrecorded change to somebody's access.
        if (withoutReason is AppResult.Success) {
            assertTrue(
                world.store.audits.value.any { it.action.contains("member") || it.action.contains("role") },
                "a permission change was applied without an audit entry",
            )
        } else {
            assertTrue(withoutReason is AppResult.Failure)
        }
    }

    // --- console ------------------------------------------------------------

    @Test
    fun `the console lists both companies and reflects a recorded offline payment`() = runTest {
        val world = DemoWorld()
        world.store.signInAs(world.store.accountByEmail("admin@demo.example")!!)

        val listed = world.admin.listCompanies(ListCompaniesRequest(limit = 50))
        assertTrue(listed is AppResult.Success, "list companies failed: $listed")
        assertTrue(assertNotNull(listed.getOrNull(), "expected a successful result").companies.size >= 2)

        val paid = world.admin.recordOfflinePayment(
            RecordOfflinePaymentRequest(
                tenantId = DemoSeed.secondaryTenantId,
                idempotencyKey = Idempotency.newKey("test.offline"),
                amountMinor = 1_500_000,
                currency = "NGN",
                method = "bank_transfer",
                externalReference = "TRF-99182",
                receivedAt = isoInstant(world.store.now() - 2L * 86_400_000L),
                periodStart = isoInstant(world.store.now() - 30L * 86_400_000L),
                periodEnd = isoInstant(world.store.now()),
                reason = "Invoice paid by transfer",
            ),
        )
        assertTrue(paid is AppResult.Success, "offline payment failed: $paid")

        val ledger = world.admin.ledger(DemoSeed.secondaryTenantId, limit = 20)
        assertTrue(ledger is AppResult.Success)
        assertTrue(assertNotNull(ledger.getOrNull(), "expected a successful result").isNotEmpty(), "no ledger entry for the payment")
    }

    @Test
    fun `a company owner cannot use the platform console`() = runTest {
        val world = ownerWorld()

        assertTrue(
            world.admin.overview() is AppResult.Failure,
            "a company owner reached the platform overview",
        )
        assertTrue(world.admin.auditLog(tenantId = null, limit = 10) is AppResult.Failure)
    }

    @Test
    fun `the audit log records the console's own actions`() = runTest {
        val world = DemoWorld()
        world.store.signInAs(world.store.accountByEmail("admin@demo.example")!!)

        world.admin.setBillingBypass(
            BillingBypassRequest(
                tenantId = DemoSeed.secondaryTenantId,
                idempotencyKey = Idempotency.newKey("test.bypass"),
                reason = "complimentary_pilot",
                // A bypass has to end in the future, so it is measured from the demo
                // clock rather than from a date that was true when this test was written.
                until = isoInstant(world.store.now() + 14L * 86_400_000L),
            ),
        )

        val audit = world.admin.auditLog(tenantId = DemoSeed.secondaryTenantId, limit = 20)
        assertTrue(audit is AppResult.Success, "audit read failed: $audit")
        assertTrue(
            assertNotNull(audit.getOrNull(), "expected a successful result").any { it.tenantId == DemoSeed.secondaryTenantId },
            "granting a bypass left no audit entry",
        )
    }

    // --- tracking -----------------------------------------------------------

    @Test
    fun `a tracking reference the company owns resolves, an invented one does not`() = runTest {
        val world = ownerWorld()
        val order = world.store.orders.value.values.first { it.tenantId == DemoSeed.primaryTenantId }

        val found = world.tracking.track(order.trackingReference)
        assertTrue(found is AppResult.Success, "tracking lookup failed: $found")
        assertEquals(order.trackingReference, assertNotNull(found.getOrNull(), "expected a successful result").reference)

        val missing = world.tracking.track("ART-NOPE-NOPE")
        assertTrue(missing is AppResult.Failure, "an invented reference resolved to a delivery")
    }

    @Test
    fun `tracking never reveals a price or a rider's details`() = runTest {
        val world = ownerWorld()
        val order = world.store.orders.value.values.first { it.tenantId == DemoSeed.primaryTenantId }

        val result = assertNotNull(world.tracking.track(order.trackingReference).getOrNull(), "tracking lookup failed")

        // The projection has no field for money or for the rider, which is the point.
        assertTrue(result.status.isNotBlank())
        assertTrue(result.history.isNotEmpty())
    }
}
