package io.github.kemko.grimmoryuploader.data.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable data class HealthcheckResponse(val status: String? = null)

@Serializable data class PublicSettings(
    val oidcEnabled: Boolean = false,
    val oidcForceOnlyMode: Boolean = false,
)

@Serializable data class LoginRequest(val username: String, val password: String)
@Serializable data class RefreshRequest(val refreshToken: String)

@Serializable data class TokenResponse(
    @SerialName("accessToken") val accessToken: String,
    @SerialName("refreshToken") val refreshToken: String? = null,
    @SerialName("expiresIn") val expiresInSeconds: Long? = null,
    @SerialName("expiresAt") val expiresAtMillis: Long? = null,
)

@Serializable data class UserResponse(
    val id: Long? = null,
    val username: String? = null,
    val email: String? = null,
)

@Serializable data class UploadResponse(val id: String? = null, val name: String? = null)

@Serializable data class OidcStateResponse(
    val state: String? = null,
    val issuer: String? = null,
    val authorizationEndpoint: String? = null,
    val clientId: String? = null,
    val redirectUri: String? = null,
)

@Serializable data class OidcDiscoveryResponse(
    @SerialName("authorization_endpoint") val authorizationEndpoint: String? = null,
    @SerialName("token_endpoint") val tokenEndpoint: String? = null,
)

@Serializable data class OidcCallbackRequest(
    val code: String,
    val state: String,
    val redirectUri: String,
    val codeVerifier: String,
    val nonce: String,
)
