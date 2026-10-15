package com.articals.logistics.demo

import com.articals.logistics.core.DemoCredentials
import com.articals.logistics.core.businessDateKey
import com.articals.logistics.core.isoInstant
import com.articals.logistics.domain.ExpenseKind
import com.articals.logistics.domain.PricingMethods
import com.articals.logistics.domain.RiderCostMethods
import com.articals.logistics.domain.order.ItemCategory
import com.articals.logistics.domain.order.OrderStatus
import com.articals.logistics.domain.order.ServiceLevel
import com.articals.logistics.data.dto.AdditionalCostDto
import com.articals.logistics.data.dto.AuditEntryDto
import com.articals.logistics.data.dto.CompanyDto
import com.articals.logistics.data.dto.CustomerDto
import com.articals.logistics.data.dto.CustomerSnapshotDto
import com.articals.logistics.data.dto.DeliveryEventDto
import com.articals.logistics.data.dto.DistanceBandDto
import com.articals.logistics.data.dto.EntitlementPolicyDto
import com.articals.logistics.data.dto.ExpenseDto
import com.articals.logistics.data.dto.LedgerEntryDto
import com.articals.logistics.data.dto.MembershipDto
import com.articals.logistics.data.dto.OfflinePaymentViewDto
import com.articals.logistics.data.dto.OnboardingDto
import com.articals.logistics.data.dto.OrderDto
import com.articals.logistics.data.dto.OrderLocationDto
import com.articals.logistics.data.dto.PlanDto
import com.articals.logistics.data.dto.PricingConfigDto
import com.articals.logistics.data.dto.PublicTrackingSettingsDto
import com.articals.logistics.data.dto.QuoteDto
import com.articals.logistics.data.dto.QuoteLocationDto
import com.articals.logistics.data.dto.QuoteRequestDto
import com.articals.logistics.data.dto.RiderCostConfigDto
import com.articals.logistics.data.dto.RiderDto
import com.articals.logistics.data.dto.RiderSnapshotDto
import com.articals.logistics.data.dto.SavedQuoteDto
import com.articals.logistics.data.dto.SurchargeDto
import com.articals.logistics.data.dto.ZoneRuleDto

/**
 * The demo world.
 *
 * Two companies, six riders, fifteen customers and sixty deliveries across the last
 * thirty days — generated deterministically, so the same numbers appear on every
 * device and in every screenshot.
 *
 * Everything here is fiction: the businesses, the people, the streets and the phone
 * numbers. The rate card is explicitly labelled a placeholder, because the pilot's
 * real rates belong to the pilot (spec §3).
 */
object DemoSeed {

    const val primaryTenantId = "demoexpress"
    const val secondaryTenantId = "kanoswift"

    /**
     * Plan catalogue, mirroring the platform defaults the backend seeds into
     * `platformConfig/plans`. Prices are shown from this configuration only — no
     * screen hardcodes them, and the console can edit them.
     */
    val defaultPlans: List<PlanDto> = listOf(
        PlanDto(
            id = "pilot",
            name = "Pilot",
            description = "For the first logistics partners validating the platform with us.",
            priceMinor = 0,
            periodDays = 30,
            trialDays = 30,
            features = listOf(
                "Up to 3 staff accounts",
                "Unlimited deliveries",
                "Pricing engine",
                "Public tracking",
            ),
            isActive = true,
        ),
        PlanDto(
            id = "starter",
            name = "Starter",
            description = "For a growing dispatch business running daily deliveries.",
            priceMinor = 1_500_000,
            periodDays = 30,
            trialDays = 14,
            features = listOf(
                "Up to 10 staff accounts",
                "Catalogue of pricing methods",
                "Rider app access",
                "Daily and monthly reports",
                "CSV export",
            ),
            isActive = true,
        ),
        PlanDto(
            id = "business",
            name = "Business",
            description = "For established logistics companies with multiple dispatchers.",
            priceMinor = 3_500_000,
            periodDays = 30,
            trialDays = 14,
            features = listOf(
                "Unlimited staff",
                "Zone-based pricing",
                "Financial reporting",
                "Priority support",
            ),
            isActive = true,
        ),
    )

    /** Trial, grace and bypass limits, the same numbers the backend ships with. */
    val defaultEntitlementPolicy: EntitlementPolicyDto = EntitlementPolicyDto(
        trialDays = 30,
        graceDays = 7,
        bypassMaxDays = 90,
        standardPeriodDays = 30,
    )

    fun populate(store: DemoStore) {
        val random = DemoRandom(DemoStore.SEED)
        val simulator = DemoQuoteSimulator()
        val now = store.now()
        val day = DemoStore.MILLIS_PER_DAY

        // --- accounts ------------------------------------------------------
        val accounts = listOf(
            DemoUserRecord("demo-owner", DemoCredentials.ownerEmail, DemoCredentials.password, "Ada Nwankwo", "+2348030000101"),
            DemoUserRecord("demo-manager", DemoCredentials.managerEmail, DemoCredentials.password, "Tunde Bello", "+2348030000102"),
            DemoUserRecord("demo-dispatcher", DemoCredentials.dispatcherEmail, DemoCredentials.password, "Grace Umeh", "+2348030000103"),
            DemoUserRecord("demo-rider", DemoCredentials.riderEmail, DemoCredentials.password, "Musa Ibrahim", "+2348030000104"),
            DemoUserRecord("demo-rider2", DemoCredentials.secondRiderEmail, DemoCredentials.password, "Emeka Nwosu", "+2348030000105"),
            DemoUserRecord("demo-owner2", DemoCredentials.secondOwnerEmail, DemoCredentials.password, "Hauwa Sani", "+2348030000106"),
            DemoUserRecord(
                uid = "demo-platform-admin",
                email = DemoCredentials.adminEmail,
                password = DemoCredentials.password,
                displayName = "Chidi Okonkwo (Articals Studio)",
                phoneNumber = "+2348030000107",
                isPlatformAdmin = true,
                isPlatformAdminElevated = true,
            ),
        )
        store.accounts.value = accounts

        // --- companies -----------------------------------------------------
        store.companies.value = mapOf(
            primaryTenantId to CompanyDto(
                id = primaryTenantId,
                name = "Demo Express Logistics",
                slug = "demo-express",
                brandColor = "#0B6B3A",
                email = "dispatch@demoexpress.example",
                phone = "+2348030000201",
                address = "12 Opebi Road, Ikeja, Lagos",
                currency = "NGN",
                timezone = "Africa/Lagos",
                serviceAreas = listOf("Ikeja", "Yaba", "Surulere", "Gbagada", "Victoria Island", "Lekki"),
                orderNumberPrefix = "DEL",
                status = "active",
                planId = "starter",
                activePricingConfigId = "$primaryTenantId-pricing",
                onboarding = OnboardingDto(
                    isComplete = false,
                    completedSteps = listOf("company", "pricing", "riders"),
                    startedAt = isoInstant(now - 45 * day),
                    updatedAt = isoInstant(now - 2 * day),
                ),
                publicTracking = PublicTrackingSettingsDto(shareAreas = true, requireVerification = false),
                createdAt = isoInstant(now - 45 * day),
                updatedAt = isoInstant(now - day),
                createdBy = "demo-owner",
            ),
            secondaryTenantId to CompanyDto(
                id = secondaryTenantId,
                name = "Kano Swift Couriers",
                slug = "kano-swift",
                brandColor = "#1F4E79",
                email = "hello@kanoswift.example",
                phone = "+2348030000301",
                address = "9 Zoo Road, Kano",
                currency = "NGN",
                timezone = "Africa/Lagos",
                serviceAreas = listOf("Kano Central"),
                orderNumberPrefix = "KSC",
                status = "active",
                planId = "pilot",
                activePricingConfigId = "$secondaryTenantId-pricing",
                onboarding = OnboardingDto(isComplete = true, completedSteps = listOf("company", "pricing", "riders", "customers"), startedAt = isoInstant(now - 20 * day), updatedAt = isoInstant(now - 12 * day)),
                publicTracking = PublicTrackingSettingsDto(shareAreas = false, requireVerification = true),
                createdAt = isoInstant(now - 20 * day),
                updatedAt = isoInstant(now - 3 * day),
                createdBy = "demo-owner2",
            ),
        )

        // --- memberships ---------------------------------------------------
        fun membership(userId: String, tenantId: String, role: String, displayName: String, email: String,
                       granted: List<String> = emptyList(), revoked: List<String> = emptyList()) =
            MembershipDto(
                id = "${tenantId}_$userId",
                tenantId = tenantId,
                userId = userId,
                role = role,
                status = "active",
                displayName = displayName,
                email = email,
                grantedPermissions = granted,
                revokedPermissions = revoked,
                createdAt = isoInstant(now - 44 * day),
                updatedAt = isoInstant(now - 5 * day),
            )

        store.memberships.value = listOf(
            membership("demo-owner", primaryTenantId, "owner", "Ada Nwankwo", DemoCredentials.ownerEmail),
            membership("demo-manager", primaryTenantId, "manager", "Tunde Bello", DemoCredentials.managerEmail),
            membership("demo-dispatcher", primaryTenantId, "dispatcher", "Grace Umeh", DemoCredentials.dispatcherEmail),
            membership("demo-rider", primaryTenantId, "rider", "Musa Ibrahim", DemoCredentials.riderEmail),
            membership("demo-rider2", primaryTenantId, "rider", "Emeka Nwosu", DemoCredentials.secondRiderEmail),
            membership("demo-owner2", secondaryTenantId, "owner", "Hauwa Sani", DemoCredentials.secondOwnerEmail),
        )

        // --- pricing -------------------------------------------------------
        store.pricingConfigs.value = mapOf(
            "$primaryTenantId-pricing" to PricingConfigDto(
                id = "$primaryTenantId-pricing",
                tenantId = primaryTenantId,
                name = "Placeholder standard rate card (not MX Logistics' rates)",
                isActive = true,
                version = 3,
                currency = "NGN",
                method = PricingMethods.BasePlusDistance,
                baseChargeMinor = 70_000,
                perKmRateMinor = 15_000,
                distanceBands = listOf(
                    DistanceBandDto(upToMetres = 3_000, chargeMinor = 80_000, label = "Up to 3 km"),
                    DistanceBandDto(upToMetres = 8_000, chargeMinor = 140_000, label = "3 to 8 km"),
                    DistanceBandDto(upToMetres = 15_000, chargeMinor = 220_000, label = "8 to 15 km"),
                    DistanceBandDto(upToMetres = null, chargeMinor = 320_000, label = "Above 15 km"),
                ),
                zoneRules = listOf(
                    ZoneRuleDto(fromZoneId = "ikeja", toZoneId = "victoria_island", chargeMinor = 210_000),
                    ZoneRuleDto(fromZoneId = "victoria_island", toZoneId = "ikeja", chargeMinor = 210_000),
                    ZoneRuleDto(fromZoneId = "lekki", toZoneId = "ajah", chargeMinor = 95_000),
                    ZoneRuleDto(fromZoneId = "ajah", toZoneId = "lekki", chargeMinor = 95_000),
                ),
                fallbackToDistanceWhenZoneMissing = true,
                minimumChargeMinor = 80_000,
                maximumChargeMinor = null,
                roundingIncrementMinor = 5_000,
                roundingMode = "nearest",
                surcharges = listOf(
                    SurchargeDto(
                        id = "waiting",
                        label = "Waiting time",
                        kind = "flat",
                        amountMinor = 20_000,
                        appliesToServiceLevels = listOf(ServiceLevel.Express),
                    ),
                    SurchargeDto(
                        id = "scheduled",
                        label = "Scheduled delivery",
                        kind = "flat",
                        amountMinor = 10_000,
                        appliesToServiceLevels = listOf(ServiceLevel.Scheduled),
                    ),
                ),
                riderCost = RiderCostConfigDto(method = RiderCostMethods.Fixed, defaultFeeMinor = 50_000),
                targetProfitAmountMinor = null,
                targetProfitMarginBps = 2_500,
            ),
            "$secondaryTenantId-pricing" to PricingConfigDto(
                id = "$secondaryTenantId-pricing",
                tenantId = secondaryTenantId,
                name = "Flat city rate",
                isActive = true,
                version = 1,
                currency = "NGN",
                // One price for any distance: a single open-ended band. The engine has
                // no separate "flat rate" method, and this is exactly what one means.
                method = PricingMethods.DistanceBands,
                baseChargeMinor = 120_000,
                perKmRateMinor = 0,
                distanceBands = listOf(
                    DistanceBandDto(upToMetres = null, chargeMinor = 120_000, label = "Any distance in the city"),
                ),
                fallbackToDistanceWhenZoneMissing = false,
                minimumChargeMinor = 120_000,
                roundingIncrementMinor = 0,
                riderCost = RiderCostConfigDto(method = RiderCostMethods.Fixed, defaultFeeMinor = 60_000),
            ),
        )

        // --- subscriptions -------------------------------------------------
        store.subscriptions.value = mapOf(
            primaryTenantId to DemoSubscriptionRecord(
                tenantId = primaryTenantId,
                planId = "starter",
                billingMode = "trial",
                status = "trialing",
                trialEndsAt = isoInstant(now + 18 * day),
                lastOfflinePayment = OfflinePaymentViewDto(
                    amountMinor = 1_500_000,
                    currency = "NGN",
                    method = "bank_transfer",
                    receivedAt = isoInstant(now - 62 * day),
                    periodStart = isoInstant(now - 92 * day),
                    periodEnd = isoInstant(now - 62 * day),
                ),
                paidPeriodsRecorded = 1,
            ),
            secondaryTenantId to DemoSubscriptionRecord(
                tenantId = secondaryTenantId,
                planId = "pilot",
                billingMode = "trial",
                status = "trialing",
                trialEndsAt = isoInstant(now + 6 * day),
            ),
        )

        // --- riders --------------------------------------------------------
        val primaryRiders = listOf(
            "demoexpress-rider-1" to Triple("Musa Ibrahim", "08030000101", "motorcycle"),
            "demoexpress-rider-2" to Triple("Emeka Nwosu", "08030000102", "motorcycle"),
            "demoexpress-rider-3" to Triple("Sani Bello", "08030000103", "tricycle"),
            "demoexpress-rider-4" to Triple("Tunde Adeyemi", "08030000104", "motorcycle"),
            "demoexpress-rider-5" to Triple("Blessing Eze", "08030000105", "car"),
            "demoexpress-rider-6" to Triple("Kelechi Obi", "08030000106", "bicycle"),
        )
        val secondaryRiders = listOf(
            "$secondaryTenantId-rider-1" to Triple("Suleiman Garba", "08030000301", "motorcycle"),
            "$secondaryTenantId-rider-2" to Triple("Fatima Yusuf", "08030000302", "car"),
        )

        val riderRecords = mutableMapOf<String, RiderDto>()
        for ((tenantId, riders) in listOf(primaryTenantId to primaryRiders, secondaryTenantId to secondaryRiders)) {
            riders.forEachIndexed { index, (id, details) ->
                val (name, phone, vehicle) = details
                riderRecords[id] = RiderDto(
                    id = id,
                    tenantId = tenantId,
                    name = name,
                    phone = phone,
                    email = if (tenantId == primaryTenantId) "${name.substringBefore(' ').lowercase()}@demo.example" else null,
                    vehicleType = vehicle,
                    status = if (index == 5 && tenantId == primaryTenantId) "inactive" else "active",
                    userId = when (id) {
                        "demoexpress-rider-1" -> "demo-rider"
                        "demoexpress-rider-2" -> "demo-rider2"
                        else -> null
                    },
                    deliveriesCompleted = 0,
                    deliveriesFailed = 0,
                    createdAt = isoInstant(now - 40 * day),
                    updatedAt = isoInstant(now - 4 * day),
                )
            }
        }
        store.riders.value = riderRecords

        // --- customers -----------------------------------------------------
        val primaryCustomerNames = listOf(
            "Chidinma Okeke" to "individual",
            "Sunrise Pharmacy" to "business",
            "Adeola Bakare" to "individual",
            "Green Basket Groceries" to "business",
            "Ibrahim Suleiman" to "individual",
            "Lagos Print Hub" to "business",
            "Ngozi Uche" to "individual",
            "Bluewave Diagnostics" to "business",
            "Yusuf Danladi" to "individual",
            "Harmony Foods" to "business",
            "Bolanle Ajayi" to "individual",
            "Trustline Hardware" to "business",
            "Peter Mba" to "individual",
            "Coastal Textiles" to "business",
            "Amina Lawal" to "individual",
        )
        val secondaryCustomerNames = listOf(
            "Arewa Textiles" to "business",
            "Kano Fresh Foods" to "business",
        )

        val areaAddresses = listOf(
            "48 Allen Avenue, Ikeja", "5 Herbert Macaulay Way, Yaba", "23 Adeniran Ogunsanya, Surulere",
            "Ifako, Gbagada", "14 Adeola Odeku Street, Victoria Island", "22 Admiralty Way, Lekki Phase 1",
            "1 Otigba Street, Computer Village, Ikeja", "Sabo, Yaba", "Oniru, Victoria Island",
            "Sangotedo, Ajah", "Wharf Road, Apapa", "21 Road, Festac Town",
        )

        val customerRecords = mutableMapOf<String, CustomerDto>()
        primaryCustomerNames.forEachIndexed { index, (name, type) ->
            val id = "demoexpress-customer-${index + 1}"
            customerRecords[id] = CustomerDto(
                id = id,
                tenantId = primaryTenantId,
                name = name,
                customerType = type,
                phone = "080${3 + index % 7}${(1000000 + index * 137).toString().takeLast(6)}",
                email = if (type == "business") "${name.substringBefore(' ').lowercase()}@customer.example" else null,
                address = areaAddresses[index % areaAddresses.size],
                notes = if (type == "business") "Monthly account, invoices at the end of the month." else null,
                totalOrders = 0,
                status = "active",
                createdAt = isoInstant(now - (35 - index) * day),
                updatedAt = isoInstant(now - 3 * day),
            )
        }
        secondaryCustomerNames.forEachIndexed { index, (name, type) ->
            val id = "$secondaryTenantId-customer-${index + 1}"
            customerRecords[id] = CustomerDto(
                id = id,
                tenantId = secondaryTenantId,
                name = name,
                customerType = type,
                phone = "08036000${index + 10}",
                address = "Zoo Road, Kano",
                totalOrders = 0,
                status = "active",
                createdAt = isoInstant(now - 18 * day),
                updatedAt = isoInstant(now - 6 * day),
            )
        }
        store.customers.value = customerRecords

        // --- deliveries ----------------------------------------------------
        val primaryConfig = store.pricingConfigs.value.getValue("$primaryTenantId-pricing")
        val secondaryConfig = store.pricingConfigs.value.getValue("$secondaryTenantId-pricing")
        val primaryCustomers = customerRecords.values.filter { it.tenantId == primaryTenantId }
        val activeRiders = riderRecords.values.filter { it.tenantId == primaryTenantId && it.status == "active" }

        val orders = mutableMapOf<String, OrderDto>()
        val orderEvents = mutableMapOf<String, List<DeliveryEventDto>>()
        val savedQuotes = mutableListOf<SavedQuoteDto>()
        val deliveryCounts = mutableMapOf<String, Int>()
        val failedCounts = mutableMapOf<String, Int>()
        val customerOrderCounts = mutableMapOf<String, Int>()
        var sequence = 1

        /** The pairs the distance table knows, so seeded deliveries always price. */
        val routes = listOf(
            "ikeja" to "yaba", "yaba" to "ikeja", "ikeja" to "maryland", "maryland" to "gbagada",
            "yaba" to "surulere", "victoria_island" to "ikoyi", "lekki" to "ajah", "yaba" to "victoria_island",
            "ikeja" to "victoria_island", "surulere" to "apapa", "gbagada" to "lekki", "mushin" to "yaba",
            "ikoyi" to "lekki", "festac" to "apapa", "maryland" to "ikoyi", "ikeja" to "lekki",
        )
        val streetByZone = mapOf(
            "ikeja" to "48 Allen Avenue, Ikeja",
            "maryland" to "1 Ajao Estate Road, Maryland",
            "yaba" to "5 Herbert Macaulay Way, Yaba",
            "surulere" to "23 Adeniran Ogunsanya, Surulere",
            "gbagada" to "Ifako, Gbagada",
            "mushin" to "Idioro, Mushin",
            "victoria_island" to "14 Adeola Odeku Street, Victoria Island",
            "ikoyi" to "101 Awolowo Road, Ikoyi",
            "lekki" to "22 Admiralty Way, Lekki Phase 1",
            "ajah" to "Sangotedo, Ajah",
            "apapa" to "Wharf Road, Apapa",
            "festac" to "21 Road, Festac Town",
        )
        val contactNames = listOf(
            "Chidinma Okeke", "Adeola Bakare", "Ibrahim Suleiman", "Ngozi Uche", "Yusuf Danladi",
            "Bolanle Ajayi", "Peter Mba", "Amina Lawal", "Fatima Yusuf", "Sadiq Bala",
        )

        for (dayOffset in 29 downTo 0) {
            val ordersThatDay = if (dayOffset == 0) 2 else 2
            for (indexInDay in 0 until ordersThatDay) {
                val createdAt = now - dayOffset * day + (9 + indexInDay * 4) * 3_600_000L
                val (fromZone, toZone) = routes[random.nextInt(routes.size)]
                val customer = primaryCustomers[random.nextInt(primaryCustomers.size)]
                val serviceLevel = when {
                    random.chance(15) -> ServiceLevel.Express
                    random.chance(10) -> ServiceLevel.Scheduled
                    else -> ServiceLevel.Standard
                }
                val pickupAddress = streetByZone.getValue(fromZone)
                val destinationAddress = streetByZone.getValue(toZone)
                val contactName = contactNames[random.nextInt(contactNames.size)]

                val manualDistance = dayOffset == 4 && indexInDay == 1
                // One delivery was booked while the rider's pay for that route was still
                // being agreed: the customer price is on the order, the rider's cost is
                // not confirmed, and the warning saying so was acknowledged to get the
                // delivery out. This is the second thing the "needs attention" list is
                // for, and the reason its review warning exists at all.
                val riderCostUnconfirmed = dayOffset == 7 && indexInDay == 1
                val pickup = QuoteLocationDto(
                    address = pickupAddress,
                    zoneId = fromZone,
                    area = fromZone.replace('_', ' ').replaceFirstChar { it.uppercase() },
                    latitude = null,
                    longitude = null,
                    contactName = contactName,
                    contactPhone = "0803300${(1000 + sequence).toString().takeLast(4)}",
                    manualDistanceMetres = if (manualDistance) 9_400 else null,
                )
                val destination = QuoteLocationDto(
                    address = destinationAddress,
                    zoneId = toZone,
                    area = toZone.replace('_', ' ').replaceFirstChar { it.uppercase() },
                    contactName = customer.name,
                    contactPhone = customer.phone,
                )

                val additionalCosts = if (random.chance(18)) {
                    listOf(
                        AdditionalCostDto(
                            id = "extra-${sequence}-parking",
                            label = "Parking",
                            amountMinor = random.between(2, 8) * 10_000L,
                            kind = ExpenseKind.Delivery,
                        ),
                    )
                } else {
                    emptyList()
                }

                // A handful of deliveries deliberately need attention, so the
                // "needs attention" list and the review warnings have content.
                val riderCostOverride = if (dayOffset == 0 && indexInDay == 1) {
                    com.articals.logistics.data.dto.AmountWithReasonDto(
                        amountMinor = 65_000,
                        reason = "Rider quoted extra for the evening peak",
                    )
                } else {
                    null
                }

                val request = QuoteRequestDto(
                    tenantId = primaryTenantId,
                    pickup = pickup,
                    destination = destination,
                    serviceLevel = serviceLevel,
                    deliveryType = "parcel",
                    additionalCosts = additionalCosts,
                    riderCostOverride = riderCostOverride,
                    saveQuote = true,
                )
                val response = simulator.quote(primaryConfig, request, isoInstant(createdAt))
                val quote: QuoteDto = response.quote ?: error("Demo seed produced an unpriceable delivery")
                val charge = quote.recommendedCustomerChargeMinor

                val status = when {
                    dayOffset == 0 && indexInDay == 0 -> OrderStatus.Pending
                    dayOffset == 0 && indexInDay == 1 -> OrderStatus.Assigned
                    dayOffset == 1 && indexInDay == 0 -> OrderStatus.InTransit
                    dayOffset >= 3 && random.chance(6) -> OrderStatus.Failed
                    dayOffset >= 3 && random.chance(4) -> OrderStatus.Cancelled
                    else -> OrderStatus.Delivered
                }

                val rider = if (status == OrderStatus.Pending) null else activeRiders[random.nextInt(activeRiders.size)]
                val orderId = "$primaryTenantId-order-${sequence.toString().padStart(4, '0')}"
                val businessDate = businessDateKey(createdAt, "Africa/Lagos")
                val orderNumber = DemoIds.orderNumber("DEL", businessDate, sequence)
                val trackingReference = DemoIds.trackingReference(random)
                val estimatedProfit = quote.estimatedProfitMinor

                // Actuals: what really happened. A fifth of them came in worse than
                // the estimate, which is what the underperformance report looks for.
                // The chance is rolled for every delivered delivery so that the seed's
                // random stream does not shift; the unconfirmed one simply stays open.
                val recordActuals = status == OrderStatus.Delivered && random.chance(88)
                val finalAmounts = recordActuals && !riderCostUnconfirmed
                var actualRiderCost: Long? = null
                var actualCharge: Long? = null
                var actualAdditional: Long? = null
                var actualProfit: Long? = null
                if (finalAmounts) {
                    actualRiderCost = when {
                        random.chance(18) -> quote.estimatedRiderCost.amountMinor + 30_000
                        random.chance(25) -> quote.estimatedRiderCost.amountMinor + 15_000
                        else -> quote.estimatedRiderCost.amountMinor
                    }
                    actualCharge = when {
                        random.chance(14) -> (charge * 90L) / 100L
                        random.chance(10) -> charge + 20_000
                        else -> charge
                    }
                    actualAdditional = additionalCosts.sumOf { it.amountMinor } + if (random.chance(20)) 5_000 else 0
                    actualProfit = actualCharge - actualRiderCost - actualAdditional
                }

                val order = OrderDto(
                    id = orderId,
                    tenantId = primaryTenantId,
                    orderNumber = orderNumber,
                    trackingReference = trackingReference,
                    status = status,
                    businessDate = businessDate,
                    customerId = customer.id,
                    customerSnapshot = CustomerSnapshotDto(customer.name, customer.phone, customer.customerType),
                    pickup = pickup.toOrderLocation(),
                    destination = destination.toOrderLocation(),
                    deliveryType = "parcel",
                    itemCategory = ItemCategory.all[random.nextInt(ItemCategory.all.size)],
                    description = descriptions[random.nextInt(descriptions.size)],
                    serviceLevel = serviceLevel,
                    assignedRiderId = rider?.id,
                    riderSnapshot = rider?.let { RiderSnapshotDto(it.name, it.phone, it.id) },
                    distance = quote.distance,
                    pricingSnapshot = quote.pricingSnapshot.copy(
                        capturedAt = isoInstant(createdAt),
                        // Pinned to the version that was live when it was booked: the
                        // current card is version 3, and that one averaged rider pay.
                        pricingConfigVersion = if (riderCostUnconfirmed) 2 else quote.pricingSnapshot.pricingConfigVersion,
                    ),
                    customerChargeMinor = charge,
                    estimatedCustomerChargeMinor = quote.computedCustomerChargeMinor,
                    estimatedRiderCostMinor = quote.estimatedRiderCost.amountMinor,
                    riderCostConfidence = if (riderCostUnconfirmed) "unconfirmed" else quote.estimatedRiderCost.confidence,
                    estimatedAdditionalCostsMinor = quote.estimatedAdditionalDeliveryCostsMinor,
                    estimatedProfitMinor = estimatedProfit,
                    estimatedMarginBps = quote.estimatedMarginBps,
                    actualRiderCostMinor = actualRiderCost,
                    actualAdditionalCostsMinor = actualAdditional,
                    actualCustomerChargeMinor = actualCharge,
                    actualProfitMinor = actualProfit,
                    actualsRecordedAt = if (finalAmounts) isoInstant(createdAt + 5 * 3_600_000L) else null,
                    additionalCosts = additionalCosts,
                    acknowledgedWarnings = buildList {
                        if (manualDistance) add("manual_distance")
                        if (riderCostUnconfirmed) add("rider_cost_unconfirmed")
                    },
                    internalNotes = if (manualDistance) "Distance entered by hand while the provider was down." else null,
                    scheduledAt = if (serviceLevel == ServiceLevel.Scheduled) isoInstant(createdAt + 2 * 3_600_000L) else null,
                    currency = "NGN",
                    createdBy = "demo-dispatcher",
                    createdAt = isoInstant(createdAt),
                    updatedAt = isoInstant(createdAt + 6 * 3_600_000L),
                    completedAt = if (status == OrderStatus.Delivered) isoInstant(createdAt + 4 * 3_600_000L) else null,
                    cancelledAt = if (status == OrderStatus.Cancelled) isoInstant(createdAt + 3 * 3_600_000L) else null,
                    failedAt = if (status == OrderStatus.Failed) isoInstant(createdAt + 3 * 3_600_000L) else null,
                    failureReason = if (status == OrderStatus.Failed) listOf(
                        "customer_unreachable", "wrong_address", "refused_delivery", "rider_issue",
                    )[random.nextInt(4)] else null,
                )
                orders[orderId] = order
                orderEvents[orderId] = buildEvents(
                    order = order,
                    createdAt = createdAt,
                    random = random,
                    actorUserId = "demo-dispatcher",
                    riderUserId = rider?.userId,
                )

                rider?.let { assigned ->
                    if (status == OrderStatus.Delivered) {
                        deliveryCounts[assigned.id] = (deliveryCounts[assigned.id] ?: 0) + 1
                    }
                    if (status == OrderStatus.Failed) {
                        failedCounts[assigned.id] = (failedCounts[assigned.id] ?: 0) + 1
                    }
                }
                customerOrderCounts[customer.id] = (customerOrderCounts[customer.id] ?: 0) + 1

                savedQuotes += SavedQuoteDto(
                    id = "$primaryTenantId-quote-${sequence.toString().padStart(4, '0')}",
                    orderId = orderId,
                    createdBy = "demo-dispatcher",
                    createdAt = isoInstant(createdAt),
                    pickup = pickup,
                    destination = destination,
                    serviceLevel = serviceLevel,
                    distance = quote.distance,
                    recommendedCustomerChargeMinor = charge,
                    estimatedRiderCost = quote.estimatedRiderCost,
                    estimatedAdditionalDeliveryCostsMinor = quote.estimatedAdditionalDeliveryCostsMinor,
                    estimatedProfitMinor = estimatedProfit,
                    estimatedMarginBps = quote.estimatedMarginBps,
                    warnings = quote.warnings,
                )
                sequence += 1
            }
        }

        // The second company: small on purpose, so isolation is obvious.
        val secondaryCustomers = customerRecords.values.filter { it.tenantId == secondaryTenantId }
        for (index in 0 until 3) {
            val createdAt = now - (index + 1) * day + 11 * 3_600_000L
            val customer = secondaryCustomers[index % secondaryCustomers.size]
            val pickup = QuoteLocationDto(address = "9 Zoo Road, Kano", zoneId = "kano_central", area = "Kano Central", contactName = "Dispatch desk")
            val destination = QuoteLocationDto(address = "12 Bompai Road, Kano", zoneId = "kano_central", area = "Kano Central", contactName = customer.name)
            val response = simulator.quote(
                secondaryConfig,
                QuoteRequestDto(tenantId = secondaryTenantId, pickup = pickup, destination = destination, serviceLevel = ServiceLevel.Standard),
                isoInstant(createdAt),
            )
            val quote = response.quote ?: error("Secondary demo delivery failed to price")
            val orderId = "$secondaryTenantId-order-${index + 1}"
            val rider = riderRecords.values.first { it.tenantId == secondaryTenantId }
            orders[orderId] = OrderDto(
                id = orderId,
                tenantId = secondaryTenantId,
                orderNumber = DemoIds.orderNumber("KSC", businessDateKey(createdAt, "Africa/Lagos"), index + 1),
                trackingReference = DemoIds.trackingReference(random),
                status = if (index == 0) OrderStatus.Delivered else OrderStatus.Assigned,
                businessDate = businessDateKey(createdAt, "Africa/Lagos"),
                customerId = customer.id,
                customerSnapshot = CustomerSnapshotDto(customer.name, customer.phone, customer.customerType),
                pickup = pickup.toOrderLocation(),
                destination = destination.toOrderLocation(),
                deliveryType = "documents",
                itemCategory = "documents",
                description = "Signed contract, handle with care",
                serviceLevel = ServiceLevel.Standard,
                assignedRiderId = rider.id,
                riderSnapshot = RiderSnapshotDto(rider.name, rider.phone, rider.id),
                distance = quote.distance,
                pricingSnapshot = quote.pricingSnapshot.copy(capturedAt = isoInstant(createdAt)),
                customerChargeMinor = quote.recommendedCustomerChargeMinor,
                estimatedCustomerChargeMinor = quote.computedCustomerChargeMinor,
                estimatedRiderCostMinor = quote.estimatedRiderCost.amountMinor,
                riderCostConfidence = quote.estimatedRiderCost.confidence,
                estimatedAdditionalCostsMinor = 0,
                estimatedProfitMinor = quote.estimatedProfitMinor,
                estimatedMarginBps = quote.estimatedMarginBps,
                currency = "NGN",
                createdBy = "demo-owner2",
                createdAt = isoInstant(createdAt),
                updatedAt = isoInstant(createdAt + 3_600_000L),
                completedAt = if (index == 0) isoInstant(createdAt + 3 * 3_600_000L) else null,
            )
            orderEvents[orderId] = buildEvents(orders.getValue(orderId), createdAt, random, "demo-owner2", rider.userId)
            customerOrderCounts[customer.id] = (customerOrderCounts[customer.id] ?: 0) + 1
        }

        store.orders.value = orders
        store.events.value = orderEvents
        store.quotes.value = savedQuotes

        store.riders.value = riderRecords.mapValues { (_, rider) ->
            rider.copy(
                deliveriesCompleted = deliveryCounts[rider.id] ?: 0,
                deliveriesFailed = failedCounts[rider.id] ?: 0,
            )
        }
        store.customers.value = customerRecords.mapValues { (id, customer) ->
            customer.copy(totalOrders = customerOrderCounts[id] ?: 0)
        }

        // --- expenses ------------------------------------------------------
        val expenses = mutableMapOf<String, ExpenseDto>()
        val operatingExpenses = listOf(
            "fuel" to Triple("Fuel: bulk purchase for the week", 4_500_000L, 2),
            "fuel" to Triple("Fuel: Ikeja station", 1_800_000L, 6),
            "fuel" to Triple("Fuel: Lekki station", 1_650_000L, 13),
            "maintenance" to Triple("Motorcycle servicing", 900_000L, 9),
            "maintenance" to Triple("Tyre replacement (two bikes)", 1_250_000L, 20),
            "rent" to Triple("Office rent: Ikeja", 12_000_000L, 15),
            "salaries" to Triple("Dispatcher salary", 11_000_000L, 25),
            "airtime" to Triple("Airtime and data for dispatch line", 450_000L, 3),
            "airtime" to Triple("Airtime and data", 420_000L, 17),
            "parking" to Triple("Parking permits", 300_000L, 11),
            "toll" to Triple("Toll top-up", 550_000L, 8),
            "rider_advance" to Triple("Rider advances settled", 2_000_000L, 22),
        )
        operatingExpenses.forEachIndexed { index, (category, details) ->
            val (label, amount, daysAgo) = details
            val id = "demoexpress-expense-${index + 1}"
            val date = businessDateKey(now - daysAgo * day, "Africa/Lagos")
            expenses[id] = ExpenseDto(
                id = id,
                tenantId = primaryTenantId,
                category = category,
                kind = ExpenseKind.Operating,
                amountMinor = amount,
                currency = "NGN",
                expenseDate = date,
                businessDate = date,
                label = label,
                notes = null,
                createdBy = if (index % 3 == 0) "demo-owner" else "demo-manager",
                createdAt = isoInstant(now - daysAgo * day),
            )
        }
        // Delivery-level expenses: money spent on one delivery, not overhead.
        orders.values.filter { it.tenantId == primaryTenantId && it.status == OrderStatus.Delivered }
            .take(4)
            .forEachIndexed { index, order ->
                val id = "demoexpress-expense-delivery-${index + 1}"
                expenses[id] = ExpenseDto(
                    id = id,
                    tenantId = primaryTenantId,
                    category = if (index % 2 == 0) "waiting_fee" else "toll",
                    kind = ExpenseKind.Delivery,
                    amountMinor = (2 + index) * 10_000L,
                    currency = "NGN",
                    expenseDate = order.businessDate,
                    businessDate = order.businessDate,
                    label = if (index % 2 == 0) "Waiting fee at pickup" else "Toll on the way",
                    orderId = order.id,
                    createdBy = "demo-dispatcher",
                    createdAt = order.createdAt,
                )
            }
        val secondaryExpenseDate = businessDateKey(now - 5 * day, "Africa/Lagos")
        expenses["$secondaryTenantId-expense-1"] = ExpenseDto(
            id = "$secondaryTenantId-expense-1",
            tenantId = secondaryTenantId,
            category = "fuel",
            kind = ExpenseKind.Operating,
            amountMinor = 1_200_000,
            currency = "NGN",
            expenseDate = secondaryExpenseDate,
            businessDate = secondaryExpenseDate,
            label = "Fuel: Kano",
            createdBy = "demo-owner2",
            createdAt = isoInstant(now - 5 * day),
        )
        store.expenses.value = expenses

        // --- platform records ----------------------------------------------
        store.audits.value = listOf(
            AuditEntryDto(
                id = "audit-seed-4",
                tenantId = primaryTenantId,
                actorUserId = "demo-owner",
                actorRole = "owner",
                action = "pricing.updated",
                resourceType = "pricingConfig",
                resourceId = "$primaryTenantId-pricing",
                reason = "Raised the base charge after the fuel increase",
                metadata = mapOf("version" to "3", "method" to PricingMethods.BasePlusDistance),
                timestamp = isoInstant(now - 6 * day),
            ),
            AuditEntryDto(
                id = "audit-seed-3",
                tenantId = primaryTenantId,
                actorUserId = "demo-dispatcher",
                actorRole = "dispatcher",
                action = "order.assigned",
                resourceType = "order",
                resourceId = "$primaryTenantId-order-0001",
                metadata = mapOf("riderId" to "demoexpress-rider-1"),
                timestamp = isoInstant(now - 2 * day),
            ),
            AuditEntryDto(
                id = "audit-seed-2",
                tenantId = primaryTenantId,
                actorUserId = "demo-platform-admin",
                actorRole = "platform_admin",
                action = "subscription.payment_recorded_offline",
                resourceType = "subscription",
                resourceId = primaryTenantId,
                reason = "Initial invoice settled by bank transfer",
                metadata = mapOf("amountMinor" to "1500000", "method" to "bank_transfer"),
                timestamp = isoInstant(now - 62 * day),
            ),
            AuditEntryDto(
                id = "audit-seed-1",
                tenantId = primaryTenantId,
                actorUserId = "demo-owner",
                actorRole = "owner",
                action = "company.created",
                resourceType = "company",
                resourceId = primaryTenantId,
                metadata = mapOf("planId" to "starter"),
                timestamp = isoInstant(now - 45 * day),
            ),
        )

        store.ledgers.value = mapOf(
            primaryTenantId to listOf(
                LedgerEntryDto(
                    id = "ledger-seed-1",
                    type = "payment_recorded_offline",
                    amountMinor = 1_500_000,
                    currency = "NGN",
                    recordedBy = "demo-platform-admin",
                    reason = "Initial invoice settled by bank transfer",
                    externalReference = "TRF/2026/008431",
                    periodStart = isoInstant(now - 92 * day),
                    periodEnd = isoInstant(now - 62 * day),
                    createdAt = isoInstant(now - 62 * day),
                ),
            ),
        )

        store.notifications.value = listOf(
            com.articals.logistics.data.dto.AppNotificationDto(
                id = "notification-seed-1",
                title = "New delivery assigned",
                body = "DEL-${businessDateKey(now, "Africa/Lagos")}-0002 was assigned to Musa Ibrahim.",
                at = isoInstant(now - 3_600_000L),
                simulated = true,
                read = false,
            ),
        )
    }

    private val descriptions = listOf(
        "Two cartons of medication, keep cool",
        "Documents in a sealed envelope",
        "Birthday cake, handle with care",
        "Laptop for repair",
        "Grocery order, three bags",
        "Spare motorcycle part",
        "Printed banners, do not fold",
        "Legal documents, signature required",
        "Fresh produce for the restaurant",
        "Phone screen replacement kit",
    )

    /** Builds the status history a real delivery would have produced. */
    private fun buildEvents(
        order: OrderDto,
        createdAt: Long,
        random: DemoRandom,
        actorUserId: String,
        riderUserId: String?,
    ): List<DeliveryEventDto> {
        val events = mutableListOf<DeliveryEventDto>()
        var index = 0
        fun add(status: String, at: Long, actor: String, notes: String? = null, metadata: Map<String, String> = emptyMap()) {
            index += 1
            events += DeliveryEventDto(
                id = "${order.id}-event-$index",
                status = status,
                actorUserId = actor,
                notes = notes,
                createdAt = isoInstant(at),
                metadata = metadata,
            )
        }

        add(OrderStatus.Pending, createdAt, actorUserId, "Order created")
        val progress = when (order.status) {
            OrderStatus.Pending -> listOf(OrderStatus.Pending)
            OrderStatus.Confirmed -> listOf(OrderStatus.Pending, OrderStatus.Confirmed)
            OrderStatus.Assigned -> listOf(OrderStatus.Pending, OrderStatus.Confirmed, OrderStatus.Assigned)
            OrderStatus.PickedUp -> listOf(OrderStatus.Pending, OrderStatus.Confirmed, OrderStatus.Assigned, OrderStatus.PickedUp)
            OrderStatus.InTransit -> listOf(OrderStatus.Pending, OrderStatus.Confirmed, OrderStatus.Assigned, OrderStatus.PickedUp, OrderStatus.InTransit)
            OrderStatus.Delivered -> listOf(OrderStatus.Pending, OrderStatus.Confirmed, OrderStatus.Assigned, OrderStatus.PickedUp, OrderStatus.InTransit, OrderStatus.Delivered)
            OrderStatus.Failed -> listOf(OrderStatus.Pending, OrderStatus.Confirmed, OrderStatus.Assigned, OrderStatus.Failed)
            else -> listOf(OrderStatus.Pending, OrderStatus.Cancelled)
        }
        var at = createdAt
        progress.drop(1).forEach { status ->
            at += (25 + random.nextInt(70)) * 60_000L
            val actor = when {
                status in setOf(OrderStatus.PickedUp, OrderStatus.InTransit, OrderStatus.Delivered) -> riderUserId ?: actorUserId
                status == OrderStatus.Assigned -> actorUserId
                else -> actorUserId
            }
            val notes = when (status) {
                OrderStatus.Confirmed -> "Customer confirmed the pickup details"
                OrderStatus.Assigned -> "Assigned to ${order.riderSnapshot?.name ?: "a rider"}"
                OrderStatus.PickedUp -> "Picked up from ${order.pickup.address}"
                OrderStatus.InTransit -> "On the way to ${order.destination.area ?: order.destination.address}"
                OrderStatus.Delivered -> "Delivered and signed for"
                OrderStatus.Failed -> "Failed: ${order.failureReason ?: "reported by the rider"}"
                OrderStatus.Cancelled -> "Cancelled by the customer"
                else -> null
            }
            add(status, at, actor, notes)
        }
        return events
    }

    private fun QuoteLocationDto.toOrderLocation() = OrderLocationDto(
        address = address,
        zoneId = zoneId,
        area = area,
        latitude = latitude,
        longitude = longitude,
        contactName = contactName,
        contactPhone = contactPhone,
        notes = notes,
    )
}
