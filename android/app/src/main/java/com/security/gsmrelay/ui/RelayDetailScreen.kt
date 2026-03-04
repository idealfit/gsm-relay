package com.security.gsmrelay.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.security.gsmrelay.BuildConfig
import com.security.gsmrelay.data.network.CommandQueueItem
import com.security.gsmrelay.model.AppNotification
import com.security.gsmrelay.model.CommandHistory
import com.security.gsmrelay.model.Relay
import com.security.gsmrelay.model.RelayEvent
import com.security.gsmrelay.model.User
import com.security.gsmrelay.viewmodel.AppViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RelayDetailScreen(
    modifier: Modifier = Modifier,
    viewModel: AppViewModel,
    onBack: () -> Unit
) {
    val relay by viewModel.selectedRelay.collectAsState()
    val isGateway = BuildConfig.IS_GATEWAY
    val selectedRelay = relay ?: return

    val tabs = remember(isGateway) {
        val base = mutableListOf(
            "Utilizatori",
            "Comenzi",
            "Coada",
            "Istoric",
            "Evenimente",
            "Notificari"
        )
        if (isGateway) {
            base.add("Istoric general")
            base.add("Notificari general")
        }
        base
    }
    var selectedTab by remember { mutableStateOf(0) }

    LaunchedEffect(selectedRelay.id) {
        viewModel.syncCommands()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(12.dp)
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer
            )
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                }
                Column {
                    Text(selectedRelay.name.ifBlank { "Releu" }, style = MaterialTheme.typography.titleMedium)
                    Text(selectedRelay.phoneNumber)
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AssistChip(
                onClick = {},
                label = { Text("${selectedRelay.users.count { it.phone.isNotBlank() }} / 999 utilizatori") }
            )
            if (selectedRelay.location.isNotBlank()) {
                AssistChip(onClick = {}, label = { Text(selectedRelay.location) })
            }
        }

        Spacer(Modifier.height(8.dp))
        ScrollableTabRow(selectedTabIndex = selectedTab, edgePadding = 0.dp) {
            tabs.forEachIndexed { index, title ->
                Tab(
                    selected = selectedTab == index,
                    onClick = { selectedTab = index },
                    text = { Text(title) }
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        when (tabs[selectedTab]) {
            "Utilizatori" -> RelayUsersTab(viewModel, selectedRelay)
            "Comenzi" -> RelayCommandsTab(viewModel)
            "Coada" -> RelayQueueTab(viewModel, selectedRelay)
            "Istoric" -> RelayHistoryTab(viewModel, selectedRelay)
            "Evenimente" -> RelayEventsTab(viewModel, selectedRelay)
            "Notificari" -> RelayNotificationsTab(viewModel, selectedRelay)
            "Istoric general" -> HistoryScreen(viewModel = viewModel)
            "Notificari general" -> NotificationsScreen(viewModel = viewModel)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RelayUsersTab(viewModel: AppViewModel, relay: Relay) {
    val users = relay.users
    val usersWithPhone = users.filter { it.phone.isNotBlank() }
    val emptySlots = users.filter { it.phone.isBlank() && it.known }
    val anyFreeSlots = users.filter { it.phone.isBlank() }
    val history by viewModel.history.collectAsState()
    val notifications by viewModel.notifications.collectAsState()
    val isGateway = BuildConfig.IS_GATEWAY
    val now = System.currentTimeMillis()
    val pendingQuery = history.any {
        it.status == "trimis" &&
            it.description.startsWith("Interogare utilizatori") &&
            sameRelay(it.relayPhone, relay.phoneNumber) &&
            (now - it.timestamp) < 5 * 60 * 1000
    }
    val lastNotif = notifications.firstOrNull { sameRelay(it.relayPhone, relay.phoneNumber) }

    val context = LocalContext.current
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val text = readTextFromUri(context, uri)
            if (text.isNotBlank()) {
                viewModel.importCsv(text)
            }
        }
    }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        if (uri != null) {
            val csv = viewModel.exportCsv()
            writeTextToUri(context, uri, csv)
        }
    }

    var showAddUser by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    val config = LocalConfiguration.current
    val compactActions = config.screenWidthDp < 420 || config.fontScale > 1.1f

    val usersToShow = users.filter { user ->
        if (searchQuery.isBlank()) return@filter true
        val q = searchQuery.trim().lowercase()
        val phone = user.phone.lowercase()
        val group = user.group.lowercase()
        val id = user.id.toString().padStart(3, '0')
        phone.contains(q) || group.contains(q) || id.contains(q)
    }

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            Text("Utilizatori: ${usersWithPhone.size} / 999")
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                label = { Text("Cauta: telefon, grup, ID (ex. 007)") },
                modifier = Modifier.fillMaxWidth()
            )
        }

        if (pendingQuery) {
            item {
                Spacer(Modifier.height(8.dp))
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer
                    )
                ) {
                    Text(
                        "Interogare in curs... astept raspuns SMS",
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }
        }

        if (lastNotif != null) {
            item {
                Spacer(Modifier.height(8.dp))
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    )
                ) {
                    Text(lastNotif.message, modifier = Modifier.padding(12.dp))
                }
            }
        }

        item {
            Spacer(Modifier.height(12.dp))
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(onClick = { showAddUser = true }) {
                    Text("Adauga")
                }
                Button(onClick = { importLauncher.launch(arrayOf("text/*", "text/csv")) }) {
                    Icon(Icons.Filled.FileUpload, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Import CSV")
                }
                Button(onClick = { exportLauncher.launch("${relay.name}_utilizatori.csv") }) {
                    Icon(Icons.Filled.Save, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Export CSV")
                }
            }
        }

        item {
            Spacer(Modifier.height(8.dp))
            if (isGateway) {
                Button(
                    onClick = { viewModel.syncFromInbox() },
                    modifier = if (compactActions) {
                        Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 40.dp)
                    } else {
                        Modifier.defaultMinSize(minHeight = 40.dp)
                    }
                ) { Text("Sync SMS") }
            }
        }

        item { Spacer(Modifier.height(12.dp)) }

        items(usersToShow) { user ->
            UserRow(user, onDelete = { viewModel.deleteUser(user.id) })
        }
    }

    if (showAddUser) {
        AddUserDialog(
            emptySlots = emptySlots.map { it.id },
            anyFreeSlots = anyFreeSlots.map { it.id },
            onDismiss = { showAddUser = false },
            onAdd = { id, phone, name, group ->
                val ok = viewModel.addUser(id, phone, name, group)
                if (ok) showAddUser = false
                ok
            }
        )
    }
}

@Composable
private fun RelayCommandsTab(viewModel: AppViewModel) {
    var showQuery by remember { mutableStateOf(false) }
    var showChangePassword by remember { mutableStateOf(false) }
    var showTimer by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
        ) {
            Text(
                "Comenzi rapide pentru administrarea releului.",
                modifier = Modifier.padding(12.dp),
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
        }

        Button(onClick = { showQuery = true }, modifier = Modifier.fillMaxWidth()) {
            Text("Interogare utilizatori")
        }
        Button(onClick = { showChangePassword = true }, modifier = Modifier.fillMaxWidth()) {
            Text("Schimba parola")
        }
        Button(onClick = { showTimer = true }, modifier = Modifier.fillMaxWidth()) {
            Text("Temporizare")
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(onClick = { viewModel.allowAllAccess() }, modifier = Modifier.weight(1f)) {
                Text("ALL")
            }
            Button(onClick = { viewModel.allowAuthorizedOnly() }, modifier = Modifier.weight(1f)) {
                Text("AUT")
            }
        }
    }

    if (showQuery) {
        QueryUsersDialog(
            onDismiss = { showQuery = false },
            onQuery = { start, end ->
                viewModel.queryUsers(start, end)
                showQuery = false
            }
        )
    }

    if (showChangePassword) {
        ChangePasswordDialog(
            onDismiss = { showChangePassword = false },
            onSave = { newPassword ->
                viewModel.changeRelayPassword(newPassword)
                showChangePassword = false
            }
        )
    }

    if (showTimer) {
        TimerDialog(
            onDismiss = { showTimer = false },
            onSave = { seconds ->
                viewModel.setRelayTimer(seconds)
                showTimer = false
            }
        )
    }
}

@Composable
private fun RelayHistoryTab(viewModel: AppViewModel, relay: Relay) {
    val history by viewModel.history.collectAsState()
    val formatter = remember { SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()) }
    val items = history.filter { sameRelay(it.relayPhone, relay.phoneNumber) }
    if (items.isEmpty()) {
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Text("Nu exista comenzi pentru acest releu.", modifier = Modifier.padding(12.dp))
        }
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(items) { cmd ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(cmd.description, fontWeight = FontWeight.SemiBold)
                    Text(cmd.command)
                    AssistChip(onClick = {}, label = { Text("Status: ${cmd.status}") })
                    Text(
                        formatter.format(Date(cmd.timestamp)),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun RelayNotificationsTab(viewModel: AppViewModel, relay: Relay) {
    val notifications by viewModel.notifications.collectAsState()
    val formatter = remember { SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()) }
    val items = notifications.filter { sameRelay(it.relayPhone, relay.phoneNumber) }
    if (items.isEmpty()) {
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Text("Nu exista notificari pentru acest releu.", modifier = Modifier.padding(12.dp))
        }
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(items) { note ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text(note.message, fontWeight = FontWeight.SemiBold)
                    AssistChip(onClick = {}, label = { Text("Tip: ${note.type}") })
                    Text(formatter.format(Date(note.timestamp)))
                }
            }
        }
    }
}

@Composable
private fun RelayEventsTab(viewModel: AppViewModel, relay: Relay) {
    val events by viewModel.events.collectAsState()
    val context = LocalContext.current
    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }
    val now = remember { System.currentTimeMillis() }
    var startMillis by remember { mutableStateOf(now - 24 * 60 * 60 * 1000L) }
    var endMillis by remember { mutableStateOf(now) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        if (uri != null) {
            val filtered = events.filter { sameRelay(it.relayPhone, relay.phoneNumber) }
            val csv = buildEventsCsv(filtered)
            writeTextToUri(context, uri, csv)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Box(modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = dateFormat.format(Date(startMillis)),
                        onValueChange = {},
                        label = { Text("De la") },
                        modifier = Modifier.fillMaxWidth(),
                        readOnly = true,
                        singleLine = true
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clickable { pickDateTime(context, startMillis) { startMillis = it } }
                    )
                }
                Spacer(Modifier.height(6.dp))
                Box(modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = dateFormat.format(Date(endMillis)),
                        onValueChange = {},
                        label = { Text("Pana la") },
                        modifier = Modifier.fillMaxWidth(),
                        readOnly = true,
                        singleLine = true
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clickable { pickDateTime(context, endMillis) { endMillis = it } }
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        viewModel.requestScrapeEvents(relay, startMillis, endMillis)
                    }) {
                        Text("Scrape")
                    }
                    Button(onClick = { exportLauncher.launch("events.csv") }) {
                        Text("Export CSV")
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        val filtered = events.filter { sameRelay(it.relayPhone, relay.phoneNumber) }
        if (filtered.isEmpty()) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Text("Nu exista evenimente.", modifier = Modifier.padding(12.dp))
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(filtered.sortedByDescending { it.timestamp }) { ev ->
                    Card(
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                ev.relayName.ifBlank { relay.name.ifBlank { relay.phoneNumber } },
                                fontWeight = FontWeight.SemiBold
                            )
                            Text("Operat de: ${ev.operatorPhone}")
                            Text(dateFormat.format(Date(ev.timestamp)))
                            Text(ev.message)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RelayQueueTab(viewModel: AppViewModel, relay: Relay) {
    val commands by viewModel.commands.collectAsState()
    val items = commands.filter { sameRelay(it.relayPhone, relay.phoneNumber) }
    if (items.isEmpty()) {
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Text("Coada este goala.", modifier = Modifier.padding(12.dp))
        }
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(items) { cmd ->
            CommandQueueRow(cmd)
        }
    }
}

@Composable
private fun CommandQueueRow(cmd: CommandQueueItem) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(cmd.description.ifBlank { "Comanda" }, fontWeight = FontWeight.SemiBold)
            Text(cmd.command)
            AssistChip(onClick = {}, label = { Text("Status: ${cmd.status}") })
            if (cmd.responseText.isNotBlank()) {
                Text("Raspuns: ${cmd.responseText}")
            }
        }
    }
}

@Composable
private fun UserRow(user: User, onDelete: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (user.phone.isNotBlank()) {
                MaterialTheme.colorScheme.surface
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(Modifier.padding(end = 8.dp)) {
                Text("Pozitie ${user.id.toString().padStart(3, '0')}", fontWeight = FontWeight.SemiBold)
                Text(if (user.phone.isNotBlank()) user.phone else "Empty")
                if (user.phone.isNotBlank()) {
                    Text("Grup: ${user.group}")
                }
            }
            if (user.phone.isNotBlank()) {
                IconButton(onClick = onDelete) {
                    Icon(Icons.Filled.Delete, contentDescription = "Delete")
                }
            }
        }
    }
}

private fun sameRelay(a: String, b: String): Boolean {
    val aDigits = a.filter { it.isDigit() }.takeLast(8)
    val bDigits = b.filter { it.isDigit() }.takeLast(8)
    return aDigits.isNotBlank() && aDigits == bDigits
}

private fun buildEventsCsv(events: List<RelayEvent>): String {
    val header = "Timp,Releu,Telefon Releu,Operat de,Mesaj"
    val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    val body = events.joinToString("\n") { ev ->
        val time = formatter.format(Date(ev.timestamp))
        "${time},${ev.relayName},${ev.relayPhone},${ev.operatorPhone},${ev.message}"
    }
    return "$header\n$body"
}

private fun pickDateTime(
    context: android.content.Context,
    initialMillis: Long,
    onSelected: (Long) -> Unit
) {
    val cal = java.util.Calendar.getInstance().apply { timeInMillis = initialMillis }
    android.app.DatePickerDialog(
        context,
        { _, year, month, day ->
            val temp = java.util.Calendar.getInstance().apply {
                set(java.util.Calendar.YEAR, year)
                set(java.util.Calendar.MONTH, month)
                set(java.util.Calendar.DAY_OF_MONTH, day)
                set(java.util.Calendar.HOUR_OF_DAY, cal.get(java.util.Calendar.HOUR_OF_DAY))
                set(java.util.Calendar.MINUTE, cal.get(java.util.Calendar.MINUTE))
            }
            android.app.TimePickerDialog(
                context,
                { _, hour, minute ->
                    temp.set(java.util.Calendar.HOUR_OF_DAY, hour)
                    temp.set(java.util.Calendar.MINUTE, minute)
                    onSelected(temp.timeInMillis)
                },
                cal.get(java.util.Calendar.HOUR_OF_DAY),
                cal.get(java.util.Calendar.MINUTE),
                true
            ).show()
        },
        cal.get(java.util.Calendar.YEAR),
        cal.get(java.util.Calendar.MONTH),
        cal.get(java.util.Calendar.DAY_OF_MONTH)
    ).show()
}
