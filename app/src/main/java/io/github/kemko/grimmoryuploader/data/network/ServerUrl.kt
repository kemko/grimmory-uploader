package io.github.kemko.grimmoryuploader.data.network

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

class InvalidServerUrl(message: String) : IllegalArgumentException(message)

class ServerUrl private constructor(val url: HttpUrl) {
    val normalized: String = url.toString().trimEnd('/')
    val isCleartext: Boolean = url.scheme == "http"

    fun endpoint(path: String): HttpUrl {
        require(path.isNotBlank() && !path.contains("?")) { "Endpoint must be a path" }
        val prefix = url.encodedPath.trimEnd('/')
        return url.newBuilder()
            .encodedPath("${if (prefix.isEmpty()) "" else prefix}/${path.trimStart('/')}")
            .build()
    }

    companion object {
        fun parse(raw: String): ServerUrl {
            val parsed = raw.trim().toHttpUrlOrNull()
                ?: throw InvalidServerUrl("Server URL must be a valid HTTP(S) URL")
            if (parsed.scheme != "http" && parsed.scheme != "https") {
                throw InvalidServerUrl("Only HTTP(S) server URLs are supported")
            }
            if (parsed.host.isBlank() || parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) {
                throw InvalidServerUrl("Server URL must not contain credentials")
            }
            if (parsed.query != null || parsed.fragment != null) {
                throw InvalidServerUrl("Server URL must not contain a query or fragment")
            }
            return ServerUrl(
                parsed.newBuilder()
                    .scheme(parsed.scheme.lowercase())
                    .host(parsed.host.lowercase())
                    .build(),
            )
        }
    }
}
