package com.security.gsmrelay.model

data class User(
    val id: Int,
    val phone: String = "",
    val name: String = "",
    val group: String = "general",
    val addedDate: Long? = null,
    val known: Boolean = false
)
