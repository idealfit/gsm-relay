package com.security.gsmrelay.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.security.gsmrelay.BuildConfig
import com.security.gsmrelay.viewmodel.AppViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Calendar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventsScreen(
    modifier: Modifier = Modifier,
    viewModel: AppViewModel
) {
    val events by viewModel.events.collectAsState()
    val relays by viewModel.relays.collectAsState()
    val isGateway = BuildConfig.IS_GATEWAY
    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }
    val now = remember { System.currentTimeMillis() }
    val context = LocalContext.current
    var startMillis by remember { mutableStateOf(now - 24 * 60 * 60 * 1000L) }
    var endMillis by remember { mutableStateOf(now) }
    var selectedRelayId by remember { mutableStateOf<Long?>(null) }
    var relayMenuExpanded by remember { mutableStateOf(false) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        if (uri != null) {
            val csv = viewModel.exportEventsCsv()
            writeTextToUri(context, uri, csv)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(12.dp)
    ) {
        Text(if (isGateway) "Scraping evenimente (Gateway)" else "Scraping evenimente")
        Spacer(Modifier.height(6.dp))
        val selectedRelay = relays.firstOrNull { it.id == selectedRelayId } ?: relays.firstOrNull()
        if (selectedRelayId == null && selectedRelay != null) {
            selectedRelayId = selectedRelay.id
        }

        Box(modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = selectedRelay?.name?.ifBlank { selectedRelay.phoneNumber } ?: "Selecteaza releu",
                onValueChange = {},
                label = { Text("Releu") },
                modifier = Modifier.fillMaxWidth(),
                readOnly = true,
                singleLine = true
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable { relayMenuExpanded = true }
            )
        }
        DropdownMenu(
            expanded = relayMenuExpanded,
            onDismissRequest = { relayMenuExpanded = false }
        ) {
            relays.forEach { relay ->
                DropdownMenuItem(
                    text = { Text(relay.name.ifBlank { relay.phoneNumber }) },
                    onClick = {
                        selectedRelayId = relay.id
                        relayMenuExpanded = false
                    }
                )
            }
        }

        Spacer(Modifier.height(6.dp))
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
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                val relay = relays.firstOrNull { it.id == selectedRelayId }
                if (relay == null) {
                    viewModel.addNotification("Selecteaza un releu", "error")
                    return@Button
                }
                viewModel.requestScrapeEvents(relay, startMillis, endMillis)
            }) {
                Text("Scrape")
            }
            Button(onClick = { exportLauncher.launch("events.csv") }) {
                Text("Export CSV")
            }
        }

        Spacer(Modifier.height(12.dp))
        if (events.isEmpty()) {
            Text("Nu exista evenimente.")
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(events.sortedByDescending { it.timestamp }) { ev ->
                    Card(modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(ev.relayName.ifBlank { ev.relayPhone })
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
