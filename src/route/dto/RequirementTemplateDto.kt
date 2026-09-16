package com.pgsystem.employee.requirement.tracker.route.dto

import kotlinx.serialization.Serializable

/**
 * One document type, as HR sees it (ERT-340, PRD 8.11).
 *
 * **The omissions are the control.** A serialised domain model publishes whatever fields it happens
 * to carry, so the DTO boundary is a security decision rather than tidiness — 8.6 forbids the portal
 * ever returning an original filename, and the only thing that makes that structural is a hand-
 * written wire type per surface.
 *
 * Four of `RequirementTemplate`'s nine fields stay behind. `isActive` is always `true` on this route,
 * and publishing a constant invites a client to filter on it and then to ask for the inactive ones.
 * `expires`, `validityMonths` and `renewalLeadDays` are Phase 4 validity-window internals that 9.3
 * will change; nothing on the add-hire screen reads them. `RequirementTemplateRoutesTest` asserts
 * their absence, so adding one back is a decision rather than an accident.
 */
@Serializable
data class RequirementTemplateDto(
    val id: String,
    val name: String,
    val instructions: String,
    val isRequired: Boolean,
    val sortOrder: Int,
)
