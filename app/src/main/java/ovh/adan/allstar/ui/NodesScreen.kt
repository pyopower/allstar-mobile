package ovh.adan.allstar.ui

import android.text.format.DateUtils
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.material.icons.filled.Call
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
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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
import ovh.adan.allstar.data.ElType
import ovh.adan.allstar.data.NodeInfo
import ovh.adan.allstar.data.isEchoLinkNumber
import ovh.adan.allstar.data.NodeStats
import java.text.NumberFormat

private enum class Net { ALLSTAR, ECHOLINK }
private enum class Tab { FAVORITES, RECENTS, REPEATERS, LINKS, CONFERENCES }

@Composable
fun NodesScreen(state: RadioState, favorites: Set<String>, recents: List<String>,
                onToggleFavorite: (String) -> Unit, onOpenNode: (String) -> Unit) {
    val nodes by Radio.directory.nodes.collectAsState()
    val online by Radio.directory.online.collectAsState()
    val echo by Radio.directory.echo.collectAsState()
    var net by rememberSaveable { mutableStateOf(Net.ALLSTAR) }
    var query by remember { mutableStateOf("") }
    var tab by rememberSaveable { mutableStateOf(Tab.FAVORITES) }
    var onlineOnly by rememberSaveable { mutableStateOf(false) }
    var list by remember { mutableStateOf<List<String>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val el = net == Net.ECHOLINK

    LaunchedEffect(Unit) { Radio.directory.loadOnline() }
    LaunchedEffect(net) {
        if (net == Net.ECHOLINK) {
            if (echo.isEmpty()) loading = true
            error = Radio.directory.loadEchoLink().isFailure && Radio.directory.echo.value.isEmpty()
            loading = false
        }
    }

    val favs = favorites.filter { isEchoLinkNumber(it) == el }
    val recs = recents.filter { isEchoLinkNumber(it) == el }
    LaunchedEffect(net, query, tab, onlineOnly, nodes, online, echo, favorites, recents) {
        list = withContext(Dispatchers.Default) {
            val on = Radio.directory.online.value
            fun List<String>.onlineFilter() = if (!onlineOnly || on.isEmpty()) this else filter { it in on }
            when {
                el && query.isNotBlank() -> (Radio.directory.directCandidates(query).filter(::isEchoLinkNumber) +
                    Radio.directory.searchEchoLink(query).map { it.number }).distinct()
                el -> when (tab) {
                    Tab.REPEATERS -> Radio.directory.searchEchoLink("", ElType.REPEATER).map { it.number }
                    Tab.LINKS -> Radio.directory.searchEchoLink("", ElType.LINK).map { it.number }
                    Tab.CONFERENCES -> Radio.directory.searchEchoLink("", ElType.CONFERENCE).map { it.number }
                    Tab.RECENTS -> recs
                    else -> favs.sorted()
                }
                query.isNotBlank() -> {
                    val r = (Radio.directory.directCandidates(query).filterNot(::isEchoLinkNumber) +
                        Radio.directory.search(query).map { it.number }).distinct()
                    // nodes on the air first
                    (if (on.isEmpty()) r else r.sortedBy { if (it in on) 0 else 1 }).onlineFilter()
                }
                tab == Tab.RECENTS -> recs.onlineFilter()
                else -> favs.sorted().onlineFilter()
            }
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.tab_nodes), style = MaterialTheme.typography.headlineMedium)
                val nf = NumberFormat.getIntegerInstance()
                val sub = when {
                    el && echo.isEmpty() -> stringResource(if (error) R.string.el_error else R.string.loading_nodes)
                    el -> stringResource(R.string.el_count, nf.format(echo.size)) + " · " + ago(Radio.directory.echoAgeMs)
                    nodes.isEmpty() -> stringResource(R.string.loading_nodes)
                    else -> stringResource(R.string.nodes_count, nf.format(nodes.size)) +
                        (if (online.isNotEmpty()) " · " + stringResource(R.string.nodes_online, nf.format(online.size)) else "") +
                        " · " + ago(Radio.directory.ageMs)
                }
                Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = {
                loading = true
                scope.launch {
                    if (el) error = Radio.directory.loadEchoLink(force = true).isFailure && Radio.directory.echo.value.isEmpty()
                    else { Radio.directory.load(forceRefresh = true); Radio.directory.loadOnline(force = true) }
                    loading = false
                }
            }, enabled = !loading) { Icon(Icons.Default.Refresh, stringResource(R.string.refresh)) }
        }
        if (loading || (!el && nodes.isEmpty())) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))

        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 12.dp)) {
            Net.entries.forEachIndexed { i, n ->
                SegmentedButton(selected = net == n, onClick = {
                    net = n
                    if (tab != Tab.FAVORITES && tab != Tab.RECENTS && n == Net.ALLSTAR) tab = Tab.FAVORITES
                }, shape = SegmentedButtonDefaults.itemShape(i, Net.entries.size)) {
                    Text(stringResource(if (n == Net.ALLSTAR) R.string.net_allstar else R.string.net_echolink))
                }
            }
        }

        OutlinedTextField(
            value = query, onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
            placeholder = { Text(stringResource(if (el) R.string.search_hint_el else R.string.search_hint)) },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Default.Clear, null) } },
            singleLine = true, shape = RoundedCornerShape(16.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        )

        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (query.isBlank()) {
                FilterChip(tab == Tab.FAVORITES, { tab = Tab.FAVORITES }, { Text(stringResource(R.string.favorites)) },
                    leadingIcon = { Icon(Icons.Default.Star, null, Modifier.size(18.dp)) })
                FilterChip(tab == Tab.RECENTS, { tab = Tab.RECENTS }, { Text(stringResource(R.string.recents)) })
                if (el) {
                    FilterChip(tab == Tab.REPEATERS, { tab = Tab.REPEATERS }, { Text(stringResource(R.string.el_repeaters)) })
                    FilterChip(tab == Tab.LINKS, { tab = Tab.LINKS }, { Text(stringResource(R.string.el_links)) })
                    FilterChip(tab == Tab.CONFERENCES, { tab = Tab.CONFERENCES }, { Text(stringResource(R.string.el_conferences)) })
                }
            }
            if (!el && online.isNotEmpty()) {
                FilterChip(onlineOnly, { onlineOnly = !onlineOnly }, { Text(stringResource(R.string.online_only)) },
                    leadingIcon = { StatusDot(LocalRadioColors.current.rx) })
            }
        }

        val linked = state.links.map { it.number }.toSet()
        if (list.isEmpty() && !loading) {
            Text(
                stringResource(when {
                    query.isNotBlank() -> R.string.no_results
                    tab == Tab.FAVORITES -> R.string.no_favorites
                    tab == Tab.RECENTS -> R.string.no_recents
                    else -> R.string.no_results
                }),
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 24.dp),
            )
        }
        LazyColumn(contentPadding = PaddingValues(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(list, key = { it }) { n ->
                NodeRow(n, Radio.directory.lookup(n), nodeStatus(n), n in favorites, n in linked,
                    onClick = { onOpenNode(n) }, onStar = { onToggleFavorite(n) })
            }
        }
    }
}

private enum class Status { ONLINE, BUSY, OFFLINE, UNKNOWN }

private fun nodeStatus(n: String): Status = if (isEchoLinkNumber(n)) {
    val st = Radio.directory.echoStation(n)
    when {
        Radio.directory.echo.value.isEmpty() -> Status.UNKNOWN
        st == null -> Status.OFFLINE
        st.busy -> Status.BUSY
        else -> Status.ONLINE
    }
} else when (Radio.directory.isOnline(n)) {
    true -> Status.ONLINE
    false -> Status.OFFLINE
    null -> Status.UNKNOWN
}

@Composable
private fun ago(ms: Long) = stringResource(R.string.updated_ago, DateUtils.getRelativeTimeSpanString(
    System.currentTimeMillis() - ms.coerceAtMost(365L * 86400_000)).toString().replaceFirstChar { it.lowercase() })

@Composable
private fun NodeRow(number: String, info: NodeInfo?, status: Status, fav: Boolean, linked: Boolean, onClick: () -> Unit, onStar: () -> Unit) {
    val rc = LocalRadioColors.current
    Card(onClick = onClick, shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            NodeBadge(number, highlight = linked)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (status != Status.UNKNOWN) {
                        StatusDot(when (status) { Status.ONLINE -> rc.rx; Status.BUSY -> rc.star; else -> rc.muted.copy(alpha = 0.5f) })
                        Spacer(Modifier.width(8.dp))
                    }
                    NodeTitle(info, number)
                    if (status == Status.BUSY) {
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.el_busy), style = MaterialTheme.typography.labelSmall, color = rc.star)
                    }
                }
                val sub = if (info == null) stringResource(
                    if (isEchoLinkNumber(number)) R.string.echolink_node else R.string.not_in_list)
                    else listOf(info.description, info.location).filter { it.isNotBlank() }.joinToString(" · ")
                if (sub.isNotBlank()) Text(sub, style = MaterialTheme.typography.bodySmall, maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (status == Status.OFFLINE) 0.6f else 1f))
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
fun NodeSheet(number: String, state: RadioState, fav: Boolean, onToggleFavorite: () -> Unit, onDismiss: () -> Unit,
              onLinkRequested: () -> Unit = onDismiss, onCallNode: ((String) -> Unit)? = null) {
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
                        ?: if (echoLink) "EchoLink ${number.drop(1).trimStart('0')}" else number,
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
                    if (echoLink) {
                        val st = Radio.directory.echoStation(number)
                        when {
                            st?.busy == true -> Pill(stringResource(R.string.el_busy_long), rc.star)
                            st != null -> Pill(stringResource(R.string.online), rc.rx)
                            Radio.directory.echo.value.isNotEmpty() -> Pill(stringResource(R.string.el_offline), rc.muted)
                        }
                        Text(stringResource(R.string.echolink_hint), style = MaterialTheme.typography.bodyMedium)
                    }
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

            if (onCallNode != null) {
                // AllStarLink account profile: no own node, call this node directly
                if (echoLink) Text(stringResource(R.string.wt_no_echolink), color = MaterialTheme.colorScheme.onSurfaceVariant)
                else if (isSelf && state.conn != Conn.DISCONNECTED) Pill(stringResource(R.string.wt_on_this_node), rc.rx)
                else Button(onClick = { onCallNode(number) }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                    Icon(Icons.Default.Call, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.call_node))
                }
            } else if (!connected) {
                Text(stringResource(R.string.connect_first), color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else if (!isSelf) {
                if (isLinked) {
                    Button(onClick = { Radio.unlinkNode(number); onDismiss() }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                        Icon(Icons.Default.LinkOff, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.unlink))
                    }
                } else {
                    Button(onClick = { Radio.linkNode(number); onLinkRequested() }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                        Icon(Icons.Default.Link, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.link_tx))
                    }
                    OutlinedButton(onClick = { Radio.linkNode(number, monitorOnly = true); onLinkRequested() },
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
