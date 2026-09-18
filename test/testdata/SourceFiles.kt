package com.pgsystem.employee.requirement.tracker.testdata

import java.io.File

/**
 * Reading a source file as text, for the guards that cannot be written any other way.
 *
 * Two rules in this codebase are about what a file **does not contain** — the audit adapter offers
 * no mutation path (ERT-330), and the settings adapter never constructs a `LinkPolicy` from its
 * Kotlin defaults (ERT-310). Neither is reachable by reflection: the first risk is a private helper,
 * and the second is a default that produces the identical value the database holds. `ArchitectureTest`
 * already reads sources this way; these helpers let a repository test do it without duplicating the
 * walk.
 *
 * [codeOf] strips comments, which is not fussiness — a KDoc explaining that `update(` is banned
 * contains the literal `update(`, and a guard that read it would fail on its own documentation.
 */
fun sourceOf(relativePath: String): String = projectFile(relativePath).readText()

/**
 * A path relative to the project root, resolved the same way [sourceOf] resolves one.
 *
 * `ApiContractsTest` needs the **directory** rather than a known file — it sweeps every contract in
 * `apicontracts/`, because the set grows with each module and a guard listing them by name would be
 * the hand-kept copy it exists to make unnecessary.
 */
fun projectFile(relativePath: String): File = File(sourceRoot, relativePath)

fun codeOf(relativePath: String): String = sourceOf(relativePath).withoutComments()

fun String.withoutComments(): String =
    replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
        .lineSequence()
        .joinToString("\n") { it.substringBefore("//") }

private val sourceRoot: File
    get() = generateSequence(File(".").absoluteFile) { it.parentFile }
        .first { File(it, "module.yaml").exists() }
