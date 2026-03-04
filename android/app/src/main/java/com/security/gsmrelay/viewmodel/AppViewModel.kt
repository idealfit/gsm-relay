package com.security.gsmrelay.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.security.gsmrelay.data.model.ServerConfig
import com.security.gsmrelay.data.network.ServerApi
import com.security.gsmrelay.data.network.ServerSnapshot
import com.security.gsmrelay.data.network.CommandQueueItem
import com.security.gsmrelay.data.repository.AppRepository
import com.security.gsmrelay.model.AppNotification
import com.security.gsmrelay.model.CommandHistory
import com.security.gsmrelay.model.Relay
import com.security.gsmrelay.model.RelayEvent
import com.security.gsmrelay.model.User
import com.security.gsmrelay.sms.EventScraper
import com.security.gsmrelay.sms.SmsSender
import com.security.gsmrelay.BuildConfig
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import android.net.Uri
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.runBlocking

data class RelaySetupOptions(
    val forcePasswordReset: Boolean = true,
    val setDateTime: Boolean = true,
    val setMaster: Boolean = true,
    val setConfirmOn: Boolean = true,
    val setConfirmOff: Boolean = true,
    val queryUsers: Boolean = true,
    val autoAddAdmins: Boolean = true
)

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = AppRepository(application.applicationContext)
    private val _relays = MutableStateFlow<List<Relay>>(emptyList())
    val relays: StateFlow<List<Relay>> = _relays

    private val _history = MutableStateFlow<List<CommandHistory>>(emptyList())
    val history: StateFlow<List<CommandHistory>> = _history

    private val _notifications = MutableStateFlow<List<AppNotification>>(emptyList())
    val notifications: StateFlow<List<AppNotification>> = _notifications

    private val _pendingCommands = MutableStateFlow<List<String>>(emptyList())
    val pendingCommands: StateFlow<List<String>> = _pendingCommands

    private val _events = MutableStateFlow<List<RelayEvent>>(emptyList())
    val events: StateFlow<List<RelayEvent>> = _events

    private val _commands = MutableStateFlow<List<CommandQueueItem>>(emptyList())
    val commands: StateFlow<List<CommandQueueItem>> = _commands

    private val _selectedRelay = MutableStateFlow<Relay?>(null)
    val selectedRelay: StateFlow<Relay?> = _selectedRelay

    private val _filterGroup = MutableStateFlow("all")
    val filterGroup: StateFlow<String> = _filterGroup

    private val _serverConfig = MutableStateFlow(ServerConfig())
    val serverConfig: StateFlow<ServerConfig> = _serverConfig

    private val _locations = MutableStateFlow<List<String>>(emptyList())
    val locations: StateFlow<List<String>> = _locations

    private var initialServerSync = false
    private var uploadJob: Job? = null
    private var autoSyncJob: Job? = null
    private var serverConfigMigrated = false
    private var isServerSyncInProgress = false
    private var suppressScheduledUpload = false
    private var explicitLocations: List<String> = emptyList()

    init {
        viewModelScope.launch {
            repository.relaysFlow().collectLatest { relays ->
                val normalized = relays.map { sanitizeRelay(it) }.map { relay ->
                    val relayUsers = runCatching { relay.users }.getOrDefault(emptyList())
                    val normalizedUsers = (1..MAX_RELAY_CHANNELS).map { id ->
                        val existing = relayUsers.firstOrNull { runCatching { it.id }.getOrDefault(-1) == id }
                        if (existing != null) {
                            val safePhone = safeText { existing.phone }
                            if (safePhone.isNotBlank() && !runCatching { existing.known }.getOrDefault(false)) {
                                existing.copy(known = true)
                            } else {
                                existing
                            }
                        } else {
                            User(id = id)
                        }
                    }
                    relay.copy(users = normalizedUsers)
                }
                _relays.value = normalized
                refreshLocationsState(relays = normalized)
                val currentSelected = _selectedRelay.value
                if (currentSelected != null) {
                    val updatedSelected = normalized.firstOrNull { it.id == currentSelected.id }
                    if (updatedSelected != null) {
                        _selectedRelay.value = updatedSelected
                    }
                }
            }
        }
        viewModelScope.launch {
            repository.historyFlow().collectLatest { items ->
                _history.value = items.map { sanitizeHistoryItem(it) }
            }
        }
        viewModelScope.launch {
            repository.eventsFlow().collectLatest { items ->
                _events.value = items.map { sanitizeEvent(it) }
            }
        }
        viewModelScope.launch {
            repository.notificationsFlow().collectLatest { items ->
                _notifications.value = items.map { sanitizeNotification(it) }
            }
        }
        viewModelScope.launch {
            repository.serverConfigFlow().collectLatest { config ->
                if (!serverConfigMigrated && (config.baseUrl.isBlank() || isLocalUrl(config.baseUrl) || isLegacyRailwayUrl(config.baseUrl))) {
                    serverConfigMigrated = true
                    updateServerConfig(
                        ServerConfig.DEFAULT_BASE_URL,
                        ServerConfig.DEFAULT_USERNAME,
                        ServerConfig.DEFAULT_PASSWORD,
                        ServerConfig.DEFAULT_GATEWAY_ID,
                        ServerConfig.DEFAULT_MASTER_PHONE
                    )
                    return@collectLatest
                }
                _serverConfig.value = config
                if (config.isValid()) {
                    if (!initialServerSync) {
                        initialServerSync = true
                        syncFromServer()
                    }
                    ensureAutoSyncLoop()
                } else {
                    stopAutoSyncLoop()
                }
            }
        }
    }

    fun selectRelay(relay: Relay?) {
        _selectedRelay.value = relay
    }

    fun setFilterGroup(group: String) {
        _filterGroup.value = group
    }

    fun updateServerConfig(baseUrl: String, username: String, password: String, gatewayId: String, masterPhone: String) {
        val normalized = if (baseUrl.endsWith("/")) baseUrl.dropLast(1) else baseUrl
        val config = ServerConfig(normalized, username, password, gatewayId, masterPhone)
        _serverConfig.value = config
        viewModelScope.launch {
            repository.saveServerConfig(config)
        }
        if (config.isValid()) {
            addNotification("Setari server salvate", "success")
        } else {
            addNotification("Completeaza URL, user si parola", "error")
        }
    }

    fun syncFromServer(showNotifications: Boolean = true) {
        val config = _serverConfig.value
        if (!config.isValid()) return
        viewModelScope.launch {
            performServerSync(config, showNotifications)
        }
    }

    fun syncCommands(showNotifications: Boolean = true) {
        val config = _serverConfig.value
        if (!config.isValid()) return
        viewModelScope.launch {
            try {
                val items = withContext(Dispatchers.IO) {
                    ServerApi.fetchCommands(config, "", 1000)
                }
                _commands.value = items.sortedByDescending { it.createdAt }
            } catch (_: Exception) {
                _commands.value = emptyList()
                if (showNotifications) {
                    addNotification("Nu am putut incarca coada de comenzi", "error")
                }
            }
        }
    }

    private suspend fun performServerSync(config: ServerConfig, showNotifications: Boolean) {
        if (isServerSyncInProgress) return
        isServerSyncInProgress = true
        try {
            val snapshot = withContext(Dispatchers.IO) {
                ServerApi.downloadSnapshot(config)
            }
            if (snapshot != null) {
                suppressScheduledUpload = true
                val mergedRelays = mergeRelays(_relays.value, snapshot.relays)
                saveRelays(mergedRelays)
                saveHistory(snapshot.history)
                saveEvents(snapshot.events)
                refreshLocationsState(snapshot.locations, mergedRelays)
                suppressScheduledUpload = false
                if (showNotifications) {
                    addNotification("Sincronizare server reusita", "success")
                }
                syncCommands(showNotifications)
            } else if (showNotifications) {
                addNotification("Sincronizare server esuata", "error")
            }
        } finally {
            suppressScheduledUpload = false
            isServerSyncInProgress = false
        }
    }

    private fun ensureAutoSyncLoop() {
        if (autoSyncJob?.isActive == true) return
        autoSyncJob = viewModelScope.launch {
            while (true) {
                delay(15_000)
                val config = _serverConfig.value
                if (!config.isValid()) continue
                performServerSync(config, showNotifications = false)
            }
        }
    }

    private fun stopAutoSyncLoop() {
        autoSyncJob?.cancel()
        autoSyncJob = null
    }

    fun pollCommandsOnce() {
        val config = _serverConfig.value
        if (!config.isValid()) return
        viewModelScope.launch {
            val commands = withContext(Dispatchers.IO) {
                ServerApi.fetchPendingCommands(config, 50)
            }
            if (commands.isEmpty()) return@launch
            commands.forEach { item ->
                processQueuedCommand(item)
            }
        }
    }

    fun uploadToServerNow() {
        val config = _serverConfig.value
        if (!config.isValid()) return
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                ServerApi.uploadSnapshot(config, ServerSnapshot(_relays.value, _history.value, _events.value, _locations.value))
            }
            if (ok) {
                addNotification("Upload server reusit", "success")
            } else {
                addNotification("Upload server esuat", "error")
            }
        }
    }

    fun addRelay(
        name: String,
        phoneNumber: String,
        password: String,
        location: String,
        queryStart: Int = 1,
        queryEnd: Int = MAX_RELAY_CHANNELS,
        setupOptions: RelaySetupOptions = RelaySetupOptions()
    ) {
        val enforcedPassword = "2005"
        val safeQueryStart = queryStart.coerceIn(1, MAX_RELAY_CHANNELS)
        val safeQueryEnd = queryEnd.coerceIn(1, MAX_RELAY_CHANNELS)
        val newRelay = Relay(
            id = System.currentTimeMillis(),
            name = name,
            phoneNumber = phoneNumber,
            password = enforcedPassword,
            location = location,
            users = (1..MAX_RELAY_CHANNELS).map { User(id = it) },
            lastSync = System.currentTimeMillis()
        )
        val updated = _relays.value + newRelay
        saveRelays(updated)
        addNotification("Releu \"$name\" adaugat cu succes", "success", newRelay)
        viewModelScope.launch {
            delay(1000)
            setupRelay(newRelay, safeQueryStart, safeQueryEnd, setupOptions)
        }
    }

    fun addLocation(name: String): Boolean {
        val normalized = normalizeLocationName(name)
        if (normalized == null) {
            addNotification("Numele locatiei este obligatoriu", "error")
            return false
        }
        if (_locations.value.any { it.equals(normalized, ignoreCase = true) }) {
            addNotification("Locatia exista deja", "error")
            return false
        }
        explicitLocations = (explicitLocations + normalized)
            .distinctBy { it.lowercase() }
            .sortedBy { it.lowercase() }
        refreshLocationsState(relays = _relays.value)
        scheduleUpload()
        addNotification("Locatie \"$normalized\" adaugata", "success")
        return true
    }

    fun renameLocation(oldName: String, newName: String): Boolean {
        val source = normalizeLocationLabel(oldName)
        val target = normalizeLocationName(newName)
        if (target == null) {
            addNotification("Numele nou al locatiei este obligatoriu", "error")
            return false
        }
        if (source.equals(target, ignoreCase = true)) {
            addNotification("Locatia are deja acest nume", "info")
            return false
        }
        if (_locations.value.any { it.equals(target, ignoreCase = true) }) {
            addNotification("Exista deja o locatie cu acest nume", "error")
            return false
        }
        val now = System.currentTimeMillis()
        val updatedRelays = _relays.value.map { relay ->
            val relayLocation = normalizeLocationLabel(relay.location)
            if (relayLocation.equals(source, ignoreCase = true)) {
                relay.copy(location = target, lastSync = now)
            } else {
                relay
            }
        }
        explicitLocations = explicitLocations
            .filterNot { it.equals(source, ignoreCase = true) }
            .plus(target)
            .distinctBy { it.lowercase() }
            .sortedBy { it.lowercase() }
        saveRelays(updatedRelays)
        refreshLocationsState(relays = updatedRelays)
        scheduleUpload()
        addNotification("Locatie redenumita in \"$target\"", "success")
        return true
    }

    fun deleteLocation(name: String): Boolean {
        val source = normalizeLocationLabel(name)
        val relaysToDelete = _relays.value.filter { relay ->
            normalizeLocationLabel(relay.location).equals(source, ignoreCase = true)
        }
        if (relaysToDelete.isNotEmpty()) {
            deleteRelaysAndAssociatedData(relaysToDelete)
        }
        explicitLocations = explicitLocations
            .filterNot { it.equals(source, ignoreCase = true) }
            .distinctBy { it.lowercase() }
            .sortedBy { it.lowercase() }
        refreshLocationsState(relays = _relays.value)
        scheduleUpload()
        addNotification(
            "Locatie \"$source\" stearsa" +
                if (relaysToDelete.isNotEmpty()) " impreuna cu ${relaysToDelete.size} relee si datele lor" else "",
            "info"
        )
        return true
    }

    fun updateRelayDetails(id: Long, name: String, phoneNumber: String, password: String, location: String) {
        val current = _relays.value.firstOrNull { it.id == id } ?: return
        val updatedRelay = current.copy(
            name = name,
            phoneNumber = phoneNumber,
            password = password,
            location = location,
            lastSync = System.currentTimeMillis()
        )
        val updatedRelays = _relays.value.map { if (it.id == id) updatedRelay else it }
        saveRelays(updatedRelays)
        if (_selectedRelay.value?.id == id) {
            _selectedRelay.value = updatedRelay
        }
        addNotification("Releu \"$name\" actualizat", "success", updatedRelay)
    }

    fun deleteRelay(id: Long) {
        val relay = _relays.value.firstOrNull { it.id == id } ?: return
        deleteRelaysAndAssociatedData(listOf(relay))
        addNotification("Releu \"${relay.name}\" sters", "info")
    }

    fun addUser(userId: Int, phone: String, name: String, group: String): Boolean {
        val relay = _selectedRelay.value ?: return false
        val slot = relay.users.firstOrNull { it.id == userId }
        if (slot != null && !slot.known) {
            addNotification("Pozitia $userId nu este verificata. Fa o interogare inainte.", "error", relay)
            return false
        }
        if (slot != null && slot.phone.isNotBlank()) {
            addNotification("Pozitia $userId este ocupata.", "error", relay)
            return false
        }
        val command = "${relay.password}A${userId.toString().padStart(3, '0')}#$phone#"
        val ok = sendSms(
            relay,
            command,
            "Adaugat utilizator ${name.ifBlank { phone }} ($group) la pozitia $userId",
            scheduleSmsSync = true
        ) {
            val updatedUsers = relay.users.map {
                if (it.id == userId) it.copy(
                    phone = phone,
                    name = name,
                    group = group,
                    addedDate = System.currentTimeMillis(),
                    known = true
                ) else it
            }
            updateRelay(relay.copy(users = updatedUsers, lastSync = System.currentTimeMillis()))
        }
        return ok
    }

    fun deleteUser(userId: Int) {
        val relay = _selectedRelay.value ?: return
        val command = "${relay.password}A${userId.toString().padStart(3, '0')}##"
        sendSms(relay, command, "Sters utilizator de la pozitia $userId", scheduleSmsSync = true) {
            val updatedUsers = relay.users.map {
                if (it.id == userId) it.copy(phone = "", name = "", group = "general", addedDate = null) else it
            }
            updateRelay(relay.copy(users = updatedUsers, lastSync = System.currentTimeMillis()))
        }
    }

    fun addUserToRelays(
        relayIds: List<Long>,
        phone: String,
        name: String,
        group: String
    ): Boolean {
        if (relayIds.isEmpty()) return false
        val targets = _relays.value.filter { relayIds.contains(it.id) }
        if (targets.isEmpty()) return false

        var successCount = 0
        val errors = mutableListOf<String>()

        targets.forEach { relay ->
            val slot = relay.users
                .sortedBy { it.id }
                .firstOrNull { it.known && it.phone.isBlank() }
                ?: relay.users
                    .sortedBy { it.id }
                    .firstOrNull { it.phone.isBlank() }
            if (slot == null) {
                errors += "${relay.name}: nu are canal liber in intervalul 1-999"
                return@forEach
            }
            val userId = slot.id

            val command = "${relay.password}A${userId.toString().padStart(3, '0')}#$phone#"
            val ok = sendSms(
                relay,
                command,
                "Adaugat utilizator ${name.ifBlank { phone }} ($group) la pozitia $userId",
                scheduleSmsSync = true
            ) {
                val updatedUsers = relay.users.map {
                    if (it.id == userId) it.copy(
                        phone = phone,
                        name = name,
                        group = group,
                        addedDate = System.currentTimeMillis(),
                        known = true
                    ) else it
                }
                val updatedRelay = relay.copy(users = updatedUsers, lastSync = System.currentTimeMillis())
                val updatedRelays = _relays.value.map { if (it.id == relay.id) updatedRelay else it }
                saveRelays(updatedRelays)
                if (_selectedRelay.value?.id == relay.id) {
                    _selectedRelay.value = updatedRelay
                }
            }
            if (ok) {
                successCount++
            } else {
                errors += "${relay.name}: trimitere esuata"
            }
        }

        if (successCount > 0) {
            addNotification(
                "Utilizator adaugat pe $successCount relee" +
                    if (errors.isNotEmpty()) " (${errors.size} erori)" else "",
                if (errors.isEmpty()) "success" else "info"
            )
        } else {
            addNotification("Nu am putut adauga utilizatorul pe releele selectate", "error")
        }
        return successCount > 0
    }

    fun importCsv(csvText: String) {
        val relay = _selectedRelay.value ?: return
        val lines = csvText.trim().split("\n")
        if (lines.isEmpty()) return
        val headers = lines.first().lowercase().split(",").map { it.trim() }
        var importCount = 0
        val errors = mutableListOf<String>()
        var updatedRelay = relay
        for (i in 1 until lines.size) {
            val values = lines[i].split(",").map { it.trim() }
            val row = headers.mapIndexed { index, header ->
                header to values.getOrNull(index).orEmpty()
            }.toMap()
            val userId = (row["id"] ?: row["pozitie"])?.toIntOrNull()
            val phone = row["telefon"] ?: row["phone"] ?: row["numar"]
            val phoneValue = phone.orEmpty()
            val name = row["nume"] ?: row["name"] ?: ""
            val group = row["grup"] ?: row["group"] ?: "general"
            if (userId == null || phoneValue.isBlank()) {
                errors.add("Linia ${i + 1}: ID sau telefon lipsa")
                continue
            }
            if (userId !in 1..MAX_RELAY_CHANNELS) {
                errors.add("Linia ${i + 1}: ID invalid ($userId)")
                continue
            }
            val slot = updatedRelay.users.firstOrNull { it.id == userId }
            if (slot != null && !slot.known) {
                errors.add("Linia ${i + 1}: Pozitia $userId nu este verificata (interogheaza)")
                continue
            }
            if (slot != null && slot.phone.isNotBlank()) {
                errors.add("Linia ${i + 1}: Pozitia $userId este ocupata")
                continue
            }
            val command = "${relay.password}A${userId.toString().padStart(3, '0')}#$phoneValue#"
            sendSms(relay, command, "Import CSV: ${name.ifBlank { phoneValue }} la pozitia $userId", scheduleSmsSync = true) {
                // no-op; optimistic update below
            }
            updatedRelay = updatedRelay.copy(
                users = updatedRelay.users.map {
                    if (it.id == userId) it.copy(
                        phone = phoneValue,
                        name = name,
                        group = group,
                        addedDate = System.currentTimeMillis(),
                        known = true
                    ) else it
                },
                lastSync = System.currentTimeMillis()
            )
            importCount++
        }
        updateRelay(updatedRelay)
        if (errors.isNotEmpty()) {
            addNotification("Import finalizat cu $importCount utilizatori. Erori: ${errors.joinToString("; ")}", "error", relay)
        } else {
            addNotification("$importCount utilizatori importati cu succes", "success", relay)
        }
    }

    fun exportCsv(): String {
        val relay = _selectedRelay.value ?: return ""
        val users = relay.users.filter { it.phone.isNotBlank() }
        val header = "ID,Telefon,Nume,Grup,Data_Adaugare"
        val body = users.joinToString("\n") { user ->
            val date = user.addedDate?.let { java.text.SimpleDateFormat("dd.MM.yyyy").format(it) } ?: ""
            "${user.id},${user.phone},${user.name},${user.group},$date"
        }
        return "$header\n$body"
    }

    fun exportEventsCsv(): String {
        val header = "Timp,Releu,Telefon Releu,Operat de,Mesaj"
        val body = _events.value.joinToString("\n") { ev ->
            val time = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(ev.timestamp))
            "${time},${ev.relayName},${ev.relayPhone},${ev.operatorPhone},${ev.message}"
        }
        return "$header\n$body"
    }

    fun markNotificationsRead() {
        val updated = _notifications.value.map { it.copy(read = true) }
        saveNotifications(updated)
    }

    fun addNotification(message: String, type: String = "info", relay: Relay? = null) {
        val updated = listOf(
            AppNotification(
                id = System.currentTimeMillis(),
                message = message,
                type = type,
                timestamp = System.currentTimeMillis(),
                read = false,
                relayPhone = relay?.phoneNumber.orEmpty(),
                relayName = relay?.name.orEmpty()
            )
        ) + _notifications.value
        saveNotifications(updated.take(50))
    }

    fun queryUsers(start: Int, end: Int) {
        val relay = _selectedRelay.value ?: return
        val safeStart = start.coerceIn(1, MAX_RELAY_CHANNELS)
        val safeEnd = end.coerceIn(1, MAX_RELAY_CHANNELS)
        if (safeStart > safeEnd) {
            addNotification("Interval invalid pentru interogare", "error", relay)
            return
        }
        val command = "${relay.password}AL${safeStart.toString().padStart(3, '0')}#${safeEnd.toString().padStart(3, '0')}#"
        sendSms(relay, command, "Interogare utilizatori $safeStart-$safeEnd", scheduleSmsSync = true) {}
    }

    fun syncFromInbox() {
        if (!BuildConfig.IS_GATEWAY) {
            addNotification("Sync SMS disponibil doar pe Gateway", "error")
            return
        }
        val relay = _selectedRelay.value ?: return
        viewModelScope.launch {
            syncFromInboxForRelayBlocking(relay)
        }
    }

    fun requestScrapeEvents(relay: Relay, startMillis: Long, endMillis: Long) {
        if (startMillis <= 0 || endMillis <= 0 || startMillis > endMillis) {
            addNotification("Interval invalid pentru scraping", "error", relay)
            return
        }
        if (BuildConfig.IS_GATEWAY) {
            scrapeRelayEventsLocal(relay, startMillis, endMillis)
        } else {
            val config = _serverConfig.value
            if (!config.isValid()) {
                addNotification("Setari server incomplete", "error", relay)
                return
            }
            if (config.gatewayId.isBlank()) {
                addNotification("Gateway ID lipsa", "error", relay)
                return
            }
            viewModelScope.launch {
                val cmd = "SCRAPE_EVENTS|$startMillis|$endMillis"
                val result = withContext(Dispatchers.IO) {
                    ServerApi.createCommand(config, relay.phoneNumber, cmd, "Scrape events", "android")
                }
                if (result.ok) {
                    addNotification("Cerere scraping trimisa catre gateway", "success", relay)
                } else {
                    addNotification(
                        if (result.statusCode == 404) "Serverul nu are /api/commands" else "Cerere respinsa",
                        "error",
                        relay
                    )
                }
            }
        }
    }

    private fun scrapeRelayEventsLocal(relay: Relay, startMillis: Long, endMillis: Long) {
        val context = getApplication<Application>().applicationContext
        viewModelScope.launch(Dispatchers.IO) {
            val merged = EventScraper.scrapeRelayEvents(context, relay, startMillis, endMillis, _events.value)
            if (merged == _events.value) {
                addNotification("Nu au fost gasite evenimente", "info", relay)
                return@launch
            }
            val added = merged.size - _events.value.size
            saveEvents(merged)
            addNotification("Evenimente gasite: $added", "success", relay)
        }
    }

    private fun isSetupUsersQueryCommand(command: String): Boolean {
        return Regex("^\\d{4}AL\\d{3}#\\d{3}#$").matches(command.trim().uppercase())
    }

    private fun extractQueryEndMarker(command: String): String? {
        val match = Regex("^\\d{4}AL\\d{3}#(\\d{3})#$").find(command.trim().uppercase()) ?: return null
        return match.groupValues.getOrNull(1)
    }

    private fun isSetupStepWithSmsReply(command: String): Boolean {
        val upper = command.trim().uppercase()
        return Regex("^\\d{4}T\\d{10}$").matches(upper) ||
            Regex("^\\d{4}A001#.+#$").matches(upper) ||
            Regex("^\\d{4}GON10#.*#$").matches(upper) ||
            Regex("^\\d{4}GOFF##$").matches(upper)
    }

    private suspend fun waitForSetupStepReplyBlocking(
        command: String,
        relayPhone: String,
        sinceMs: Long,
        timeoutMs: Long
    ): Boolean {
        val marker = expectedSetupReplyMarker(command)
        return if (marker.isNullOrBlank()) {
            waitForAnyRelayReplyBlocking(relayPhone, sinceMs, timeoutMs)
        } else {
            waitForRelaySmsMarkerBlocking(relayPhone, sinceMs, marker, timeoutMs)
        }
    }

    private fun expectedSetupReplyMarker(command: String): String? {
        val upper = command.trim().uppercase()
        return when {
            Regex("^\\d{4}T\\d{10}$").matches(upper) -> "SET TIME OK"
            Regex("^\\d{4}A001#.+#$").matches(upper) -> "001:"
            Regex("^\\d{4}GON10#.*#$").matches(upper) -> "RELAY ON WILL RETURN SMS"
            Regex("^\\d{4}GOFF##$").matches(upper) -> "RELAY OFF WILL NOT RETURN SMS"
            else -> null
        }
    }

    private suspend fun waitForSetupUsersQueryCompletionBlocking(
        relay: Relay,
        sentAt: Long,
        queryEndMarker: String
    ): Boolean {
        val deadline = System.currentTimeMillis() + 240_000L
        while (System.currentTimeMillis() < deadline) {
            if (hasRelaySmsMarkerSince(relay.phoneNumber, sentAt, "$queryEndMarker:")) {
                return true
            }
            syncFromInboxForRelayBlocking(relay)
            delay(5_000L)
        }
        return false
    }

    private suspend fun waitForAnyRelayReplyBlocking(relayPhone: String, sinceMs: Long, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (hasAnyRelaySmsSince(relayPhone, sinceMs)) {
                return true
            }
            delay(2_000L)
        }
        return false
    }

    private suspend fun waitForRelaySmsMarkerBlocking(
        relayPhone: String,
        sinceMs: Long,
        marker: String,
        timeoutMs: Long
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (hasRelaySmsMarkerSince(relayPhone, sinceMs, marker)) {
                return true
            }
            delay(2_000L)
        }
        return false
    }

    private fun hasAnyRelaySmsSince(relayPhone: String, sinceMs: Long): Boolean {
        val digits = relayPhone.filter { it.isDigit() }.takeLast(8)
        if (digits.isBlank()) return false
        val uri = Uri.parse("content://sms")
        val projection = arrayOf("date")
        val selection = "address LIKE ? AND date >= ?"
        val selectionArgs = arrayOf("%$digits%", sinceMs.toString())
        getApplication<Application>().contentResolver.query(uri, projection, selection, selectionArgs, "date DESC")?.use { cursor ->
            return cursor.moveToFirst()
        }
        return false
    }

    private fun hasRelaySmsMarkerSince(relayPhone: String, sinceMs: Long, marker: String): Boolean {
        val digits = relayPhone.filter { it.isDigit() }.takeLast(8)
        if (digits.isBlank()) return false
        val uri = Uri.parse("content://sms")
        val projection = arrayOf("body", "date")
        val selection = "address LIKE ? AND date >= ?"
        val selectionArgs = arrayOf("%$digits%", sinceMs.toString())
        getApplication<Application>().contentResolver.query(uri, projection, selection, selectionArgs, "date DESC")?.use { cursor ->
            val bodyIdx = cursor.getColumnIndex("body")
            while (cursor.moveToNext()) {
                val body = cursor.getString(bodyIdx).orEmpty()
                if (body.contains(marker, ignoreCase = true)) {
                    return true
                }
            }
        }
        return false
    }

    private suspend fun syncFromInboxForRelayBlocking(relay: Relay) {
        val context = getApplication<Application>().applicationContext
        val digits = relay.phoneNumber.filter { it.isDigit() }.takeLast(8)
        if (digits.isBlank()) return
        val uri = Uri.parse("content://sms")
        val projection = arrayOf("address", "body", "date")
        val selection = "address LIKE ?"
        val selectionArgs = arrayOf("%$digits%")
        val sortOrder = "date DESC"
        val parsedUsers = mutableMapOf<Int, String>()
        var lastSender = ""
        var lastBody = ""
        var parsedCount = 0
        context.contentResolver.query(uri, projection, selection, selectionArgs, sortOrder)?.use { cursor ->
            val bodyIdx = cursor.getColumnIndex("body")
            val addrIdx = cursor.getColumnIndex("address")
            while (cursor.moveToNext()) {
                val body = cursor.getString(bodyIdx).orEmpty()
                lastBody = body
                lastSender = if (addrIdx >= 0) cursor.getString(addrIdx).orEmpty() else ""
                parseUserLines(body).forEach { (id, phone) ->
                    if (!parsedUsers.containsKey(id)) {
                        parsedUsers[id] = phone
                    }
                }
            }
        }
        parsedCount = parsedUsers.size
        val samplePairs = parsedUsers.entries.take(3).joinToString { "${it.key}:${it.value}" }
        val bodyPreview = if (lastBody.length > 120) lastBody.take(120) + "..." else lastBody
        val debugMsg = "SMS debug: sender=$lastSender parsed=$parsedCount sample=$samplePairs body=$bodyPreview"
        if (parsedUsers.isNotEmpty()) {
            val updatedUsers = relay.users.map { user ->
                parsedUsers[user.id]?.let { phone ->
                    user.copy(
                        phone = phone,
                        known = true
                    )
                } ?: user
            }
            updateRelay(relay.copy(users = updatedUsers, lastSync = System.currentTimeMillis()))
            confirmLatestHistoryFromRelay(relay)
            addNotification("Sincronizare SMS: ${parsedUsers.size} pozitii", "success", relay)
            addNotification(debugMsg, "info", relay)
        } else {
            addNotification(debugMsg, "error", relay)
        }
    }

    private fun syncFromInboxForRelay(relay: Relay) {
        viewModelScope.launch {
            syncFromInboxForRelayBlocking(relay)
        }
    }

    private fun parseUserLines(message: String): Map<Int, String> {
        val regex = Regex("\\b(\\d{3})\\s*:\\s*([A-Za-z0-9]+)", RegexOption.IGNORE_CASE)
        val result = mutableMapOf<Int, String>()
        regex.findAll(message).forEach { match ->
            val id = match.groupValues[1].toInt()
            if (id < 0 || id > 999) return@forEach
            val value = match.groupValues[2].trim().trimEnd('.', ',', ';', ')')
            val token = if (value.equals("Empty", ignoreCase = true)) "" else value.filter { it.isLetterOrDigit() }
            result[id] = token
        }
        return result
    }


    private fun confirmLatestHistoryFromRelay(relay: Relay) {
        val relayDigits = relay.phoneNumber.filter { it.isDigit() }.takeLast(8)
        val idx = _history.value.indexOfFirst {
            it.status == "trimis" && it.relayPhone.filter { ch -> ch.isDigit() }.takeLast(8) == relayDigits
        }
        if (idx == -1) return
        val updated = _history.value.mapIndexed { index, item ->
            if (index == idx) item.copy(status = "confirmat") else item
        }
        saveHistory(updated)
    }

    fun changeRelayPassword(newPassword: String) {
        val relay = _selectedRelay.value ?: return
        val command = "${relay.password}P$newPassword"
        sendSms(relay, command, "Schimbare parola releu") {
            updateRelay(relay.copy(password = newPassword, lastSync = System.currentTimeMillis()))
        }
    }

    fun setRelayTimer(seconds: Int) {
        val relay = _selectedRelay.value ?: return
        val command = "${relay.password}GOT${seconds}#"
        sendSms(relay, command, "Setare temporizare $seconds sec") {}
    }

    fun allowAllAccess() {
        val relay = _selectedRelay.value ?: return
        val command = "${relay.password}ALL#"
        sendSms(relay, command, "Acces permis tuturor") {}
    }

    fun allowAuthorizedOnly() {
        val relay = _selectedRelay.value ?: return
        val command = "${relay.password}AUT#"
        sendSms(relay, command, "Acces permis doar autorizatilor") {}
    }

    private fun sendSms(
        relay: Relay,
        command: String,
        description: String,
        scheduleSmsSync: Boolean = false,
        onSuccess: () -> Unit
    ): Boolean {
        val context = getApplication<Application>().applicationContext
        val ok = if (BuildConfig.IS_GATEWAY) {
            SmsSender.sendSms(context, relay.phoneNumber, command)
        } else {
            val config = _serverConfig.value
            if (!config.isValid()) {
                addNotification("Setari server incomplete", "error", relay)
                false
            } else if (config.gatewayId.isBlank()) {
                addNotification("Gateway ID lipsa", "error", relay)
                false
            } else {
                val result = runBlocking {
                    withContext(Dispatchers.IO) {
                        ServerApi.createCommand(config, relay.phoneNumber, command, description, "android")
                    }
                }
                if (!result.ok) {
                    addNotification(
                        if (result.statusCode == 404) "Serverul nu are /api/commands" else "Comanda respinsa",
                        "error",
                        relay
                    )
                }
                result.ok
            }
        }
        val status = if (ok) "trimis" else "eroare"
        if (ok) {
            val pendingId = "${relay.phoneNumber}-${System.currentTimeMillis()}"
            _pendingCommands.value = _pendingCommands.value + pendingId
            viewModelScope.launch {
                delay(2000)
                _pendingCommands.value = _pendingCommands.value.filterNot { it == pendingId }
            }
        }
        val updatedHistory = listOf(
            CommandHistory(
                id = System.currentTimeMillis(),
                relayName = relay.name,
                relayPhone = relay.phoneNumber,
                command = command,
                description = description,
                timestamp = System.currentTimeMillis(),
                status = status
            )
        ) + _history.value
        saveHistory(updatedHistory.take(200))
        if (ok) {
            onSuccess()
            if (scheduleSmsSync && BuildConfig.IS_GATEWAY) {
                scheduleSmsSync(relay)
            }
        } else {
            if (BuildConfig.IS_GATEWAY) {
                addNotification("Nu exista permisiune pentru trimiterea SMS", "error", relay)
            }
        }
        return ok
    }

    private fun scheduleSmsSync(relay: Relay) {
        if (!BuildConfig.IS_GATEWAY) return
        viewModelScope.launch {
            delay(15_000)
            syncFromInboxForRelayBlocking(relay)
            delay(15_000)
            syncFromInboxForRelayBlocking(relay)
            delay(30_000)
            syncFromInboxForRelayBlocking(relay)
        }
    }

    private fun processQueuedCommand(item: CommandQueueItem) {
        val config = _serverConfig.value
        if (!config.isValid()) return
        val context = getApplication<Application>().applicationContext
        val targetRelay = _relays.value.firstOrNull { sameRelayNumber(it.phoneNumber, item.relayPhone) }
        val relayName = targetRelay?.name ?: "Relay ${item.relayPhone}"
        if (item.command.startsWith("SCRAPE_EVENTS|", ignoreCase = true) && BuildConfig.IS_GATEWAY) {
            val parts = item.command.split("|")
            val start = parts.getOrNull(1)?.toLongOrNull()
            val end = parts.getOrNull(2)?.toLongOrNull()
            if (targetRelay != null && start != null && end != null && start > 0 && end > 0 && start <= end) {
                viewModelScope.launch(Dispatchers.IO) {
                    val merged = EventScraper.scrapeRelayEvents(context, targetRelay, start, end, _events.value)
                    val added = merged.size - _events.value.size
                    saveEvents(merged)
                    ServerApi.updateCommandStatus(config, item.id, "done", "events=$added")
                }
            } else {
                viewModelScope.launch(Dispatchers.IO) {
                    ServerApi.updateCommandStatus(config, item.id, "failed", "invalid_relay_or_range")
                }
            }
            return
        }
        if (item.command.equals("SYNC_SMS", ignoreCase = true) && BuildConfig.IS_GATEWAY) {
            viewModelScope.launch(Dispatchers.IO) {
                targetRelay?.let { syncFromInboxForRelayBlocking(it) }
                ServerApi.updateCommandStatus(config, item.id, "done", "manual_sms_sync_done")
            }
            return
        }
        val ok = SmsSender.sendSms(context, item.relayPhone, item.command)
        val status = if (ok) "sent_waiting" else "failed"

        val pendingId = "${item.relayPhone}-${System.currentTimeMillis()}"
        if (ok) {
            _pendingCommands.value = _pendingCommands.value + pendingId
            viewModelScope.launch {
                delay(2000)
                _pendingCommands.value = _pendingCommands.value.filterNot { it == pendingId }
            }
        }

        val updatedHistory = listOf(
            CommandHistory(
                id = System.currentTimeMillis(),
                relayName = relayName,
                relayPhone = item.relayPhone,
                command = item.command,
                description = item.description.ifBlank { "Comanda din desktop" },
                timestamp = System.currentTimeMillis(),
                status = if (ok) "trimis" else "eroare"
            )
        ) + _history.value
        saveHistory(updatedHistory.take(200))

        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                ServerApi.updateCommandStatus(config, item.id, status)
            }
        }

        if (!ok) {
            addNotification("Eroare SMS: ${item.relayPhone}", "error", targetRelay)
        }
    }

    private fun sameRelayNumber(a: String, b: String): Boolean {
        val aDigits = a.filter { it.isDigit() }.takeLast(8)
        val bDigits = b.filter { it.isDigit() }.takeLast(8)
        return aDigits.isNotBlank() && aDigits == bDigits
    }

    private fun mergeRelays(local: List<Relay>, remote: List<Relay>): List<Relay> {
        if (local.isEmpty()) return remote
        if (remote.isEmpty()) return local

        val merged = mutableListOf<Relay>()

        remote.forEach { remoteRelay ->
            val localIndex = local.indexOfFirst { sameRelayNumber(it.phoneNumber, remoteRelay.phoneNumber) }
            if (localIndex >= 0) {
                val localRelay = local[localIndex]
                val preferred = if ((localRelay.lastSync ?: 0L) > (remoteRelay.lastSync ?: 0L)) localRelay else remoteRelay
                merged += preferred.copy(
                    id = localRelay.id,
                    users = preferred.users.ifEmpty { localRelay.users }
                )
            } else {
                merged += remoteRelay
            }
        }
        return merged
    }

    private fun setupRelay(relay: Relay, queryStart: Int, queryEnd: Int, options: RelaySetupOptions) {
        val setupPassword = "2005"
        val safeStart = queryStart.coerceIn(1, MAX_RELAY_CHANNELS)
        val safeEnd = queryEnd.coerceIn(1, MAX_RELAY_CHANNELS)
        val normalizedStart = minOf(safeStart, safeEnd)
        val normalizedEnd = maxOf(safeStart, safeEnd)
        val timeStamp = SimpleDateFormat("ddMMyyHHmm", Locale.getDefault()).format(Date())
        val cmdForcePassword = "1234P2005"
        val cmdTime = "${setupPassword}T$timeStamp"
        val masterPhone = _serverConfig.value.masterPhone.trim()
        val cmdConfirmOn = "${setupPassword}GON10#RIDICARE/DESCHIDERE#"
        val cmdConfirmOff = "${setupPassword}GOFF##"
        val cmdQuery = "${setupPassword}AL${normalizedStart.toString().padStart(3, '0')}#${normalizedEnd.toString().padStart(3, '0')}#"

        viewModelScope.launch {
            val setupSteps = mutableListOf<Pair<String, String>>()
            if (options.forcePasswordReset) {
                setupSteps += cmdForcePassword to "Setare parola standard 2005"
            }
            if (options.setDateTime) {
                setupSteps += cmdTime to "Setare data/ora $timeStamp"
            }
            if (options.setMaster && masterPhone.isNotBlank()) {
                setupSteps += "${setupPassword}A001#$masterPhone#" to "Setare master 001"
            }
            if (options.setConfirmOn) {
                setupSteps += cmdConfirmOn to "Setare confirmare deschidere"
            }
            if (options.setConfirmOff) {
                setupSteps += cmdConfirmOff to "Anulare confirmare inchidere"
            }
            if (options.queryUsers) {
                setupSteps += cmdQuery to "Interogare utilizatori $normalizedStart-$normalizedEnd"
            }

            for ((command, description) in setupSteps) {
                val sentAt = System.currentTimeMillis()
                val ok = sendSms(relay, command, description) {}
                if (!ok) continue

                if (!BuildConfig.IS_GATEWAY) {
                    continue
                }

                when {
                    command.equals("1234P2005", ignoreCase = true) -> {
                        delay(15_000)
                    }
                    isSetupUsersQueryCommand(command) -> {
                        val queryEndMarker = extractQueryEndMarker(command)
                        if (queryEndMarker == null) {
                            continue
                        }
                        val setupConfirmed = waitForSetupUsersQueryCompletionBlocking(relay, sentAt, queryEndMarker)
                        if (setupConfirmed) {
                            syncFromInboxForRelayBlocking(relay)
                            waitForStableUserSync(relay.id)
                            if (options.autoAddAdmins) {
                                autoRegisterDefaultAdmins(relay.id, setupPassword)
                            }
                        }
                    }
                    isSetupStepWithSmsReply(command) -> {
                        waitForSetupStepReplyBlocking(command, relay.phoneNumber, sentAt, 20_000L)
                        syncFromInboxForRelayBlocking(relay)
                    }
                }
            }
            if (options.autoAddAdmins && !options.queryUsers) {
                addNotification(
                    "Auto-adaugarea adminilor necesita interogare utilizatori activa la onboarding",
                    "info",
                    relay
                )
            }
        }
    }

    private suspend fun waitForStableUserSync(relayId: Long) {
        var previousSignature: String? = null
        for (attempt in 1..4) {
            val currentRelay = _relays.value.firstOrNull { it.id == relayId } ?: return
            syncFromInboxForRelayBlocking(currentRelay)
            val refreshedRelay = _relays.value.firstOrNull { it.id == relayId } ?: currentRelay
            val currentSignature = buildRelayUsersSignature(refreshedRelay)
            if (previousSignature != null && previousSignature == currentSignature) {
                return
            }
            previousSignature = currentSignature
            delay(20_000)
        }
    }

    private fun buildRelayUsersSignature(relay: Relay): String {
        return relay.users
            .sortedBy { it.id }
            .joinToString("|") {
                val phone = it.phone.filter(Char::isDigit).takeLast(10)
                "${it.id}:${it.known}:$phone"
            }
    }

    private suspend fun autoRegisterDefaultAdmins(relayId: Long, relayPassword: String) {
        val relay = _relays.value.firstOrNull { it.id == relayId } ?: return
        val existingPhones = relay.users
            .map { it.phone.filter(Char::isDigit).takeLast(10) }
            .filter { it.isNotBlank() }
            .toMutableSet()

        var updatedRelay = relay
        var addedCount = 0
        ADMIN_PHONES.forEach { adminPhone ->
            val normalized = adminPhone.filter(Char::isDigit).takeLast(10)
            if (normalized.isBlank() || existingPhones.contains(normalized)) {
                return@forEach
            }
            val freeSlot = updatedRelay.users
                .sortedBy { it.id }
                .firstOrNull { it.known && it.phone.isBlank() }
            if (freeSlot == null) {
                return@forEach
            }

            val cmd = "${relayPassword}A${freeSlot.id.toString().padStart(3, '0')}#$adminPhone#"
            val sentAt = System.currentTimeMillis()
            val ok = sendSms(
                updatedRelay,
                cmd,
                "Auto-adaugare administrator la pozitia ${freeSlot.id}"
            ) { }
            if (!ok) {
                return@forEach
            }

            updatedRelay = updatedRelay.copy(
                users = updatedRelay.users.map { user ->
                    if (user.id == freeSlot.id) {
                        user.copy(
                            phone = adminPhone,
                            name = "ADMIN",
                            group = "admin",
                            addedDate = System.currentTimeMillis(),
                            known = true
                        )
                    } else {
                        user
                    }
                },
                lastSync = System.currentTimeMillis()
            )
            existingPhones.add(normalized)
            addedCount++
            if (BuildConfig.IS_GATEWAY) {
                waitForAnyRelayReplyBlocking(updatedRelay.phoneNumber, sentAt, 20_000L)
                syncFromInboxForRelayBlocking(updatedRelay)
            }
        }

        if (addedCount > 0) {
            val latestRelay = _relays.value.firstOrNull { it.id == relayId } ?: updatedRelay
            if (BuildConfig.IS_GATEWAY) {
                syncFromInboxForRelayBlocking(latestRelay)
                waitForStableUserSync(relayId)
            } else {
                updateRelay(latestRelay)
            }
            val notifyRelay = _relays.value.firstOrNull { it.id == relayId } ?: latestRelay
            addNotification("Administratori adaugati automat: $addedCount", "success", notifyRelay)
        } else {
            addNotification("Nu exista administratori noi de adaugat automat", "info", updatedRelay)
        }
    }

    private fun updateRelay(updatedRelay: Relay) {
        val updatedRelays = _relays.value.map { if (it.id == updatedRelay.id) updatedRelay else it }
        saveRelays(updatedRelays)
        _selectedRelay.value = updatedRelay
    }

    private fun saveRelays(relays: List<Relay>) {
        val sanitized = relays.map { sanitizeRelay(it) }
        _relays.value = sanitized
        refreshLocationsState(relays = sanitized)
        viewModelScope.launch { repository.saveRelays(sanitized) }
        scheduleUpload()
    }

    private fun refreshLocationsState(remoteLocations: List<String>? = null, relays: List<Relay> = _relays.value) {
        if (remoteLocations != null) {
            explicitLocations = remoteLocations
                .mapNotNull { normalizeLocationName(it) }
                .distinctBy { it.lowercase() }
                .sortedBy { it.lowercase() }
        }
        val relayLocations = relays
            .mapNotNull { normalizeLocationName(it.location) }
        val all = (relayLocations + explicitLocations)
            .distinctBy { it.lowercase() }
            .sortedBy { it.lowercase() }
        _locations.value = all
    }

    private fun normalizeLocationName(location: String?): String? {
        val value = location?.trim().orEmpty()
        if (value.isBlank()) return null
        if (value.equals("Fara locatie", ignoreCase = true)) return null
        return value
    }

    private fun normalizeLocationLabel(location: String?): String {
        return location?.trim().orEmpty().ifBlank { "Fara locatie" }
    }

    private fun deleteRelaysAndAssociatedData(relaysToDelete: List<Relay>) {
        if (relaysToDelete.isEmpty()) return
        val relayIds = relaysToDelete.map { it.id }.toSet()
        val relayPhones = relaysToDelete.map { it.phoneNumber }
        val updatedRelays = _relays.value.filterNot { relayIds.contains(it.id) }
        val updatedHistory = _history.value.filterNot { item ->
            relayPhones.any { phone -> sameRelayPhone(item.relayPhone, phone) }
        }
        val updatedEvents = _events.value.filterNot { item ->
            relayPhones.any { phone -> sameRelayPhone(item.relayPhone, phone) }
        }
        val updatedNotifications = _notifications.value.filterNot { item ->
            relayPhones.any { phone -> sameRelayPhone(item.relayPhone, phone) }
        }
        saveRelays(updatedRelays)
        saveHistory(updatedHistory)
        saveEvents(updatedEvents)
        saveNotifications(updatedNotifications)
        if (_selectedRelay.value?.id in relayIds) {
            _selectedRelay.value = null
        }
    }

    private fun sameRelayPhone(a: String, b: String): Boolean {
        val aDigits = a.filter { it.isDigit() }.takeLast(8)
        val bDigits = b.filter { it.isDigit() }.takeLast(8)
        return aDigits.isNotBlank() && aDigits == bDigits
    }

    private fun saveHistory(history: List<CommandHistory>) {
        val sanitized = history.map { sanitizeHistoryItem(it) }
        _history.value = sanitized
        viewModelScope.launch { repository.saveHistory(sanitized) }
        scheduleUpload()
    }

    private fun saveEvents(events: List<RelayEvent>) {
        val sanitized = events.map { sanitizeEvent(it) }
        _events.value = sanitized
        viewModelScope.launch { repository.saveEvents(sanitized) }
        scheduleUpload()
    }

    private fun saveNotifications(notifications: List<AppNotification>) {
        val sanitized = notifications.map { sanitizeNotification(it) }
        _notifications.value = sanitized
        viewModelScope.launch { repository.saveNotifications(sanitized) }
    }

    private inline fun safeText(fallback: String = "", read: () -> String): String {
        return runCatching { read() }.getOrNull()?.trim().orEmpty().ifBlank { fallback }
    }

    private fun sanitizeUser(user: User): User {
        val id = runCatching { user.id }.getOrDefault(0).coerceIn(1, MAX_RELAY_CHANNELS)
        val phone = safeText { user.phone }
        val name = safeText { user.name }
        val group = safeText("general") { user.group }
        val known = runCatching { user.known }.getOrDefault(false) || phone.isNotBlank()
        val added = runCatching { user.addedDate }.getOrNull()
        return User(
            id = if (id == 0) 1 else id,
            phone = phone,
            name = name,
            group = group,
            addedDate = added,
            known = known
        )
    }

    private fun sanitizeRelay(relay: Relay): Relay {
        val id = runCatching { relay.id }.getOrDefault(System.currentTimeMillis())
        val usersRaw = runCatching { relay.users }.getOrDefault(emptyList())
        val usersById = usersRaw.map { sanitizeUser(it) }
            .groupBy { it.id }
            .mapValues { entry -> entry.value.last() }
        val fullUsers = (1..MAX_RELAY_CHANNELS).map { idx ->
            usersById[idx] ?: User(id = idx)
        }
        return Relay(
            id = id,
            name = safeText("Releu") { relay.name },
            phoneNumber = safeText { relay.phoneNumber },
            password = safeText("1234") { relay.password },
            location = safeText { relay.location },
            users = fullUsers,
            lastSync = runCatching { relay.lastSync }.getOrNull(),
            cloudBackup = runCatching { relay.cloudBackup }.getOrDefault(false)
        )
    }

    private fun sanitizeHistoryItem(item: CommandHistory): CommandHistory {
        return CommandHistory(
            id = runCatching { item.id }.getOrDefault(System.currentTimeMillis()),
            relayName = safeText("Releu") { item.relayName },
            relayPhone = safeText { item.relayPhone },
            command = safeText { item.command },
            description = safeText { item.description },
            timestamp = runCatching { item.timestamp }.getOrDefault(System.currentTimeMillis()),
            status = safeText("trimis") { item.status }
        )
    }

    private fun sanitizeEvent(item: RelayEvent): RelayEvent {
        return RelayEvent(
            id = runCatching { item.id }.getOrDefault(System.currentTimeMillis()),
            relayName = safeText("Releu") { item.relayName },
            relayPhone = safeText { item.relayPhone },
            operatorPhone = safeText { item.operatorPhone },
            message = safeText { item.message },
            timestamp = runCatching { item.timestamp }.getOrDefault(System.currentTimeMillis())
        )
    }

    private fun sanitizeNotification(item: AppNotification): AppNotification {
        return AppNotification(
            id = runCatching { item.id }.getOrDefault(System.currentTimeMillis()),
            message = safeText("Notificare") { item.message },
            type = safeText("info") { item.type },
            timestamp = runCatching { item.timestamp }.getOrDefault(System.currentTimeMillis()),
            read = runCatching { item.read }.getOrDefault(false),
            relayPhone = safeText { item.relayPhone },
            relayName = safeText { item.relayName }
        )
    }

    private fun scheduleUpload() {
        val config = _serverConfig.value
        if (!config.isValid()) return
        if (suppressScheduledUpload) return
        uploadJob?.cancel()
        uploadJob = viewModelScope.launch {
            delay(500)
            withContext(Dispatchers.IO) {
                ServerApi.uploadSnapshot(config, ServerSnapshot(_relays.value, _history.value, _events.value, _locations.value))
            }
        }
    }

    private fun isLocalUrl(url: String): Boolean {
        val clean = url.lowercase()
        if (clean.contains("localhost") || clean.contains("127.0.0.1")) return true
        val ipMatch = Regex("(\\d{1,3}\\.){3}\\d{1,3}").find(clean) ?: return false
        val ip = ipMatch.value.split(".").mapNotNull { it.toIntOrNull() }
        if (ip.size != 4) return false
        if (ip[0] == 10) return true
        if (ip[0] == 192 && ip[1] == 168) return true
        if (ip[0] == 172 && ip[1] in 16..31) return true
        return false
    }

    private fun isLegacyRailwayUrl(url: String): Boolean {
        return url.contains("gsm-relay-firebase-sync-production.up.railway.app", ignoreCase = true)
    }

    companion object {
        private const val MAX_RELAY_CHANNELS = 999

        private val ADMIN_PHONES = listOf(
            "0739850968",
            "0736927058",
            "0736428957",
            "0762359969",
            "0745281667",
            "0752207784",
            "0763481397",
            "0737859251",
            "0723167841",
            "0721263456",
            "0732399632",
            "0728336319",
            "0728260501",
            "0769956561",
            "0733262643"
        )
    }
}
