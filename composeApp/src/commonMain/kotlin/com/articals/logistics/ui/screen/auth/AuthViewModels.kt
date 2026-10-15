package com.articals.logistics.ui.screen.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.articals.logistics.core.AppConfig
import com.articals.logistics.core.AppError
import com.articals.logistics.core.AppResult
import com.articals.logistics.core.AuthUser
import com.articals.logistics.core.DemoCredentials
import com.articals.logistics.data.api.SessionRepository
import com.articals.logistics.di.SupportLocator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The sign-in, registration, reset and verification forms.
 *
 * They share one shape — a set of fields, a busy flag, one error — and one rule: the
 * failure the repository returns is shown exactly as the backend wrote it, field
 * errors included, so a person sees "that email address already has an account"
 * rather than "operation failed".
 */

data class AuthFormState(
    val email: String = "",
    val password: String = "",
    val displayName: String = "",
    val busy: Boolean = false,
    val error: AppError? = null,
    val fieldErrors: Map<String, String> = emptyMap(),
    val succeeded: Boolean = false,
) {
    fun errorFor(field: String): String? = error?.fieldErrors?.get(field) ?: fieldErrors[field]
    val canSubmit: Boolean get() = email.isNotBlank() && password.isNotBlank() && !busy
}

/** The list of one-tap demo accounts, empty in a build that is not a demo. */
fun demoAccountsFor(config: AppConfig) : List<com.articals.logistics.core.DemoAccount> =
    if (config.mode.isDemo) DemoCredentials.accounts else emptyList()

/**
 * Sign-in.
 *
 * In a demo build the accounts can be filled in with one tap — and the password is
 * shown on screen, because a demo that hides its own credentials is a demo nobody can
 * use. In a Firebase build none of that appears.
 */
class SignInViewModel(
    private val session: SessionRepository,
) : ViewModel() {

    private val mutableState = MutableStateFlow(AuthFormState())
    val state: StateFlow<AuthFormState> = mutableState.asStateFlow()

    /** True when the account list and the demo password should be offered. */
    val isDemo: Boolean = SupportLocator.config().mode.isDemo

    val demoAccounts = demoAccountsFor(SupportLocator.config())

    val demoPassword: String = if (isDemo) DemoCredentials.password else ""

    fun onEmailChange(value: String) = update { it.copy(email = value, error = null) }

    fun onPasswordChange(value: String) = update { it.copy(password = value, error = null) }

    /** Fills the form from a demo account; the tap is the whole gesture. */
    fun useDemoAccount(email: String) {
        update { it.copy(email = email, password = DemoCredentials.password, error = null, fieldErrors = emptyMap()) }
    }

    fun signIn() {
        val current = mutableState.value
        if (!current.canSubmit) return
        update { it.copy(busy = true, error = null, fieldErrors = emptyMap()) }
        viewModelScope.launch {
            when (val result = session.signIn(current.email.trim(), current.password)) {
                is AppResult.Success -> update { it.copy(busy = false, succeeded = true) }
                is AppResult.Failure -> update {
                    it.copy(busy = false, error = result.error, fieldErrors = result.error.fieldErrors)
                }
            }
        }
    }

    private fun update(transform: (AuthFormState) -> AuthFormState) {
        mutableState.value = transform(mutableState.value)
    }
}

/** Registration: creates the account, then leaves the email unverified on purpose. */
class RegisterViewModel(
    private val session: SessionRepository,
) : ViewModel() {

    private val mutableState = MutableStateFlow(AuthFormState())
    val state: StateFlow<AuthFormState> = mutableState.asStateFlow()

    val isDemo: Boolean = SupportLocator.config().mode.isDemo

    fun onEmailChange(value: String) = update { it.copy(email = value, error = null) }

    fun onPasswordChange(value: String) = update { it.copy(password = value, error = null) }

    fun onNameChange(value: String) = update { it.copy(displayName = value, error = null) }

    fun register() {
        val current = mutableState.value
        if (!current.canSubmit || current.busy) return
        if (current.password.length < MIN_PASSWORD_LENGTH) {
            update { it.copy(fieldErrors = mapOf("password" to "Use at least 8 characters.")) }
            return
        }
        update { it.copy(busy = true, error = null, fieldErrors = emptyMap()) }
        viewModelScope.launch {
            val result = session.register(
                email = current.email.trim(),
                password = current.password,
                displayName = current.displayName.trim().ifBlank { current.email.substringBefore('@') },
            )
            when (result) {
                is AppResult.Success -> update { it.copy(busy = false, succeeded = true) }
                is AppResult.Failure -> update {
                    it.copy(busy = false, error = result.error, fieldErrors = result.error.fieldErrors)
                }
            }
        }
    }

    private fun update(transform: (AuthFormState) -> AuthFormState) {
        mutableState.value = transform(mutableState.value)
    }

    private companion object {
        const val MIN_PASSWORD_LENGTH = 8
    }
}

/**
 * Password reset.
 *
 * The screen says the same thing whether or not the address has an account, and this
 * view model is what makes that true: both outcomes end in [ResetState.sent], and the
 * only way to see an error is for the request itself to fail.
 */
class ResetPasswordViewModel(
    private val session: SessionRepository,
) : ViewModel() {

    private val mutableState = MutableStateFlow(AuthFormState())
    val state: StateFlow<AuthFormState> = mutableState.asStateFlow()

    fun onEmailChange(value: String) = update { it.copy(email = value, error = null) }

    fun send() {
        val current = mutableState.value
        if (current.email.isBlank() || current.busy) return
        update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            when (val result = session.sendPasswordReset(current.email.trim())) {
                is AppResult.Success -> update { it.copy(busy = false, succeeded = true) }
                is AppResult.Failure -> update { it.copy(busy = false, error = result.error) }
            }
        }
    }

    fun reset() = update { AuthFormState() }

    private fun update(transform: (AuthFormState) -> AuthFormState) {
        mutableState.value = transform(mutableState.value)
    }
}

/**
 * Email verification.
 *
 * "I have verified my email" re-reads the account from the identity provider, which
 * is what makes the link click count. Demo mode has no inbox, so the same button
 * marks the address verified — and the screen says so, in as many words.
 */
class VerifyEmailViewModel(
    private val session: SessionRepository,
) : ViewModel() {

    private val mutableState = MutableStateFlow(AuthFormState())
    val state: StateFlow<AuthFormState> = mutableState.asStateFlow()

    val isDemo: Boolean = SupportLocator.config().mode.isDemo

    /** The address waiting to be verified, so the screen can name it. */
    val email: String? = session.currentAuthUser()?.email

    fun check() {
        if (mutableState.value.busy) return
        update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            when (val result = session.reloadUser()) {
                is AppResult.Success -> {
                    val user: AuthUser? = session.currentAuthUser()
                    update { it.copy(busy = false, succeeded = user?.isEmailVerified == true) }
                }

                is AppResult.Failure -> update { it.copy(busy = false, error = result.error) }
            }
        }
    }

    fun resend() {
        val address = email ?: return
        viewModelScope.launch {
            session.sendPasswordReset(address)
        }
    }

    fun signOut() {
        viewModelScope.launch { session.signOut() }
    }

    private fun update(transform: (AuthFormState) -> AuthFormState) {
        mutableState.value = transform(mutableState.value)
    }
}
