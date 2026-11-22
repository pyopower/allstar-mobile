package ovh.adan.allstar.ui

import androidx.compose.material3.RadioButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.FilledTonalButton
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import android.widget.Toast
import ovh.adan.allstar.data.SetupLink
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ovh.adan.allstar.BuildConfig
import ovh.adan.allstar.R
import ovh.adan.allstar.Radio
import ovh.adan.allstar.data.Profile
import ovh.adan.allstar.data.PttMode

@Composable
fun SettingsScreen(
    profiles: List<Profile>, activeId: String?,
    onSaveProfile: (Profile) -> Unit, onDeleteProfile: (Profile) -> Unit, onSelectProfile: (Profile) -> Unit,
    editRequest: Profile?, onEditRequestHandled: () -> Unit, onImportLink: (Profile) -> Profile,
    settingsVersion: Int, onSettingsChanged: () -> Unit,
) {
    val prefs = Radio.prefs
    var editing by remember { mutableStateOf<Profile?>(null) }
    if (editRequest != null) { editing = editRequest; onEditRequestHandled() }
    val ctx = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var showManual by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.tab_settings), style = MaterialTheme.typography.headlineMedium)

        SectionTitle(stringResource(R.string.profiles)) {
            TextButton(onClick = { editing = Profile() }) {
                Icon(Icons.Default.Add, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.add_profile))
            }
        }
        profiles.forEach { p ->
            val active = p.id == (activeId ?: profiles.firstOrNull()?.id)
            Card(onClick = { onSelectProfile(p) }, shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                Row(Modifier.fillMaxWidth().padding(start = 14.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    NodeBadge(p.node, highlight = active)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(p.title, style = MaterialTheme.typography.titleMedium)
                        Text("${p.username}@${p.host}:${p.port}", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (active) Icon(Icons.Default.CheckCircle, stringResource(R.string.active), tint = LocalRadioColors.current.rx)
                    IconButton(onClick = { editing = p }) { Icon(Icons.Default.Edit, stringResource(R.string.edit_profile)) }
                }
            }
        }

        // settingsVersion forces recomposition after a preference change
        key(settingsVersion) {
            SettingsCard {
                Text(stringResource(R.string.section_language), style = MaterialTheme.typography.titleMedium)
                val act = LocalContext.current as? android.app.Activity
                val cur = ovh.adan.allstar.Lang.code(LocalContext.current)
                var open by remember { mutableStateOf(false) }
                OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(ovh.adan.allstar.Lang.OPTIONS.firstOrNull { it.first == cur }?.second ?: stringResource(R.string.lang_system))
                }
                if (open) AlertDialog(
                    onDismissRequest = { open = false },
                    title = { Text(stringResource(R.string.section_language)) },
                    text = {
                        Column {
                            (listOf("" to stringResource(R.string.lang_system)) + ovh.adan.allstar.Lang.OPTIONS).forEach { (code, name) ->
                                Row(Modifier.fillMaxWidth().clickable {
                                    open = false
                                    if (code != cur) { ovh.adan.allstar.Lang.set(act ?: return@clickable, code); act.recreate() }
                                }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                    RadioButton(selected = code == cur, onClick = null)
                                    Spacer(Modifier.width(12.dp))
                                    Text(name, style = MaterialTheme.typography.bodyLarge)
                                }
                            }
                        }
                    },
                    confirmButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.cancel)) } },
                )
            }

            SettingsCard {
                Text(stringResource(R.string.ptt), style = MaterialTheme.typography.titleMedium)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    PttMode.entries.forEachIndexed { i, m ->
                        SegmentedButton(selected = prefs.pttMode == m, onClick = { prefs.pttMode = m; onSettingsChanged() },
                            shape = SegmentedButtonDefaults.itemShape(i, PttMode.entries.size)) {
                            Text(stringResource(if (m == PttMode.HOLD) R.string.ptt_hold else R.string.ptt_toggle))
                        }
                    }
                }
                ToggleRow(stringResource(R.string.volume_ptt), stringResource(R.string.volume_ptt_sub), prefs.volumeKeyPtt) {
                    prefs.volumeKeyPtt = it; onSettingsChanged()
                }
                ToggleRow(stringResource(R.string.keep_screen), null, prefs.keepScreenOn) { prefs.keepScreenOn = it; onSettingsChanged() }
                ToggleRow(stringResource(R.string.auto_connect), stringResource(R.string.auto_connect_sub), prefs.autoConnect) {
                    prefs.autoConnect = it; onSettingsChanged()
                }
                ToggleRow(stringResource(R.string.start_on_boot), stringResource(R.string.start_on_boot_sub), prefs.startOnBoot) {
                    prefs.startOnBoot = it; onSettingsChanged()
                }
                HorizontalDivider()
                var gain by remember { mutableFloatStateOf(prefs.micGain) }
                Text("${stringResource(R.string.mic_gain)}: ${"%.1f".format(gain)}×", style = MaterialTheme.typography.bodyMedium)
                Slider(gain, { gain = it }, valueRange = 0.5f..4f, steps = 6, onValueChangeFinished = { Radio.setMicGain(gain) })
                var tot by remember { mutableFloatStateOf(prefs.txTimeoutSec.toFloat()) }
                Text("${stringResource(R.string.tx_timeout)}: ${tot.toInt()} s", style = MaterialTheme.typography.bodyMedium)
                Slider(tot, { tot = it }, valueRange = 30f..600f, steps = 18, onValueChangeFinished = { prefs.txTimeoutSec = tot.toInt() })
            }
        }

        SettingsCard {
            Text(stringResource(R.string.auto_setup_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.auto_setup_text), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            SelectionContainer {
                Text(SetupLink.COMMAND, fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                    modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(10.dp)).padding(12.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = {
                    clipboard.setText(AnnotatedString(SetupLink.COMMAND))
                    Toast.makeText(ctx, R.string.copied, Toast.LENGTH_SHORT).show()
                }) { Icon(Icons.Default.ContentCopy, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.copy_command)) }
                FilledTonalButton(onClick = {
                    val p = SetupLink.parse(clipboard.getText()?.text)
                    if (p == null) Toast.makeText(ctx, R.string.bad_link, Toast.LENGTH_LONG).show()
                    else editing = onImportLink(p)
                }) { Icon(Icons.Default.ContentPaste, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.paste_link)) }
            }
            TextButton(onClick = { showManual = !showManual }) {
                Text(stringResource(R.string.manual_setup))
                Icon(if (showManual) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
            }
            if (showManual) {
            Text(stringResource(R.string.setup_help_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.setup_help), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            SelectionContainer {
                Text(
                    """
                    ; iax.conf
                    [allstarmobile]
                    type = user
                    context = allstarmobile
                    auth = md5
                    secret = <password>
                    host = dynamic
                    disallow = all
                    allow = ulaw
                    transfer = no

                    ; extensions.conf
                    [allstarmobile]
                    exten => <node>,1,rpt(${'$'}{EXTEN}|X)
                    """.trimIndent(),
                    fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                    modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(10.dp)).padding(12.dp),
                )
            }
            }
            Text(stringResource(R.string.external_ptt), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        SettingsCard {
            Text(stringResource(R.string.about), style = MaterialTheme.typography.titleMedium)
            Text("Mobile ASL Client ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.about_text), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    editing?.let { p ->
        ProfileDialog(p, isNew = profiles.none { it.id == p.id },
            onSave = { onSaveProfile(it); editing = null },
            onDelete = { onDeleteProfile(p); editing = null },
            onDismiss = { editing = null })
    }
}

@Composable
private fun key(v: Int, content: @Composable () -> Unit) = androidx.compose.runtime.key(v) { content() }

@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { content() }
    }
}

@Composable
private fun ToggleRow(title: String, sub: String?, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            sub?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Switch(value, onChange)
    }
}

@Composable
private fun ProfileDialog(p: Profile, isNew: Boolean, onSave: (Profile) -> Unit, onDelete: () -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(p.name) }
    var host by remember { mutableStateOf(p.host) }
    var port by remember { mutableStateOf(p.port.toString()) }
    var user by remember { mutableStateOf(p.username) }
    var secret by remember { mutableStateOf(p.secret) }
    var node by remember { mutableStateOf(p.node) }
    var call by remember { mutableStateOf(p.callsign) }
    var allmon by remember { mutableStateOf(p.allmon) }
    var show by remember { mutableStateOf(false) }
    val valid = host.isNotBlank() && user.isNotBlank() && node.isNotBlank() && node.all { it.isDigit() } &&
        (port.toIntOrNull() ?: 0) in 1..65535

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (isNew) R.string.add_profile else R.string.edit_profile)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Field(node, { node = it.filter(Char::isDigit) }, stringResource(R.string.f_node), KeyboardType.Number)
                Field(call, { call = it.uppercase() }, stringResource(R.string.f_callsign))
                Field(host, { host = it.trim() }, stringResource(R.string.f_host), KeyboardType.Uri)
                Field(port, { port = it.filter(Char::isDigit) }, stringResource(R.string.f_port), KeyboardType.Number)
                Field(user, { user = it.trim() }, stringResource(R.string.f_user))
                OutlinedTextField(secret, { secret = it }, label = { Text(stringResource(R.string.f_secret)) }, singleLine = true,
                    visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = { IconButton(onClick = { show = !show }) {
                        Icon(if (show) Icons.Default.VisibilityOff else Icons.Default.Visibility, null) } },
                    modifier = Modifier.fillMaxWidth())
                Field(name, { name = it }, stringResource(R.string.f_name))
                Field(allmon, { allmon = it.trim() }, stringResource(R.string.f_allmon), KeyboardType.Uri)
                Text(stringResource(R.string.f_allmon_sub), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = {
                onSave(p.copy(name = name.trim(), host = host, port = port.toInt(), username = user, secret = secret,
                    node = node, callsign = call.trim(), allmon = allmon))
            }) { Text(stringResource(R.string.save)) }
        },
        dismissButton = {
            Row {
                if (!isNew) TextButton(onClick = onDelete) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
            }
        },
    )
}

@Composable
private fun Field(v: String, on: (String) -> Unit, label: String, type: KeyboardType = KeyboardType.Text) {
    OutlinedTextField(v, on, label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(keyboardType = type))
}
