package com.oceanreader.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.oceanreader.ui.theme.LocalAccent
import com.oceanreader.ui.theme.LocalGlass
import com.oceanreader.ui.theme.SuperCard
import com.oceanreader.ui.theme.contentColor
import com.oceanreader.ui.theme.glassPanel

/* ---------------------------------------------------------------------------
 *  Layout tokens — every screen lays out on the same 4dp grid and the same
 *  control sizes, so a button is the same size wherever it appears.
 * ------------------------------------------------------------------------- */

/** Spacing scale (4/8/12/16/20/24dp). */
object AppSpacing {
    val xs: Dp = 4.dp
    val sm: Dp = 8.dp
    val md: Dp = 12.dp
    val lg: Dp = 16.dp
    val xl: Dp = 20.dp
    val xxl: Dp = 24.dp
    val screen: Dp = 20.dp
}

/** Control sizes: 40dp visual targets inside 48dp touch areas. */
object AppSize {
    val iconButton: Dp = 40.dp
    val touchTarget: Dp = 48.dp
    val fab: Dp = 56.dp
    val listRow: Dp = 56.dp
    val chip: Dp = 32.dp
    val coverRatio: Float = 0.70f
    /** Bottom-sheet corner radius, matching Material's large shape. */
    val sheetCorner: Dp = 28.dp
}

/** Top app bar with a frosted backdrop. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuroraTopBar(
    title: String,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {}
) {
    val glass = LocalGlass.current
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = glass.topBarTint.copy(alpha = 0.92f),
        tonalElevation = 2.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 4.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                }
            } else {
                Spacer(Modifier.width(16.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (subtitle != null) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            actions()
        }
    }
}

/** Frosted bottom bar used by the reader and the shelf selection mode. */
@Composable
fun GlassBottomBar(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val glass = LocalGlass.current
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = glass.bottomBarTint.copy(alpha = 0.92f),
        tonalElevation = 2.dp
    ) {
        Column(modifier = Modifier.navigationBarsPadding()) {
            content()
        }
    }
}

/**
 * Standard settings/menu row: leading icon, title, optional subtitle and a
 * trailing control. Every list on every screen is built from this.
 */
@Composable
fun ListRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null
) {
    val base = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(base)
            .padding(horizontal = AppSpacing.screen, vertical = AppSpacing.md),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(AppSpacing.lg))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(AppSpacing.md))
            trailing()
        }
    }
}

/** Standard header inside a bottom sheet (drag handle + title + subtitle). */
@Composable
fun SheetHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .padding(vertical = AppSpacing.sm)
                .size(width = 32.dp, height = 4.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.outlineVariant)
        )
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(horizontal = AppSpacing.xxl)
        )
        if (subtitle != null) {
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = AppSpacing.xxl)
            )
        }
    }
}

/** Filter chip used for sorting / view switching on the shelf. */
@Composable
fun AppFilterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        modifier = modifier,
        label = { Text(label) },
        leadingIcon = if (selected) {
            { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
        } else null
    )
}

/** Large hero number with a label, used by the stats screen. */
@Composable
fun StatTile(
    value: String,
    label: String,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = SuperCard,
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 1.dp
    ) {
        Column(
            modifier = Modifier.padding(vertical = AppSpacing.lg, horizontal = AppSpacing.md),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                value,
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
            )
            Spacer(Modifier.height(AppSpacing.xs))
            Text(
                label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** A circular icon button matching the ColorOS tile style. */
@Composable
fun AuroraIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color = MaterialTheme.colorScheme.onSurface
) {
    FilledIconButton(
        onClick = onClick,
        modifier = modifier.size(40.dp),
        enabled = enabled,
        colors = IconButtonDefaults.filledIconButtonColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = tint
        )
    ) {
        Icon(icon, contentDescription)
    }
}

/** Section heading used on the shelf and inside sheets. */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
        )
        Spacer(Modifier.weight(1f))
        trailing?.invoke()
    }
}

/** Shown when a list is empty. */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    hint: String,
    modifier: Modifier = Modifier,
    action: @Composable (() -> Unit)? = null
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(48.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(96.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(20.dp))
        Text(title, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
        Spacer(Modifier.height(8.dp))
        Text(
            hint,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (action != null) {
            Spacer(Modifier.height(24.dp))
            action()
        }
    }
}

/** Colour chip row (page palettes, highlight colours). */
@Composable
fun ColorChipRow(
    colors: List<Color>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        colors.forEachIndexed { index, color ->
            val selected = index == selectedIndex
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(color)
                    .clickable { onSelect(index) },
                contentAlignment = Alignment.Center
            ) {
                if (selected) {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = null,
                        tint = color.contentColor(),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

/**
 * A labelled setting row with an optional trailing control.
 * Thin alias over [ListRow] so settings pages and menus look identical.
 */
@Composable
fun SettingRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    trailing: @Composable (() -> Unit)? = null
) {
    ListRow(title = title, modifier = modifier, subtitle = subtitle, trailing = trailing)
}