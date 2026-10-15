package com.articals.logistics.data.repository

import com.articals.logistics.core.AppResult
import com.articals.logistics.data.BackendFunctions
import com.articals.logistics.data.FirestorePaths
import com.articals.logistics.data.FunctionsCaller
import com.articals.logistics.data.dto.AckDto
import com.articals.logistics.data.dto.CompanyDto
import com.articals.logistics.data.dto.CreateCompanyRequest
import com.articals.logistics.data.dto.CreateCompanyResponse
import com.articals.logistics.data.dto.UpdateCompanySettingsRequest
import com.articals.logistics.data.documentFlow
import dev.gitlive.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.flow.Flow

/** The company workspace: identity, branding, service areas, onboarding state. */
class CompanyRepository(
    private val firestore: FirebaseFirestore,
    private val caller: FunctionsCaller,
) {
    /** Live company document. Branding changes appear on every device at once. */
    fun observeCompany(tenantId: String): Flow<CompanyDto?> =
        firestore.documentFlow(FirestorePaths.company(tenantId))

    /**
     * Creates a company workspace and makes the caller its owner.
     *
     * [CreateCompanyRequest.idempotencyKey] must be stable per attempt (the
     * onboarding screen generates one when the form opens) so a retry after a
     * dropped connection cannot create a second, empty workspace.
     */
    suspend fun createCompany(request: CreateCompanyRequest): AppResult<CreateCompanyResponse> =
        caller.call(BackendFunctions.createCompany, request)

    /** Updates the settings a manager may change. */
    suspend fun updateSettings(request: UpdateCompanySettingsRequest): AppResult<AckDto> =
        caller.call(BackendFunctions.updateCompanySettings, request)

    /** Marks onboarding progress without leaving the flow. */
    suspend fun completeOnboardingStep(
        tenantId: String,
        step: String,
        completedSteps: List<String>,
        isComplete: Boolean,
    ): AppResult<AckDto> = caller.call(
        BackendFunctions.updateCompanySettings,
        UpdateCompanySettingsRequest(
            tenantId = tenantId,
            onboardingSteps = (completedSteps + step).distinct(),
            onboardingComplete = isComplete,
        ),
    )
}
