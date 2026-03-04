package com.security.gsmrelay.sms

import android.content.Context
import android.net.Uri
import com.security.gsmrelay.model.Relay
import com.security.gsmrelay.model.RelayEvent

object EventScraper {
    private val regex = Regex(
        "(?:Relay\\s+ON!|DESCHIDERE|RIDICARE/DESCHIDERE)\\s*Operated\\s*by\\s*([+0-9]+)",
        RegexOption.IGNORE_CASE
    )

    fun scrapeRelayEvents(
        context: Context,
        relay: Relay,
        startMillis: Long,
        endMillis: Long,
        existing: List<RelayEvent>
    ): List<RelayEvent> {
        val digits = relay.phoneNumber.filter { it.isDigit() }.takeLast(8)
        if (digits.isBlank()) return existing

        val existingKeys = HashSet<String>(existing.size)
        existing.forEach { existingKeys.add(eventKey(it.relayPhone, it.timestamp, it.operatorPhone)) }

        val found = mutableListOf<RelayEvent>()
        val uri = Uri.parse("content://sms")
        val projection = arrayOf("address", "body", "date")
        val selection = "address LIKE ? AND date BETWEEN ? AND ?"
        val selectionArgs = arrayOf("%$digits%", startMillis.toString(), endMillis.toString())
        val sortOrder = "date DESC"

        context.contentResolver.query(uri, projection, selection, selectionArgs, sortOrder)?.use { cursor ->
            val bodyIdx = cursor.getColumnIndex("body")
            val dateIdx = cursor.getColumnIndex("date")
            while (cursor.moveToNext()) {
                val body = cursor.getString(bodyIdx).orEmpty()
                val match = regex.find(body) ?: continue
                val ts = cursor.getLong(dateIdx)
                val operatorPhone = match.groupValues[1].trim()
                val key = eventKey(relay.phoneNumber, ts, operatorPhone)
                if (existingKeys.contains(key)) continue
                existingKeys.add(key)
                found.add(
                    RelayEvent(
                        id = ts,
                        relayName = relay.name,
                        relayPhone = relay.phoneNumber,
                        operatorPhone = operatorPhone,
                        message = body,
                        timestamp = ts
                    )
                )
            }
        }

        if (found.isEmpty()) return existing
        return (found + existing).sortedByDescending { it.timestamp }.take(2000)
    }

    private fun eventKey(relayPhone: String, timestamp: Long, operatorPhone: String): String {
        val relayDigits = relayPhone.filter { it.isDigit() }.takeLast(8)
        return "$relayDigits|$timestamp|$operatorPhone"
    }
}
