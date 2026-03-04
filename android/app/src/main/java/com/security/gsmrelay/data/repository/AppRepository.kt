package com.security.gsmrelay.data.repository

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.security.gsmrelay.data.model.ServerConfig
import com.security.gsmrelay.model.AppNotification
import com.security.gsmrelay.model.CommandHistory
import com.security.gsmrelay.model.RelayEvent
import com.security.gsmrelay.model.Relay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "gsm_relay_manager")

class AppRepository(private val context: Context) {
    private val gson = Gson()
    private val defaultServerUrl = ServerConfig.DEFAULT_BASE_URL
    private val defaultServerUser = ServerConfig.DEFAULT_USERNAME
    private val defaultServerPass = ServerConfig.DEFAULT_PASSWORD
    private val defaultGatewayId = ServerConfig.DEFAULT_GATEWAY_ID
    private val defaultMasterPhone = ServerConfig.DEFAULT_MASTER_PHONE

    private val relaysKey = stringPreferencesKey("relays_json")
    private val historyKey = stringPreferencesKey("history_json")
    private val eventsKey = stringPreferencesKey("events_json")
    private val notificationsKey = stringPreferencesKey("notifications_json")
    private val serverUrlKey = stringPreferencesKey("server_url")
    private val serverUserKey = stringPreferencesKey("server_user")
    private val serverPassKey = stringPreferencesKey("server_pass")
    private val serverGatewayKey = stringPreferencesKey("server_gateway_id")
    private val serverMasterKey = stringPreferencesKey("server_master_phone")

    suspend fun loadRelays(): List<Relay> {
        val prefs = context.dataStore.data.first()
        val json = prefs[relaysKey].orEmpty()
        if (json.isBlank()) return emptyList()
        val type = object : TypeToken<List<Relay>>() {}.type
        return gson.fromJson(json, type) ?: emptyList()
    }

    suspend fun saveRelays(relays: List<Relay>) {
        val json = gson.toJson(relays)
        context.dataStore.edit { it[relaysKey] = json }
    }

    fun relaysFlow(): Flow<List<Relay>> {
        val type = object : TypeToken<List<Relay>>() {}.type
        return context.dataStore.data.map { prefs ->
            val json = prefs[relaysKey].orEmpty()
            if (json.isBlank()) emptyList() else gson.fromJson(json, type) ?: emptyList()
        }
    }

    suspend fun loadHistory(): List<CommandHistory> {
        val prefs = context.dataStore.data.first()
        val json = prefs[historyKey].orEmpty()
        if (json.isBlank()) return emptyList()
        val type = object : TypeToken<List<CommandHistory>>() {}.type
        return gson.fromJson(json, type) ?: emptyList()
    }

    suspend fun saveHistory(history: List<CommandHistory>) {
        val json = gson.toJson(history)
        context.dataStore.edit { it[historyKey] = json }
    }

    fun historyFlow(): Flow<List<CommandHistory>> {
        val type = object : TypeToken<List<CommandHistory>>() {}.type
        return context.dataStore.data.map { prefs ->
            val json = prefs[historyKey].orEmpty()
            if (json.isBlank()) emptyList() else gson.fromJson(json, type) ?: emptyList()
        }
    }

    suspend fun loadEvents(): List<RelayEvent> {
        val prefs = context.dataStore.data.first()
        val json = prefs[eventsKey].orEmpty()
        if (json.isBlank()) return emptyList()
        val type = object : TypeToken<List<RelayEvent>>() {}.type
        return gson.fromJson(json, type) ?: emptyList()
    }

    suspend fun saveEvents(events: List<RelayEvent>) {
        val json = gson.toJson(events)
        context.dataStore.edit { it[eventsKey] = json }
    }

    fun eventsFlow(): Flow<List<RelayEvent>> {
        val type = object : TypeToken<List<RelayEvent>>() {}.type
        return context.dataStore.data.map { prefs ->
            val json = prefs[eventsKey].orEmpty()
            if (json.isBlank()) emptyList() else gson.fromJson(json, type) ?: emptyList()
        }
    }

    suspend fun loadNotifications(): List<AppNotification> {
        val prefs = context.dataStore.data.first()
        val json = prefs[notificationsKey].orEmpty()
        if (json.isBlank()) return emptyList()
        val type = object : TypeToken<List<AppNotification>>() {}.type
        return gson.fromJson(json, type) ?: emptyList()
    }

    suspend fun saveNotifications(notifications: List<AppNotification>) {
        val json = gson.toJson(notifications)
        context.dataStore.edit { it[notificationsKey] = json }
    }

    fun notificationsFlow(): Flow<List<AppNotification>> {
        val type = object : TypeToken<List<AppNotification>>() {}.type
        return context.dataStore.data.map { prefs ->
            val json = prefs[notificationsKey].orEmpty()
            if (json.isBlank()) emptyList() else gson.fromJson(json, type) ?: emptyList()
        }
    }

    fun serverConfigFlow(): Flow<ServerConfig> {
        return context.dataStore.data.map { prefs ->
            ServerConfig(
                baseUrl = prefs[serverUrlKey].orEmpty().ifBlank { defaultServerUrl },
                username = prefs[serverUserKey].orEmpty().ifBlank { defaultServerUser },
                password = prefs[serverPassKey].orEmpty().ifBlank { defaultServerPass },
                gatewayId = prefs[serverGatewayKey].orEmpty().ifBlank { defaultGatewayId },
                masterPhone = prefs[serverMasterKey].orEmpty().ifBlank { defaultMasterPhone }
            )
        }
    }

    suspend fun loadServerConfig(): ServerConfig {
        val prefs = context.dataStore.data.first()
        return ServerConfig(
            baseUrl = prefs[serverUrlKey].orEmpty().ifBlank { defaultServerUrl },
            username = prefs[serverUserKey].orEmpty().ifBlank { defaultServerUser },
            password = prefs[serverPassKey].orEmpty().ifBlank { defaultServerPass },
            gatewayId = prefs[serverGatewayKey].orEmpty().ifBlank { defaultGatewayId },
            masterPhone = prefs[serverMasterKey].orEmpty().ifBlank { defaultMasterPhone }
        )
    }

    suspend fun saveServerConfig(config: ServerConfig) {
        context.dataStore.edit {
            it[serverUrlKey] = config.baseUrl
            it[serverUserKey] = config.username
            it[serverPassKey] = config.password
            it[serverGatewayKey] = config.gatewayId
            it[serverMasterKey] = config.masterPhone
        }
    }
}
