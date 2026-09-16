package com.pgsystem.employee.requirement.tracker.data.mapper

import com.pgsystem.employee.requirement.tracker.core.error.DomainResult
import com.pgsystem.employee.requirement.tracker.core.value.EntityId
import com.pgsystem.employee.requirement.tracker.core.value.Identifier
import com.pgsystem.employee.requirement.tracker.core.value.PersonId
import com.pgsystem.employee.requirement.tracker.data.db.table.AuditLogs
import com.pgsystem.employee.requirement.tracker.domain.model.AuditAction
import com.pgsystem.employee.requirement.tracker.domain.model.AuditEntry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.core.ResultRow

/**
 * `audit_logs` row ↔ [AuditEntry] (ERT-330).
 *
 * **Metadata is encoded here and never accepted as a caller-supplied string.** The port takes a
 * `Map<String, String>`, so there is no signature through which a caller can hand in text that is
 * not JSON — a malformed row is unrepresentable rather than merely discouraged.
 *
 * The encoding lives in `data/` rather than on the model because `kotlinx.serialization` is banned
 * in `domain/` and `core/`; the architecture test fails the build on that import.
 */

fun ResultRow.toAuditEntry(): AuditEntry = AuditEntry(
    id = EntityId.of(this[AuditLogs.id].value).orFail("audit_logs.id"),
    actor = this[AuditLogs.actor],
    action = AuditAction.valueOf(this[AuditLogs.action]),
    entity = this[AuditLogs.entity],
    entityId = Identifier.of(this[AuditLogs.entityId]).orFail("audit_logs.entity_id"),
    actorUserId = this[AuditLogs.actorUserId]?.let { PersonId.of(it.value).orFail("audit_logs.actor_user_id") },
    timestamp = this[AuditLogs.timestamp],
    metadata = this[AuditLogs.metadata].toMetadata(),
)

/**
 * The map as JSON, refused outright if it looks like it is carrying a credential.
 *
 * Goes through a real serializer rather than string concatenation. A hand-rolled encoder passes a
 * round-trip test — it decodes its own corruption back to the original — and produces unparseable
 * text the first time an HR officer types a quotation mark or a comma into a rejection reason.
 * `AuditEntryMapperTest` hands the output to an independent parser for exactly that reason.
 */
fun Map<String, String>.toMetadataJson(): String {
    refuseCredentials()
    return JsonObject(mapValues { (_, value) -> JsonPrimitive(value) }).toString()
}

/** Throws on anything that is not a flat JSON object of strings — a row nobody could have written. */
fun String.toMetadata(): Map<String, String> =
    Json.parseToJsonElement(this).jsonObject.mapValues { (_, value) -> value.jsonPrimitive.content }

/**
 * Refuses metadata that looks like it is carrying a credential (PRD 12, SEC-01).
 *
 * Two layers, and they are worth very different amounts.
 *
 * **The key denylist is the control.** A credential reaches an audit row through a key somebody
 * names for it — `mapOf("pin" to pin.value)`, typed by a use-case author who is thinking about the
 * trail rather than about the PIN. Deliberately over-broad: `token_digest` is refused too, because
 * nothing in Phase 1 has cause to put a digest in an audit row, and the escape hatch is to rename
 * the key.
 *
 * **The value-shape rule is a tripwire, not a control.** A token and a digest are both 43-character
 * unpadded base64url, so a long run of that alphabet carrying all three character classes is
 * credential-shaped and nothing legitimate here looks like it — a reason is prose, an identifier is
 * 8 or 12 characters, a verification method is SCREAMING_SNAKE with no digits. It will not catch a
 * lowercase hex digest; the key denylist is what catches that.
 *
 * **A bare six-digit rule was considered and rejected.** `AccessPin` is `^\d{6}$`, so it is the
 * obvious missing check — but `size_bytes` of `204800` is also six digits, and ERT-810's
 * `DOCUMENT_DOWNLOADED` metadata will carry exactly that. Adding the rule later would break a
 * ticket nobody has written yet. `AuditEntryMapperTest` pins the limit so it is documented rather
 * than rediscovered.
 *
 * A `require`, not a `DomainResult`: [com.pgsystem.employee.requirement.tracker.domain.port.AuditLog]
 * returns `Unit`, and a PIN in a metadata map is a programming error no user can produce.
 */
private fun Map<String, String>.refuseCredentials() {
    forEach { (key, value) ->
        val lowered = key.lowercase()
        val named = CREDENTIAL_KEY_FRAGMENTS.firstOrNull { it in lowered }
        require(named == null) {
            "Audit metadata key '$key' names a credential ('$named'). PRD 12: a credential is never logged."
        }
        require(!value.isCredentialShaped()) {
            "Audit metadata value under '$key' is credential-shaped (${value.length} characters of " +
                "mixed-case base64url). PRD 12: a credential is never logged."
        }
    }
}

private fun String.isCredentialShaped(): Boolean =
    length >= CREDENTIAL_VALUE_LENGTH &&
        all { it.isLetterOrDigit() && it.code < 128 || it == '_' || it == '-' } &&
        any { it.isLowerCase() } &&
        any { it.isUpperCase() } &&
        any { it.isDigit() }

private val CREDENTIAL_KEY_FRAGMENTS =
    listOf("pin", "token", "password", "passcode", "secret", "otp", "credential")

/** A token and an HMAC digest are both 43 characters; 32 leaves room without reaching prose. */
private const val CREDENTIAL_VALUE_LENGTH = 32

private fun <T> DomainResult<T>.orFail(column: String): T = when (this) {
    is DomainResult.Ok -> value
    is DomainResult.Err -> error("$column holds a value that is not a valid identifier: ${error.code}")
}
