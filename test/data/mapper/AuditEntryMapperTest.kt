package com.pgsystem.employee.requirement.tracker.data.mapper

import com.pgsystem.employee.requirement.tracker.domain.model.VerificationMethod
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * The metadata column's encoding and its credential refusal (ERT-330).
 *
 * Pure — no database. The refusal is worth testing away from SQL because what it protects is a
 * rule about *content*, and a reader of `ExposedAuditLogTest` would reasonably assume the column
 * type was doing the work.
 */
class AuditEntryMapperTest {

    // ── Encoding ────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `audit metadata - a value containing a quote and a backslash - round-trips rather than corrupting the json`() {
        // The reason this test exists: a hand-rolled encoder produces unparseable JSON the first
        // time an HR officer types a quotation mark.
        //
        // Round-tripping through our own two functions is NOT enough to prove that — a symmetric
        // hand-rolled codec decodes its own corruption back to the original and passes. So the
        // encoded text is also handed to an independent parser, which is the property that actually
        // matters: the column holds JSON that something other than us can read.
        val metadata = mapOf("reason" to """He said "the scan is illegible" \ twice""")

        val encoded = metadata.toMetadataJson()

        Json.parseToJsonElement(encoded).jsonObject.mapValues { it.value.jsonPrimitive.content } shouldBe metadata
        encoded.toMetadata() shouldBe metadata
    }

    @Test
    fun `audit metadata - a value containing a comma and a colon - round-trips rather than splitting into two keys`() {
        // The separators. A naive encoder survives the quote test above by being symmetrically
        // broken; it cannot survive this one.
        val metadata = mapOf("reason" to "Illegible, blurred: please re-upload")

        val encoded = metadata.toMetadataJson()

        Json.parseToJsonElement(encoded).jsonObject.keys shouldBe setOf("reason")
        encoded.toMetadata() shouldBe metadata
    }

    @Test
    fun `audit metadata - an empty map - encodes as an empty json object`() {
        emptyMap<String, String>().toMetadataJson() shouldBe "{}"
        "{}".toMetadata() shouldBe emptyMap()
    }

    @Test
    fun `audit metadata - a stored value that is not json - fails rather than returning a partial map`() {
        assertFailsWith<Exception> { "not json".toMetadata() }
    }

    // ── The credential refusal ──────────────────────────────────────────────────────────────────

    @Test
    fun `audit metadata - a key named for a credential - is refused whatever its value`() {
        // Layer 1, and the one that does the work: a credential reaches metadata through a key
        // somebody names for it. `mapOf("pin" to pin.value)` is the realistic mistake.
        listOf("pin", "access_pin", "token", "tokenDigest", "password", "passcode", "secret", "otp", "credential")
            .forEach { key ->
                val thrown = assertFailsWith<IllegalArgumentException>("'$key' should be refused") {
                    mapOf(key to "anything at all").toMetadataJson()
                }
                thrown.message!! shouldContain key
            }
    }

    @Test
    fun `audit metadata - a token-shaped value under an innocent key - is refused`() {
        // Layer 2, a tripwire rather than a control. Nothing legitimate in audit metadata is a
        // 32-character run of base64url: a reason is prose, an identifier is 8 or 12 characters.
        assertFailsWith<IllegalArgumentException> {
            mapOf("note" to "Zm9vYmFyYmF6cXV4MDEyMzQ1Njc4OWFiY2RlZg").toMetadataJson()
        }
    }

    @Test
    fun `audit metadata - an ordinary reason and verification method - is accepted`() {
        // The vacuity guard for the two refusals above: a denylist that refused everything would
        // satisfy both of them.
        val metadata = mapOf(
            "reason" to "Illegible scan, please re-upload",
            "verification_method" to VerificationMethod.PHONE_CALL_TO_RECRUITMENT_RECORD.name,
            "old_value" to "90",
            "new_value" to "45",
        )

        metadata.toMetadataJson().toMetadata() shouldBe metadata
    }

    @Test
    fun `audit metadata - a long reason with no spaces - is refused today, which ERT-431 made reachable`() {
        // NOT an endorsement — this pins a trap so it is visible rather than discovered in
        // production. `isCredentialShaped` assumes "a reason is prose", and prose has spaces: one
        // space fails the `all {}` and the value is accepted. A reason with NO spaces that happens
        // to be 32+ characters with mixed case and a digit satisfies every clause, and the `require`
        // throws out through `ExposedAuditLog.record` as a 500.
        //
        // Before ERT-431 no free-text HR value reached this map, so the trap was unreachable.
        // `CreateHireUseCase` now writes the typed duplicate reason here, and an officer pasting a
        // ticket reference rather than a sentence is all it takes.
        //
        // Deliberately not fixed here: this is ERT-330's security control, and weakening a tripwire
        // is a specification change — which is the C2 lesson ERT-431 itself carries. Escalated as
        // C25, owned by ERT-450, which is where a length and shape rule for body text belongs.
        val spaceless = "ReplacingRecord2026ForJoseDelaCruz"

        // The property, not a magic number: it is over the 32-character threshold and carries no
        // character that would take it out of the allowed set.
        (spaceless.length >= 32) shouldBe true
        spaceless.none { it.isWhitespace() } shouldBe true

        assertFailsWith<IllegalArgumentException> { mapOf("reason" to spaceless).toMetadataJson() }

        // The same reason as prose is accepted, which is what makes the trap narrow rather than
        // theoretical — and what makes it easy to miss.
        val prose = "Replacing record 2026 for Jose Dela Cruz"
        mapOf("reason" to prose).toMetadataJson().toMetadata() shouldBe mapOf("reason" to prose)
    }

    @Test
    fun `audit metadata - a six digit value under a key that is not credential-shaped - is accepted and the limit is deliberate`() {
        // An AccessPin is exactly six digits, so a bare six-digit rule is tempting. It is refused
        // here on purpose: `size_bytes` of 204800 is also six digits, and ERT-810's
        // DOCUMENT_DOWNLOADED metadata will carry exactly that. Adding the rule later breaks a
        // ticket nobody has written yet. This test documents the guard's limit rather than
        // overclaiming it.
        mapOf("size_bytes" to "204800").toMetadataJson().toMetadata() shouldBe mapOf("size_bytes" to "204800")
    }

    @Test
    fun `audit metadata - a key naming a credential in a different case - is still refused`() {
        assertFailsWith<IllegalArgumentException> { mapOf("AccessPIN" to "123456").toMetadataJson() }
    }

    // ── The settings exemption, and its three edges (SEC-38) ────────────────────────────────────

    @Test
    fun `audit metadata - every link policy setting key - survives the credential guard`() {
        // SEC-38's build guard. `updateLinkPolicy` names each changed setting as `<key>.old` and
        // `<key>.new`, and two of the nine keys contain `pin` -- so saving either threw an
        // IllegalArgumentException out of a DomainResult method and the two settings §6.4 promises
        // are configurable were not.
        //
        // Driven off the enum so a tenth setting is covered the day it is added. The exemption it
        // relies on is DECLARED rather than derived (see CREDENTIAL_FRAGMENT_EXEMPT_SETTINGS), which
        // is what makes this test able to fail: a tenth setting named `portal.otp_window_minutes`
        // would not be in that set, and this line is where the build says so.
        val metadata = LinkPolicySetting.entries.flatMap { setting ->
            val (old, new) = setting.auditMetadataKeys()
            listOf(old to "90", new to "45")
        }.toMap()

        // Anti-vacuity. Without this the guard passes just as happily against an enum whose keys
        // have all been renamed away from the denylist, proving nothing about the exemption.
        metadata.keys shouldHaveSize LinkPolicySetting.entries.size * 2
        metadata.keys.count { "pin" in it } shouldBe 4

        metadata.toMetadataJson().toMetadata() shouldBe metadata
    }

    @Test
    fun `audit metadata - a credential-shaped value under a link policy metadata key - is still refused`() {
        // The exemption skips the KEY check and nothing else. Widening it to skip the value check
        // too would compile, pass every other test in this file, and turn the one key nobody
        // inspects into a channel for the credential the guard exists to stop.
        val exempt = "portal.pin_attempts_before_lockout.new"

        assertFailsWith<IllegalArgumentException> {
            mapOf(exempt to "Zm9vYmFyYmF6cXV4MDEyMzQ1Njc4OWFiY2RlZg").toMetadataJson()
        }
    }

    @Test
    fun `audit metadata - a settings key without its old or new suffix - is still refused`() {
        // Exact match against the eighteen generated keys, not a prefix or a pattern. Nothing
        // writes the bare key, so nothing needs it exempt -- and `startsWith` would quietly exempt
        // `portal.pin_attempts_before_lockout.whatever_a_caller_typed` as well.
        assertFailsWith<IllegalArgumentException> {
            mapOf("portal.pin_attempts_before_lockout" to "5").toMetadataJson()
        }
    }

    @Test
    fun `audit metadata - every exempted setting - actually collides with the denylist`() {
        // The other direction. An exemption that stops being necessary -- because a key was renamed
        // -- is a hole nobody closed, and it would never announce itself.
        CREDENTIAL_FRAGMENT_EXEMPT_SETTINGS.shouldHaveSize(2)

        CREDENTIAL_FRAGMENT_EXEMPT_SETTINGS.forEach { setting ->
            val (old, _) = setting.auditMetadataKeys()
            withClue("'${setting.key}' no longer collides, so its exemption is dead weight") {
                assertFailsWith<IllegalArgumentException> {
                    // The same key, one character off the exempt list, is the control.
                    mapOf("$old-unexempted" to "90").toMetadataJson()
                }
            }
        }
    }
}
