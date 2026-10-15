package com.articals.logistics.ui.screen.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.articals.logistics.core.AppError
import com.articals.logistics.ui.component.ArticalsLogo
import com.articals.logistics.ui.component.ArticalsPasswordField
import com.articals.logistics.ui.component.ArticalsTextField
import com.articals.logistics.ui.component.Banner
import com.articals.logistics.ui.component.BannerTone
import com.articals.logistics.ui.demo.DemoStrip
import com.articals.logistics.ui.text.ArticalsStrings
import com.articals.logistics.ui.theme.Spacing
import org.koin.compose.viewmodel.koinViewModel

/** Registration. The verification step follows, because the address is not trusted yet. */
@Composable
fun RegisterScreen(
    onRegistered: () -> Unit,
    onBackToSignIn: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: RegisterViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(state.succeeded) {
        if (state.succeeded) onRegistered()
    }

    AuthPageScaffold(
        modifier = modifier,
        title = ArticalsStrings.Auth.registerTitle,
        subtitle = ArticalsStrings.Auth.registerSubtitle,
        error = state.error,
    ) {
        ArticalsTextField(
            value = state.displayName,
            onValueChange = viewModel::onNameChange,
            label = ArticalsStrings.Auth.displayName,
            helper = ArticalsStrings.Common.optional,
            enabled = !state.busy,
        )
        Spacer(Modifier.height(Spacing.md))
        ArticalsTextField(
            value = state.email,
            onValueChange = viewModel::onEmailChange,
            label = ArticalsStrings.Auth.email,
            error = state.errorFor("email"),
            enabled = !state.busy,
            keyboardType = KeyboardType.Email,
        )
        Spacer(Modifier.height(Spacing.md))
        ArticalsPasswordField(
            value = state.password,
            onValueChange = viewModel::onPasswordChange,
            label = ArticalsStrings.Auth.password,
            error = state.errorFor("password"),
            enabled = !state.busy,
            imeAction = ImeAction.Done,
            onDone = viewModel::register,
        )
        Spacer(Modifier.height(Spacing.xl))
        Button(
            onClick = viewModel::register,
            modifier = Modifier.fillMaxWidth().height(Spacing.touchTarget),
            enabled = state.canSubmit,
        ) {
            Text(if (state.busy) ArticalsStrings.Auth.registering else ArticalsStrings.Auth.register)
        }
        Spacer(Modifier.height(Spacing.sm))
        TextButton(onClick = onBackToSignIn, modifier = Modifier.fillMaxWidth()) {
            Text(ArticalsStrings.Auth.haveAccount)
        }
    }
}

/**
 * Password reset.
 *
 * The confirmation is the same whether or not the address exists — that is the whole
 * point of this screen, and it is why the view model treats both as success.
 */
@Composable
fun ResetPasswordScreen(
    onBackToSignIn: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ResetPasswordViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    AuthPageScaffold(
        modifier = modifier,
        title = ArticalsStrings.Auth.resetTitle,
        subtitle = ArticalsStrings.Auth.resetBody,
        error = state.error,
    ) {
        if (state.succeeded) {
            Banner(
                text = ArticalsStrings.Auth.resetSentBody,
                title = ArticalsStrings.Auth.resetSentTitle,
                tone = BannerTone.Success,
            )
            Spacer(Modifier.height(Spacing.lg))
            Button(
                onClick = onBackToSignIn,
                modifier = Modifier.fillMaxWidth().height(Spacing.touchTarget),
            ) { Text(ArticalsStrings.Auth.signIn) }
        } else {
            ArticalsTextField(
                value = state.email,
                onValueChange = viewModel::onEmailChange,
                label = ArticalsStrings.Auth.email,
                error = state.errorFor("email"),
                enabled = !state.busy,
                keyboardType = KeyboardType.Email,
                imeAction = ImeAction.Done,
            )
            Spacer(Modifier.height(Spacing.xl))
            Button(
                onClick = viewModel::send,
                modifier = Modifier.fillMaxWidth().height(Spacing.touchTarget),
                enabled = state.email.isNotBlank() && !state.busy,
            ) { Text(ArticalsStrings.Auth.sendResetLink) }
            Spacer(Modifier.height(Spacing.sm))
            TextButton(onClick = onBackToSignIn, modifier = Modifier.fillMaxWidth()) {
                Text(ArticalsStrings.Auth.haveAccount)
            }
        }
    }
}

/**
 * Email verification.
 *
 * Two honest behaviours share this screen: a real build re-reads the account and waits
 * for the link to have been clicked; a demo build has no inbox, so the button marks
 * the address verified and the screen says exactly that.
 */
@Composable
fun VerifyEmailScreen(
    onVerified: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: VerifyEmailViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(state.succeeded) {
        if (state.succeeded) onVerified()
    }

    AuthPageScaffold(
        modifier = modifier,
        title = ArticalsStrings.Auth.verifyTitle,
        subtitle = viewModel.email?.let { "${ArticalsStrings.Auth.verifyBody}" } ?: ArticalsStrings.Auth.verifyBody,
        error = state.error,
    ) {
        Button(
            onClick = viewModel::check,
            modifier = Modifier.fillMaxWidth().height(Spacing.touchTarget),
            enabled = !state.busy,
        ) {
            Text(if (state.busy) ArticalsStrings.Auth.verifyChecking else ArticalsStrings.Auth.verifyCheck)
        }
        Spacer(Modifier.height(Spacing.sm))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            TextButton(onClick = viewModel::resend) { Text(ArticalsStrings.Auth.verifyResend) }
            TextButton(onClick = viewModel::signOut) { Text(ArticalsStrings.Auth.signOut) }
        }
        if (viewModel.isDemo) {
            Spacer(Modifier.height(Spacing.lg))
            Banner(text = ArticalsStrings.Auth.verifySimulated, tone = BannerTone.Warning)
        }
    }
}

/** The shared frame of the four unauthenticated screens. */
@Composable
private fun AuthPageScaffold(
    title: String,
    subtitle: String,
    error: AppError?,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        DemoStrip()
        Column(modifier = Modifier.padding(Spacing.screen)) {
            Spacer(Modifier.height(Spacing.xl))
            ArticalsLogo()
            Spacer(Modifier.height(Spacing.lg))
            Text(title, style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(Spacing.xs))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(Spacing.xl))
            error?.let {
                Banner(
                    text = it.userMessage,
                    title = ArticalsStrings.Errors.titleFor(it),
                    tone = BannerTone.Danger,
                )
                Spacer(Modifier.height(Spacing.md))
            }
            content()
            Spacer(Modifier.height(Spacing.xxl))
        }
    }
}
