package ovh.adan.allstar

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CellTower
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.TravelExplore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ovh.adan.allstar.data.Profile
import ovh.adan.allstar.data.SetupLink
import ovh.adan.allstar.ui.AllStarTheme
import ovh.adan.allstar.ui.NodeSheet
import ovh.adan.allstar.ui.NodesScreen
import ovh.adan.allstar.ui.RadioScreen
import ovh.adan.allstar.ui.SettingsScreen

class MainActivity : ComponentActivity() {
    private var pendingConnect: Profile? = null
    /** Profile from a mobileasl:// setup link, waiting to be reviewed in Settings. */
    private var importRequest by mutableStateOf<Profile?>(null)

    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (!ok) Toast.makeText(this, R.string.mic_needed, Toast.LENGTH_LONG).show()
        pendingConnect?.let { Radio.connect(it) }
        pendingConnect = null
    }
    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Radio.init(this)
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        handleLink(intent)
        setContent { AllStarTheme { App() } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleLink(intent)
    }

    private fun handleLink(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        SetupLink.parse(intent.dataString)?.let { importRequest = mergeImport(it) }
    }

    /** Re-running the setup script on the same node updates the existing profile instead of duplicating it. */
    private fun mergeImport(p: Profile): Profile {
        val old = Radio.prefs.profiles.firstOrNull { it.host.equals(p.host, true) && it.username == p.username && it.node == p.node }
        return old?.let { p.copy(id = it.id, name = it.name, callsign = it.callsign) } ?: p
    }

    private fun connect(p: Profile) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            Radio.connect(p)
        } else {
            pendingConnect = p
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (Radio.prefs.volumeKeyPtt && event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN && Radio.state.value.conn == Conn.CONNECTED) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                if (Radio.prefs.pttMode == ovh.adan.allstar.data.PttMode.HOLD) Radio.pttDown() else Radio.togglePtt()
            } else if (event.action == KeyEvent.ACTION_UP && Radio.prefs.pttMode == ovh.adan.allstar.data.PttMode.HOLD) {
                Radio.pttUp()
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    @Composable
    private fun App() {
        val state by Radio.state.collectAsStateWithLifecycle()
        var tab by rememberSaveable { mutableIntStateOf(0) }
        var version by remember { mutableIntStateOf(0) }       // bump to re-read prefs
        var openNode by remember { mutableStateOf<String?>(null) }
        var addRequest by remember { mutableStateOf<Profile?>(null) }
        val prefs = Radio.prefs
        // read prefs under `version` so changes recompose
        val profiles = remember(version) { prefs.profiles }
        val active = remember(version) { prefs.activeProfile }
        val favorites = remember(version) { prefs.favorites }
        val recents = remember(version, state.events.size) { prefs.recents }
        val pttMode = remember(version) { prefs.pttMode }

        LaunchedEffect(importRequest) {
            importRequest?.let { addRequest = it; tab = 2; importRequest = null }
        }

        LaunchedEffect(version) {
            if (prefs.keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        val toggleFav: (String) -> Unit = { n ->
            Radio.directory.rememberEchoLink(n)
            prefs.favorites = if (n in prefs.favorites) prefs.favorites - n else prefs.favorites + n
            version++
        }

        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            bottomBar = {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                    NavigationBarItem(tab == 0, { tab = 0 }, { Icon(Icons.Default.CellTower, null) }, label = { Text(stringResource(R.string.tab_radio)) })
                    NavigationBarItem(tab == 1, { tab = 1 }, { Icon(Icons.Default.TravelExplore, null) }, label = { Text(stringResource(R.string.tab_nodes)) })
                    NavigationBarItem(tab == 2, { tab = 2 }, { Icon(Icons.Default.Settings, null) }, label = { Text(stringResource(R.string.tab_settings)) })
                }
            },
        ) { pad ->
            Box(Modifier.fillMaxSize().padding(pad)) {
                when (tab) {
                    0 -> RadioScreen(state, profiles, active, pttMode,
                        onConnect = { connect(it) },
                        onSelectProfile = { prefs.activeProfileId = it.id; version++ },
                        onAddProfile = { tab = 2; addRequest = Profile() },
                        onOpenNode = { openNode = it })
                    1 -> NodesScreen(state, favorites, recents, toggleFav) { openNode = it }
                    2 -> SettingsScreen(profiles, prefs.activeProfileId,
                        onSaveProfile = { p ->
                            prefs.profiles = prefs.profiles.filter { it.id != p.id } + p
                            if (prefs.activeProfileId == null || prefs.profiles.size == 1) prefs.activeProfileId = p.id
                            version++
                        },
                        onDeleteProfile = { p -> prefs.profiles = prefs.profiles.filter { it.id != p.id }; version++ },
                        onSelectProfile = { prefs.activeProfileId = it.id; version++ },
                        editRequest = addRequest, onEditRequestHandled = { addRequest = null },
                        onImportLink = { mergeImport(it) },
                        settingsVersion = version, onSettingsChanged = { version++ })
                }
            }
        }

        openNode?.let { n ->
            NodeSheet(n, state, n in favorites, onToggleFavorite = { toggleFav(n) }, onDismiss = { openNode = null })
        }
    }
}
