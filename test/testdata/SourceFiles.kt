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

/**
 * The same text with every string literal emptied (ERT-250, HAR-20).
 *
 * A guard that reads declarations out of source has one blind spot the comment stripper does not
 * cover: `ArchitectureTest` carries its own synthetic fixtures as string literals, so a sweep over
 * `test/` reads `"class X : SomePort {"` as a real declaration and reports the guard's own negative
 * control as a violation. Excluding that one file was the alternative and was declined -- it is a
 * hole keyed on a filename, and the next test to embed a fixture reopens it.
 *
 * Raw strings first, because a `"""` block may contain single quotes that the second pattern would
 * otherwise pair across. The second pattern refuses to cross a newline, so an unbalanced quote left
 * behind by some other stripper spoils at most one line rather than swallowing a file — a Kotlin
 * single-line literal cannot contain a raw newline, so nothing legitimate is lost. Both are replaced
 * by an empty literal rather than deleted, so a declaration is never joined to the token after it.
 */
fun String.withoutStringLiterals(): String =
    replace(Regex("\"\"\".*?\"\"\"", RegexOption.DOT_MATCHES_ALL), "\"\"")
        .replace(Regex("\"(\\\\.|[^\"\\\\\\n])*\""), "\"\"")

private val sourceRoot: File
    get() = generateSequence(File(".").absoluteFile) { it.parentFile }
        .first { File(it, "module.yaml").exists() }
