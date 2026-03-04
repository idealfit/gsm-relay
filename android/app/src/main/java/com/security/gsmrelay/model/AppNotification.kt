package com.security.gsmrelay.model

data class AppNotification(
    val id: Long,
    val message: String,
    val type: String = "info",
    val timestamp: Long,
    val read: Boolean = false,
    val relayPhone: String = "",
    val relayName: String = ""
)
