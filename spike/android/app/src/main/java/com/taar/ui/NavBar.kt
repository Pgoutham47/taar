package com.taar.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/**
 * The tab bar: a floating ink pill, icons only, the active tab in a white disc.
 *
 * Icons are drawn here as thin line icons in one style, rather than taken from
 * Material's filled set, whose wrench and star did not say "tools" and "ask". Each
 * tab keeps its name for TalkBack, since the bar shows no words.
 */
@Composable
fun TaarNavBar(current: MainActivity.Tab, onTab: (MainActivity.Tab) -> Unit) {
    Box(
        Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars)
            .padding(horizontal = 24.dp, vertical = 12.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().height(72.dp)
                .shadow(12.dp, CircleShape, ambientColor = Color.Black.copy(alpha = 0.25f), spotColor = Color.Black.copy(alpha = 0.25f))
                .clip(CircleShape).background(TaarPalette.Ink)
                .padding(horizontal = 10.dp)
                .selectableGroup(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for (t in MainActivity.Tab.entries) NavItem(t, selected = t == current, onClick = { onTab(t) })
        }
    }
}

@Composable
private fun NavItem(tab: MainActivity.Tab, selected: Boolean, onClick: () -> Unit) {
    val disc by animateColorAsState(if (selected) Color.White else Color.Transparent, tween(200), label = "disc")
    val tint by animateColorAsState(if (selected) TaarPalette.Ink else NavIdle, tween(200), label = "tint")
    Box(
        Modifier.size(54.dp).clip(CircleShape).background(disc)
            .selectable(
                selected = selected, onClick = onClick, role = Role.Tab,
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(bounded = true, color = Color.White),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(iconOf(tab), contentDescription = tab.label, tint = tint, modifier = Modifier.size(24.dp))
    }
}

/** Inactive icons: light enough to read on ink (7:1), quiet enough not to compete with the active disc. */
private val NavIdle = Color(0xFFA3A9B6)

private fun iconOf(tab: MainActivity.Tab) = when (tab) {
    MainActivity.Tab.HOME -> NavIcons.Home
    MainActivity.Tab.TOOLS -> NavIcons.Tools
    MainActivity.Tab.ASK -> NavIcons.Ask
    MainActivity.Tab.HISTORY -> NavIcons.History
}

/** 24-unit line icons, 1.8 stroke, round caps and joins. */
private object NavIcons {
    val Home = line(
        "home",
        "M3 10.5 12 3l9 7.5V19a2 2 0 0 1-2 2h-4v-6.5H9V21H5a2 2 0 0 1-2-2z",
    )
    /** Sliders: the tools and setup behind the measurement. */
    val Tools = line(
        "tools",
        "M21 5h-7", "M10 5H3", "M21 12h-9", "M8 12H3", "M21 19h-5", "M12 19H3",
        "M14 3v4", "M8 10v4", "M16 17v4",
    )
    /** A speech bubble with a spark: asking the on-device assistant. */
    val Ask = line(
        "ask",
        "M21 13v1a2 2 0 0 1-2 2H8l-5 4V6a2 2 0 0 1 2-2h7",
        "M18 2.2l.95 2.35 2.35.95-2.35.95L18 8.8l-.95-2.35-2.35-.95 2.35-.95z",
    )
    /** A clock turning back. */
    val History = line(
        "history",
        "M3 12a9 9 0 1 0 2.64-6.36L3 8.25", "M3 3.5v4.75h4.75", "M12 7.5V12l3.5 2",
    )

    private fun line(name: String, vararg paths: String): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            for (d in paths) addPath(
                pathData = addPathNodes(d),
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.8f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()
}
