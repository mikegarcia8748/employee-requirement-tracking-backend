package com.pgsystem.employee.requirement.tracker.route.dto

import kotlinx.serialization.Serializable

/**
 * The reference data the add-hire form binds to (ERT-350, PRD 8.1).
 *
 * Id and name, and nothing else — these tables have nothing else, and the point of the endpoints is
 * that HR picks from the real list rather than typing an id. Two resources rather than one combined
 * payload: [ApiMeta.total] means nothing on a heterogeneous body, and 8.11's Phase 2 admin screen
 * edits the employment-type mapping and will want `/api/employment-types` as a resource of its own.
 */
@Serializable
data class DepartmentDto(val id: String, val name: String)

@Serializable
data class EmploymentTypeDto(val id: String, val name: String)
