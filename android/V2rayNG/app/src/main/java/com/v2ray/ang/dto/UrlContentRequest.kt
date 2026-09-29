package com.v2ray.ang.dto

data class UrlContentRequest(
    val url: String?,
    val timeout: Int = 15000,
    val httpPort: Int = 0,
    val proxyUsername: String? = null,
    val proxyPassword: String? = null,
    val userAgent: String? = null,
    val requestHeaders: String? = null,
    /** Applied before [requestHeaders], so user-configured headers can override them. */
    val defaultHeaders: Map<String, String> = emptyMap(),
    /** Resolve the host over DNS-over-HTTPS instead of the system resolver. */
    val secureDns: Boolean = false
)