package com.pgsystem.employee.requirement.tracker.data.mapper

import com.pgsystem.employee.requirement.tracker.domain.model.VerificationMethod
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
}
