package com.directlink.client

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class SpecialKey(
    val label: String,
    val vkCode: Int,
    val isModifier: Boolean = false,
    val isShortcut: Boolean = false,
    val shortcutModifier: Int = 0x11 // VK_CONTROL by default
)

val SPECIAL_KEYS = listOf(
    // One-Tap Quick Action Shortcuts
    SpecialKey("COPY", 0x43, isShortcut = true),
    SpecialKey("PASTE", 0x56, isShortcut = true),
    SpecialKey("CUT", 0x58, isShortcut = true),
    SpecialKey("SEL ALL", 0x41, isShortcut = true),
    SpecialKey("UNDO", 0x5A, isShortcut = true),
    SpecialKey("⇧+ENT", 0x0D, isShortcut = true, shortcutModifier = 0x10),

    // Standard Modifiers
    SpecialKey("CTRL", 0x11, isModifier = true),
    SpecialKey("ALT", 0x12, isModifier = true),
    SpecialKey("SHIFT", 0x10, isModifier = true),
    SpecialKey("WIN", 0x5B, isModifier = true),

    // Essential Keys
    SpecialKey("ESC", 0x1B),
    SpecialKey("TAB", 0x09),
    SpecialKey("ENT", 0x0D),
    SpecialKey("DEL", 0x2E),
    SpecialKey("BKSP", 0x08),
    SpecialKey("SPACE", 0x20),

    // Common Alphabet Keys for custom combos (Ctrl+C, Ctrl+V, etc.)
    SpecialKey("A", 0x41),
    SpecialKey("C", 0x43),
    SpecialKey("V", 0x56),
    SpecialKey("X", 0x58),
    SpecialKey("Z", 0x5A),
    SpecialKey("F", 0x46),
    SpecialKey("S", 0x53),

    // Navigation & Editing
    SpecialKey("↑", 0x26),
    SpecialKey("↓", 0x28),
    SpecialKey("←", 0x25),
    SpecialKey("→", 0x27),
    SpecialKey("HOME", 0x24),
    SpecialKey("END", 0x23),
    SpecialKey("PGUP", 0x21),
    SpecialKey("PGDN", 0x22),
    SpecialKey("INS", 0x2D),

    // Function Keys
    SpecialKey("F1", 0x70),
    SpecialKey("F2", 0x71),
    SpecialKey("F3", 0x72),
    SpecialKey("F4", 0x73),
    SpecialKey("F5", 0x74),
    SpecialKey("F6", 0x75),
    SpecialKey("F7", 0x76),
    SpecialKey("F8", 0x77),
    SpecialKey("F9", 0x78),
    SpecialKey("F10", 0x79),
    SpecialKey("F11", 0x7A),
    SpecialKey("F12", 0x7B),
    SpecialKey("PRT", 0x2C)
)

@Composable
fun SpecialKeysToolbar(
    activeModifiers: Set<Int>,
    onToggleModifier: (Int) -> Unit,
    onTapKey: (Int) -> Unit,
    onShortcut: ((modifier: Int, key: Int) -> Unit)? = null
) {
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF1E1E1E))
            .padding(vertical = 6.dp, horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        items(SPECIAL_KEYS) { key ->
            val isActive = activeModifiers.contains(key.vkCode)
            val isAction = key.isShortcut
            
            val bgColor = when {
                isActive -> Color(0xFF0D6EFD)
                isAction -> Color(0xFF132F4C)
                else -> Color(0xFF2C2C2C)
            }
            val textColor = when {
                isActive -> Color.White
                isAction -> Color(0xFF00E5FF)
                else -> Color(0xFFE0E0E0)
            }
            
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(bgColor)
                    .clickable {
                        when {
                            key.isShortcut -> onShortcut?.invoke(key.shortcutModifier, key.vkCode)
                            key.isModifier -> onToggleModifier(key.vkCode)
                            else -> onTapKey(key.vkCode)
                        }
                    }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = key.label,
                    color = textColor,
                    fontSize = 13.sp,
                    fontWeight = if (isAction || isActive) FontWeight.Bold else FontWeight.Medium
                )
            }
        }
    }
}
