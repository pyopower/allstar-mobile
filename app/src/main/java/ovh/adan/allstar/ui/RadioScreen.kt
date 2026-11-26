package ovh.adan.allstar.ui

import androidx.compose.material.icons.filled.Minimize
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.material3.HorizontalDivider
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Dialpad
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Backspace
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import ovh.adan.allstar.Conn
import ovh.adan.allstar.LogEvent
import ovh.adan.allstar.R
import ovh.adan.allstar.Radio
import ovh.adan.allstar.RadioState
import ovh.adan.allstar.data.LinkedNode
import ovh.adan.allstar.data.Profile
import ovh.adan.allstar.data.PttMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RadioScreen(
    state: RadioState,
    profiles: List<Profile>,
    activeProfile: Profile?,
    pttMode: PttMode,
    onConnect: (Profile) -> Unit,
    onSelectProfile: (Profile) -> Unit,
    onAddProfile: () -> Unit,
    onOpenNode: (String) -> Unit,
) {
    val rc = LocalRadioColors.current
    var showKeypad by remember { mutableStateOf(false) }
    var showLog by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Header(state, profiles, activeProfile, onConnect, onSelectProfile)

        if (activeProfile == null) {
            Welcome(onAddProfile)
            return@Column
        }

        StatusCard(state)

        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                SectionTitle(stringResource(R.string.linked_nodes)) {
                    if (state.links.size > 1 && state.conn == Conn.CONNECTED)
                        TextButton(onClick = { Radio.unlinkAll() }) {
                            Icon(Icons.Default.LinkOff, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.unlink_all))
                        }
                }
            }
            if (state.links.isEmpty()) item {
                Text(
                    if (state.conn == Conn.CONNECTED) stringResource(R.string.no_links) else stringResource(if (activeProfile.account) R.string.connect_first_wt else R.string.connect_first),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
            items(state.links, key = { it.number }) { l -> LinkedRow(l, state.conn == Conn.CONNECTED, l.number in state.talkers, onOpenNode) }
            item {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { showLog = !showLog }.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SectionTitle(stringResource(R.string.activity), Modifier.weight(1f))
                    Icon(if (showLog) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (showLog) items(state.events.take(40)) { EventRow(it) }
            else state.events.firstOrNull()?.let { e -> item { EventRow(e) } }
        }

        Row(
            Modifier.fillMaxWidth().padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Box(Modifier.size(64.dp))
            PttButton(state, pttMode)
            Box(Modifier.size(64.dp), contentAlignment = Alignment.Center) {
                Surface(
                    onClick = { showKeypad = true }, enabled = state.conn == Conn.CONNECTED,
                    shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.size(56.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Dialpad, stringResource(R.string.keypad),
                            tint = if (state.conn == Conn.CONNECTED) MaterialTheme.colorScheme.onSurface else rc.muted)
                    }
                }
            }
        }
    }

    if (showKeypad) {
        ModalBottomSheet(onDismissRequest = { showKeypad = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Keypad(onSend = { Radio.dtmf(it); showKeypad = false }, onClose = { showKeypad = false })
        }
    }
}

@Composable
private fun Header(
    state: RadioState, profiles: List<Profile>, active: Profile?,
    onConnect: (Profile) -> Unit, onSelect: (Profile) -> Unit,
) {
    val rc = LocalRadioColors.current
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.labelLarge, color = rc.star)
            if (active != null) Box {
                Row(
                    Modifier.clip(RoundedCornerShape(8.dp))
                        .clickable(enabled = profiles.size > 1 && state.conn == Conn.DISCONNECTED) { menu = true },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(listOf(active.node, active.callsign).filter { it.isNotBlank() }.joinToString(" · "),
                        style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (profiles.size > 1 && state.conn == Conn.DISCONNECTED) Icon(Icons.Default.ExpandMore, null)
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    profiles.forEach { p ->
                        DropdownMenuItem(text = { Text(listOf(p.node, p.callsign, p.name).filter { it.isNotBlank() }.joinToString(" · ")) },
                            onClick = { menu = false; onSelect(p) })
                    }
                }
            }
        }
        if (active != null) {
            val up = state.conn != Conn.DISCONNECTED
            Button(
                onClick = { if (up) Radio.disconnect() else onConnect(active) },
                colors = if (up) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    contentColor = MaterialTheme.colorScheme.onSurface) else ButtonDefaults.buttonColors(),
                contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
            ) {
                Icon(Icons.Default.PowerSettingsNew, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                Text(stringResource(if (up) R.string.disconnect else R.string.connect))
            }
        }
        ExitMenu()
    }
}

@Composable
private fun StatusCard(state: RadioState) {
    val rc = LocalRadioColors.current
    val (label, color) = when {
        state.conn == Conn.DISCONNECTED -> stringResource(R.string.status_disconnected) to rc.muted
        state.conn == Conn.CONNECTING -> stringResource(R.string.status_connecting) to rc.star
        state.tx -> stringResource(R.string.status_tx) to rc.tx
        state.rx && state.cidCall != null && System.currentTimeMillis() - state.cidAt < 15_000 ->
            stringResource(R.string.status_rx_from, state.cidCall) to rc.rx
        state.talkers.isNotEmpty() && !state.tx -> stringResource(R.string.status_rx_from, state.talkers.joinToString(", ") { Radio.describe(it) }) to rc.rx
        state.rx -> stringResource(R.string.status_rx) to rc.rx
        else -> stringResource(R.string.status_connected) to rc.idle
    }
    val border by animateColorAsState(if (state.rx || state.tx) color.copy(alpha = 0.6f) else MaterialTheme.colorScheme.outlineVariant, label = "b")
    var now by remember { mutableLongStateOf(android.os.SystemClock.elapsedRealtime()) }
    LaunchedEffect(state.tx) { while (state.tx) { now = android.os.SystemClock.elapsedRealtime(); delay(200) } }

    Card(
        colors = CardDefaults.cardColors(containerColor = rc.card),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth().border(1.dp, border, RoundedCornerShape(20.dp)),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Pill(label, color)
                Spacer(Modifier.weight(1f))
                if (state.tx) {
                    val secs = ((now - state.txStartedAt) / 1000).coerceAtLeast(0)
                    Text("%d:%02d".format(secs / 60, secs % 60), fontFamily = FontFamily.Monospace,
                        color = rc.tx, fontWeight = FontWeight.Bold)
                } else if (state.profile != null) {
                    Text(stringResource(if (state.profile.account) R.string.wt_node else R.string.my_node, state.profile.node), style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (state.cidCall != null && state.conn == Conn.CONNECTED) {
                Text(stringResource(R.string.cid_last, state.cidCall,
                    java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(state.cidAt))),
                    style = MaterialTheme.typography.bodyMedium, color = rc.rx)
            }
            if (state.reason != null && state.conn != Conn.CONNECTED) {
                Text(state.reason, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Hearing, null, Modifier.size(16.dp), tint = rc.muted); Spacer(Modifier.width(8.dp))
                LevelMeter(if (state.conn == Conn.CONNECTED) state.rxLevel else -90f, rc.rx)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Mic, null, Modifier.size(16.dp), tint = rc.muted); Spacer(Modifier.width(8.dp))
                LevelMeter(if (state.tx) state.txLevel else -90f, rc.tx)
            }
        }
    }
}

@Composable
private fun LinkedRow(l: LinkedNode, canControl: Boolean, talking: Boolean, onOpen: (String) -> Unit) {
    val echoList = Radio.directory.echo.collectAsState().value   // read it: repaint when the EchoLink list arrives
    val info = remember(l.number, echoList) { Radio.directory.lookup(l.number) }
    val rc = LocalRadioColors.current
    val border by animateColorAsState(if (talking) rc.rx else Color.Transparent, label = "talk")
    Card(
        onClick = { onOpen(l.number) },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.border(2.dp, border, RoundedCornerShape(16.dp)),
    ) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            NodeBadge(l.number, highlight = talking)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    NodeTitle(info, l.number)
                    if (talking) {
                        Spacer(Modifier.width(8.dp))
                        Icon(Icons.Default.GraphicEq, null, tint = rc.rx, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.talking), style = MaterialTheme.typography.labelMedium, color = rc.rx)
                    }
                }
                val sub = listOf(info?.description, info?.location).filter { !it.isNullOrBlank() }.joinToString(" · ")
                val mode = when (l.mode) {
                    'R' -> stringResource(R.string.mode_r); 'C' -> stringResource(R.string.mode_c)
                    else -> stringResource(R.string.mode_t)
                }
                Text(listOf(mode, sub).filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (canControl) IconButton(onClick = { Radio.unlinkNode(l.number) }) {
                Icon(Icons.Default.Close, stringResource(R.string.unlink), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Minimize (keeps the link in the background) or quit completely (disconnects and closes). */
@Composable
private fun ExitMenu() {
    val act = androidx.compose.ui.platform.LocalContext.current as? android.app.Activity
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.AutoMirrored.Filled.ExitToApp, stringResource(R.string.exit_menu))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.minimize)) },
                leadingIcon = { Icon(Icons.Default.Minimize, null) },
                onClick = { open = false; act?.moveTaskToBack(true) })
            DropdownMenuItem(text = { Text(stringResource(R.string.quit)) },
                leadingIcon = { Icon(Icons.Default.PowerSettingsNew, null) },
                onClick = { open = false; Radio.disconnect(); act?.finishAndRemoveTask() })
        }
    }
}

@Composable
private fun EventRow(e: LogEvent) {
    val rc = LocalRadioColors.current
    val c = when (e.kind) {
        LogEvent.Kind.RX -> rc.rx; LogEvent.Kind.TX -> rc.tx; LogEvent.Kind.CMD -> rc.star
        LogEvent.Kind.ERROR -> MaterialTheme.colorScheme.error; else -> rc.idle
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(e.clock, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = rc.muted)
        Spacer(Modifier.width(10.dp)); StatusDot(c, Modifier.size(6.dp)); Spacer(Modifier.width(10.dp))
        Text(e.text, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun PttButton(state: RadioState, mode: PttMode) {
    val rc = LocalRadioColors.current
    val haptic = LocalHapticFeedback.current
    val enabled = state.conn == Conn.CONNECTED
    val base = when {
        !enabled -> MaterialTheme.colorScheme.surfaceContainerHighest
        state.tx -> rc.tx
        state.rx -> rc.rx
        else -> MaterialTheme.colorScheme.primary
    }
    val color by animateColorAsState(base, label = "ptt")
    val pulse = rememberInfiniteTransition(label = "pulse")
    val ring by pulse.animateFloat(1f, 1.12f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "ring")

    val hint = when {
        !enabled -> stringResource(if (state.profile?.account == true) R.string.connect_first_wt else R.string.connect_first)
        state.tx -> stringResource(if (mode == PttMode.HOLD) R.string.release_to_listen else R.string.tap_to_stop)
        else -> stringResource(if (mode == PttMode.HOLD) R.string.hold_to_talk else R.string.tap_to_talk)
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(176.dp), contentAlignment = Alignment.Center) {
            if (state.tx || state.rx) Box(
                Modifier.size(160.dp).scale(ring).clip(CircleShape).background(color.copy(alpha = 0.18f))
            )
            Box(
                Modifier.size(150.dp).clip(CircleShape)
                    .background(Brush.radialGradient(listOf(color.copy(alpha = 0.95f), color.copy(alpha = 0.75f))))
                    .border(2.dp, Color.White.copy(alpha = if (enabled) 0.18f else 0.05f), CircleShape)
                    .pointerInput(enabled, mode) {
                        if (!enabled) return@pointerInput
                        awaitEachGesture {
                            awaitFirstDown()
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            if (mode == PttMode.HOLD) {
                                Radio.pttDown()
                                waitForUpOrCancellation()
                                Radio.pttUp()
                            } else {
                                waitForUpOrCancellation()
                                Radio.togglePtt()
                            }
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.Mic, null, Modifier.size(46.dp),
                        tint = if (enabled) Color.White else MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("PTT", color = if (enabled) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.Black, letterSpacing = 3.sp)
                }
            }
        }
        Text(hint, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Welcome(onAdd: () -> Unit) {
    val rc = LocalRadioColors.current
    Card(
        colors = CardDefaults.cardColors(containerColor = rc.card), shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.welcome_title), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.welcome_text), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = onAdd) { Text(stringResource(R.string.add_node)) }
        }
    }
}

/** Stock ASL3 rpt.conf [functions]; "+" means the node number follows. */
private val QUICK_COMMANDS = listOf(
    "*3" to R.string.cmd_link, "*2" to R.string.cmd_monitor, "*1" to R.string.cmd_unlink,
    "*70" to R.string.cmd_status, "*722" to R.string.cmd_time, "*721" to R.string.cmd_id,
)

@Composable
private fun Keypad(onSend: (String) -> Unit, onClose: () -> Unit) {
    var digits by remember { mutableStateOf("") }
    val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "*", "0", "#")
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.keypad), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            IconButton(onClick = onClose) { Icon(Icons.Default.Close, stringResource(R.string.close)) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(digits.ifEmpty { "—" }, fontFamily = FontFamily.Monospace, fontSize = 30.sp, fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
            IconButton(onClick = { digits = digits.dropLast(1) }, enabled = digits.isNotEmpty()) {
                Icon(Icons.Default.Backspace, null)
            }
        }
        keys.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { k ->
                    FilledTonalButton(onClick = { digits += k }, modifier = Modifier.weight(1f).height(56.dp),
                        shape = RoundedCornerShape(16.dp)) {
                        Text(k, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        Button(onClick = { onSend(digits) }, enabled = digits.isNotEmpty(), modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Text(stringResource(R.string.send))
        }

        Text(stringResource(R.string.quick_commands).uppercase(), style = MaterialTheme.typography.labelMedium,
            letterSpacing = 1.2.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
        Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
            QUICK_COMMANDS.forEachIndexed { idx, (code, desc) ->
                if (idx > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                Row(Modifier.fillMaxWidth().clickable { digits = code }.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(code, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 17.sp,
                        color = MaterialTheme.colorScheme.primary, modifier = Modifier.width(64.dp))
                    Text(stringResource(desc), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        Text(stringResource(R.string.quick_commands_note), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth())
    }
}
