package com.pgsystem.employee.requirement.tracker.route.hr

import com.pgsystem.employee.requirement.tracker.domain.port.RequirementTemplateRepository
import com.pgsystem.employee.requirement.tracker.route.dto.ApiMeta
import com.pgsystem.employee.requirement.tracker.route.dto.ApiResponse
import com.pgsystem.employee.requirement.tracker.route.dto.RequirementTemplateDto
import com.pgsystem.employee.requirement.tracker.route.mapper.respondOk
import com.pgsystem.employee.requirement.tracker.route.mapper.toDto
import io.ktor.openapi.jsonSchema
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.openapi.describe

/**
 * The requirement catalogue, for the add-hire screen (ERT-340, PRD 8.11).
 *
 * **Deliberately auth-agnostic.** There is no `authenticate` here: the gate is applied once around
 * every HR route in `Routing.kt`. Two reasons, and the second is the load-bearing one. `HR_AUTH`
 * lives in `plugin/`, and the dependency between the packages runs `plugin` → `route` and never the
 * reverse — `ArchitectureTest` fails the build on a route importing `plugin`. And a route that
 * carries its own gate cannot be mounted in a test without the security plugin, which for this
 * application means without a security plugin at all, which keeps a payload assertion about the
 * payload. (Until ERT-190 it also meant without any token a test could mint; that is no longer
 * true — `testdata/HrTokens` mints one — but mounting the handler bare is still the right shape.)
 *
 * The repository arrives as a parameter rather than through `inject()` for the same reason: the
 * handler is then a function of its inputs, and the container is `Routing.kt`'s concern.
 *
 * Reads the whole catalogue, never one employment type's set. Filtering by employment type is out of
 * scope until the add-hire screen needs it — and `findActiveForEmploymentType` is the method the
 * snapshot rule reserves for hire creation (PRD 5), so borrowing it here would blur the one call
 * 8.11 depends on.
 */
fun Route.requirementTemplateRoutes(templates: RequirementTemplateRepository) {
    get("/api/requirement-templates") {
        val catalogue = templates.findAll().map { it.toDto() }

        call.respondOk(catalogue, meta = ApiMeta(total = catalogue.size))
    }.describe {
        summary = "List the requirement catalogue"
        description = """
            The active document types, in `sort_order`.

            The order is an HR decision rather than a consequence of insertion: it is the sequence a
            new hire reads the checklist in on a phone (PRD 5, 8.11).

            An empty catalogue is a `200` with an empty list, never a `404`.

            The catalogue is currently PRD Appendix A, seeded as **illustrative pending open
            question 2**. It is data, so replacing it is a seed change rather than a code change.
        """.trimIndent()
        operationId = "listRequirementTemplates"
        tag("Requirement catalogue")
        responses {
            response(200) {
                description = "The active catalogue, in sort order."
                schema = jsonSchema<ApiResponse<List<RequirementTemplateDto>>>()
            }
        }
    }
}
