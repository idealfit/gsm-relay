package com.security.gsmrelay.model

data class RelayEvent(
    val id: Long,
    val relayName: String,
    val relayPhone: String,
    val operatorPhone: String,
    val message: String,
    val timestamp: Long
)
