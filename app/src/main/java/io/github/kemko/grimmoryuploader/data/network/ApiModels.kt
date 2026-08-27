package io.github.kemko.grimmoryuploader.data.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable data class PublicSettings(
    val oidcEnabled: Boolean = false,
    val oidcForceOnlyMode: Boolean = false,
    val oidcProviderDetails: OidcProviderDetails? = null,
)

@Serializable data class OidcProviderDetails(
    val clientId: String? = null,
    val issuerUri: String? = null,
    val scopes: String? = null,
)

@Serializable data class LoginRequest(
    val username: String,
    val password: String,
)

@Serializable data class RefreshRequest(
    val refreshToken: String,
)

@Serializable data class TokenResponse(
    @SerialName("accessToken") val accessToken: String,
    @SerialName("refreshToken") val refreshToken: String? = null,
    @SerialName("expires") val expiresInSeconds: Long? = null,
)

@Serializable data class UserResponse(
    val id: Long? = null,
    val username: String? = null,
    val email: String? = null,
)

@Serializable data class OidcStateResponse(
    val state: String? = null,
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

@Serializable data class GrimmoryErrorResponse(
    val status: Int? = null,
    val message: String? = null,
    val timestamp: String? = null,
    val details: List<String>? = null,
)

@Serializable data class OAuthErrorResponse(
    val error: String? = null,
    @SerialName("error_description") val errorDescription: String? = null,
)
