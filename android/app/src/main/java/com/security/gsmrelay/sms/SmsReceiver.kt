package com.security.gsmrelay.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.telephony.SmsMessage
import com.security.gsmrelay.data.network.ServerApi
import com.security.gsmrelay.data.network.ServerSnapshot
import com.security.gsmrelay.data.repository.AppRepository
import com.security.gsmrelay.model.AppNotification
import com.security.gsmrelay.model.CommandHistory
import com.security.gsmrelay.model.Relay
import com.security.gsmrelay.model.User
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val bundle: Bundle? = intent.extras
        if (bundle == null) return

        val pdus = bundle.get("pdus") as? Array<*> ?: return
        val format = bundle.getString("format")
        val messages = pdus.mapNotNull { pdu ->
            SmsMessage.createFromPdu(pdu as ByteArray, format)
        }

        val fullMessage = messages.joinToString(separator = "") { it.messageBody }
        val fromNumber = messages.firstOrNull()?.originatingAddress.orEmpty()

        if (fullMessage.isBlank()) return

        val repository = AppRepository(context)
        CoroutineScope(Dispatchers.IO).launch {
            val relays = repository.loadRelays()
            val relay = findRelayByNumber(relays, fromNumber)
            val parsedUsers = parseUserLines(fullMessage)
            val looksLikeSuccess = looksLikeSuccessMessage(fullMessage, parsedUsers)
            val config = repository.loadServerConfig()
            if (config.isValid() && looksLikeSuccess) {
                val ackPhone = relay?.phoneNumber?.takeIf { it.isNotBlank() } ?: fromNumber
                if (ackPhone.isNotBlank()) {
                    ServerApi.acknowledgeRelayWaitingCommand(
                        config,
                        ackPhone,
                        fullMessage.take(500)
                    )
                }
            }

            if (relay != null) {
                var relaysToSave = relays
                if (parsedUsers.isNotEmpty()) {
                    val updatedUsers = relay.users.map { user ->
                        parsedUsers[user.id]?.let { parsed ->
                            user.copy(
                                phone = parsed.phone,
                                known = true
                            )
                        } ?: user
                    }
                    val updatedRelay = relay.copy(
                        users = updatedUsers,
                        lastSync = System.currentTimeMillis()
                    )
                    relaysToSave = relays.map { if (it.id == relay.id) updatedRelay else it }
                    repository.saveRelays(relaysToSave)
                    val existingNotif = repository.loadNotifications()
                    val notif = AppNotification(
                        id = System.currentTimeMillis(),
                        message = "Interogare actualizata: ${parsedUsers.size} pozitii",
                        type = "success",
                        timestamp = System.currentTimeMillis(),
                        read = false
                    )
                    repository.saveNotifications((listOf(notif) + existingNotif).take(50))
                }

                val history = repository.loadHistory()
                val updatedHistory = confirmLatestHistory(history, relay, looksLikeSuccess)
                repository.saveHistory(updatedHistory)

                if (config.isValid()) {
                    val events = repository.loadEvents()
                    val snapshot = ServerSnapshot(relaysToSave, updatedHistory, events)
                    ServerApi.uploadSnapshot(config, snapshot)
                }
            }

            val existing = repository.loadNotifications()
            val updated = listOf(
                AppNotification(
                    id = System.currentTimeMillis(),
                    message = "Raspuns de la $fromNumber: $fullMessage",
                    type = "success",
                    timestamp = System.currentTimeMillis(),
                    read = false
                )
            ) + existing
            repository.saveNotifications(updated.take(50))
        }
    }
}

private data class ParsedUser(val id: Int, val phone: String)

private fun parseUserLines(message: String): Map<Int, ParsedUser> {
    val regex = Regex("\\b(\\d{3})\\s*:\\s*([A-Za-z0-9]+)", RegexOption.IGNORE_CASE)
    val result = mutableMapOf<Int, ParsedUser>()
    regex.findAll(message).forEach { match ->
        val id = match.groupValues[1].toInt()
        if (id < 0 || id > 999) return@forEach
        val value = match.groupValues[2].trim().trimEnd('.', ',', ';', ')')
        val token = if (value.equals("Empty", ignoreCase = true)) "" else value.filter { it.isLetterOrDigit() }
        result[id] = ParsedUser(id, token)
    }
    return result
}

private fun findRelayByNumber(relays: List<Relay>, fromNumber: String): Relay? {
    val fromDigits = fromNumber.filter { it.isDigit() }
    if (fromDigits.isBlank()) return null
    return relays.firstOrNull { relay ->
        val relayDigits = relay.phoneNumber.filter { it.isDigit() }
        if (relayDigits.isBlank()) false else {
            val a = relayDigits.takeLast(8)
            val b = fromDigits.takeLast(8)
            a == b
        }
    }
}

private fun confirmLatestHistory(
    history: List<CommandHistory>,
    relay: Relay,
    looksLikeSuccess: Boolean
): List<CommandHistory> {
    if (!looksLikeSuccess) return history
    val relayDigits = relay.phoneNumber.filter { it.isDigit() }.takeLast(8)
    val idx = history.indexOfFirst {
        it.status == "trimis" && it.relayPhone.filter { ch -> ch.isDigit() }.takeLast(8) == relayDigits
    }
    if (idx == -1) return history
    return history.mapIndexed { index, item ->
        if (index == idx) item.copy(status = "confirmat") else item
    }
}

private fun looksLikeSuccessMessage(
    message: String,
    parsedUsers: Map<Int, ParsedUser>
): Boolean {
    val text = message.uppercase()
    if (parsedUsers.isNotEmpty()) return true
    if (text.contains("SET TIME OK")) return true
    if (text.contains("PASSWORD CHANGED")) return true
    if (text.contains("RELAY ON WILL RETURN SMS")) return true
    if (text.contains("RELAY OFF WILL NOT RETURN SMS")) return true
    if (Regex("\\b001\\s*:").containsMatchIn(text)) return true
    if (Regex("\\b\\d{3}\\s*:").containsMatchIn(text)) return true
    return false
}
