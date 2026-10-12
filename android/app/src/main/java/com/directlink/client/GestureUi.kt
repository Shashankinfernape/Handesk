package com.directlink.client

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog

/**
 * Renders an authentic, sharp desktop mouse cursor overlay for MOUSE mode.
 * Positions the cursor tip precisely at physical screen pixel (screenPxX, screenPxY).
 * Keeps constant 24.dp size across any zoom level and avoids clipping.
 */
@Composable
fun VirtualMousePointer(
    screenPxX: Float,
    screenPxY: Float,
    modifier: Modifier = Modifier
) {
    Canvas(
        modifier = modifier
            .size(24.dp)
            .graphicsLayer {
                translationX = screenPxX
                translationY = screenPxY
            }
    ) {
        val w = size.width
        val h = size.height

        // Classic desktop mouse arrow path
        val path = Path().apply {
            moveTo(0f, 0f)
            lineTo(w * 0.82f, h * 0.60f)
            lineTo(w * 0.44f, h * 0.58f)
            lineTo(w * 0.68f, h * 0.98f)
            lineTo(w * 0.48f, h)
            lineTo(w * 0.26f, h * 0.62f)
            lineTo(0f, h * 0.78f)
            close()
        }

        // Drop shadow for visibility on bright desktop backgrounds
        val shadowPath = Path().apply {
            moveTo(2f, 2f)
            lineTo(w * 0.82f + 2f, h * 0.60f + 2f)
            lineTo(w * 0.44f + 2f, h * 0.58f + 2f)
            lineTo(w * 0.68f + 2f, h * 0.98f + 2f)
            lineTo(w * 0.48f + 2f, h + 2f)
            lineTo(w * 0.26f + 2f, h * 0.62f + 2f)
            lineTo(2f, h * 0.78f + 2f)
            close()
        }

        drawPath(shadowPath, color = Color(0x66000000))
        drawPath(path, color = Color.White)
        drawPath(path, color = Color(0xFF1E1E1E), style = Stroke(width = 2.dp.toPx()))
    }
}


data class GestureCardItem(
    val title: String,
    val action: String,
    val description: String
)

/**
 * RustDesk-style interactive Gesture Guide Dialog.
 * Allows switching modes on the fly and viewing gesture mechanics for all 3 modes.
 */
@Composable
fun GestureHelpDialog(
    currentMode: GestureMode,
    onSelectMode: (GestureMode) -> Unit,
    onDismiss: () -> Unit
) {
    var selectedTab by remember { mutableStateOf(currentMode) }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp)
                .heightIn(max = 580.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
            shape = RoundedCornerShape(24.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "Gesture Controls",
                            color = Color.White,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Choose your preferred interaction mode",
                            color = Color(0xFFAAAAAA),
                            fontSize = 12.sp
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Filled.Close, contentDescription = "Close", tint = Color.White)
                    }
                }

                Spacer(Modifier.height(16.dp))

                // Mode Tabs (Segmented Button Switcher)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF2C2C2C), RoundedCornerShape(12.dp))
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    GestureMode.entries.forEach { mode ->
                        val isSelected = selectedTab == mode
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .background(
                                    if (isSelected) Color(0xFF007ACC) else Color.Transparent,
                                    RoundedCornerShape(8.dp)
                                )
                                .clickable {
                                    selectedTab = mode
                                    onSelectMode(mode)
                                }
                                .padding(vertical = 10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = when (mode) {
                                    GestureMode.HANDESK -> "Handesk"
                                    GestureMode.TOUCH -> "Touch"
                                    GestureMode.MOUSE -> "Mouse"
                                },
                                color = if (isSelected) Color.White else Color(0xFFAAAAAA),
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                fontSize = 13.sp
                            )
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))

                // Active Mode Description Badge
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF252525), RoundedCornerShape(10.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = selectedTab.subtitle,
                        color = Color(0xFF00D4FF),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                Spacer(Modifier.height(12.dp))

                // Gesture Actions List
                val gestureItems = when (selectedTab) {
                    GestureMode.HANDESK -> listOf(
                        GestureCardItem("1-Finger Tap", "Left Click", "Sends a left mouse click at the touched location"),
                        GestureCardItem("1-Finger Double Tap", "Right Click", "Sends a right mouse click at the touched location"),
                        GestureCardItem("1-Finger Drag / Swipe", "Mouse Scroll", "Smoothly scrolls web pages & documents up/down"),
                        GestureCardItem("Double Tap & Hold Drag", "Left Mouse Drag", "Holds left mouse button to drag windows or select text"),
                        GestureCardItem("2-Finger Tap", "Keyboard Shortcut", "Toggles system keyboard & special keys bar"),
                        GestureCardItem("2-Finger Pinch", "Zoom In/Out", "Zooms the view using remote Ctrl + Mouse Wheel"),
                        GestureCardItem("2-Finger Drag", "Canvas Pan", "Drags canvas using middle mouse button"),
                        GestureCardItem("3-Finger Drag", "Viewport Zoom & Pan", "Scales and moves the viewport view")
                    )
                    GestureMode.TOUCH -> listOf(
                        GestureCardItem("1-Finger Tap", "Left Click", "Instantly left clicks at the touched screen location"),
                        GestureCardItem("1-Finger Double Tap", "Double Click", "Instantly double left clicks at the touched location"),
                        GestureCardItem("1-Finger Long Press", "Right Click", "Right clicks at the touched location with haptic feedback"),
                        GestureCardItem("1-Finger Drag", "Direct Left Drag", "Presses left button down and drags files/windows with your finger"),
                        GestureCardItem("2-Finger Tap", "Toggle Keyboard", "Quickly brings up the on-screen keyboard"),
                        GestureCardItem("2-Finger Drag", "Canvas Pan", "Moves the visible viewport across the desktop"),
                        GestureCardItem("2-Finger Pinch", "Canvas Zoom", "Smoothly zooms the viewport in and out"),
                        GestureCardItem("3-Finger Vertical Drag", "Mouse Scroll", "Scrolls pages and documents up and down")
                    )
                    GestureMode.MOUSE -> listOf(
                        GestureCardItem("1-Finger Move", "Move Cursor", "Relative trackpad: sliding finger moves virtual pointer"),
                        GestureCardItem("1-Finger Tap", "Left Click", "Left clicks at the current cursor position"),
                        GestureCardItem("1-Finger Double Tap", "Double Click", "Double left clicks at current cursor position"),
                        GestureCardItem("1-Finger Long Press", "Right Click", "Right clicks at current cursor position"),
                        GestureCardItem("2-Finger Tap", "Right Click", "Classic laptop trackpad right click at cursor"),
                        GestureCardItem("Double-Tap & Drag", "Mouse Drag", "Picks up files, moves windows, or highlights text"),
                        GestureCardItem("2-Finger Drag", "Two-Finger Scroll", "Scrolls vertically or horizontally at cursor"),
                        GestureCardItem("3-Finger Drag", "Mouse Scroll", "Quick vertical mouse scroll wheel")
                    )
                }

                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(gestureItems) { item ->
                        Card(
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF282828)),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(item.title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                    Spacer(Modifier.height(2.dp))
                                    Text(item.description, color = Color(0xFF9E9E9E), fontSize = 11.sp)
                                }
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = item.action,
                                    color = Color(0xFF00E5FF),
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 12.sp,
                                    textAlign = TextAlign.End
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))

                Button(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF007ACC)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Apply & Close", color = Color.White, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
