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
        if (key !in EXEMPT_METADATA_KEYS) {
            val lowered = key.lowercase()
            val named = CREDENTIAL_KEY_FRAGMENTS.firstOrNull { it in lowered }
            require(named == null) {
                "Audit metadata key '$key' names a credential ('$named'). PRD 12: a credential is never logged."
            }
        }
        if (key !in FREE_TEXT_METADATA_KEYS) {
            require(!value.isCredentialShaped()) {
                "Audit metadata value under '$key' is credential-shaped (${value.length} characters of " +
                    "mixed-case base64url). PRD 12: a credential is never logged."
            }
        }
    }
}

/**
 * The handful of metadata keys the application generates for itself that collide with the denylist
 * (SEC-38, 2026-09-18).
 *
 * `updateLinkPolicy` names each changed setting in the trail as `<key>.old` / `<key>.new`, and two
 * of the nine §6.4 keys are `portal.pin_attempts_before_lockout` and
 * `portal.pin_failures_before_suspend`. The guard refused both for precisely the reason it was
 * built, and the two settings were permanently unsaveable: an `IllegalArgumentException` out of a
 * method whose declared contract is `DomainResult<Unit>`.
 *
 * **Three things bound the exemption, and each is the difference between a fix and a hole.**
 *
 * It is an *exact match* against a closed set, not a pattern. A regex over `link.*` or `portal.*`
 * would be a wider hole than the one it patches, and `portal.pin_attempts_before_lockout` without a
 * suffix is still refused — nothing generates that key, so nothing needs it.
 *
 * It skips the *key* check only. [isCredentialShaped] still runs on the value of every entry,
 * including these, so an exempt key cannot become a channel for a credential-shaped value.
 *
 * And the settings it covers are [CREDENTIAL_FRAGMENT_EXEMPT_SETTINGS] — **declared** one by one
 * beside the enum rather than derived from it, so a tenth colliding setting fails the build instead
 * of exempting itself. That file carries the argument.
 *
 * **C25 is untouched.** That is the same guard's *value*-side false positive — a typed duplicate
 * reason with no spaces reading as credential-shaped — and it stays pinned exactly as it was. The
 * two are not the same call: C25's trap is reachable-but-rare and belongs to text a user typed;
 * this one was unconditional on two of nine inputs and belongs to a key this codebase chose.
 */
private val EXEMPT_METADATA_KEYS: Set<String> =
    CREDENTIAL_FRAGMENT_EXEMPT_SETTINGS.flatMap { it.auditMetadataKeys().toList() }.toSet()

/**
 * The metadata keys whose value is free text, and which are therefore exempt from the value-shape
 * tripwire (C25, closed by ERT-450).
 *
 * **This is SEC-38's fix applied to the other side of the same guard, and it keeps all three of
 * SEC-38's bounds.** Exact matches against a closed set, never a pattern. **Declared** one by one
 * rather than derived from anything, so a second free-text key is refused until somebody chooses to
 * add it — deriving the list is what would make the guard exempt its own next hole. And it skips the
 * **value** check only: [CREDENTIAL_KEY_FRAGMENTS] still runs on every key here, so `reason_pin`
 * gets no relief from appearing beside `reason`.
 *
 * **Why the tripwire was wrong about this key rather than merely inconvenient.** The value rule
 * rests on the premise stated above — *"a reason is prose"* — and prose has spaces, so one space is
 * all that saved a value. `ReplacingRecord2026ForJoseDelaCruz` is a reason an officer would
 * plausibly type, satisfies every clause of [isCredentialShaped], and threw an
 * `IllegalArgumentException` out through `ExposedAuditLog.record` **after the hire was already
 * written** — a user-reachable 500 produced by text a user chose. For a field a human types freely
 * the premise is simply false, and the tripwire cannot protect it in any case: anyone pasting a
 * credential into a reason box defeats it by adding a space.
 *
 * The control is untouched. `reason` names no credential, every other key still has its value
 * checked, and `AuditEntryMapperTest` pins both halves — the exempt key accepts the value, and the
 * same value under `note`, `email` or `duplicateOf` is still refused, so the exemption cannot be
 * mistaken for having deleted the rule.
 *
 * Two writers produce this key today and both are free text: `DUPLICATE_EMAIL_OVERRIDDEN` carries
 * the reason HR typed, and `INVITATION_DELIVERY_FAILED` carries the failure string the notifier
 * returned.
 */
val FREE_TEXT_METADATA_KEYS: Set<String> = setOf("reason")

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
