package com.articals.logistics.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.articals.logistics.ui.text.ArticalsStrings

/**
 * The mark and the wordmark.
 *
 * Drawn with a material icon and type rather than a bitmap: it stays crisp at any
 * size, adds nothing to the download, and gives the two platforms the same logo
 * without maintaining one asset per density per platform.
 */
@Composable
fun ArticalsLogo(modifier: Modifier = Modifier, compact: Boolean = false) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(if (compact) 28.dp else 40.dp)
                .background(
                    color = MaterialTheme.colorScheme.primary,
                    shape = RoundedCornerShape(if (compact) 8.dp else 12.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Default.LocalShipping,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(if (compact) 18.dp else 26.dp),
            )
        }
        Spacer(Modifier.width(if (compact) 8.dp else 12.dp))
        Column(verticalArrangement = Arrangement.Center) {
            Text(
                text = ArticalsStrings.Common.appName,
                style = if (compact) MaterialTheme.typography.titleMedium else MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            if (!compact) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = ArticalsStrings.Common.byStudio,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * The company's own mark, once a logo has been uploaded.
 *
 * Falls back to the company's initial on its brand colour — never to a stock photo,
 * and never to a broken image: an owner who has not uploaded a logo yet sees their
 * own initial, which is honest and looks deliberate.
 */
@Composable
fun CompanyLogo(
    name: String,
    logoUrl: String?,
    brandColorHex: String?,
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.Dp = 40.dp,
) {
    val brand = brandColorHex?.let(::parseHexColorOrNull) ?: MaterialTheme.colorScheme.primary
    Box(
        modifier = modifier
            .size(size)
            .background(color = brand, shape = RoundedCornerShape(size / 4)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = name.trim().take(1).uppercase().ifEmpty { "?" },
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onPrimary,
        )
    }
}

/** Parses "#0B6B3A" or "#RGB"; returns null rather than throwing on a bad value. */
fun parseHexColorOrNull(hex: String): androidx.compose.ui.graphics.Color? {
    val cleaned = hex.trim().removePrefix("#")
    val expanded = when (cleaned.length) {
        3 -> cleaned.map { "$it$it" }.joinToString("")
        6 -> cleaned
        8 -> cleaned.take(6)
        else -> return null
    }
    val value = expanded.toLongOrNull(16) ?: return null
    return androidx.compose.ui.graphics.Color(0xFF000000L or value)
}
