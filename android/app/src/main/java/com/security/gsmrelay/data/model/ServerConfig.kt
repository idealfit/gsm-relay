package com.security.gsmrelay.data.model

data class ServerConfig(
    val baseUrl: String = "",
    val username: String = "",
    val password: String = "",
    val gatewayId: String = "",
    val masterPhone: String = ""
) {
    fun isValid(): Boolean {
        return baseUrl.isNotBlank() && username.isNotBlank() && password.isNotBlank()
    }

    companion object {
        const val DEFAULT_BASE_URL = "http://86.120.150.58:5174"
        const val DEFAULT_USERNAME = "admin"
        const val DEFAULT_PASSWORD = "admin1316"
        const val DEFAULT_GATEWAY_ID = "pQF6bci9"
        const val DEFAULT_MASTER_PHONE = "0724264464"
    }
}
