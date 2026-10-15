package com.articals.logistics.ui.screen.workspace

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.articals.logistics.core.AppError
import com.articals.logistics.core.AppResult
import com.articals.logistics.core.deviceTimeZoneId
import com.articals.logistics.core.isValidEmail
import com.articals.logistics.core.DEFAULT_CURRENCY
import com.articals.logistics.data.api.CompanyRepository
import com.articals.logistics.data.api.MembersRepository
import com.articals.logistics.data.dto.AcceptInvitationResponse
import com.articals.logistics.data.dto.CreateCompanyRequest
import com.articals.logistics.data.dto.CreateCompanyResponse
import com.articals.logistics.util.Idempotency
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Creating a company, and accepting an invitation to somebody else's.
 *
 * Both write through the same repository the rest of the app uses, so demo mode and
 * Firebase mode behave identically here: a company created in either one really
 * exists afterwards, with its owner membership, its first rate card and its trial
 * subscription.
 */
data class CompanyFormState(
    val companyName: String = "",
    val contactPhone: String = "",
    val contactEmail: String = "",
    val businessAddress: String = "",
    val serviceAreas: String = "",
    val currency: String = DEFAULT_CURRENCY,
    val timezone: String = "",
    val orderNumberPrefix: String = "",
    val busy: Boolean = false,
    val error: AppError? = null,
    val created: CreateCompanyResponse? = null,
) {
    /** Service areas are typed as a comma-separated list; empty means "not set yet". */
    val serviceAreaList: List<String>
        get() = serviceAreas.split(',').map { it.trim() }.filter { it.isNotEmpty() }

    val canSubmit: Boolean
        get() = companyName.trim().length >= 2 &&
            contactPhone.trim().length >= 7 &&
            isValidEmail(contactEmail) &&
            !busy
}

class CompanySetupViewModel(
    private val companies: CompanyRepository,
    private val members: MembersRepository,
) : ViewModel() {

    private val mutableState = MutableStateFlow(
        CompanyFormState(
            // Saves the owner a decision: it is the time zone their deliveries will be
            // reported in, and it can be changed in company settings.
            timezone = deviceTimeZoneId(),
        ),
    )
    val state: StateFlow<CompanyFormState> = mutableState.asStateFlow()

    private val invitationState = MutableStateFlow(InvitationFormState())
    val invitation: StateFlow<InvitationFormState> = invitationState.asStateFlow()

    fun onCompanyName(value: String) = update { it.copy(companyName = value, error = null) }

    fun onPhone(value: String) = update { it.copy(contactPhone = value, error = null) }

    fun onEmail(value: String) = update { it.copy(contactEmail = value, error = null) }

    fun onAddress(value: String) = update { it.copy(businessAddress = value) }

    fun onServiceAreas(value: String) = update { it.copy(serviceAreas = value) }

    fun onCurrency(value: String) = update { it.copy(currency = value.uppercase().take(3)) }

    fun onTimezone(value: String) = update { it.copy(timezone = value) }

    fun onPrefix(value: String) = update { it.copy(orderNumberPrefix = value.uppercase().take(4)) }

    fun create() {
        val current = mutableState.value
        if (!current.canSubmit) return
        update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            val request = CreateCompanyRequest(
                idempotencyKey = Idempotency.newKey("createCompany"),
                companyName = current.companyName.trim(),
                contactPhone = current.contactPhone.trim(),
                contactEmail = current.contactEmail.trim(),
                businessAddress = current.businessAddress.trim().ifBlank { null },
                serviceAreas = current.serviceAreaList.ifEmpty { null },
                currency = current.currency.takeIf { it.length == 3 },
                timezone = current.timezone.ifBlank { null },
                orderNumberPrefix = current.orderNumberPrefix.ifBlank { null },
            )
            when (val result = companies.createCompany(request)) {
                is AppResult.Success -> update { it.copy(busy = false, created = result.value) }
                is AppResult.Failure -> update { it.copy(busy = false, error = result.error) }
            }
        }
    }

    fun onInvitationIdChange(value: String) {
        invitationState.value = invitationState.value.copy(invitationId = value, error = null)
    }

    fun acceptInvitation() {
        val current = invitationState.value
        if (current.invitationId.isBlank() || current.busy) return
        invitationState.value = current.copy(busy = true, error = null)
        viewModelScope.launch {
            when (val result = members.acceptInvitation(current.invitationId.trim(), null)) {
                is AppResult.Success -> invitationState.value =
                    invitationState.value.copy(busy = false, accepted = result.value)

                is AppResult.Failure -> invitationState.value =
                    invitationState.value.copy(busy = false, error = result.error)
            }
        }
    }

    private fun update(transform: (CompanyFormState) -> CompanyFormState) {
        mutableState.value = transform(mutableState.value)
    }
}

data class InvitationFormState(
    val invitationId: String = "",
    val busy: Boolean = false,
    val error: AppError? = null,
    val accepted: AcceptInvitationResponse? = null,
)
