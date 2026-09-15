package com.pgsystem.employee.requirement.tracker.core.crypto

/**
 * A reproducible keyed digest of a bearer token — the upload-link token and the portal session
 * token.
 *
 * Separate from [Hasher] because the two credentials are used differently. A token is **looked up**:
 * `UploadLinkRepository.findByTokenHash` resolves a link by its digest and `upload_links.token_hash`
 * carries a unique index, so digesting the same token twice must yield the same string. A PIN is
 * **verified** against one already-located row, so its hash may — and should — be salted.
 *
 * Reproducibility is why this is not, and must not become, a work-factor hash. The compensating
 * property is entropy: a token carries 256 bits, so there is no offline guessing attack for a work
 * factor to slow down. Keying the digest with a server-side pepper is what stops a leaked database
 * from being digested against a candidate list.
 *
 * Implementations must be safe to call concurrently — every portal request goes through one.
 */
fun interface TokenDigest {
    fun digest(token: String): String
}
