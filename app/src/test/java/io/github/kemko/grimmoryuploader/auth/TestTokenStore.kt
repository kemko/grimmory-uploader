package io.github.kemko.grimmoryuploader.auth

import io.github.kemko.grimmoryuploader.data.auth.TokenPair
import io.github.kemko.grimmoryuploader.data.auth.TokenStore

class TestTokenStore : TokenStore {
    private var value: TokenPair? = null
    override suspend fun read(): TokenPair? = value
    override suspend fun write(tokens: TokenPair) { value = tokens }
    override suspend fun clear() { value = null }
}
