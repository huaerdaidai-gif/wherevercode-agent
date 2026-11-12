package wherevercode.agent.core.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecretRedactorTest {

    @Test
    fun `sk- keys are redacted`() {
        assertEquals(
            "key is [REDACTED] end",
            SecretRedactor.redact("key is sk-abcdefghijklmnop123456 end"),
        )
    }

    @Test
    fun `Bearer tokens are redacted`() {
        // Ported faithfully from the old _EVENT_SECRET_PATTERNS: the
        // authorization pattern's group 1 is only the "Authorization:"
        // prefix, so "Bearer " is consumed with the token and dropped.
        assertEquals(
            "Authorization: [REDACTED]",
            SecretRedactor.redact("Authorization: Bearer abcdefgh.ijklmnop.qrstuv"),
        )
    }

    @Test
    fun `private key blocks are redacted`() {
        val begin = "-----BEGIN " + "RSA PRIVATE KEY" + "-----"
        val end = "-----END " + "RSA PRIVATE KEY" + "-----"
        val text = "$begin\nMIIEow==\n$end"
        assertEquals("[REDACTED]", SecretRedactor.redact(text))
    }

    @Test
    fun `api key assignments are redacted but the name stays`() {
        assertEquals(
            "api_key=\"[REDACTED]\"",
            SecretRedactor.redact("api_key=\"super-secret-value\""),
        )
        assertEquals(
            "API_KEY=[REDACTED]",
            SecretRedactor.redact("API_KEY=super-secret-value"),
        )
    }

    @Test
    fun `cli key flags are redacted`() {
        assertEquals(
            "--api-key [REDACTED]",
            SecretRedactor.redact("--api-key super-secret-value"),
        )
    }

    @Test
    fun `open tail in a stream chunk is masked before completion`() {
        val chunk = "{\"api_key\": \"sk-abc"
        val redacted = SecretRedactor.redactStreamChunk(chunk)
        assertFalse(redacted.contains("sk-abc"))
        assertTrue(redacted.contains("[REDACTED]"))
        // An incomplete Bearer at the end of a chunk is masked too.
        val chunk2 = "partial Bearer abc"
        assertTrue(SecretRedactor.redactStreamChunk(chunk2).contains("[REDACTED]"))
    }

    @Test
    fun `ordinary text passes through and the limit keeps the tail`() {
        assertEquals("hello world", SecretRedactor.redact("hello world"))
        val long = "a".repeat(3000)
        assertEquals(2000, SecretRedactor.redact(long).length)
    }

    @Test
    fun `containsSecret detects secret-like text`() {
        assertTrue(SecretRedactor.containsSecret("token=abc123"))
        assertFalse(SecretRedactor.containsSecret("just a normal sentence"))
    }
}
