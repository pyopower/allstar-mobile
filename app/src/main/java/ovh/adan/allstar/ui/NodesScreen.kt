package ovh.adan.allstar.ui

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ovh.adan.allstar.Conn
import ovh.adan.allstar.R
import ovh.adan.allstar.Radio
import ovh.adan.allstar.RadioState
import ovh.adan.allstar.data.NodeDetails
import ovh.adan.allstar.data.NodeInfo
import ovh.adan.allstar.data.isEchoLinkNumber
import ovh.adan.allstar.data.NodeStats
import java.text.NumberFormat

private enum class Tab { FAVORITES, RECENTS }

@Composable
fun NodesScreen(state: RadioState, favorites: Set<String>, recents: List<String>,
                onToggleFavorite: (String) -> Unit, onOpenNode: (String) -> Unit) {
    val nodes by Radio.directory.nodes.collectAsState()
    var query by remember { mutableStateOf("") }
    var tab by remember { mutableStateOf(if (favorites.isEmpty()) Tab.RECENTS else Tab.FAVORITES) }
    var results by remember { mutableStateOf<List<NodeInfo>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(query, nodes) {
        results = withContext(Dispatchers.Default) { Radio.directory.search(query) }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.tab_nodes), style = MaterialTheme.typography.headlineMedium)
                val sub = if (nodes.isEmpty()) stringResource(R.string.loading_nodes) else
                    stringResource(R.string.nodes_count, NumberFormat.getIntegerInstance().format(nodes.size)) + " · " +
                        stringResource(R.string.updated_ago, DateUtils.getRelativeTimeSpanString(
                            System.currentTimeMillis() - Radio.directory.ageMs.coerceAtMost(365L * 86400_000)).toString()
                            .replaceFirstChar { it.lowercase() })
                Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = {
                loading = true
                scope.launch { Radio.directory.load(forceRefresh = true); loading = false }
            }, enabled = !loading) { Icon(Icons.Default.Refresh, stringResource(R.string.refresh)) }
        }
        if (loading || nodes.isEmpty()) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))

        OutlinedTextField(
            value = query, onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
            placeholder = { Text(stringResource(R.string.search_hint)) },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Default.Clear, null) } },
            singleLine = true, shape = RoundedCornerShape(16.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        )

        val list: List<String>
        if (query.isBlank()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(tab == Tab.FAVORITES, { tab = Tab.FAVORITES }, { Text(stringResource(R.string.favorites)) },
                    leadingIcon = { Icon(Icons.Default.Star, null, Modifier.size(18.dp)) })
                FilterChip(tab == Tab.RECENTS, { tab = Tab.RECENTS }, { Text(stringResource(R.string.recents)) })
            }
            list = if (tab == Tab.FAVORITES) favorites.sorted() else recents
        } else list = (Radio.directory.directCandidates(query) + results.map { it.number }).distinct()

        val linked = state.links.map { it.number }.toSet()
        if (list.isEmpty()) {
            Text(
                stringResource(when {
                    query.isNotBlank() -> R.string.no_results
                    tab == Tab.FAVORITES -> R.string.no_favorites
                    else -> R.string.no_recents
                }),
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 24.dp),
            )
        }
        LazyColumn(contentPadding = PaddingValues(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(list, key = { it }) { n ->
                NodeRow(n, Radio.directory.lookup(n), n in favorites, n in linked,
                    onClick = { onOpenNode(n) }, onStar = { onToggleFavorite(n) })
            }
        }
    }
}

@Composable
private fun NodeRow(number: String, info: NodeInfo?, fav: Boolean, linked: Boolean, onClick: () -> Unit, onStar: () -> Unit) {
    val rc = LocalRadioColors.current
    Card(onClick = onClick, shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            NodeBadge(number, highlight = linked)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                NodeTitle(info, number)
                val sub = if (info == null) stringResource(
                    if (isEchoLinkNumber(number)) R.string.echolink_node else R.string.not_in_list)
                    else listOf(info?.description, info?.location).filter { !it.isNullOrBlank() }.joinToString(" · ")
                if (sub.isNotBlank()) Text(sub, style = MaterialTheme.typography.bodySmall, maxLines = 2,
                    overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onStar) {
                Icon(if (fav) Icons.Default.Star else Icons.Default.StarBorder, stringResource(R.string.favorites),
                    tint = if (fav) rc.star else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NodeSheet(number: String, state: RadioState, fav: Boolean, onToggleFavorite: () -> Unit, onDismiss: () -> Unit) {
    val rc = LocalRadioColors.current
    val info = Radio.directory.lookup(number)
    var details by remember { mutableStateOf<NodeDetails?>(null) }
    var loading by remember { mutableStateOf(true) }
    val echoLink = isEchoLinkNumber(number)
    LaunchedEffect(number) {
        if (!echoLink) details = NodeStats.fetch(number).getOrNull()
        loading = false
    }
    val connected = state.conn == Conn.CONNECTED
    val isLinked = state.links.any { it.number == number }
    val isSelf = state.profile?.node == number

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                NodeBadge(number, highlight = isLinked)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(info?.callsign?.ifBlank { null } ?: details?.callsign?.ifBlank { null }
                        ?: if (echoLink) "EchoLink ${number.drop(1)}" else number,
                        style = MaterialTheme.typography.titleLarge)
                    info?.description?.takeIf { it.isNotBlank() }?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                IconButton(onClick = onToggleFavorite) {
                    Icon(if (fav) Icons.Default.Star else Icons.Default.StarBorder, null,
                        tint = if (fav) rc.star else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh), shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (echoLink) Text(stringResource(R.string.echolink_hint), style = MaterialTheme.typography.bodyMedium)
                    else if (loading) Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp); Spacer(Modifier.width(10.dp))
                        Text(stringResource(R.string.details_loading), style = MaterialTheme.typography.bodyMedium)
                    } else {
                        val d = details
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            when {
                                d == null || !d.online -> Pill(stringResource(R.string.offline), rc.muted)
                                d.keyed -> Pill(stringResource(R.string.keyed_now), rc.rx)
                                else -> Pill(stringResource(R.string.online), rc.idle)
                            }
                            if (d != null && d.links.isNotEmpty()) Pill(pluralStringResource(R.plurals.links_n, d.links.size, d.links.size), rc.star)
                        }
                        val loc = info?.location?.ifBlank { null } ?: d?.location?.ifBlank { null }
                        loc?.let { Detail(stringResource(R.string.location), it) }
                        d?.frequency?.takeIf { it.isNotBlank() }?.let { Detail(stringResource(R.string.frequency), it) }
                        d?.affiliation?.takeIf { it.isNotBlank() }?.let { Detail("", it) }
                    }
                }
            }

            if (!connected) {
                Text(stringResource(R.string.connect_first), color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else if (!isSelf) {
                if (isLinked) {
                    Button(onClick = { Radio.unlinkNode(number); onDismiss() }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                        Icon(Icons.Default.LinkOff, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.unlink))
                    }
                } else {
                    Button(onClick = { Radio.linkNode(number); onDismiss() }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                        Icon(Icons.Default.Link, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.link_tx))
                    }
                    OutlinedButton(onClick = { Radio.linkNode(number, monitorOnly = true); onDismiss() },
                        modifier = Modifier.fillMaxWidth().height(52.dp)) {
                        Icon(Icons.Default.Hearing, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.link_monitor))
                    }
                }
            }
        }
    }
}

@Composable
private fun Detail(label: String, value: String) {
    Row {
        if (label.isNotBlank()) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(96.dp))
        }
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
