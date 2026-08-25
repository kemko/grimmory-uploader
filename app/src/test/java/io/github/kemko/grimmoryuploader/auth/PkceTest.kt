package io.github.kemko.grimmoryuploader.auth

import io.github.kemko.grimmoryuploader.data.auth.Pkce
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PkceTest {
    @Test
    fun createsUrlSafeVerifierAndS256Challenge() {
        val verifier = Pkce.verifier()
        val challenge = Pkce.challenge(verifier)
        assertTrue(verifier.length >= 43)
        assertTrue(verifier.matches(Regex("[A-Za-z0-9_-]+")))
        assertTrue(challenge.matches(Regex("[A-Za-z0-9_-]+")))
        assertEquals(challenge, Pkce.challenge(verifier))
        assertNotEquals(verifier, Pkce.nonce())
    }
}
