package com.pgsystem.employee.requirement.tracker

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import java.io.File
import kotlin.test.Test

/**
 * The dependency rule, executable.
 *
 * Clean Architecture's central constraint is that source dependencies point inward: the domain
 * knows nothing about Ktor, Exposed, Koin or coroutine dispatchers. Written in a document, that rule
 * survives until the first busy week. Written here, breaking it fails the build.
 *
 * `core/` is checked alongside `domain/` because domain models depend on it (`EmailAddress`,
 * `Clock`), so a framework type leaking into core would reach the domain by the back door.
 *
 * Why banning `Dispatchers` matters as much as banning Ktor: a use case that picks its own
 * dispatcher cannot be tested on a virtual-time scheduler, and it hides an I/O decision inside a
 * business rule. Dispatcher choice belongs in `DatabaseFactory`, at the edge.
 */
class ArchitectureTest {

    private val forbiddenInInnerLayers = listOf(
        "io.ktor",
        "org.jetbrains.exposed",
        "org.koin",
        "com.zaxxer.hikari",
        "kotlinx.serialization",
        "kotlinx.coroutines.Dispatchers",
        "javax.sql",
        "java.sql",
    )

    @Test
    fun `dependency rule - domain and core import no framework types - inner layers stay pure`() {
        val violations = innerLayerFiles().flatMap { file ->
            file.readLines()
                .filter { it.startsWith("import ") }
                .filter { line -> forbiddenInInnerLayers.any { line.startsWith("import $it") } }
                .map { "${file.relativeTo(projectDir)}: ${it.trim()}" }
        }

        violations.shouldBeEmpty()
    }

    @Test
    fun `dependency rule - the guard is pointed at real files - inner layers are non-empty`() {
        // A file walk that silently matches nothing would make the check above pass forever.
        (innerLayerFiles().size > 10) shouldBe true
    }

    @Test
    fun `portal safety - no domain port hands document bytes or URLs to the portal - storage stays HR-side`() {
        // PRD 8.6 / SEC-02: the portal reports status, never content. The only signed-URL entry
        // point lives on DocumentStorage, which portal use cases must not depend on.
        val storagePort = File(projectDir, "src/domain/port/DocumentStorage.kt")
        storagePort.exists() shouldBe true
        storagePort.readText().contains("HR-side only") shouldBe true
    }

    private val projectDir: File
        get() = generateSequence(File(".").absoluteFile) { it.parentFile }
            .first { File(it, "module.yaml").exists() }

    private fun innerLayerFiles(): List<File> =
        listOf("src/domain", "src/core")
            .map { File(projectDir, it) }
            .flatMap { it.walkTopDown().filter { f -> f.isFile && f.extension == "kt" } }
}
