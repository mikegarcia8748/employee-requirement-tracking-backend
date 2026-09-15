package com.pgsystem.employee.requirement.tracker.core.crypto

import com.pgsystem.employee.requirement.tracker.data.crypto.BcryptHasher
import com.pgsystem.employee.requirement.tracker.data.crypto.HmacTokenDigest
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * The two portal credentials use different primitives, and the reason is not style (ERT-160).
 *
 * The link token is **looked up** — `UploadLinkRepository.findByTokenHash` resolves a link by its
 * hash and `upload_links.token_hash` carries a unique index — so the digest has to be reproducible.
 * bcrypt is salted, so before this ticket the same token hashed differently on every call and **no
 * presented token could ever be resolved**. The PIN is the opposite case: verified against one known
 * row, never looked up, with a keyspace of only 10^6, which is exactly what a work factor defends.
 *
 * Peppers in these tests are realistic lengths because the constructor refuses a weak one.
 */
class TokenDigestTest {

    private val pepper = "a-test-pepper-with-enough-entropy-to-pass"
    private val digest = HmacTokenDigest(pepper)

    @Test
    fun `token digest - the same token digested twice - produces the same value so a link resolves by hash`() {
        // The bug this ticket exists to fix. With bcrypt these two differ and findByTokenHash can
        // never match anything.
        val token = "zR6kQm2Xb9YwT4vN8pL1sHcD3fG5jK7a"

        digest.digest(token) shouldBe digest.digest(token)
    }

    @Test
    fun `token digest - two different tokens - produce different digests`() {
        digest.digest("token-one") shouldNotBe digest.digest("token-two")
    }

    @Test
    fun `token digest - two different peppers - produce different digests for the same token`() {
        // The only test that fails if the pepper is accepted, stored, and then never mixed into the
        // MAC. Without it a plain unkeyed SHA-256 passes every other test in this file.
        val other = HmacTokenDigest("a-different-pepper-also-long-enough-ok")

        digest.digest("same-token") shouldNotBe other.digest("same-token")
    }

    @Test
    fun `token digest - a stored digest - does not contain the token it was made from`() {
        // A leaked database must not yield a usable token. Stated as a test because "it's a hash"
        // is the kind of assumption an encoding change could quietly break.
        val token = "zR6kQm2Xb9YwT4vN8pL1sHcD3fG5jK7a"

        digest.digest(token).contains(token) shouldBe false
    }

    @Test
    fun `credential hashing - an access pin - is hashed with a work factor rather than a fast digest`() {
        // Guards against the tempting simplification of "one primitive for both credentials" now
        // that a fast digest exists in the codebase. bcrypt's per-call salt is what proves it is
        // still bcrypt: a digest would return the same string twice.
        val hasher = BcryptHasher()
        val pin = "004821"

        val first = hasher.hash(pin)
        val second = hasher.hash(pin)

        first shouldNotBe second
        hasher.verify(pin, first) shouldBe true
        hasher.verify(pin, second) shouldBe true
        BcryptHasher.DEFAULT_COST shouldBe 12
    }

    @Test
    fun `token digest - no pepper configured outside dev - startup refuses`() {
        // `pepper` is an injected parameter rather than a read of the ambient environment, so this
        // asserts the same thing whether or not the machine running the suite has TOKEN_PEPPER set.
        val failure = assertFailsWith<IllegalStateException> {
            HmacTokenDigest.fromEnvironment(devMode = false, pepper = null)
        }

        failure.message!! shouldContain "TOKEN_PEPPER"
    }

    @Test
    fun `token digest - a blank pepper outside dev - is treated as absent rather than accepted`() {
        // `?:` catches null but not "". A .env copied from .env.example and sourced supplies exactly
        // the empty string, so blank has to mean absent or the refusal above is bypassable by
        // following the documentation.
        assertFailsWith<IllegalStateException> {
            HmacTokenDigest.fromEnvironment(devMode = false, pepper = "   ")
        }
    }

    @Test
    fun `token digest - a pepper below the minimum length - is refused in dev as well as outside it`() {
        // Weakness is refused unconditionally; only *absence* is forgiven in dev. Otherwise the
        // check is satisfied by TOKEN_PEPPER=x.
        assertFailsWith<IllegalArgumentException> {
            HmacTokenDigest.fromEnvironment(devMode = true, pepper = "short")
        }
    }

    @Test
    fun `token digest - no pepper configured in dev - falls back to an ephemeral pepper that still works`() {
        // A fresh checkout must run with no configuration, matching the DATABASE_URL default.
        val ephemeral = HmacTokenDigest.fromEnvironment(devMode = true, pepper = null)

        ephemeral.digest("token") shouldBe ephemeral.digest("token")
    }
}
