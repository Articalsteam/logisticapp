package com.articals.logistics.ui.screen.system

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import com.articals.logistics.core.AppConfig
import com.articals.logistics.ui.component.ArticalsLogo
import com.articals.logistics.ui.component.Banner
import com.articals.logistics.ui.component.BannerTone
import com.articals.logistics.ui.component.SectionCard
import com.articals.logistics.ui.demo.BuildFooterCard
import com.articals.logistics.ui.demo.DemoStrip
import com.articals.logistics.ui.text.ArticalsStrings
import com.articals.logistics.ui.theme.Spacing

/**
 * The three screens that are not about a company's work: waiting, a build without
 * configuration, and a role meeting a screen it may not use.
 *
 * They are deliberately plain. Each one exists to tell the truth about a situation
 * quickly — and in the not-configured case, to say exactly what to do about it.
 */

/**
 * Shown while the session is being restored.
 *
 * A Firebase build has to ask the SDK who is signed in, which takes a moment; showing
 * the sign-in form first would make a signed-in dispatcher watch their own app look
 * like it had forgotten them.
 */
@Composable
fun SplashScreen(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        ArticalsLogo(compact = true)
        Spacer(Modifier.height(Spacing.xl))
        CircularProgressIndicator()
        Spacer(Modifier.height(Spacing.md))
        Text(
            text = ArticalsStrings.Shell.loadingJourney,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(Spacing.xxl))
        DemoStrip()
    }
}

/**
 * A Firebase build that was compiled without `google-services.json` or the iOS plist.
 *
 * The alternative — falling back to the demo data — would be the worst outcome
 * available: a company would be looking at fictional deliveries believing them to be
 * real. So this screen exists, it says what is missing, and it says where the
 * instructions are.
 */
@Composable
fun NotConfiguredScreen(
    config: AppConfig,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(Spacing.screen),
    ) {
        Spacer(Modifier.height(Spacing.xl))
        ArticalsLogo()
        Spacer(Modifier.height(Spacing.xl))

        Text(
            text = ArticalsStrings.Config.firebaseMissingTitle,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(Spacing.sm))
        Text(
            text = ArticalsStrings.Config.firebaseMissingBody,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(Spacing.lg))
        SectionCard(title = ArticalsStrings.Shell.support) {
            StepLine("1", ArticalsStrings.Config.firebaseMissingStep1)
            StepLine("2", ArticalsStrings.Config.firebaseMissingStep2)
            StepLine("3", ArticalsStrings.Config.firebaseMissingStep3)
            Spacer(Modifier.height(Spacing.sm))
            Text(
                text = "docs/SETUP.md",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = ArticalsStrings.Config.readSetupGuide,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(Spacing.md))
        Banner(
            text = ArticalsStrings.Config.demoInstead,
            tone = BannerTone.Info,
        )

        Spacer(Modifier.height(Spacing.lg))
        BuildFooterCard()

        if (!config.isRunnable) {
            Spacer(Modifier.height(Spacing.md))
            Text(
                text = "articals.mode=${config.mode.name.lowercase()}, firebaseConfigured=false",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** One numbered instruction. The number is a label, not decoration. */
@Composable
private fun StepLine(number: String, text: String) {
    Column(modifier = Modifier.padding(bottom = Spacing.sm)) {
        Text(
            text = "$number. $text",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

/**
 * A route this role may not open.
 *
 * Reachable only if something navigates without asking [com.articals.logistics.ui.nav.Routes.canOpen],
 * or if a role changes while a screen is on the stack — a rider who has just been
 * demoted, say. It states the rule and offers the one useful action.
 */
@Composable
fun NoAccessScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(Spacing.screen),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = ArticalsStrings.Shell.noAccessTitle,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(Spacing.sm))
        Text(
            text = ArticalsStrings.Shell.noAccessBody,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(Spacing.xl))
        Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
            Text(ArticalsStrings.Shell.backToYourWork)
        }
    }
}

/**
 * Shown when the company's access has stopped covering new work.
 *
 * It is not a wall: reading, exporting and sharing keep working, and the screen says so.
 * What it explains is the difference between "you cannot work" and "you cannot start new
 * work" — the second is what a limited workspace actually means.
 */
@Composable
fun LimitedAccessScreen(
    entitlement: String?,
    daysRemaining: Int?,
    onOpenSubscription: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state = entitlement.orEmpty()
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(Spacing.screen),
    ) {
        DemoStrip()
        Spacer(Modifier.height(Spacing.md))
        Text(
            text = ArticalsStrings.Subscription.readOnlyTitle,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(Spacing.sm))
        Banner(
            text = com.articals.logistics.domain.EntitlementStates.notice(state, daysRemaining)
                ?: ArticalsStrings.Subscription.readOnlyBody,
            tone = BannerTone.Warning,
        )

        Spacer(Modifier.height(Spacing.md))
        SectionCard(title = ArticalsStrings.Subscription.title) {
            Text(
                text = ArticalsStrings.Subscription.labelFor(state),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(Spacing.xs))
            Text(
                text = ArticalsStrings.Subscription.readOnlyBody,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(Spacing.md))
        SectionCard(title = ArticalsStrings.Subscription.stillWorksTitle) {
            ArticalsStrings.Subscription.stillWorksBody.forEach { line ->
                Text(
                    text = "• $line",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        Spacer(Modifier.height(Spacing.md))
        Button(onClick = onOpenSubscription, modifier = Modifier.fillMaxWidth()) {
            Text(ArticalsStrings.Subscription.title)
        }
        Spacer(Modifier.height(Spacing.sm))
        Button(
            onClick = onBack,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(ArticalsStrings.Common.back)
        }
        Spacer(Modifier.height(Spacing.xxl))
    }
}
