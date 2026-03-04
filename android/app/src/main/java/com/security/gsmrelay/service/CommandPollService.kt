package com.security.gsmrelay.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.security.gsmrelay.R
import com.security.gsmrelay.data.network.ServerApi
import com.security.gsmrelay.data.repository.AppRepository
import com.security.gsmrelay.data.network.ServerSnapshot
import com.security.gsmrelay.model.CommandHistory
import com.security.gsmrelay.model.RelayEvent
import com.security.gsmrelay.sms.EventScraper
import com.security.gsmrelay.sms.SmsSender
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel

class CommandPollService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollJob: Job? = null
    private val recentlySentCommands = mutableMapOf<String, Long>()
    private val processedSetupQueryCommands = mutableSetOf<String>()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())
        if (pollJob == null) {
            pollJob = scope.launch { pollLoop() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        pollJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private suspend fun pollLoop() {
        val repo = AppRepository(applicationContext)
        while (scope.isActive) {
            val config = repo.loadServerConfig()
            if (!config.isValid() || config.gatewayId.isBlank()) {
                delay(POLL_INTERVAL_MS)
                continue
            }

            recoverInFlightCommands(repo, config)

            val commands = ServerApi.fetchPendingCommands(config, 50)
            if (commands.isNotEmpty()) {
                val relays = repo.loadRelays()
                val relaysByKey = relays.associateBy { normalizePhone(it.phoneNumber) }
                val history = repo.loadHistory().toMutableList()
                var events = repo.loadEvents()
                val blockedRelayKeys = mutableSetOf<String>()

                for (item in commands) {
                    val relayKey = normalizePhone(item.relayPhone)
                    if (relayKey.isNotBlank() && blockedRelayKeys.contains(relayKey)) {
                        continue
                    }

                    if (wasSentRecently(item.id)) {
                        val statusFixed = updateCommandStatusWithRetry(
                            config = config,
                            commandId = item.id,
                            status = "sent_waiting",
                            responseText = "duplicate_guard_already_sent"
                        )
                        if (statusFixed && relayKey.isNotBlank()) {
                            blockedRelayKeys.add(relayKey)
                        }
                        continue
                    }

                    if (isSyncSmsCommand(item.command)) {
                        syncFromInboxForRelay(repo, item.relayPhone)
                        ServerApi.updateCommandStatus(config, item.id, "done", "manual_sms_sync_done")
                        continue
                    }

                    if (isScrapeCommand(item.command)) {
                        val relay = relaysByKey[normalizePhone(item.relayPhone)]
                        val parsed = parseScrapeCommand(item.command)
                        if (relay != null && parsed != null) {
                            val (start, end) = parsed
                            val mergedEvents = EventScraper.scrapeRelayEvents(
                                applicationContext,
                                relay,
                                start,
                                end,
                                events
                            )
                            val added = mergedEvents.size - events.size
                            repo.saveEvents(mergedEvents)
                            events = mergedEvents
                            ServerApi.updateCommandStatus(config, item.id, "done", "events=$added")
                        } else {
                            ServerApi.updateCommandStatus(config, item.id, "failed", "invalid_relay_or_range")
                        }
                        continue
                    }

                    val ok = SmsSender.sendSms(applicationContext, item.relayPhone, item.command)
                    if (ok) {
                        markSentNow(item.id)
                        updateCommandStatusWithRetry(config, item.id, "sent_waiting")
                    } else {
                        // Keep command pending so transient SMS gateway failures do not stop the flow.
                        updateCommandStatusWithRetry(
                            config,
                            item.id,
                            "pending",
                            "send_sms_failed_retry_poll"
                        )
                    }

                    val relay = relaysByKey[normalizePhone(item.relayPhone)]
                    history.add(
                        0,
                        CommandHistory(
                            id = System.currentTimeMillis(),
                            relayName = relay?.name ?: "Relay ${item.relayPhone}",
                            relayPhone = item.relayPhone,
                            command = item.command,
                            description = item.description.ifBlank { "Comanda din desktop" },
                            timestamp = System.currentTimeMillis(),
                            status = if (ok) "trimis" else "eroare"
                        )
                    )

                    if (ok) {
                        if (relayKey.isNotBlank()) {
                            // Keep one in-flight command per relay in this poll cycle.
                            blockedRelayKeys.add(relayKey)
                        }
                        val sentAt = System.currentTimeMillis()
                        scope.launch {
                            when {
                                isForcePasswordCommand(item.command) -> {
                                    delay(PASSWORD_STEP_TIMEOUT_MS)
                                    updateCommandStatusWithRetry(config, item.id, "done", "setup_step1_timeout_15s")
                                }
                                isSetupUsersQueryCommand(item.command) -> {
                                    val setupConfirmed = waitForSetupUsersQueryCompletion(repo, item.relayPhone, sentAt, item.command)
                                    if (setupConfirmed) {
                                        updateCommandStatusWithRetry(config, item.id, "done", "setup_query_999_confirmed")
                                        handleConfirmedSetupUsersQuery(repo, config, item, history)
                                    }
                                }
                                isSetupStepWithSmsReply(item.command) -> {
                                    val setupConfirmed = waitForSetupStepReply(item.command, item.relayPhone, sentAt)
                                    syncFromInboxForRelay(repo, item.relayPhone)
                                    if (setupConfirmed) {
                                        updateCommandStatusWithRetry(config, item.id, "done", "setup_marker_confirmed")
                                    }
                                }
                                okCommandNeedsSync(item.command) -> {
                                    scheduleSmsSync(repo, item.relayPhone)
                                }
                            }
                        }
                    }
                }

                repo.saveHistory(history.take(200))
                val latestRelays = repo.loadRelays()
                val latestEvents = repo.loadEvents()
                ServerApi.uploadSnapshot(config, ServerSnapshot(latestRelays, history.take(200), latestEvents))
            }

            delay(POLL_INTERVAL_MS)
        }
    }

    private suspend fun recoverInFlightCommands(
        repo: AppRepository,
        config: com.security.gsmrelay.data.model.ServerConfig
    ) {
        val now = System.currentTimeMillis()
        val sentWaiting = ServerApi.fetchCommands(config, "sent_waiting", 300)
        val sentLegacy = ServerApi.fetchCommands(config, "sent", 300).filter { item ->
            isSetupRecoveryCommand(item.command) &&
                (now - (if (item.updatedAt > 0) item.updatedAt else item.createdAt)) <= MAX_LEGACY_SENT_AGE_MS
        }
        val waiting = (sentWaiting + sentLegacy)
            .distinctBy { it.id }
            .sortedByDescending { if (it.updatedAt > 0) it.updatedAt else it.createdAt }
        if (waiting.isEmpty()) return

        val relayHandled = mutableSetOf<String>()

        for (item in waiting) {
            val relayKey = normalizePhone(item.relayPhone)
            if (relayKey.isBlank() || relayHandled.contains(relayKey)) {
                continue
            }
            relayHandled.add(relayKey)

            val sinceMs = when {
                isSetupUsersQueryCommand(item.command) -> item.createdAt
                item.updatedAt > 0 -> item.updatedAt
                else -> item.createdAt
            }.coerceAtLeast(0L)
            if (sinceMs <= 0L) continue

            val confirmed = when {
                isForcePasswordCommand(item.command) -> (now - sinceMs) >= PASSWORD_STEP_TIMEOUT_MS
                isSetupUsersQueryCommand(item.command) -> {
                    val queryEndMarker = extractSetupQueryEndMarker(item.command) ?: "999"
                    hasRelaySmsMarkerSince(item.relayPhone, sinceMs, "$queryEndMarker:")
                }
                isSetupStepWithSmsReply(item.command) -> {
                    val marker = expectedSetupReplyMarker(item.command)
                    if (marker.isNullOrBlank()) {
                        hasAnyRelaySmsSince(item.relayPhone, sinceMs)
                    } else {
                        hasRelaySmsMarkerSince(item.relayPhone, sinceMs, marker)
                    }
                }
                else -> hasAnyRelaySmsSince(item.relayPhone, sinceMs)
            }

            if (confirmed) {
                val reason = if (isForcePasswordCommand(item.command)) "recovered_timeout_step1" else "recovered_sms_confirmed"
                updateCommandStatusWithRetry(config, item.id, "done", reason)
                if (isSetupStepWithSmsReply(item.command) || isSetupUsersQueryCommand(item.command) || okCommandNeedsSync(item.command)) {
                    syncFromInboxForRelay(repo, item.relayPhone)
                }
                if (isSetupUsersQueryCommand(item.command)) {
                    handleConfirmedSetupUsersQuery(repo, config, item, history = null)
                }
                continue
            }

            val retryTimeoutMs = retryTimeoutForCommand(item.command)
            val waitMs = now - sinceMs
            if (waitMs < retryTimeoutMs) {
                continue
            }
            // No auto-resend from sent_waiting: wait for real SMS confirmation to avoid duplicates.
            updateCommandStatusWithRetry(
                config,
                item.id,
                "sent_waiting",
                "waiting_sms_confirmation; elapsed_ms=$waitMs"
            )
        }
    }

    private suspend fun updateCommandStatusWithRetry(
        config: com.security.gsmrelay.data.model.ServerConfig,
        commandId: String,
        status: String,
        responseText: String = ""
    ): Boolean {
        repeat(3) { attempt ->
            val ok = ServerApi.updateCommandStatus(config, commandId, status, responseText)
            if (ok) return true
            if (attempt < 2) {
                delay(1_500)
            }
        }
        return false
    }

    private suspend fun handleConfirmedSetupUsersQuery(
        repo: AppRepository,
        config: com.security.gsmrelay.data.model.ServerConfig,
        item: com.security.gsmrelay.data.network.CommandQueueItem,
        history: MutableList<CommandHistory>?
    ) {
        synchronized(processedSetupQueryCommands) {
            if (processedSetupQueryCommands.contains(item.id)) {
                return
            }
            processedSetupQueryCommands.add(item.id)
        }

        try {
            syncFromInboxForRelay(repo, item.relayPhone)
            waitForStableUserSync(repo, item.relayPhone)
            if (!shouldSkipAutoAdminForSetupQuery(item)) {
                val relayPassword = extractRelayPassword(item.command) ?: "2005"
                autoRegisterDefaultAdmins(
                    repo = repo,
                    config = config,
                    relayPhone = item.relayPhone,
                    relayPassword = relayPassword,
                    history = history ?: mutableListOf()
                )
            }
        } finally {
            // Keep only a small in-memory dedupe window; command IDs are UUIDs so growth is low,
            // but prune old entries opportunistically if the set gets large.
            synchronized(processedSetupQueryCommands) {
                if (processedSetupQueryCommands.size > 5000) {
                    processedSetupQueryCommands.clear()
                }
            }
        }
    }

    private fun wasSentRecently(commandId: String): Boolean {
        val now = System.currentTimeMillis()
        recentlySentCommands.entries.removeIf { now - it.value > RECENT_SENT_WINDOW_MS }
        val sentAt = recentlySentCommands[commandId] ?: return false
        return now - sentAt <= RECENT_SENT_WINDOW_MS
    }

    private fun markSentNow(commandId: String) {
        recentlySentCommands[commandId] = System.currentTimeMillis()
    }

    private fun retryTimeoutForCommand(command: String): Long {
        return when {
            isForcePasswordCommand(command) -> PASSWORD_STEP_TIMEOUT_MS
            isSetupUsersQueryCommand(command) -> SETUP_QUERY_RETRY_TIMEOUT_MS
            isSetupStepWithSmsReply(command) -> SETUP_STEP_RETRY_TIMEOUT_MS
            else -> GENERIC_COMMAND_RETRY_TIMEOUT_MS
        }
    }

    private fun isSetupRecoveryCommand(command: String): Boolean {
        val upper = command.trim().uppercase()
        return upper == "1234P2005" ||
            Regex("^\\d{4}T\\d{10}$").matches(upper) ||
            Regex("^\\d{4}A001#.+#$").matches(upper) ||
            Regex("^\\d{4}GON10#.*#$").matches(upper) ||
            Regex("^\\d{4}GOFF##$").matches(upper) ||
            Regex("^\\d{4}AL\\d{3}#\\d{3}#$").matches(upper)
    }

    private fun normalizePhone(phone: String): String {
        val digits = phone.filter { it.isDigit() }
        return if (digits.length <= 8) digits else digits.takeLast(8)
    }

    private fun isForcePasswordCommand(command: String): Boolean {
        return command.trim().equals("1234P2005", ignoreCase = true)
    }

    private fun isSetupStepWithSmsReply(command: String): Boolean {
        val upper = command.uppercase()
        return Regex("^\\d{4}T\\d{10}$").matches(upper) ||
            Regex("^\\d{4}A001#.+#$").matches(upper) ||
            Regex("^\\d{4}GON10#.*#$").matches(upper) ||
            Regex("^\\d{4}GOFF##$").matches(upper)
    }

    private suspend fun waitForSetupStepReply(command: String, relayPhone: String, sentAt: Long): Boolean {
        val marker = expectedSetupReplyMarker(command)
        return if (marker.isNullOrBlank()) {
            waitForAnyRelayReply(relayPhone, sentAt, SETUP_STEP_TIMEOUT_MS)
        } else {
            waitForRelaySmsMarker(relayPhone, sentAt, marker, SETUP_STEP_TIMEOUT_MS)
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

    private suspend fun waitForSetupUsersQueryCompletion(
        repo: AppRepository,
        relayPhone: String,
        sentAt: Long,
        command: String
    ): Boolean {
        val queryEndMarker = extractSetupQueryEndMarker(command) ?: "999"
        val deadline = System.currentTimeMillis() + SETUP_QUERY_TIMEOUT_MS
        while (scope.isActive && System.currentTimeMillis() < deadline) {
            if (hasRelaySmsMarkerSince(relayPhone, sentAt, "$queryEndMarker:")) {
                return true
            }
            syncFromInboxForRelay(repo, relayPhone)
            delay(QUERY_POLL_STEP_MS)
        }
        return false
    }

    private suspend fun waitForAnyRelayReply(relayPhone: String, sinceMs: Long, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (scope.isActive && System.currentTimeMillis() < deadline) {
            if (hasAnyRelaySmsSince(relayPhone, sinceMs)) {
                return true
            }
            delay(REPLY_POLL_STEP_MS)
        }
        return false
    }

    private suspend fun waitForRelaySmsMarker(relayPhone: String, sinceMs: Long, marker: String, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (scope.isActive && System.currentTimeMillis() < deadline) {
            if (hasRelaySmsMarkerSince(relayPhone, sinceMs, marker)) {
                return true
            }
            delay(REPLY_POLL_STEP_MS)
        }
        return false
    }

    private fun hasAnyRelaySmsSince(relayPhone: String, sinceMs: Long): Boolean {
        val digits = normalizePhone(relayPhone)
        if (digits.isBlank()) return false
        val uri = android.net.Uri.parse("content://sms")
        val projection = arrayOf("date")
        val selection = "address LIKE ? AND date >= ?"
        val args = arrayOf("%$digits%", sinceMs.toString())
        applicationContext.contentResolver.query(uri, projection, selection, args, "date DESC")?.use { cursor ->
            return cursor.moveToFirst()
        }
        return false
    }

    private fun hasRelaySmsMarkerSince(relayPhone: String, sinceMs: Long, marker: String): Boolean {
        val digits = normalizePhone(relayPhone)
        if (digits.isBlank()) return false
        val uri = android.net.Uri.parse("content://sms")
        val projection = arrayOf("body", "date")
        val selection = "address LIKE ? AND date >= ?"
        val args = arrayOf("%$digits%", sinceMs.toString())
        applicationContext.contentResolver.query(uri, projection, selection, args, "date DESC")?.use { cursor ->
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

    private fun okCommandNeedsSync(command: String): Boolean {
        val upper = command.uppercase()
        if (isScrapeCommand(upper)) return false
        if (isSetupUsersQueryCommand(upper)) return false
        val isQuery = Regex("AL\\d{3}#\\d{3}#").containsMatchIn(upper)
        val isAddDelete = Regex("A\\d{3}#").containsMatchIn(upper)
        return isQuery || isAddDelete
    }

    private fun scheduleSmsSync(repo: AppRepository, relayPhone: String) {
        scope.launch {
            delay(15_000)
            syncFromInboxForRelay(repo, relayPhone)
        }
    }

    private suspend fun syncFromInboxForRelay(repo: AppRepository, relayPhone: String) {
        val digits = normalizePhone(relayPhone)
        if (digits.isBlank()) return
        val uri = android.net.Uri.parse("content://sms")
        val projection = arrayOf("address", "body", "date")
        val selection = "address LIKE ?"
        val selectionArgs = arrayOf("%$digits%")
        val sortOrder = "date DESC"

        val parsedUsers = mutableMapOf<Int, String>()
        applicationContext.contentResolver.query(uri, projection, selection, selectionArgs, sortOrder)?.use { cursor ->
            val bodyIdx = cursor.getColumnIndex("body")
            while (cursor.moveToNext()) {
                val body = cursor.getString(bodyIdx).orEmpty()
                parseUserLines(body).forEach { (id, phone) ->
                    if (!parsedUsers.containsKey(id)) {
                        parsedUsers[id] = phone
                    }
                }
            }
        }
        if (parsedUsers.isEmpty()) return

        val relays = repo.loadRelays()
        val relay = relays.firstOrNull { sameRelayNumber(it.phoneNumber, relayPhone) } ?: return
        val updatedUsers = relay.users.map { user ->
            parsedUsers[user.id]?.let { phone ->
                user.copy(phone = phone, known = true)
            } ?: user
        }
        val updatedRelay = relay.copy(users = updatedUsers, lastSync = System.currentTimeMillis())
        val updatedRelays = relays.map { if (it.id == relay.id) updatedRelay else it }
        repo.saveRelays(updatedRelays)

        val history = repo.loadHistory()
        val updatedHistory = confirmLatestHistory(history, relayPhone)
        val events = repo.loadEvents()
        repo.saveHistory(updatedHistory.take(200))

        val config = repo.loadServerConfig()
        if (config.isValid()) {
            ServerApi.uploadSnapshot(config, ServerSnapshot(updatedRelays, updatedHistory.take(200), events))
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

    private fun sameRelayNumber(a: String, b: String): Boolean {
        val aDigits = normalizePhone(a)
        val bDigits = normalizePhone(b)
        return aDigits.isNotBlank() && aDigits == bDigits
    }

    private fun confirmLatestHistory(history: List<CommandHistory>, relayPhone: String): List<CommandHistory> {
        val relayDigits = normalizePhone(relayPhone)
        val idx = history.indexOfFirst {
            it.status == "trimis" && normalizePhone(it.relayPhone) == relayDigits
        }
        if (idx == -1) return history
        return history.mapIndexed { index, item ->
            if (index == idx) item.copy(status = "confirmat") else item
        }
    }

    private fun isScrapeCommand(command: String): Boolean {
        return command.startsWith("SCRAPE_EVENTS|", ignoreCase = true)
    }

    private fun isSyncSmsCommand(command: String): Boolean {
        return command.trim().equals("SYNC_SMS", ignoreCase = true)
    }

    private fun parseScrapeCommand(command: String): Pair<Long, Long>? {
        val parts = command.split("|")
        if (parts.size < 3) return null
        val start = parts[1].toLongOrNull() ?: return null
        val end = parts[2].toLongOrNull() ?: return null
        if (start <= 0 || end <= 0 || start > end) return null
        return start to end
    }

    private fun isSetupUsersQueryCommand(command: String): Boolean {
        val upper = command.uppercase()
        return Regex("^\\d{4}AL\\d{3}#\\d{3}#$").matches(upper)
    }

    private fun extractSetupQueryEndMarker(command: String): String? {
        val match = Regex("^\\d{4}AL\\d{3}#(\\d{3})#$").find(command.trim().uppercase()) ?: return null
        return match.groupValues.getOrNull(1)
    }

    private fun extractRelayPassword(command: String): String? {
        val match = Regex("^(\\d{4})").find(command.trim())
        return match?.groupValues?.getOrNull(1)
    }

    private suspend fun autoRegisterDefaultAdmins(
        repo: AppRepository,
        config: com.security.gsmrelay.data.model.ServerConfig,
        relayPhone: String,
        relayPassword: String,
        history: MutableList<CommandHistory>
    ): Int {
        val relays = repo.loadRelays().toMutableList()
        val relayIndex = relays.indexOfFirst { sameRelayNumber(it.phoneNumber, relayPhone) }
        if (relayIndex < 0) return 0

        var relay = relays[relayIndex]
        val existingPhones = relay.users
            .map { normalizeUserPhone(it.phone) }
            .filter { it.isNotBlank() }
            .toMutableSet()

        var addedCount = 0
        for (adminPhone in ADMIN_PHONES) {
            val normalizedAdmin = normalizeUserPhone(adminPhone)
            if (normalizedAdmin.isBlank() || existingPhones.contains(normalizedAdmin)) {
                continue
            }

            val freeSlot = relay.users
                .sortedBy { it.id }
                .firstOrNull { it.known && it.phone.isBlank() }
            if (freeSlot == null) {
                continue
            }

            val command = "${relayPassword}A${freeSlot.id.toString().padStart(3, '0')}#$adminPhone#"
            val queued = ServerApi.createCommand(
                config = config,
                relayPhone = relay.phoneNumber,
                command = command,
                description = "Auto-adaugare administrator (${freeSlot.id})",
                source = "gateway_auto_admin"
            )
            val ok = queued.ok
            history.add(
                0,
                CommandHistory(
                    id = System.currentTimeMillis(),
                    relayName = relay.name.ifBlank { "Relay ${relay.phoneNumber}" },
                    relayPhone = relay.phoneNumber,
                    command = command,
                    description = "Auto-adaugare administrator (${freeSlot.id})",
                    timestamp = System.currentTimeMillis(),
                    status = if (ok) "trimis" else "eroare"
                )
            )
            if (!ok) {
                continue
            }

            // Reserve sloturi in memoria locala pentru a evita duplicate in acelasi batch.
            relay = relay.copy(
                users = relay.users.map { user ->
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
            relays[relayIndex] = relay
            existingPhones.add(normalizedAdmin)
            addedCount++
        }

        return addedCount
    }

    private fun shouldSkipAutoAdminForSetupQuery(item: com.security.gsmrelay.data.network.CommandQueueItem): Boolean {
        val source = item.source.lowercase()
        return source == "gateway_auto_admin_followup" || source.contains("no_auto_admin")
    }

    private suspend fun waitForStableUserSync(repo: AppRepository, relayPhone: String) {
        var previousSignature: String? = null
        for (attempt in 1..4) {
            syncFromInboxForRelay(repo, relayPhone)
            val relays = repo.loadRelays()
            val relay = relays.firstOrNull { sameRelayNumber(it.phoneNumber, relayPhone) } ?: return
            val currentSignature = buildRelayUsersSignature(relay)
            if (previousSignature != null && previousSignature == currentSignature) {
                return
            }
            previousSignature = currentSignature
            delay(20_000)
        }
    }

    private fun buildRelayUsersSignature(relay: com.security.gsmrelay.model.Relay): String {
        return relay.users
            .sortedBy { it.id }
            .joinToString("|") {
                val phone = normalizeUserPhone(it.phone)
                "${it.id}:${it.known}:$phone"
            }
    }

    private fun normalizeUserPhone(phone: String): String {
        val digits = phone.filter { it.isDigit() }
        if (digits.isBlank()) return ""
        return if (digits.length <= 10) digits else digits.takeLast(10)
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("GSM Relay Gateway")
            .setContentText("Gateway activ - comenzi SMS in background")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "GSM Relay Gateway",
            NotificationManager.IMPORTANCE_LOW
        )
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "gsm_gateway"
        private const val NOTIFICATION_ID = 5174
        private const val POLL_INTERVAL_MS = 30_000L
        private const val PASSWORD_STEP_TIMEOUT_MS = 15_000L
        private const val SETUP_STEP_TIMEOUT_MS = 20_000L
        private const val SETUP_QUERY_TIMEOUT_MS = 240_000L
        private const val ADMIN_STEP_TIMEOUT_MS = 20_000L
        private const val REPLY_POLL_STEP_MS = 2_000L
        private const val QUERY_POLL_STEP_MS = 5_000L
        private const val SETUP_STEP_RETRY_TIMEOUT_MS = 30_000L
        private const val SETUP_QUERY_RETRY_TIMEOUT_MS = 90_000L
        private const val GENERIC_COMMAND_RETRY_TIMEOUT_MS = 45_000L
        private const val MAX_LEGACY_SENT_AGE_MS = 12 * 60 * 60 * 1000L
        private const val RECENT_SENT_WINDOW_MS = 20 * 60 * 1000L
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
