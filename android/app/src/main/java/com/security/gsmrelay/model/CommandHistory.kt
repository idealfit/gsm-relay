package com.security.gsmrelay.model

data class CommandHistory(
    val id: Long,
    val relayName: String,
    val relayPhone: String,
    val command: String,
    val description: String,
    val timestamp: Long,
    val status: String
)
