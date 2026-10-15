package com.articals.logistics.data.api

import com.articals.logistics.core.AppResult
import com.articals.logistics.data.dto.AckDto
import com.articals.logistics.data.dto.CompanyDto
import com.articals.logistics.data.dto.CreateCompanyRequest
import com.articals.logistics.data.dto.CreateCompanyResponse
import com.articals.logistics.data.dto.UpdateCompanySettingsRequest
import kotlinx.coroutines.flow.Flow

/** The company workspace: identity, branding, service areas, onboarding state. */
interface CompanyRepository {
    fun observeCompany(tenantId: String): Flow<CompanyDto?>

    suspend fun createCompany(request: CreateCompanyRequest): AppResult<CreateCompanyResponse>

    suspend fun updateSettings(request: UpdateCompanySettingsRequest): AppResult<AckDto>

    suspend fun completeOnboardingStep(
        tenantId: String,
        step: String,
        completedSteps: List<String>,
        isComplete: Boolean,
    ): AppResult<AckDto>
}
