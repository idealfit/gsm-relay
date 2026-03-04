package com.security.gsmrelay.model

data class Relay(
    val id: Long,
    val name: String,
    val phoneNumber: String,
    val password: String,
    val location: String = "",
    val users: List<User> = emptyList(),
    val lastSync: Long? = null,
    val cloudBackup: Boolean = false
)
