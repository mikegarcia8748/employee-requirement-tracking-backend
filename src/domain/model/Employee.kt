package com.pgsystem.employee.requirement.tracker.domain.model

import com.pgsystem.employee.requirement.tracker.core.value.EmailAddress
import com.pgsystem.employee.requirement.tracker.core.value.EntityId
import com.pgsystem.employee.requirement.tracker.core.value.PersonId
import java.time.Instant

/** A hired person being onboarded. Created by HR, individually or via CSV import (PRD 5). */
data class Employee(
    val id: PersonId,
    val firstName: String,
    val middleInitial: String?,
    val lastName: String,
    val departmentId: EntityId,
    val position: String,
    val employmentTypeId: EntityId,
    val email: EmailAddress,
    val packetStatus: PacketStatus,
    val submittedAt: Instant?,
    /** True when HR force-submitted on the employee's behalf. Such a packet carries no attestation. */
    val submittedByHr: Boolean,
    val attestation: Attestation?,
    /** The PRD 8.5 physical checkpoint. Absent means COMPLETE is not identity assurance. */
    val originalsSightedAt: Instant?,
    val originalsSightedBy: String?,
    val anomalyFlags: Set<AnomalyFlag>,
    val completedAt: Instant?,
    val createdAt: Instant,
    val createdBy: String,
) {
    /**
     * PRD 7.1: no version may be purged while a flag is open. The superseded version is the
     * evidence — an attacker with portal access could otherwise erase a forgery by uploading five
     * innocuous replacements.
     */
    val retentionFrozen: Boolean get() = anomalyFlags.isNotEmpty()
}

/**
 * The employee's declaration at submission (PRD 7.2).
 *
 * The text is versioned because the wording will change and you will need to know which version a
 * given employee accepted; without that, a surfacing forgery meets no record of the employee having
 * claimed anything.
 */
data class Attestation(
    val textVersion: String,
    val attestedAt: Instant,
    val attestedIp: String,
)

/** Signals that route a record into the PRD 8.13 exception report and freeze retention. */
enum class AnomalyFlag {
    /** Same requirement rejected 3 times — usually unclear instructions, an HR problem to fix. */
    REPEATED_REJECTIONS,

    /** Portal accessed from more than one country or more than N distinct IPs (PRD 8.12). */
    ACCESS_ANOMALY,

    /** A burst of failed PIN entries suspended the link (PRD 6.6). */
    PIN_FAILURE_SUSPENSION,

    /** Another active hire shares this email address (PRD 8.1, SEC-11). */
    SHARED_EMAIL,

    /** One officer created, altered the email, and approved throughout (PRD 8.13, SEC-10). */
    SEPARATION_OF_DUTIES,

    /** Fraud suspected; set by HR. */
    SUSPECTED_FRAUD,
}
