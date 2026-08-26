package io.github.kemko.grimmoryuploader.auth

import io.github.kemko.grimmoryuploader.data.auth.OidcPendingRequest
import io.github.kemko.grimmoryuploader.data.auth.OidcPendingStore
import io.github.kemko.grimmoryuploader.data.auth.TokenPair
import io.github.kemko.grimmoryuploader.data.auth.TokenStore

class TestTokenStore :
    TokenStore,
    OidcPendingStore {
    private var value: TokenPair? = null
    private var pending: OidcPendingRequest? = null

    override suspend fun read(): TokenPair? = value

    override suspend fun write(tokens: TokenPair) {
        value = tokens
    }

    override suspend fun clear() {
        value = null
    }

    override suspend fun readPendingOidc(): OidcPendingRequest? = pending

    override suspend fun writePendingOidc(request: OidcPendingRequest) {
        pending = request
    }

    override suspend fun clearPendingOidc() {
        pending = null
    }
}
