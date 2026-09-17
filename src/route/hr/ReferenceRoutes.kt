package com.pgsystem.employee.requirement.tracker.route.hr

import com.pgsystem.employee.requirement.tracker.domain.port.ReferenceDataRepository
import com.pgsystem.employee.requirement.tracker.route.dto.ApiMeta
import com.pgsystem.employee.requirement.tracker.route.dto.ApiResponse
import com.pgsystem.employee.requirement.tracker.route.dto.DepartmentDto
import com.pgsystem.employee.requirement.tracker.route.dto.EmploymentTypeDto
import com.pgsystem.employee.requirement.tracker.route.auth.hrUserOrRefuse
import com.pgsystem.employee.requirement.tracker.route.mapper.respondOk
import com.pgsystem.employee.requirement.tracker.route.mapper.toDto
import io.ktor.openapi.jsonSchema
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.openapi.describe

/**
 * The reference data behind the add-hire form (ERT-350, PRD 8.1).
 *
 * **Not in PRD Appendix B.** Neither the PRD nor the API contract named a reference-data endpoint,
 * which is the same gap ERT-350 found in the port set: `Employee` requires a `departmentId` and an
 * `employmentTypeId`, and nothing offered HR a way to discover either. Two resources rather than one
 * `/api/reference-data`, because `meta.total` is meaningless over a heterogeneous payload and every
 * other path in Appendix B is one resource per path. The cost is a second round trip on one form,
 * which an aggregate can answer later by composing these two; the contract document now carries both
 * rows.
 *
 * **Authentication is applied once in `Routing.kt`; the password gate is not** (ERT-1245). The
 * `authenticate(HR_AUTH)` block around every HR route is what makes this file auth-agnostic, and it
 * is easy to read that as covering `hrUserOrRefuse()` too. It does not: that gate is per-handler, and
 * the earlier wording here claimed otherwise — which is how both handlers below shipped without it,
 * letting an account that still owes a password change read the reference data.
 */
fun Route.referenceRoutes(reference: ReferenceDataRepository) {
    get("/api/departments") {
        hrUserOrRefuse() ?: return@get
        val departments = reference.findDepartments().map { it.toDto() }

        call.respondOk(departments, meta = ApiMeta(total = departments.size))
    }.describe {
        summary = "List departments"
        description = """
            Every department, in name order, for the add-hire form.

            Reference data is **seeded, not managed** in Phase 1 — there is no create, update or
            delete. An empty list is a `200`, never a `404`.
        """.trimIndent()
        operationId = "listDepartments"
        tag("Reference data")
        responses {
            response(200) {
                description = "Every department, in name order."
                schema = jsonSchema<ApiResponse<List<DepartmentDto>>>()
            }
        }
    }

    get("/api/employment-types") {
        hrUserOrRefuse() ?: return@get
        val employmentTypes = reference.findEmploymentTypes().map { it.toDto() }

        call.respondOk(employmentTypes, meta = ApiMeta(total = employmentTypes.size))
    }.describe {
        summary = "List employment types"
        description = """
            Every employment type, in **name** order.

            The order is alphabetical rather than an HR preference, because `employment_types` has no
            `sort_order` column and insertion order is not a decision anyone made. A deliberate
            ordering is an 8.11 change, not a change here.

            The employment type chosen here selects the requirement set a hire is given, and that set
            is **snapshotted at creation** — changing the catalogue afterwards does not move a hire
            already collecting (PRD 5).
        """.trimIndent()
        operationId = "listEmploymentTypes"
        tag("Reference data")
        responses {
            response(200) {
                description = "Every employment type, in name order."
                schema = jsonSchema<ApiResponse<List<EmploymentTypeDto>>>()
            }
        }
    }
}
