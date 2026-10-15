package com.articals.logistics.ui.screen.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.articals.logistics.core.AppError
import com.articals.logistics.core.AppResult
import com.articals.logistics.core.bpsToEditablePercent
import com.articals.logistics.core.isValidPhone
import com.articals.logistics.core.parseAmountToMinorOrNull
import com.articals.logistics.core.parsePercentToBpsOrNull
import com.articals.logistics.data.api.CompanyRepository
import com.articals.logistics.data.api.MembersRepository
import com.articals.logistics.data.api.PricingRepository
import com.articals.logistics.data.dto.CompanyDto
import com.articals.logistics.data.dto.InviteMemberRequest
import com.articals.logistics.data.dto.PricingConfigDto
import com.articals.logistics.data.dto.UpdateCompanySettingsRequest
import com.articals.logistics.domain.RiderCostMethods
import com.articals.logistics.core.Roles
import com.articals.logistics.util.Idempotency
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The wizard's four steps, in order. */
enum class OnboardingStep(val key: String, val index: Int) {
    CompanyDetails("company_details", 0),
    Rates("pricing", 1),
    Team("team", 2),
    Finished("finished", 3);

    companion object {
        fun fromKey(key: String): OnboardingStep? = entries.firstOrNull { it.key == key }
    }
}

data class OnboardingState(
    val loading: Boolean = true,
    val step: OnboardingStep = OnboardingStep.CompanyDetails,
    val completedSteps: List<String> = emptyList(),
    val companyName: String = "",
    val contactPhone: String = "",
    val contactEmail: String = "",
    val businessAddress: String = "",
    val serviceAreas: String = "",
    val baseCharge: String = "",
    val perKm: String = "",
    val minimumCharge: String = "",
    val riderFixedFee: String = "",
    val targetMarginPercent: String = "",
    val pricingName: String = "",
    val pricingVersion: Int = 1,
    val inviteEmail: String = "",
    val inviteRole: String = Roles.Dispatcher,
    val inviteSent: Boolean = false,
    val busy: Boolean = false,
    val error: AppError? = null,
    val finished: Boolean = false,
    val currency: String = "NGN",
) {
    val canContinueFromCompany: Boolean
        get() = companyName.trim().length >= 2 && isValidPhone(contactPhone)

    val canContinueFromRates: Boolean
        get() = parseAmountToMinorOrNull(baseCharge) != null &&
            parseAmountToMinorOrNull(perKm) != null &&
            parseAmountToMinorOrNull(minimumCharge) != null

    val stepNumber: Int get() = step.index + 1
}

/**
 * The onboarding wizard.
 *
 * It exists because a brand-new company has nothing: no rates, no riders, no idea
 * what to do first. Each step writes what it collects through the same repositories as
 * the rest of the app — so a company set up in demo mode really is set up, and the
 * dashboard that follows shows the rates the owner just typed.
 *
 * Steps can be skipped, and the wizard says so. What it must not do is pretend: a step
 * that failed is not marked complete.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingViewModel(
    private val companies: CompanyRepository,
    private val pricing: PricingRepository,
    private val members: MembersRepository,
) : ViewModel() {

    /**
     * The workspace being set up.
     *
     * Passed in by the screen rather than injected, because Koin's parameterised
     * view-model API differs between platforms in ways that are easy to get subtly
     * wrong: a plain `load(id)` keeps the wizard's construction boring.
     */
    private var tenantId: String? = null

    private val mutableState = MutableStateFlow(OnboardingState())
    val state: StateFlow<OnboardingState> = mutableState.asStateFlow()

    /** The rate card being edited, held so a save does not lose what it does not show. */
    private var loadedConfig: PricingConfigDto? = null

    /** Starts watching the workspace. Calling it twice with the same id does nothing. */
    fun load(tenantId: String) {
        if (this.tenantId == tenantId) return
        this.tenantId = tenantId
        viewModelScope.launch {
            companies.observeCompany(tenantId).collect { company ->
                if (company != null) applyCompany(company)
            }
        }
        viewModelScope.launch {
            pricing.observeActivePricingConfigId(tenantId)
                .flatMapLatest { configId ->
                    if (configId == null) {
                        flow<PricingConfigDto?> { emit(null) }
                    } else {
                        pricing.observeActivePricingConfig(configId)
                    }
                }
                .collect { config -> config?.let(::applyPricingConfig) }
        }
    }

    private fun requireTenant(): String = requireNotNull(tenantId) {
        "OnboardingViewModel.load(tenantId) must be called before any step is saved."
    }

    private fun applyCompany(company: CompanyDto) {
        mutableState.update { current ->
            if (!current.loading && current.companyName.isNotBlank()) {
                // Don't stamp over what the user is typing mid-step.
                current.copy(currency = company.currency)
            } else {
                val completed = company.onboarding?.completedSteps.orEmpty()
                current.copy(
                    loading = false,
                    completedSteps = completed,
                    step = nextStepAfter(completed),
                    companyName = company.name,
                    contactPhone = company.phone.orEmpty(),
                    contactEmail = company.email.orEmpty(),
                    businessAddress = company.address.orEmpty(),
                    serviceAreas = company.serviceAreas.joinToString(", "),
                    currency = company.currency,
                )
            }
        }
    }

    private fun applyPricingConfig(config: PricingConfigDto) {
        loadedConfig = config
        // Only prefill the fields the user has not touched: coming back to the wizard
        // must not overwrite a rate they are halfway through changing.
        mutableState.update { current ->
            if (current.baseCharge.isNotBlank()) {
                current.copy(pricingVersion = config.version, pricingName = config.name)
            } else {
                current.copy(
                    pricingVersion = config.version,
                    pricingName = config.name,
                    baseCharge = minorToEditable(config.baseChargeMinor),
                    perKm = minorToEditable(config.perKmRateMinor),
                    minimumCharge = minorToEditable(config.minimumChargeMinor),
                    riderFixedFee = minorToEditable(config.riderCost.defaultFeeMinor ?: 0L),
                    targetMarginPercent = config.targetProfitMarginBps?.let(::bpsToEditablePercent).orEmpty(),
                    currency = config.currency,
                )
            }
        }
    }

    /** The first step the company has not finished, so reopening the wizard resumes. */
    private fun nextStepAfter(completed: List<String>): OnboardingStep =
        OnboardingStep.entries.firstOrNull { it.key !in completed } ?: OnboardingStep.Finished

    // --- field updates -----------------------------------------------------

    fun onCompanyName(value: String) = update { it.copy(companyName = value, error = null) }
    fun onPhone(value: String) = update { it.copy(contactPhone = value, error = null) }
    fun onEmail(value: String) = update { it.copy(contactEmail = value, error = null) }
    fun onAddress(value: String) = update { it.copy(businessAddress = value) }
    fun onServiceAreas(value: String) = update { it.copy(serviceAreas = value) }
    fun onBaseCharge(value: String) = update { it.copy(baseCharge = value, error = null) }
    fun onPerKm(value: String) = update { it.copy(perKm = value, error = null) }
    fun onMinimumCharge(value: String) = update { it.copy(minimumCharge = value, error = null) }
    fun onRiderFee(value: String) = update { it.copy(riderFixedFee = value, error = null) }
    fun onTargetMargin(value: String) = update { it.copy(targetMarginPercent = value, error = null) }
    fun onInviteEmail(value: String) = update { it.copy(inviteEmail = value, error = null) }
    fun onInviteRole(value: String) = update { it.copy(inviteRole = value, error = null) }

    fun goTo(step: OnboardingStep) = update { it.copy(step = step, error = null) }

    fun back() = update {
        val previous = OnboardingStep.entries.getOrNull((it.step.index - 1).coerceAtLeast(0))
            ?: OnboardingStep.CompanyDetails
        it.copy(step = previous, error = null)
    }

    // --- steps -------------------------------------------------------------

    fun saveCompanyDetails() {
        val current = mutableState.value
        if (current.busy) return
        update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            val result = companies.updateSettings(
                UpdateCompanySettingsRequest(
                    tenantId = requireTenant(),
                    companyName = current.companyName.trim(),
                    contactPhone = current.contactPhone.trim(),
                    contactEmail = current.contactEmail.trim().ifBlank { null },
                    businessAddress = current.businessAddress.trim().ifBlank { null },
                    serviceAreas = parseAreas(current.serviceAreas),
                    onboardingSteps = markDone(current, OnboardingStep.CompanyDetails),
                ),
            )
            finishStep(result, OnboardingStep.Rates)
        }
    }

    fun saveRates() {
        val current = mutableState.value
        if (current.busy) return
        val base = parseAmountToMinorOrNull(current.baseCharge)
        val perKm = parseAmountToMinorOrNull(current.perKm)
        val minimum = parseAmountToMinorOrNull(current.minimumCharge)
        val riderFee = parseAmountToMinorOrNull(current.riderFixedFee)
        if (base == null || perKm == null || minimum == null) {
            update {
                it.copy(
                    error = AppError.InvalidInput(
                        detail = "onboarding.invalid_amounts",
                        message = "Enter the amounts as numbers, for example 700 or 700.00.",
                    ),
                )
            }
            return
        }
        val marginBps = current.targetMarginPercent
            .takeIf { it.isNotBlank() }
            ?.let(::parsePercentToBpsOrNull)

        update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            val existing = loadedConfig
            val config = PricingConfigDto(
                id = existing?.id ?: "",
                tenantId = requireTenant(),
                name = current.pricingName.ifBlank { "Standard rates" },
                isActive = true,
                version = existing?.version ?: current.pricingVersion,
                currency = current.currency,
                method = existing?.method ?: com.articals.logistics.domain.PricingMethods.BasePlusDistance,
                baseChargeMinor = base,
                perKmRateMinor = perKm,
                distanceBands = existing?.distanceBands.orEmpty(),
                zoneRules = existing?.zoneRules.orEmpty(),
                fallbackToDistanceWhenZoneMissing = existing?.fallbackToDistanceWhenZoneMissing ?: true,
                minimumChargeMinor = minimum,
                maximumChargeMinor = existing?.maximumChargeMinor,
                roundingIncrementMinor = existing?.roundingIncrementMinor ?: 0,
                roundingMode = existing?.roundingMode ?: "nearest",
                surcharges = existing?.surcharges.orEmpty(),
                riderCost = (existing?.riderCost ?: com.articals.logistics.data.dto.RiderCostConfigDto())
                    .copy(
                        method = if (riderFee != null) RiderCostMethods.Fixed else existing?.riderCost?.method,
                        defaultFeeMinor = riderFee ?: existing?.riderCost?.defaultFeeMinor,
                    ),
                targetProfitAmountMinor = existing?.targetProfitAmountMinor,
                targetProfitMarginBps = marginBps ?: existing?.targetProfitMarginBps,
            )
            val saved = pricing.savePricingRules(config)
            if (saved is AppResult.Success) {
                val result = companies.completeOnboardingStep(
                    tenantId = requireTenant(),
                    step = OnboardingStep.Rates.key,
                    completedSteps = markDone(current, OnboardingStep.Rates),
                    isComplete = false,
                )
                finishStep(result, OnboardingStep.Team)
            } else {
                update { it.copy(busy = false, error = (saved as AppResult.Failure).error) }
            }
        }
    }

    /** Optional: invite one person now, or continue and do it from settings later. */
    fun invite() {
        val current = mutableState.value
        if (current.busy || current.inviteEmail.isBlank()) return
        update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            val result = members.invite(
                InviteMemberRequest(
                    tenantId = requireTenant(),
                    idempotencyKey = Idempotency.newKey("inviteMember"),
                    email = current.inviteEmail.trim(),
                    role = current.inviteRole,
                ),
            )
            when (result) {
                is AppResult.Success -> update {
                    it.copy(busy = false, inviteSent = true, inviteEmail = "")
                }

                is AppResult.Failure -> update { it.copy(busy = false, error = result.error) }
            }
        }
    }

    fun skipTeamStep() {
        val current = mutableState.value
        update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            val result = companies.completeOnboardingStep(
                tenantId = requireTenant(),
                step = OnboardingStep.Team.key,
                completedSteps = markDone(current, OnboardingStep.Team),
                isComplete = false,
            )
            finishStep(result, OnboardingStep.Finished)
        }
    }

    /** Marks the wizard finished, which is what lets the dashboard take over. */
    fun finish() {
        val current = mutableState.value
        update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            val steps = (current.completedSteps + OnboardingStep.Finished.key).distinct()
            val result = companies.updateSettings(
                UpdateCompanySettingsRequest(
                    tenantId = requireTenant(),
                    onboardingSteps = steps,
                    onboardingComplete = true,
                ),
            )
            when (result) {
                is AppResult.Success -> update { it.copy(busy = false, finished = true) }
                is AppResult.Failure -> update { it.copy(busy = false, error = result.error) }
            }
        }
    }

    private fun finishStep(result: AppResult<*>, next: OnboardingStep) {
        when (result) {
            is AppResult.Success -> update { it.copy(busy = false, step = next, error = null) }
            is AppResult.Failure -> update { it.copy(busy = false, error = result.error) }
        }
    }

    private fun markDone(current: OnboardingState, step: OnboardingStep): List<String> =
        (current.completedSteps + step.key).distinct()

    private fun update(transform: (OnboardingState) -> OnboardingState) {
        mutableState.value = transform(mutableState.value)
    }

    private fun parseAreas(value: String): List<String> =
        value.split(',').map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    private fun minorToEditable(amountMinor: Long): String =
        if (amountMinor % 100L == 0L) (amountMinor / 100L).toString() else (amountMinor / 100L).toString() + "." +
            (amountMinor % 100L).toString().padStart(2, '0')
}
