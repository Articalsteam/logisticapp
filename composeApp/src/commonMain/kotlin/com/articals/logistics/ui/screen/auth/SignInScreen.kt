package com.articals.logistics.ui.screen.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.articals.logistics.core.DemoAccount
import com.articals.logistics.ui.component.ArticalsLogo
import com.articals.logistics.ui.component.ArticalsPasswordField
import com.articals.logistics.ui.component.ArticalsTextField
import com.articals.logistics.ui.component.Banner
import com.articals.logistics.ui.component.BannerTone
import com.articals.logistics.ui.demo.DemoStrip
import com.articals.logistics.ui.component.SectionCard
import com.articals.logistics.ui.demo.DemoNotice
import com.articals.logistics.ui.text.ArticalsStrings
import com.articals.logistics.ui.theme.Spacing
import org.koin.compose.viewmodel.koinViewModel

/**
 * Sign-in.
 *
 * In a demo build this screen carries the one-tap accounts and the shared password,
 * clearly labelled: somebody opening the app for the first time without being told
 * how to get in would otherwise have to be walked through it. The DEMO strip sits at
 * the very top, above everything, on this screen as on every other.
 */
@Composable
fun SignInScreen(
    onSignedIn: () -> Unit,
    onRegister: () -> Unit,
    onForgotPassword: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SignInViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(state.succeeded) {
        if (state.succeeded) onSignedIn()
    }

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
            Text(ArticalsStrings.Auth.signInTitle, style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(Spacing.xs))
            Text(
                text = ArticalsStrings.Auth.signInSubtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(Spacing.xl))

            // The message is the backend's own, already safe to show; the title is ours
            // so that every sign-in failure looks like a sign-in failure.
            state.error?.let { error ->
                Banner(
                    text = error.userMessage,
                    tone = BannerTone.Danger,
                    title = ArticalsStrings.Errors.titleFor(error),
                )
                Spacer(Modifier.height(Spacing.md))
            }

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
                onDone = viewModel::signIn,
            )

            Spacer(Modifier.height(Spacing.xl))
            Button(
                onClick = viewModel::signIn,
                modifier = Modifier.fillMaxWidth().height(Spacing.touchTarget),
                enabled = state.canSubmit,
            ) {
                Text(if (state.busy) ArticalsStrings.Auth.signingIn else ArticalsStrings.Auth.signIn)
            }

            Spacer(Modifier.height(Spacing.sm))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                TextButton(onClick = onRegister) { Text(ArticalsStrings.Auth.register) }
                TextButton(onClick = onForgotPassword) { Text(ArticalsStrings.Auth.forgotPassword) }
            }

            if (viewModel.isDemo) {
                Spacer(Modifier.height(Spacing.xl))
                HorizontalDivider()
                Spacer(Modifier.height(Spacing.lg))
                Text(
                    text = ArticalsStrings.Auth.demoAccountsTitle,
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(Spacing.xs))
                Text(
                    text = ArticalsStrings.Auth.demoAccountsBody,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(Spacing.md))
                DemoAccountList(
                    accounts = viewModel.demoAccounts,
                    password = viewModel.demoPassword,
                    onUse = { account ->
                        viewModel.useDemoAccount(account.email)
                    },
                )
                Spacer(Modifier.height(Spacing.md))
                DemoNotice()
            }

            Spacer(Modifier.height(Spacing.xxl))
        }
    }
}

/**
 * The demo roster.
 *
 * Each row fills the form; the person then taps Sign in. It is deliberately two taps
 * rather than one: the credentials appear in the fields first, so it is obvious which
 * account is being used and no data appears without being asked for.
 */
@Composable
private fun DemoAccountList(
    accounts: List<DemoAccount>,
    password: String,
    onUse: (DemoAccount) -> Unit,
    modifier: Modifier = Modifier,
) {
    var usedEmail by remember { mutableStateOf<String?>(null) }

    Column(modifier = modifier.fillMaxWidth()) {
        accounts.forEach { account ->
            SectionCard(modifier = Modifier.padding(bottom = Spacing.sm)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = account.title,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = account.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = account.email,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Spacer(Modifier.width(Spacing.sm))
                    OutlinedButton(onClick = { usedEmail = account.email; onUse(account) }) {
                        Text(
                            if (usedEmail == account.email) {
                                ArticalsStrings.Common.done
                            } else {
                                ArticalsStrings.Auth.useAccount
                            },
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(Spacing.xs))
        Text(
            text = ArticalsStrings.Demo.fakeAccount(password),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
