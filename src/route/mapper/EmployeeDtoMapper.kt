package com.pgsystem.employee.requirement.tracker.route.mapper

import com.pgsystem.employee.requirement.tracker.core.value.PersonId
import com.pgsystem.employee.requirement.tracker.domain.model.EmployeeRequirement
import com.pgsystem.employee.requirement.tracker.domain.model.HireCreated
import com.pgsystem.employee.requirement.tracker.domain.port.DeliveryResult
import com.pgsystem.employee.requirement.tracker.domain.usecase.CreateHire
import com.pgsystem.employee.requirement.tracker.route.dto.CreateHireRequest
import com.pgsystem.employee.requirement.tracker.route.dto.HireCreatedDto
import com.pgsystem.employee.requirement.tracker.route.dto.HireRequirementDto
import com.pgsystem.employee.requirement.tracker.route.dto.InvitationDto

/** Domain ↔ wire for hire creation (ERT-450). */

/**
 * The body as a command, with the acting user taken from the token rather than from the body.
 *
 * Nothing is parsed or normalised on the way in. `CreateHireUseCase` validates the address, trims the
 * duplicate reason and resolves both reference ids, and every one of those is a rule with a test
 * behind it — repeating any of them here would put a second copy of a business decision in the layer
 * that must hold none.
 */
fun CreateHireRequest.toCommand(actingUserId: PersonId): CreateHire = CreateHire(
    firstName = firstName,
    middleInitial = middleInitial,
    lastName = lastName,
    departmentId = departmentId,
    position = position,
    employmentTypeId = employmentTypeId,
    email = email,
    duplicateReason = duplicateReason,
    actingUserId = actingUserId,
)

fun HireCreated.toDto(): HireCreatedDto = HireCreatedDto(
    id = employee.id.value,
    firstName = employee.firstName,
    middleInitial = employee.middleInitial,
    lastName = employee.lastName,
    departmentId = employee.departmentId.value,
    position = employee.position,
    employmentTypeId = employee.employmentTypeId.value,
    email = employee.email.value,
    packetStatus = employee.packetStatus.name,
    anomalyFlags = employee.anomalyFlags.map { it.name }.sorted(),
    createdAt = employee.createdAt.toString(),
    requirements = requirements.requirements.map { it.toDto() },
    linkExpiresAt = link.expiresAt.toString(),
    invitation = delivery.toDto(),
)

fun EmployeeRequirement.toDto(): HireRequirementDto = HireRequirementDto(
    id = id.value,
    name = nameSnapshot,
    isRequired = isRequiredSnapshot,
    sortOrder = sortOrderSnapshot,
    status = status.name,
)

/**
 * The delivery indicator, branched exhaustively.
 *
 * `when` on the sealed type rather than a null check, so a third [DeliveryResult] case is a compile
 * error here instead of a silently absent key — which is why ERT-434 made it a sealed type rather
 * than a nullable `String` in the first place.
 */
fun DeliveryResult.toDto(): InvitationDto = when (this) {
    DeliveryResult.Sent -> InvitationDto(status = "QUEUED")
    is DeliveryResult.Failed -> InvitationDto(status = "FAILED", reason = reason)
}
