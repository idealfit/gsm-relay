package com.security.gsmrelay.data.network

import android.util.Base64
import com.google.gson.Gson
import com.security.gsmrelay.data.model.ServerConfig
import com.security.gsmrelay.model.CommandHistory
import com.security.gsmrelay.model.Relay
import com.security.gsmrelay.model.RelayEvent
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

data class ServerSnapshot(
    val relays: List<Relay> = emptyList(),
    val history: List<CommandHistory> = emptyList(),
    val events: List<RelayEvent> = emptyList(),
    val locations: List<String>? = null
)

data class CommandQueueItem(
    val id: String = "",
    val relayPhone: String = "",
    val relayKey: String = "",
    val gatewayId: String = "",
    val command: String = "",
    val description: String = "",
    val status: String = "",
    val source: String = "",
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    val responseText: String = ""
)

private data class CommandsResponse(
    val commands: List<CommandQueueItem> = emptyList()
)

data class CommandCreateResult(
    val ok: Boolean,
    val statusCode: Int
)

data class RelayActionResult(
    val ok: Boolean,
    val statusCode: Int
)

object ServerApi {
    private val gson = Gson()

    fun downloadSnapshot(config: ServerConfig): ServerSnapshot? {
        val url = buildUrl(config.baseUrl, "/api/snapshot")
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8000
            readTimeout = 8000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Authorization", buildAuth(config))
        }
        return try {
            val code = conn.responseCode
            if (code !in 200..299) return null
            val body = conn.inputStream.bufferedReader().use(BufferedReader::readText)
            gson.fromJson(body, ServerSnapshot::class.java)
        } catch (_: Exception) {
            null
        } finally {
            conn.disconnect()
        }
    }

    fun uploadSnapshot(config: ServerConfig, snapshot: ServerSnapshot): Boolean {
        val url = buildUrl(config.baseUrl, "/api/snapshot")
        val payload = gson.toJson(snapshot)
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 8000
            readTimeout = 8000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", buildAuth(config))
        }
        return try {
            conn.outputStream.use { it.write(payload.toByteArray()) }
            val code = conn.responseCode
            code in 200..299
        } catch (_: Exception) {
            false
        } finally {
            conn.disconnect()
        }
    }

    fun fetchPendingCommands(config: ServerConfig, limit: Int = 50): List<CommandQueueItem> {
        if (config.gatewayId.isBlank()) return emptyList()
        val url = buildUrl(
            config.baseUrl,
            "/api/commands?status=pending&limit=${limit.coerceIn(1, 200)}&gatewayId=${java.net.URLEncoder.encode(config.gatewayId, "UTF-8")}"
        )
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8000
            readTimeout = 8000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Authorization", buildAuth(config))
        }
        return try {
            val code = conn.responseCode
            if (code !in 200..299) return emptyList()
            val body = conn.inputStream.bufferedReader().use(BufferedReader::readText)
            val response = gson.fromJson(body, CommandsResponse::class.java)
            response.commands
        } catch (_: Exception) {
            emptyList()
        } finally {
            conn.disconnect()
        }
    }

    fun fetchCommands(config: ServerConfig, status: String = "", limit: Int = 500): List<CommandQueueItem> {
        val query = mutableListOf(
            "status=${java.net.URLEncoder.encode(status, "UTF-8")}",
            "limit=${limit.coerceIn(1, 1000)}"
        )
        if (config.gatewayId.isNotBlank()) {
            query.add("gatewayId=${java.net.URLEncoder.encode(config.gatewayId, "UTF-8")}")
        }
        val url = buildUrl(config.baseUrl, "/api/commands?${query.joinToString("&")}")
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8000
            readTimeout = 8000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Authorization", buildAuth(config))
        }
        return try {
            val code = conn.responseCode
            if (code !in 200..299) return emptyList()
            val body = conn.inputStream.bufferedReader().use(BufferedReader::readText)
            val response = gson.fromJson(body, CommandsResponse::class.java)
            response.commands
        } catch (_: Exception) {
            emptyList()
        } finally {
            conn.disconnect()
        }
    }

    fun updateCommandStatus(
        config: ServerConfig,
        commandId: String,
        status: String,
        responseText: String = ""
    ): Boolean {
        val url = buildUrl(config.baseUrl, "/api/commands/$commandId/status")
        val payload = gson.toJson(
            mapOf(
                "status" to status,
                "responseText" to responseText
            )
        )
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 8000
            readTimeout = 8000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", buildAuth(config))
        }
        return try {
            conn.outputStream.use { it.write(payload.toByteArray()) }
            val code = conn.responseCode
            code in 200..299
        } catch (_: Exception) {
            false
        } finally {
            conn.disconnect()
        }
    }

    fun acknowledgeRelayWaitingCommand(
        config: ServerConfig,
        relayPhone: String,
        responseText: String = ""
    ): Boolean {
        val url = buildUrl(config.baseUrl, "/api/commands/ack-relay")
        val payload = gson.toJson(
            mapOf(
                "relayPhone" to relayPhone,
                "gatewayId" to config.gatewayId.trim(),
                "responseText" to responseText
            )
        )
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 8000
            readTimeout = 8000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", buildAuth(config))
        }
        return try {
            conn.outputStream.use { it.write(payload.toByteArray()) }
            val code = conn.responseCode
            code in 200..299
        } catch (_: Exception) {
            false
        } finally {
            conn.disconnect()
        }
    }

    fun createCommand(
        config: ServerConfig,
        relayPhone: String,
        command: String,
        description: String,
        source: String
    ): CommandCreateResult {
        val url = buildUrl(config.baseUrl, "/api/commands")
        val payload = gson.toJson(
            mapOf(
                "relayPhone" to relayPhone,
                "command" to command,
                "description" to description,
                "source" to source,
                "gatewayId" to config.gatewayId.trim()
            )
        )
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 8000
            readTimeout = 8000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", buildAuth(config))
        }
        return try {
            conn.outputStream.use { it.write(payload.toByteArray()) }
            val code = conn.responseCode
            CommandCreateResult(code in 200..299, code)
        } catch (_: Exception) {
            CommandCreateResult(false, 0)
        } finally {
            conn.disconnect()
        }
    }

    fun deleteRelayData(config: ServerConfig, relayPhone: String): RelayActionResult {
        val phone = relayPhone.trim()
        if (phone.isBlank()) return RelayActionResult(false, 400)
        val url = buildUrl(config.baseUrl, "/api/relays/${java.net.URLEncoder.encode(phone, "UTF-8")}")
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "DELETE"
            connectTimeout = 8000
            readTimeout = 8000
            setRequestProperty("Authorization", buildAuth(config))
        }
        return try {
            val code = conn.responseCode
            RelayActionResult(code in 200..299, code)
        } catch (_: Exception) {
            RelayActionResult(false, 0)
        } finally {
            conn.disconnect()
        }
    }

    fun clearRelayDatabase(config: ServerConfig, relayPhone: String): RelayActionResult {
        val phone = relayPhone.trim()
        if (phone.isBlank()) return RelayActionResult(false, 400)
        val url = buildUrl(config.baseUrl, "/api/relays/${java.net.URLEncoder.encode(phone, "UTF-8")}/clear-db")
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 8000
            readTimeout = 8000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", buildAuth(config))
        }
        return try {
            conn.outputStream.use { it.write("{}".toByteArray()) }
            val code = conn.responseCode
            RelayActionResult(code in 200..299, code)
        } catch (_: Exception) {
            RelayActionResult(false, 0)
        } finally {
            conn.disconnect()
        }
    }

    private fun buildAuth(config: ServerConfig): String {
        val creds = "${config.username}:${config.password}"
        val encoded = Base64.encodeToString(creds.toByteArray(), Base64.NO_WRAP)
        return "Basic $encoded"
    }

    private fun buildUrl(baseUrl: String, path: String): String {
        val cleanBase = if (baseUrl.endsWith("/")) baseUrl.dropLast(1) else baseUrl
        return cleanBase + path
    }
}
