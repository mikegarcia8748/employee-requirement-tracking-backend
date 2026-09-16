package com.pgsystem.employee.requirement.tracker.route.mapper

import com.pgsystem.employee.requirement.tracker.domain.model.Department
import com.pgsystem.employee.requirement.tracker.domain.model.EmploymentType
import com.pgsystem.employee.requirement.tracker.route.dto.DepartmentDto
import com.pgsystem.employee.requirement.tracker.route.dto.EmploymentTypeDto

/** [Department] → [DepartmentDto] (ERT-350). One direction: Phase 1 never accepts one over the wire. */
fun Department.toDto(): DepartmentDto = DepartmentDto(id = id.value, name = name)

/** [EmploymentType] → [EmploymentTypeDto] (ERT-350). */
fun EmploymentType.toDto(): EmploymentTypeDto = EmploymentTypeDto(id = id.value, name = name)
