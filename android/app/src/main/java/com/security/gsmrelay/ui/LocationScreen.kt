package com.security.gsmrelay.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.security.gsmrelay.model.Relay
import com.security.gsmrelay.viewmodel.AppViewModel
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private fun normalizedLocation(location: String): String {
    return location.trim().ifBlank { "Fara locatie" }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocationListScreen(
    modifier: Modifier = Modifier,
    viewModel: AppViewModel,
    onLocationClick: (String) -> Unit
) {
    val relays by viewModel.relays.collectAsState()
    val knownLocations by viewModel.locations.collectAsState()
    var showAddLocation by remember { mutableStateOf(false) }
    var locationToRename by remember { mutableStateOf<String?>(null) }
    var locationToDelete by remember { mutableStateOf<String?>(null) }
    var locationToDeleteConfirm by remember { mutableStateOf<String?>(null) }

    val groupedRelays = relays.groupBy { normalizedLocation(it.location) }
    val allLocations = (groupedRelays.keys + knownLocations)
        .distinctBy { it.lowercase() }
        .sortedBy { it.lowercase() }

    Scaffold(
        modifier = modifier,
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddLocation = true }) {
                Icon(Icons.Filled.Add, contentDescription = "Adauga locatie")
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(12.dp)
        ) {
            Text("Locatii", style = MaterialTheme.typography.titleLarge)
            Text(
                "Selecteaza o locatie pentru a administra releele si utilizatorii.",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(10.dp))

            if (allLocations.isEmpty()) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                ) {
                    Text(
                        "Nu exista relee configurate. Adauga primul releu si seteaza locatia.",
                        modifier = Modifier.padding(14.dp)
                    )
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(allLocations) { location ->
                        val locationRelays = groupedRelays[location].orEmpty()
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { onLocationClick(location) },
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.Filled.Place, contentDescription = null)
                                    Spacer(Modifier.width(8.dp))
                                    Column(modifier = Modifier.padding(start = 8.dp)) {
                                        Text(location, fontWeight = FontWeight.SemiBold)
                                        Text("${locationRelays.size} relee")
                                    }
                                }
                                Row {
                                    IconButton(onClick = { locationToRename = location }) {
                                        Icon(Icons.Filled.Edit, contentDescription = "Redenumeste locatie")
                                    }
                                    IconButton(onClick = { locationToDelete = location }) {
                                        Icon(Icons.Filled.Delete, contentDescription = "Sterge locatie")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        if (showAddLocation) {
            LocationNameDialog(
                onDismiss = { showAddLocation = false },
                onConfirm = { value ->
                    if (viewModel.addLocation(value)) {
                        showAddLocation = false
                    }
                },
                title = "Locatie noua",
                confirmLabel = "Adauga"
            )
        }

        if (locationToRename != null) {
            val source = locationToRename ?: ""
            LocationNameDialog(
                onDismiss = { locationToRename = null },
                onConfirm = { value ->
                    if (viewModel.renameLocation(source, value)) {
                        locationToRename = null
                    }
                },
                title = "Redenumeste locatie",
                confirmLabel = "Salveaza",
                initialValue = source
            )
        }

        if (locationToDelete != null) {
            val location = locationToDelete ?: ""
            AlertDialog(
                onDismissRequest = { locationToDelete = null },
                title = { Text("Sterge locatie") },
                text = { Text("Stergi locatia \"$location\"? Vor fi sterse toate releele din locatie si toate datele asociate (utilizatori, istoric, evenimente, notificari).") },
                confirmButton = {
                    Button(onClick = {
                        locationToDelete = null
                        locationToDeleteConfirm = location
                    }) {
                        Text("Continua")
                    }
                },
                dismissButton = {
                    Button(onClick = { locationToDelete = null }) {
                        Text("Anuleaza")
                    }
                }
            )
        }

        if (locationToDeleteConfirm != null) {
            val location = locationToDeleteConfirm ?: ""
            AlertDialog(
                onDismissRequest = { locationToDeleteConfirm = null },
                title = { Text("Confirmare finala") },
                text = { Text("Confirmi stergerea definitiva a locatiei \"$location\" si a tuturor releelor/datelor asociate?") },
                confirmButton = {
                    Button(onClick = {
                        viewModel.deleteLocation(location)
                        locationToDeleteConfirm = null
                    }) {
                        Text("Sterge definitiv")
                    }
                },
                dismissButton = {
                    Button(onClick = { locationToDeleteConfirm = null }) {
                        Text("Anuleaza")
                    }
                }
            )
        }
    }
}

@Composable
fun LocationDetailScreen(
    modifier: Modifier = Modifier,
    locationName: String,
    viewModel: AppViewModel,
    onBack: () -> Unit,
    onRelayClick: (Relay) -> Unit
) {
    val context = LocalContext.current
    val relays by viewModel.relays.collectAsState()
    val commands by viewModel.commands.collectAsState()
    val events by viewModel.events.collectAsState()
    val locationRelays = relays.filter { normalizedLocation(it.location) == locationName }
    val selectedRelayIds = remember { mutableStateListOf<Long>() }
    var selectedTab by remember { mutableStateOf(0) }
    var showAddRelay by remember { mutableStateOf(false) }
    var showAddUserMulti by remember { mutableStateOf(false) }
    var relayToEdit by remember { mutableStateOf<Relay?>(null) }
    var relayToDelete by remember { mutableStateOf<Relay?>(null) }
    var relayToDeleteConfirm by remember { mutableStateOf<Relay?>(null) }
    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }
    val now = remember { System.currentTimeMillis() }
    var startMillis by remember { mutableStateOf(now - 24 * 60 * 60 * 1000L) }
    var endMillis by remember { mutableStateOf(now) }
    val tabs = listOf("Relee", "Coada", "Evenimente")
    val exportLocationEventsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        if (uri != null) {
            val phoneKeys = locationRelays.mapNotNull { relayPhoneKey(it.phoneNumber) }.toSet()
            val eventItems = events
                .filter { relayPhoneKey(it.relayPhone) in phoneKeys }
                .sortedByDescending { it.timestamp }
            val csv = buildLocationEventsCsv(eventItems)
            writeTextToUri(context, uri, csv)
        }
    }

    LaunchedEffect(locationRelays.map { it.id }.joinToString(",")) {
        selectedRelayIds.clear()
        selectedRelayIds.addAll(locationRelays.map { it.id })
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "Inapoi")
            }
            Column {
                Text(locationName, style = MaterialTheme.typography.titleLarge)
                Text("${locationRelays.size} relee")
            }
        }

        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AssistChip(
                onClick = {},
                label = { Text("Selectate: ${selectedRelayIds.size}/${locationRelays.size}") }
            )
            AssistChip(
                onClick = {
                    selectedRelayIds.clear()
                    selectedRelayIds.addAll(locationRelays.map { it.id })
                },
                label = { Text("Selecteaza tot") }
            )
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

        Spacer(Modifier.height(10.dp))
        when (tabs[selectedTab]) {
            "Relee" -> {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { showAddUserMulti = true },
                        enabled = selectedRelayIds.isNotEmpty()
                    ) {
                        Icon(Icons.Filled.Check, contentDescription = null)
                        Text("Adauga utilizator pe selectie")
                    }
                    Button(onClick = { showAddRelay = true }) {
                        Text("Releu nou")
                    }
                }

                Spacer(Modifier.height(10.dp))
                if (locationRelays.isEmpty()) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Text(
                            "Nu exista relee in aceasta locatie.",
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(locationRelays) { relay ->
                            val checked = selectedRelayIds.contains(relay.id)
                            Card(modifier = Modifier.fillMaxWidth()) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { onRelayClick(relay) }
                                        .padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(
                                        modifier = Modifier.weight(1f),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Checkbox(
                                            checked = checked,
                                            onCheckedChange = { isChecked ->
                                                if (isChecked) {
                                                    if (!selectedRelayIds.contains(relay.id)) selectedRelayIds.add(relay.id)
                                                } else {
                                                    selectedRelayIds.remove(relay.id)
                                                }
                                            }
                                        )
                                        Column {
                                            Text(relay.name, fontWeight = FontWeight.SemiBold)
                                            Text(relay.phoneNumber)
                                            Text(
                                                "${relay.users.count { it.phone.isNotBlank() }} / 999 utilizatori",
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                    Row {
                                        IconButton(onClick = { relayToEdit = relay }) {
                                            Icon(Icons.Filled.Edit, contentDescription = "Editeaza")
                                        }
                                        IconButton(onClick = { relayToDelete = relay }) {
                                            Icon(Icons.Filled.Delete, contentDescription = "Sterge")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            "Coada" -> {
                val phoneKeys = locationRelays.mapNotNull { relayPhoneKey(it.phoneNumber) }.toSet()
                val queueItems = commands
                    .filter { relayPhoneKey(it.relayPhone) in phoneKeys }
                    .sortedByDescending { it.createdAt }
                if (queueItems.isEmpty()) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Text("Nu exista comenzi in coada pentru aceasta locatie.", modifier = Modifier.padding(12.dp))
                    }
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(queueItems) { cmd ->
                            val relayName = locationRelays.firstOrNull {
                                sameRelayPhone(it.phoneNumber, cmd.relayPhone)
                            }?.name ?: cmd.relayPhone
                            Card(modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(12.dp)) {
                                    Text(relayName, fontWeight = FontWeight.SemiBold)
                                    Text(cmd.description.ifBlank { "Comanda" })
                                    Text(cmd.command)
                                    AssistChip(onClick = {}, label = { Text("Status: ${cmd.status}") })
                                    if (cmd.responseText.isNotBlank()) {
                                        Text("Raspuns: ${cmd.responseText}")
                                    }
                                }
                            }
                        }
                    }
                }
            }
            "Evenimente" -> {
                val phoneKeys = locationRelays.mapNotNull { relayPhoneKey(it.phoneNumber) }.toSet()
                val eventItems = events
                    .filter { relayPhoneKey(it.relayPhone) in phoneKeys }
                    .filter { it.timestamp in startMillis..endMillis }
                    .sortedByDescending { it.timestamp }
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
                    Button(
                        onClick = {
                            if (startMillis > endMillis) {
                                viewModel.addNotification("Interval invalid pentru scraping", "error")
                                return@Button
                            }
                            if (locationRelays.isEmpty()) {
                                viewModel.addNotification("Nu exista relee in aceasta locatie", "error")
                                return@Button
                            }
                            locationRelays.forEach { relay ->
                                viewModel.requestScrapeEvents(relay, startMillis, endMillis)
                            }
                            viewModel.addNotification(
                                "Cerere scraping trimisa pentru ${locationRelays.size} relee din \"$locationName\"",
                                "info"
                            )
                        },
                        enabled = locationRelays.isNotEmpty()
                    ) {
                        Text("Scrape locatie")
                    }
                    Button(
                        onClick = {
                            val filename = "events_${locationName.replace(' ', '_')}.csv"
                            exportLocationEventsLauncher.launch(filename)
                        },
                        enabled = eventItems.isNotEmpty()
                    ) {
                        Text("Export CSV locatie")
                    }
                }
                Spacer(Modifier.height(8.dp))
                if (eventItems.isEmpty()) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Text("Nu exista evenimente pentru aceasta locatie in intervalul selectat.", modifier = Modifier.padding(12.dp))
                    }
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(eventItems) { ev ->
                            val relayName = locationRelays.firstOrNull {
                                sameRelayPhone(it.phoneNumber, ev.relayPhone)
                            }?.name ?: ev.relayName.ifBlank { ev.relayPhone }
                            Card(modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(12.dp)) {
                                    Text(relayName, fontWeight = FontWeight.SemiBold)
                                    Text("Operat de: ${ev.operatorPhone}")
                                    Text(java.text.SimpleDateFormat("dd.MM.yyyy HH:mm", java.util.Locale.getDefault()).format(java.util.Date(ev.timestamp)))
                                    Text(ev.message)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showAddRelay) {
        AddRelayDialog(
            onDismiss = { showAddRelay = false },
            onAddRelay = { name, phone, password, location, queryStart, queryEnd, options ->
                viewModel.addRelay(
                    name = name,
                    phoneNumber = phone,
                    password = password,
                    location = location.ifBlank { locationName },
                    queryStart = queryStart,
                    queryEnd = queryEnd,
                    setupOptions = options
                )
                showAddRelay = false
            },
            title = "Releu nou",
            confirmLabel = "Adauga",
            showQueryRange = true,
            initialLocation = if (locationName == "Fara locatie") "" else locationName
        )
    }

    if (showAddUserMulti) {
        AddUserToRelaysDialog(
            relayCount = selectedRelayIds.size,
            onDismiss = { showAddUserMulti = false },
            onAdd = { phone, name, group ->
                val ok = viewModel.addUserToRelays(selectedRelayIds.toList(), phone, name, group)
                if (ok) showAddUserMulti = false
                ok
            }
        )
    }

    if (relayToEdit != null) {
        val relay = relayToEdit ?: return
        AddRelayDialog(
            onDismiss = { relayToEdit = null },
            title = "Editeaza releu",
            confirmLabel = "Salveaza",
            initialName = relay.name,
            initialPhoneNumber = relay.phoneNumber,
            initialPassword = relay.password,
            initialLocation = relay.location,
            showQueryRange = false,
            onAddRelay = { name, phone, password, location, _, _, _ ->
                viewModel.updateRelayDetails(relay.id, name, phone, password, location)
                relayToEdit = null
            }
        )
    }

    if (relayToDelete != null) {
        val relay = relayToDelete ?: return
        AlertDialog(
            onDismissRequest = { relayToDelete = null },
            title = { Text("Sterge releu") },
            text = { Text("Stergi releul ${relay.name}? Actiunea nu poate fi anulata.") },
            confirmButton = {
                Button(onClick = {
                    relayToDelete = null
                    relayToDeleteConfirm = relay
                }) {
                    Text("Continua")
                }
            },
            dismissButton = {
                Button(onClick = { relayToDelete = null }) {
                    Text("Anuleaza")
                }
            }
        )
    }

    if (relayToDeleteConfirm != null) {
        val relay = relayToDeleteConfirm ?: return
        AlertDialog(
            onDismissRequest = { relayToDeleteConfirm = null },
            title = { Text("Confirmare finala") },
            text = { Text("Confirmi stergerea definitiva a releului ${relay.name}?") },
            confirmButton = {
                Button(onClick = {
                    viewModel.deleteRelay(relay.id)
                    relayToDeleteConfirm = null
                }) {
                    Text("Sterge definitiv")
                }
            },
            dismissButton = {
                Button(onClick = { relayToDeleteConfirm = null }) {
                    Text("Anuleaza")
                }
            }
        )
    }
}

private fun relayPhoneKey(phone: String): String? {
    val digits = phone.filter { it.isDigit() }.takeLast(8)
    return digits.ifBlank { null }
}

private fun sameRelayPhone(a: String, b: String): Boolean {
    return relayPhoneKey(a) != null && relayPhoneKey(a) == relayPhoneKey(b)
}

private fun buildLocationEventsCsv(events: List<com.security.gsmrelay.model.RelayEvent>): String {
    val header = "Timp,Releu,Telefon Releu,Operat de,Mesaj"
    val formatter = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
    val body = events.joinToString("\n") { ev ->
        val time = formatter.format(java.util.Date(ev.timestamp))
        "${time},${ev.relayName},${ev.relayPhone},${ev.operatorPhone},${ev.message}"
    }
    return if (body.isBlank()) header else "$header\n$body"
}

private fun pickDateTime(
    context: android.content.Context,
    initialMillis: Long,
    onSelected: (Long) -> Unit
) {
    val cal = Calendar.getInstance().apply { timeInMillis = initialMillis }
    android.app.DatePickerDialog(
        context,
        { _, year, month, day ->
            val temp = Calendar.getInstance().apply {
                set(Calendar.YEAR, year)
                set(Calendar.MONTH, month)
                set(Calendar.DAY_OF_MONTH, day)
                set(Calendar.HOUR_OF_DAY, cal.get(Calendar.HOUR_OF_DAY))
                set(Calendar.MINUTE, cal.get(Calendar.MINUTE))
            }
            android.app.TimePickerDialog(
                context,
                { _, hour, minute ->
                    temp.set(Calendar.HOUR_OF_DAY, hour)
                    temp.set(Calendar.MINUTE, minute)
                    onSelected(temp.timeInMillis)
                },
                cal.get(Calendar.HOUR_OF_DAY),
                cal.get(Calendar.MINUTE),
                true
            ).show()
        },
        cal.get(Calendar.YEAR),
        cal.get(Calendar.MONTH),
        cal.get(Calendar.DAY_OF_MONTH)
    ).show()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LocationNameDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
    title: String,
    confirmLabel: String,
    initialValue: String = ""
) {
    var value by remember(initialValue) { mutableStateOf(initialValue) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                label = { Text("Nume locatie") }
            )
        },
        confirmButton = {
            Button(onClick = { onConfirm(value) }) {
                Text(confirmLabel)
            }
        },
        dismissButton = {
            Button(onClick = onDismiss) {
                Text("Anuleaza")
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddUserToRelaysDialog(
    relayCount: Int,
    onDismiss: () -> Unit,
    onAdd: (String, String, String) -> Boolean
) {
    var phone by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var group by remember { mutableStateOf("general") }
    var error by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Adauga utilizator pe selectie") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Relee selectate: $relayCount")
                Text("Canalul este ales automat: primul liber din fiecare releu.")
                OutlinedTextField(
                    value = phone,
                    onValueChange = { phone = it },
                    label = { Text("Numar telefon *") }
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Nume (optional)") }
                )
                OutlinedTextField(
                    value = group,
                    onValueChange = { group = it },
                    label = { Text("Grup") }
                )
                if (error.isNotBlank()) {
                    Text(error, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                if (phone.isBlank()) {
                    error = "Completeaza numarul de telefon"
                    return@Button
                }
                val ok = onAdd(phone, name, group)
                if (!ok) error = "Nu s-a putut adauga pe releele selectate"
            }) {
                Text("Trimite")
            }
        },
        dismissButton = {
            Button(onClick = onDismiss) {
                Text("Anuleaza")
            }
        }
    )
}
