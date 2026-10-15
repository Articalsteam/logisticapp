package com.articals.logistics.demo

import com.articals.logistics.core.AppError
import com.articals.logistics.core.AppResult
import com.articals.logistics.core.Permissions
import com.articals.logistics.core.Roles
import com.articals.logistics.core.isoInstant
import com.articals.logistics.data.api.CompanyRepository
import com.articals.logistics.data.api.CustomersRepository
import com.articals.logistics.data.api.MembersRepository
import com.articals.logistics.data.api.RidersRepository
import com.articals.logistics.data.dto.AcceptInvitationRequest
import com.articals.logistics.data.dto.AcceptInvitationResponse
import com.articals.logistics.data.dto.AcceptRiderInvitationResponse
import com.articals.logistics.data.dto.AckDto
import com.articals.logistics.data.dto.CompanyDto
import com.articals.logistics.data.dto.CreateCompanyRequest
import com.articals.logistics.data.dto.CreateCompanyResponse
import com.articals.logistics.data.dto.CreateCustomerRequest
import com.articals.logistics.data.dto.CreateCustomerResponse
import com.articals.logistics.data.dto.CreateRiderRequest
import com.articals.logistics.data.dto.CreateRiderResponse
import com.articals.logistics.data.dto.CustomerDto
import com.articals.logistics.data.dto.InviteMemberRequest
import com.articals.logistics.data.dto.InviteMemberResponse
import com.articals.logistics.data.dto.MembershipDto
import com.articals.logistics.data.dto.OnboardingDto
import com.articals.logistics.data.dto.PricingConfigDto
import com.articals.logistics.data.dto.PublicTrackingSettingsDto
import com.articals.logistics.data.dto.RiderDto
import com.articals.logistics.data.dto.RiderInvitationRequest
import com.articals.logistics.data.dto.RiderInvitationResponse
import com.articals.logistics.data.dto.SetMemberStatusRequest
import com.articals.logistics.data.dto.SetMemberStatusResponse
import com.articals.logistics.data.dto.UpdateCompanySettingsRequest
import com.articals.logistics.data.dto.UpdateCustomerRequest
import com.articals.logistics.data.dto.UpdateMemberRoleRequest
import com.articals.logistics.data.dto.UpdateMemberRoleResponse
import com.articals.logistics.data.dto.UpdateRiderRequest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Companies in demo mode.
 *
 * `createCompany` performs the same four steps the backend does — company, owner
 * membership, first pricing configuration, trial subscription — because the
 * onboarding wizard depends on all four existing when it finishes.
 */
class DemoCompanyRepository(
    private val store: DemoStore,
) : CompanyRepository {

    override fun observeCompany(tenantId: String): Flow<CompanyDto?> =
        store.companies.map { companies -> companies[tenantId] }

    override suspend fun createCompany(request: CreateCompanyRequest): AppResult<CreateCompanyResponse> =
        store.demoCall {
            val user = store.requireUser()
            requireValid(request.companyName.trim().length >= 2, "companyName", "Enter the company name.", "company.invalid_name")
            requireValid(request.contactPhone.isDemoPhone(), "contactPhone", "Enter a reachable phone number.", "company.invalid_phone")
            requireValid(request.contactEmail.isDemoEmail(), "contactEmail", "Enter a valid email address.", "company.invalid_email")

            store.idempotent("createCompany:${user.uid}:${request.idempotencyKey}") {
                val tenantId = store.newId("company")
                val tenantSlug = request.companyName.trim().lowercase()
                    .replace(Regex("[^a-z0-9]+"), "-")
                    .trim('-')
                    .take(40)
                val now = store.isoNow()
                val trialDays = 30

                store.companies.value = store.companies.value + (
                    tenantId to CompanyDto(
                        id = tenantId,
                        name = request.companyName.trim(),
                        slug = tenantSlug,
                        brandColor = request.brandColor ?: "#0B6B3A",
                        email = request.contactEmail.trim(),
                        phone = request.contactPhone.trim(),
                        address = request.businessAddress?.trim(),
                        currency = request.currency ?: "NGN",
                        timezone = request.timezone ?: "Africa/Lagos",
                        serviceAreas = request.serviceAreas.orEmpty(),
                        orderNumberPrefix = request.orderNumberPrefix?.uppercase() ?: "ORD",
                        status = "active",
                        planId = "pilot",
                        activePricingConfigId = "$tenantId-pricing",
                        onboarding = OnboardingDto(isComplete = false, completedSteps = listOf("company"), startedAt = now, updatedAt = now),
                        publicTracking = PublicTrackingSettingsDto(shareAreas = true, requireVerification = false),
                        createdAt = now,
                        updatedAt = now,
                        createdBy = user.uid,
                    )
                    )

                store.memberships.value = store.memberships.value + MembershipDto(
                    id = "${tenantId}_${user.uid}",
                    tenantId = tenantId,
                    userId = user.uid,
                    role = Roles.Owner,
                    status = "active",
                    displayName = user.displayName,
                    email = user.email,
                    createdAt = now,
                    updatedAt = now,
                )

                val configId = "$tenantId-pricing"
                store.pricingConfigs.value = store.pricingConfigs.value + (
                    configId to PricingConfigDto(
                        id = configId,
                        tenantId = tenantId,
                        name = "Starter rate card (placeholder — replace with your own rates)",
                        isActive = true,
                        version = 1,
                        currency = request.currency ?: "NGN",
                        method = com.articals.logistics.domain.PricingMethods.BasePlusDistance,
                        baseChargeMinor = 50_000,
                        perKmRateMinor = 10_000,
                        minimumChargeMinor = 50_000,
                        riderCost = com.articals.logistics.data.dto.RiderCostConfigDto(
                            method = com.articals.logistics.domain.RiderCostMethods.Fixed,
                            defaultFeeMinor = 40_000,
                        ),
                    )
                    )

                store.subscriptions.value = store.subscriptions.value + (
                    tenantId to DemoSubscriptionRecord(
                        tenantId = tenantId,
                        planId = "pilot",
                        billingMode = "trial",
                        status = "trialing",
                        trialEndsAt = isoInstant(store.now() + trialDays * DemoStore.MILLIS_PER_DAY),
                    )
                    )

                store.setActiveTenant(tenantId)
                store.audit("company.created", tenantId, "company", tenantId, metadata = mapOf("planId" to "pilot"))

                CreateCompanyResponse(
                    tenantId = tenantId,
                    companyName = request.companyName.trim(),
                    pricingConfigId = configId,
                    subscriptionPlanId = "pilot",
                    trialEndsAt = isoInstant(store.now() + trialDays * DemoStore.MILLIS_PER_DAY),
                    entitlement = "trialing",
                )
            }
        }

    override suspend fun updateSettings(request: UpdateCompanySettingsRequest): AppResult<AckDto> =
        store.demoCall {
            store.requireWrite(request.tenantId, Permissions.CompanySettingsManage)
            val company = store.companies.value[request.tenantId]
                ?: throw DemoFailure(AppError.NotFound(detail = "company_missing"))
            request.companyName?.let {
                requireValid(it.trim().length >= 2, "companyName", "Enter the company name.", "company.invalid_name")
            }
            request.contactEmail?.let {
                requireValid(it.isDemoEmail(), "contactEmail", "Enter a valid email address.", "company.invalid_email")
            }
            request.orderNumberPrefix?.let {
                requireValid(it.length <= 4, "orderNumberPrefix", "Use at most 4 characters.", "company.invalid_prefix")
            }

            val updatedFields = mutableListOf<String>()
            fun <T> changed(name: String, value: T?): T? {
                if (value != null) updatedFields += name
                return value
            }

            store.companies.value = store.companies.value + (
                request.tenantId to company.copy(
                    name = request.companyName?.trim() ?: company.name,
                    phone = changed("phone", request.contactPhone) ?: company.phone,
                    email = changed("email", request.contactEmail) ?: company.email,
                    address = request.businessAddress ?: company.address,
                    serviceAreas = request.serviceAreas ?: company.serviceAreas,
                    brandColor = request.brandColor ?: company.brandColor,
                    logoUrl = request.logoUrl ?: company.logoUrl,
                    orderNumberPrefix = request.orderNumberPrefix?.uppercase() ?: company.orderNumberPrefix,
                    publicTracking = request.publicTracking ?: company.publicTracking,
                    onboarding = request.onboardingSteps?.let { steps ->
                        (company.onboarding ?: OnboardingDto()).copy(
                            completedSteps = steps,
                            isComplete = request.onboardingComplete ?: false,
                            updatedAt = store.isoNow(),
                        )
                    } ?: company.onboarding,
                    updatedAt = store.isoNow(),
                )
                )

            store.audit(
                action = "company.settings_updated",
                tenantId = request.tenantId,
                resourceType = "company",
                resourceId = request.tenantId,
                metadata = mapOf("fields" to (updatedFields.joinToString(",") + ",branding")),
            )
            AckDto(tenantId = request.tenantId, updated = updatedFields)
        }

    override suspend fun completeOnboardingStep(
        tenantId: String,
        step: String,
        completedSteps: List<String>,
        isComplete: Boolean,
    ): AppResult<AckDto> = updateSettings(
        UpdateCompanySettingsRequest(
            tenantId = tenantId,
            onboardingSteps = completedSteps.distinct(),
            onboardingComplete = isComplete,
        ),
    )
}

/** Staff management in demo mode, including the guards that make it safe. */
class DemoMembersRepository(
    private val store: DemoStore,
) : MembersRepository {

    override fun observeMembers(tenantId: String): Flow<List<MembershipDto>> =
        store.memberships.map { memberships -> memberships.filter { it.tenantId == tenantId } }

    override suspend fun invite(request: InviteMemberRequest): AppResult<InviteMemberResponse> =
        store.demoCall {
            store.requireWrite(request.tenantId, Permissions.StaffManage)
            requireValid(request.email.isDemoEmail(), "email", "Enter a valid email address.", "membership.invalid_email")
            requireValid(request.role in Roles.assignable, "role", "Choose a role.", "membership.invalid_role")
            val email = request.email.trim().lowercase()
            if (store.memberships.value.any { it.tenantId == request.tenantId && it.email.equals(email, true) }) {
                throw DemoFailure(
                    AppError.AlreadyExists(
                        detail = "membership.already_a_member",
                        message = "That person is already part of this company.",
                    ),
                )
            }
            if (store.invitations.value.any { it.tenantId == request.tenantId && it.email == email && it.acceptedBy == null }) {
                throw DemoFailure(
                    AppError.AlreadyExists(
                        detail = "membership.invitation_pending",
                        message = "An invitation is already waiting for that email address.",
                    ),
                )
            }
            val id = store.newId("invitation")
            val expiresAt = isoInstant(store.now() + 14 * DemoStore.MILLIS_PER_DAY)
            store.invitations.value = store.invitations.value + DemoInvitation(
                id = id,
                tenantId = request.tenantId,
                email = email,
                role = request.role,
                displayName = request.displayName,
                grantedPermissions = request.grantedPermissions.orEmpty(),
                revokedPermissions = request.revokedPermissions.orEmpty(),
                expiresAt = expiresAt,
            )
            store.audit(
                action = "membership.invited",
                tenantId = request.tenantId,
                resourceType = "invitation",
                resourceId = id,
                metadata = mapOf("email" to email, "role" to request.role),
            )
            InviteMemberResponse(invitationId = id, email = email, role = request.role, expiresAt = expiresAt)
        }

    override suspend fun acceptInvitation(invitationId: String, displayName: String?): AppResult<AcceptInvitationResponse> =
        store.demoCall {
            val user = store.requireUser()
            val invitation = store.invitations.value.firstOrNull { it.id == invitationId }
                ?: throw DemoFailure(
                    AppError.NotFound(
                        detail = "membership.invitation_missing",
                        message = "That invitation could not be found.",
                    ),
                )
            if (invitation.acceptedBy != null) {
                throw DemoFailure(
                    AppError.AlreadyExists(
                        detail = "membership.invitation_used",
                        message = "That invitation has already been accepted.",
                    ),
                )
            }
            // The backend matches the signed-in, verified email — possession of the id
            // is not enough to join a workspace.
            if (!invitation.email.equals(user.email, ignoreCase = true)) {
                throw DemoFailure(
                    AppError.PermissionDenied(
                        detail = "membership.invitation_email_mismatch",
                        message = "This invitation is for ${invitation.email}. Sign in with that account to accept it.",
                    ),
                )
            }
            val membershipId = "${invitation.tenantId}_${user.uid}"
            store.memberships.value = store.memberships.value + MembershipDto(
                id = membershipId,
                tenantId = invitation.tenantId,
                userId = user.uid,
                role = invitation.role,
                status = "active",
                displayName = displayName ?: invitation.displayName ?: user.displayName,
                email = user.email,
                grantedPermissions = invitation.grantedPermissions,
                revokedPermissions = invitation.revokedPermissions,
                createdAt = store.isoNow(),
                updatedAt = store.isoNow(),
            )
            store.invitations.value = store.invitations.value.map {
                if (it.id == invitationId) it.copy(acceptedBy = user.uid) else it
            }
            store.setActiveTenant(invitation.tenantId)
            store.audit("membership.accepted", invitation.tenantId, "membership", membershipId)
            AcceptInvitationResponse(tenantId = invitation.tenantId, role = invitation.role, membershipId = membershipId)
        }

    override suspend fun updateRole(request: UpdateMemberRoleRequest): AppResult<UpdateMemberRoleResponse> =
        store.demoCall {
            val actor = store.requireWrite(request.tenantId, Permissions.StaffManage)
            requireValid(request.role in Roles.assignable, "role", "Choose a role.", "membership.invalid_role")
            if (request.userId == actor.userId) {
                throw DemoFailure(
                    AppError.Rejected(
                        detail = "membership.self_role_change",
                        message = "You cannot change your own role.",
                    ),
                )
            }
            val target = store.memberships.value.firstOrNull {
                it.tenantId == request.tenantId && it.userId == request.userId
            } ?: throw DemoFailure(AppError.NotFound(detail = "membership_missing"))

            if (target.role == Roles.Owner && request.role != Roles.Owner) {
                val owners = store.memberships.value.count {
                    it.tenantId == request.tenantId && it.role == Roles.Owner && it.status == "active"
                }
                if (owners <= 1) {
                    throw DemoFailure(
                        AppError.Rejected(
                            detail = "membership.last_owner",
                            message = "A company must always have at least one owner.",
                        ),
                    )
                }
            }

            val updated = target.copy(
                role = request.role,
                grantedPermissions = request.grantedPermissions ?: target.grantedPermissions,
                revokedPermissions = request.revokedPermissions ?: target.revokedPermissions,
                updatedAt = store.isoNow(),
            )
            store.memberships.value = store.memberships.value.map {
                if (it.id == updated.id) updated else it
            }
            store.audit(
                action = "membership.role_changed",
                tenantId = request.tenantId,
                resourceType = "membership",
                resourceId = updated.id,
                reason = request.reason,
                metadata = mapOf("from" to target.role, "to" to request.role),
            )
            UpdateMemberRoleResponse(
                userId = updated.userId,
                role = updated.role,
                permissions = Permissions.effective(updated.role, updated.grantedPermissions, updated.revokedPermissions).toList(),
            )
        }

    override suspend fun setStatus(request: SetMemberStatusRequest): AppResult<SetMemberStatusResponse> =
        store.demoCall {
            val actor = store.requireWrite(request.tenantId, Permissions.StaffManage)
            requireValid(
                request.status == "active" || request.status == "disabled",
                "status",
                "Choose active or disabled.",
                "membership.invalid_status",
            )
            if (request.userId == actor.userId) {
                throw DemoFailure(
                    AppError.Rejected(
                        detail = "membership.self_status_change",
                        message = "You cannot disable your own access.",
                    ),
                )
            }
            val target = store.memberships.value.firstOrNull {
                it.tenantId == request.tenantId && it.userId == request.userId
            } ?: throw DemoFailure(AppError.NotFound(detail = "membership_missing"))

            if (target.role == Roles.Owner && request.status != "active") {
                val owners = store.memberships.value.count {
                    it.tenantId == request.tenantId && it.role == Roles.Owner && it.status == "active"
                }
                if (owners <= 1) {
                    throw DemoFailure(
                        AppError.Rejected(
                            detail = "membership.last_owner",
                            message = "A company must always have at least one active owner.",
                        ),
                    )
                }
            }

            store.memberships.value = store.memberships.value.map {
                if (it.id == target.id) it.copy(status = request.status, updatedAt = store.isoNow()) else it
            }
            store.audit(
                action = "membership.status_changed",
                tenantId = request.tenantId,
                resourceType = "membership",
                resourceId = target.id,
                reason = request.reason,
                metadata = mapOf("status" to request.status),
            )
            SetMemberStatusResponse(userId = request.userId, status = request.status)
        }
}

/** The customer directory in demo mode. */
class DemoCustomersRepository(
    private val store: DemoStore,
) : CustomersRepository {

    override fun observeCustomers(tenantId: String, limit: Int): Flow<List<CustomerDto>> =
        store.customers.map { customers ->
            customers.values.filter { it.tenantId == tenantId }.sortedBy { it.name.lowercase() }.take(limit)
        }

    override fun observeActiveCustomers(tenantId: String, limit: Int): Flow<List<CustomerDto>> =
        store.customers.map { customers ->
            customers.values
                .filter { it.tenantId == tenantId && it.status == "active" }
                .sortedBy { it.name.lowercase() }
                .take(limit)
        }

    override suspend fun create(request: CreateCustomerRequest): AppResult<CreateCustomerResponse> =
        store.demoCall {
            store.requireWrite(request.tenantId, Permissions.CustomersManage)
            requireValid(request.name.trim().length >= 2, "name", "Enter the customer's name.", "customer.invalid_name")
            requireValid(
                request.customerType == "individual" || request.customerType == "business",
                "customerType",
                "Choose whether this is a person or a business.",
                "customer.invalid_type",
            )
            requireValid(request.phone.isDemoPhone(), "phone", "Enter a reachable phone number.", "customer.invalid_phone")
            request.email?.takeIf { it.isNotBlank() }?.let {
                requireValid(it.isDemoEmail(), "email", "Enter a valid email address.", "customer.invalid_email")
            }
            store.idempotent("createCustomer:${request.tenantId}:${request.idempotencyKey}") {
                val id = store.newId("customer")
                val now = store.isoNow()
                store.customers.value = store.customers.value + (
                    id to CustomerDto(
                        id = id,
                        tenantId = request.tenantId,
                        name = request.name.trim(),
                        customerType = request.customerType,
                        phone = request.phone.trim(),
                        email = request.email?.trim(),
                        address = request.address?.trim(),
                        notes = request.notes?.trim(),
                        status = "active",
                        createdAt = now,
                        updatedAt = now,
                    )
                    )
                store.audit("customer.created", request.tenantId, "customer", id, metadata = mapOf("name" to request.name.trim()))
                CreateCustomerResponse(customerId = id)
            }
        }

    override suspend fun update(request: UpdateCustomerRequest): AppResult<AckDto> = store.demoCall {
        store.requireWrite(request.tenantId, Permissions.CustomersManage)
        val existing = store.customers.value[request.customerId]
            ?: throw DemoFailure(AppError.NotFound(detail = "customer_missing"))
        if (existing.tenantId != request.tenantId) {
            throw DemoFailure(AppError.NotFound(detail = "customer_missing"))
        }
        request.name?.let {
            requireValid(it.trim().length >= 2, "name", "Enter the customer's name.", "customer.invalid_name")
        }
        store.customers.value = store.customers.value + (
            request.customerId to existing.copy(
                name = request.name?.trim() ?: existing.name,
                phone = request.phone?.trim() ?: existing.phone,
                customerType = request.customerType ?: existing.customerType,
                email = request.email ?: existing.email,
                address = request.address ?: existing.address,
                notes = request.notes ?: existing.notes,
                status = request.status ?: existing.status,
                updatedAt = store.isoNow(),
            )
            )
        store.audit("customer.updated", request.tenantId, "customer", request.customerId)
        AckDto(tenantId = request.tenantId, customerId = request.customerId)
    }

    override suspend fun archive(tenantId: String, customerId: String): AppResult<AckDto> =
        update(UpdateCustomerRequest(tenantId = tenantId, customerId = customerId, status = "archived"))
}

/** Riders in demo mode, including the invitation that links an account to a rider. */
class DemoRidersRepository(
    private val store: DemoStore,
) : RidersRepository {

    override fun observeRiders(tenantId: String, limit: Int): Flow<List<RiderDto>> =
        store.riders.map { riders ->
            riders.values.filter { it.tenantId == tenantId }.sortedBy { it.name.lowercase() }.take(limit)
        }

    override fun observeActiveRiders(tenantId: String, limit: Int): Flow<List<RiderDto>> =
        store.riders.map { riders ->
            riders.values
                .filter { it.tenantId == tenantId && it.status == "active" }
                .sortedBy { it.name.lowercase() }
                .take(limit)
        }

    override suspend fun create(request: CreateRiderRequest): AppResult<CreateRiderResponse> =
        store.demoCall {
            store.requireWrite(request.tenantId, Permissions.RidersManage)
            requireValid(request.name.trim().length >= 2, "name", "Enter the rider's name.", "rider.invalid_name")
            requireValid(request.phone.isDemoPhone(), "phone", "Enter a reachable phone number.", "rider.invalid_phone")
            request.vehicleType?.let {
                requireValid(
                    it in listOf("motorcycle", "bicycle", "car", "van", "truck", "other"),
                    "vehicleType",
                    "Choose the rider's vehicle.",
                    "rider.invalid_vehicle",
                )
            }
            store.idempotent("createRider:${request.tenantId}:${request.idempotencyKey}") {
                val id = store.newId("rider")
                val now = store.isoNow()
                store.riders.value = store.riders.value + (
                    id to RiderDto(
                        id = id,
                        tenantId = request.tenantId,
                        name = request.name.trim(),
                        phone = request.phone.trim(),
                        email = request.email?.trim(),
                        vehicleType = request.vehicleType,
                        status = "active",
                        notes = request.internalNotes?.trim(),
                        createdAt = now,
                        updatedAt = now,
                    )
                    )
                store.audit("rider.created", request.tenantId, "rider", id, metadata = mapOf("name" to request.name.trim()))
                CreateRiderResponse(riderId = id, status = "active")
            }
        }

    override suspend fun update(request: UpdateRiderRequest): AppResult<AckDto> = store.demoCall {
        store.requireWrite(request.tenantId, Permissions.RidersManage)
        val existing = store.riders.value[request.riderId]
            ?: throw DemoFailure(AppError.NotFound(detail = "rider_missing"))
        if (existing.tenantId != request.tenantId) {
            throw DemoFailure(AppError.NotFound(detail = "rider_missing"))
        }
        request.status?.let {
            requireValid(it in listOf("active", "inactive", "suspended"), "status", "Choose a rider status.", "rider.invalid_status")
        }
        if (request.status == "inactive" || request.status == "suspended") {
            val openDeliveries = store.orders.value.values.filter {
                it.tenantId == request.tenantId && it.assignedRiderId == existing.id &&
                    it.status in com.articals.logistics.domain.order.OrderStatus.open
            }
            if (openDeliveries.isNotEmpty()) {
                throw DemoFailure(
                    AppError.Rejected(
                        detail = "rider_has_open_deliveries",
                        message = "Reassign this rider's ${openDeliveries.size} open " +
                            "${if (openDeliveries.size == 1) "delivery" else "deliveries"} first.",
                    ),
                )
            }
        }
        store.riders.value = store.riders.value + (
            request.riderId to existing.copy(
                name = request.name?.trim() ?: existing.name,
                phone = request.phone?.trim() ?: existing.phone,
                email = request.email ?: existing.email,
                vehicleType = request.vehicleType ?: existing.vehicleType,
                status = request.status ?: existing.status,
                notes = request.internalNotes ?: existing.notes,
                updatedAt = store.isoNow(),
            )
            )
        store.audit("rider.updated", request.tenantId, "rider", request.riderId, metadata = mapOf("status" to (request.status ?: existing.status)))
        AckDto(tenantId = request.tenantId, riderId = request.riderId)
    }

    override suspend fun createInvitation(request: RiderInvitationRequest): AppResult<RiderInvitationResponse> =
        store.demoCall {
            store.requireWrite(request.tenantId, Permissions.RidersManage)
            requireValid(request.email.isDemoEmail(), "email", "Enter a valid email address.", "rider.invalid_email")
            val rider = store.riders.value[request.riderId]
                ?: throw DemoFailure(AppError.NotFound(detail = "rider_missing"))
            if (rider.tenantId != request.tenantId) throw DemoFailure(AppError.NotFound(detail = "rider_missing"))
            if (rider.userId != null) {
                throw DemoFailure(
                    AppError.AlreadyExists(
                        detail = "rider.already_linked",
                        message = "${rider.name} already has an account linked to this rider profile.",
                    ),
                )
            }
            val id = store.newId("riderInvitation")
            val token = DemoIds.riderInvitationToken()
            val expiresAt = isoInstant(store.now() + 7 * DemoStore.MILLIS_PER_DAY)
            store.riderInvitations.value = store.riderInvitations.value + DemoRiderInvitation(
                id = id,
                tenantId = request.tenantId,
                riderId = rider.id,
                email = request.email.trim().lowercase(),
                token = token,
                expiresAt = expiresAt,
            )
            store.audit("rider.invited", request.tenantId, "rider", rider.id, metadata = mapOf("email" to request.email.trim().lowercase()))
            RiderInvitationResponse(
                invitationId = id,
                token = token,
                expiresAt = expiresAt,
                inviteUrl = "https://demo.example/rider-invite?token=$token",
            )
        }

    override suspend fun acceptInvitation(token: String): AppResult<AcceptRiderInvitationResponse> =
        store.demoCall {
            val user = store.requireUser()
            val invitation = store.riderInvitations.value.firstOrNull { it.token == token.trim() }
                ?: throw DemoFailure(
                    AppError.NotFound(
                        detail = "rider.invitation_missing",
                        message = "That invitation link is not valid. Ask your dispatcher to send a new one.",
                    ),
                )
            if (invitation.acceptedBy != null) {
                throw DemoFailure(
                    AppError.AlreadyExists(
                        detail = "rider.invitation_used",
                        message = "That invitation has already been used.",
                    ),
                )
            }
            if (!invitation.email.equals(user.email, ignoreCase = true)) {
                throw DemoFailure(
                    AppError.PermissionDenied(
                        detail = "rider.invitation_email_mismatch",
                        message = "This invitation is for ${invitation.email}. Sign in with that account.",
                    ),
                )
            }
            val rider = store.riders.value[invitation.riderId]
                ?: throw DemoFailure(AppError.NotFound(detail = "rider_missing"))
            store.riders.value = store.riders.value + (rider.id to rider.copy(userId = user.uid, updatedAt = store.isoNow()))

            val membershipId = "${invitation.tenantId}_${user.uid}"
            val existing = store.membershipFor(invitation.tenantId, user.uid)
            store.memberships.value = store.memberships.value.filterNot { it.id == membershipId } + MembershipDto(
                id = membershipId,
                tenantId = invitation.tenantId,
                userId = user.uid,
                role = Roles.Rider,
                status = "active",
                displayName = existing?.displayName ?: user.displayName ?: rider.name,
                email = user.email,
                createdAt = existing?.createdAt ?: store.isoNow(),
                updatedAt = store.isoNow(),
            )
            store.riderInvitations.value = store.riderInvitations.value.map {
                if (it.id == invitation.id) it.copy(acceptedBy = user.uid) else it
            }
            store.setActiveTenant(invitation.tenantId)
            store.audit("rider.invitation_accepted", invitation.tenantId, "rider", rider.id)
            AcceptRiderInvitationResponse(tenantId = invitation.tenantId, riderId = rider.id, role = Roles.Rider)
        }
}
