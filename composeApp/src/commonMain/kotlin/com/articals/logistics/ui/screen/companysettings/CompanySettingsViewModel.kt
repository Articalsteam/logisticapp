package com.articals.logistics.ui.screen.companysettings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.articals.logistics.core.AppError
import com.articals.logistics.core.AppResult
import com.articals.logistics.data.UploadPayload
import com.articals.logistics.data.api.CompanyRepository
import com.articals.logistics.data.api.ProofPhotoCapture
import com.articals.logistics.data.api.StorageRepository
import com.articals.logistics.data.dto.CompanyDto
import com.articals.logistics.data.dto.PublicTrackingSettingsDto
import com.articals.logistics.data.dto.UpdateCompanySettingsRequest
import com.articals.logistics.ui.text.ArticalsStrings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The company's details, as typed. */
data class CompanyDraft(
    val name: String = "",
    val phone: String = "",
    val email: String = "",
    val address: String = "",
    val serviceAreas: List<String> = emptyList(),
    val areaInput: String = "",
    val brandColorHex: String = "",
    val logoUrl: String = "",
    val orderNumberPrefix: String = "",
    val shareAreasOnTracking: Boolean = true,
    val requireVerificationOnTracking: Boolean = false,
) {
    val nameValid: Boolean get() = name.trim().length >= 2

    val emailValid: Boolean get() = email.isBlank() || email.trim().contains('@')

    val phoneValid: Boolean get() = phone.isBlank() || phone.trim().length >= 7

    /**
     * The colour the backend accepts: a six-digit hex, exactly. Validated here with the
     * same rule rather than a looser one, so a value this form accepts never fails the
     * save with a message about a field that looked fine.
     */
    val brandColourValid: Boolean get() = brandColorHex.isBlank() || HEX_COLOUR.matches(brandColorHex.trim())

    /** The backend allows four characters at most. */
    val prefixValid: Boolean get() = orderNumberPrefix.isBlank() || orderNumberPrefix.trim().length <= 4

    val valid: Boolean
        get() = nameValid && emailValid && phoneValid && brandColourValid && prefixValid

    fun toRequest(tenantId: String): UpdateCompanySettingsRequest = UpdateCompanySettingsRequest(
        tenantId = tenantId,
        companyName = name.trim(),
        contactPhone = phone.trim().takeIf { it.isNotEmpty() },
        contactEmail = email.trim().takeIf { it.isNotEmpty() },
        businessAddress = address.trim().takeIf { it.isNotEmpty() },
        serviceAreas = serviceAreas,
        brandColor = brandColorHex.trim().uppercase().takeIf { it.isNotEmpty() },
        logoUrl = logoUrl.trim().takeIf { it.isNotEmpty() },
        orderNumberPrefix = orderNumberPrefix.trim().uppercase().takeIf { it.isNotEmpty() },
        publicTracking = PublicTrackingSettingsDto(
            shareAreas = shareAreasOnTracking,
            requireVerification = requireVerificationOnTracking,
        ),
    )

    companion object {
        /** The backend's rule, kept private so it is stated once. */
        private val HEX_COLOUR = Regex("^#[0-9A-Fa-f]{6}$")

        fun of(company: CompanyDto) = CompanyDraft(
            name = company.name,
            phone = company.phone.orEmpty(),
            email = company.email.orEmpty(),
            address = company.address.orEmpty(),
            serviceAreas = company.serviceAreas,
            brandColorHex = company.brandColor.orEmpty(),
            logoUrl = company.logoUrl.orEmpty(),
            orderNumberPrefix = company.orderNumberPrefix.orEmpty(),
            shareAreasOnTracking = company.publicTracking?.shareAreas ?: true,
            requireVerificationOnTracking = company.publicTracking?.requireVerification ?: false,
        )
    }
}

data class CompanySettingsState(
    val company: CompanyDto? = null,
    val draft: CompanyDraft? = null,
    val loading: Boolean = true,
    val error: AppError? = null,
    val notice: String? = null,
    val saving: Boolean = false,
    val uploadingLogo: Boolean = false,
    val dirty: Boolean = false,
)

/**
 * Company settings.
 *
 * Everything a customer might see lives here — the name, the phone number on a tracking
 * page, the service areas, the colour, the order-number prefix — plus the two decisions
 * about what the public tracking page reveals. Phone numbers and the prefix are
 * validated here in the same shape the backend accepts, so a prefix the office cannot
 * use fails while the field is still open.
 *
 * The logo can be photographed or pasted as a link. A logo upload goes to Storage under
 * the company's own folder, and the rules allow only an image up to 2 MB.
 */
class CompanySettingsViewModel(
    private val companies: CompanyRepository,
    private val storage: StorageRepository,
    private val photos: ProofPhotoCapture,
) : ViewModel() {

    private val mutableState = MutableStateFlow(CompanySettingsState())
    val state: StateFlow<CompanySettingsState> = mutableState.asStateFlow()

    private var tenantId: String? = null

    fun load(tenantId: String) {
        if (this.tenantId == tenantId) return
        this.tenantId = tenantId
        mutableState.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            companies.observeCompany(tenantId).collect { company ->
                mutableState.update { current ->
                    current.copy(
                        company = company,
                        draft = if (current.dirty) current.draft else company?.let { CompanyDraft.of(it) },
                        loading = false,
                        error = if (company == null) {
                            AppError.NotFound(
                                detail = "company_missing",
                                message = ArticalsStrings.CompanySettings.missing,
                            )
                        } else {
                            null
                        },
                    )
                }
            }
        }
    }

    fun updateDraft(draft: CompanyDraft) = mutableState.update {
        it.copy(draft = draft, dirty = true, notice = null)
    }

    fun addArea() = mutableState.update { current ->
        val draft = current.draft ?: return@update current
        val area = draft.areaInput.trim()
        if (area.isEmpty()) return@update current
        current.copy(
            draft = draft.copy(
                serviceAreas = (draft.serviceAreas + area).distinct(),
                areaInput = "",
            ),
            dirty = true,
            notice = null,
        )
    }

    fun removeArea(area: String) = mutableState.update { current ->
        val draft = current.draft ?: return@update current
        current.copy(
            draft = draft.copy(serviceAreas = draft.serviceAreas.filterNot { it == area }),
            dirty = true,
            notice = null,
        )
    }

    fun clearNotice() = mutableState.update { it.copy(notice = null) }

    fun discardChanges() = mutableState.update { current ->
        val company = current.company ?: return@update current
        current.copy(draft = CompanyDraft.of(company), dirty = false)
    }

    fun save() {
        val tenantId = tenantId ?: return
        val draft = mutableState.value.draft ?: return
        if (!draft.valid) {
            mutableState.update { it.copy(error = ArticalsStrings.CompanySettings.incomplete()) }
            return
        }

        mutableState.update { it.copy(saving = true, error = null, notice = null) }
        viewModelScope.launch {
            when (val result = companies.updateSettings(draft.toRequest(tenantId))) {
                is AppResult.Failure -> mutableState.update { it.copy(saving = false, error = result.error) }
                is AppResult.Success -> mutableState.update {
                    it.copy(saving = false, dirty = false, notice = ArticalsStrings.CompanySettings.saved)
                }
            }
        }
    }

    /**
     * Takes a photo of the logo and uploads it.
     *
     * Nothing is claimed about the result until Storage answers with a URL: a failed
     * upload leaves the old logo in place and says so.
     */
    fun captureLogo() {
        val tenantId = tenantId ?: return
        mutableState.update { it.copy(uploadingLogo = true, error = null, notice = null) }
        viewModelScope.launch {
            when (val captured = photos.capture()) {
                is AppResult.Failure -> mutableState.update {
                    it.copy(uploadingLogo = false, error = captured.error)
                }

                is AppResult.Success -> {
                    val payload: UploadPayload = captured.value
                    // The same size-and-type gate the storage rules enforce, so an
                    // oversized photo is refused before the upload starts.
                    val problem = storage.validateProof(payload)
                    if (problem != null) {
                        mutableState.update { it.copy(uploadingLogo = false, error = problem) }
                        return@launch
                    }
                    when (val uploaded = storage.uploadCompanyLogo(tenantId, payload)) {
                        is AppResult.Failure -> mutableState.update {
                            it.copy(uploadingLogo = false, error = uploaded.error)
                        }

                        is AppResult.Success -> mutableState.update { current ->
                            val draft = current.draft
                            current.copy(
                                uploadingLogo = false,
                                dirty = true,
                                notice = ArticalsStrings.CompanySettings.logoUploaded,
                                draft = draft?.copy(logoUrl = uploaded.value),
                            )
                        }
                    }
                }
            }
        }
    }
}
