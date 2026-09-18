package com.pgsystem.employee.requirement.tracker

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import com.pgsystem.employee.requirement.tracker.testdata.projectFile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test

/**
 * The front-end API contracts (ERT-1145).
 *
 * [`apicontracts/`](../apicontracts/README.md) holds one contract per module, written as the module
 * lands. Each carries what the generated spec cannot: worked request and response bodies, the failure
 * `code` a client branches on, and the order to call things in. That is exactly the content a
 * generator cannot hold, which is also why nothing but a guard keeps the set complete —
 * `api-contract.md` has carried the "declare your schema" rule in prose since ERT-145 and five routes
 * broke it anyway (ERT-146). Prose does not stop the sixth.
 *
 * **The route list is read from the generated spec, not from a list kept here.** The spec is produced
 * from the live route tree by `OpenApiDocSource.Routing`, so a route cannot exist without appearing in
 * it — and therefore cannot exist without this test demanding a section for it somewhere in
 * `apicontracts/`. A hand-kept list would be a third copy of the endpoint set and would drift from
 * both.
 *
 * **`/swagger/documentation.yaml` serves JSON despite its extension**, which is why this parses rather
 * than scanning lines. Verified against the running server, not assumed: an earlier YAML line-matcher
 * silently matched nothing, which is the failure the vacuity test below exists to make loud.
 *
 * The last test is the one that earns its place. This suite's own history records six occasions where
 * a test passed while proving nothing — a `sort_order` coincidence, a single seeded row, a test
 * written specifically to prevent the problem, two more in a class whose comment cited the first
 * three, and a "does not contain" assertion that was testing H2's error text. Every assertion here is
 * over a collection built by a regex, and a regex that matches nothing is green.
 */
class ApiContractsTest {

    @Test
    fun `api contracts - every mounted api route - has a section in some contract`() = testApplication {
        application { rootModule() }

        val contracts = contractText()

        val undocumented = apiOperationsFrom(specText()).filterNot { contracts.contains("`$it`") }

        undocumented.shouldBeEmpty()
    }

    @Test
    fun `api contracts - every json sample - is parseable json`() {
        // A sample is hand-edited far more often than it is generated -- a field renamed in a DTO gets
        // renamed here by a person, and a trailing comma or a lost brace is the result. Parsing is the
        // cheapest check that the thing a reader is about to copy actually is JSON.
        val unparseable = jsonSamples().filterNot { runCatching { Json.parseToJsonElement(it) }.isSuccess }

        unparseable.shouldBeEmpty()
    }

    @Test
    fun `api contracts - every enveloped sample - carries a result the envelope allows`() {
        // `result` is derived from the status class by `resultFor` and never set at a call site, so a
        // sample carrying a fourth value documents a response this application cannot produce.
        val allowed = setOf("success", "fail", "error")

        val wrong = parsedSamples()
            .mapNotNull { it["result"]?.jsonPrimitive?.content }
            .filterNot { it in allowed }

        wrong.shouldBeEmpty()
    }

    @Test
    fun `api contracts - every failure sample - names a code a client can branch on`() {
        // `message` is display text a client may replace; `code` is the contract. A failure sample with
        // no code documents a response a client cannot act on.
        val codeless = parsedSamples()
            .filter { it["result"]?.jsonPrimitive?.content in setOf("fail", "error") }
            .filter { (it["error"] as? JsonObject)?.get("code") == null }

        codeless.shouldBeEmpty()
    }

    @Test
    fun `api contracts - the guard itself - is not vacuous`() = testApplication {
        // Four assertions above are over collections a regex built, and an empty collection satisfies
        // every one of them. Both sweeps are pinned to a floor rather than to an exact count, so adding
        // an endpoint or a sample does not fail this -- losing the ability to see them does.
        application { rootModule() }

        val operations = apiOperationsFrom(specText())

        operations.size shouldBeGreaterThanOrEqual MOUNTED_API_OPERATIONS
        jsonSamples().size shouldBeGreaterThanOrEqual DOCUMENTED_SAMPLES
        contractFiles().size shouldBeGreaterThanOrEqual MODULE_CONTRACTS
        operations.all { it.startsWith("GET /api") || it.startsWith("POST /api") } shouldBe true
    }

    private suspend fun ApplicationTestBuilder.specText(): String {
        val response = client.get(SPEC_PATH)
        response.status shouldBe HttpStatusCode.OK
        return response.bodyAsText()
    }

    /** Every `*_API_CONTRACT.md`, plus the conventions the modules share rather than repeat. */
    private fun contractFiles(): List<java.io.File> =
        projectFile(CONTRACTS_DIR).listFiles { f -> f.name.endsWith(".md") }.orEmpty().sortedBy { it.name }

    private fun contractText(): String = contractFiles().joinToString("\n") { it.readText() }

    /**
     * Every `/api` operation the spec publishes, as `METHOD /path` — the form a contract writes its
     * endpoint headings in, so a match is a substring test rather than a second parser.
     *
     * `/health` is excluded: it is outside `/api` and outside the envelope, and it belongs to the
     * conventions file's operational table rather than to a module contract.
     */
    private fun apiOperationsFrom(spec: String): List<String> =
        Json.parseToJsonElement(spec).jsonObject["paths"]!!.jsonObject
            .filterKeys { it.startsWith("/api") }
            .flatMap { (path, operations) ->
                operations.jsonObject.keys.filter { it in HTTP_METHODS }.map { "${it.uppercase()} $path" }
            }
            .sorted()

    /** Every fenced `json` block, which is where a reader copies a payload from. */
    private fun jsonSamples(): List<String> =
        JSON_FENCE.findAll(contractText()).map { it.groupValues[1].trim() }.toList()

    private fun parsedSamples(): List<JsonObject> =
        jsonSamples().mapNotNull { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() }

    private companion object {
        const val CONTRACTS_DIR = "apicontracts"
        const val SPEC_PATH = "/swagger/documentation.yaml"

        /**
         * Floors, not counts. Eleven `/api` operations across five module contracts today, plus the
         * shared conventions file; all three rise as Phase 1 lands, and they rise only when someone
         * chooses to raise them. Raised by ERT-450, which added `POST /api/employees`.
         */
        const val MOUNTED_API_OPERATIONS = 11
        const val DOCUMENTED_SAMPLES = 20
        const val MODULE_CONTRACTS = 6

        val HTTP_METHODS = setOf("get", "post", "put", "patch", "delete")

        /**
         * A fenced `json` block, **including an indented one**. A fence inside a numbered list is
         * indented to match the item, and a pattern anchored to a closing fence in column zero skips
         * its closing line, matches lazily on to the *next* one, and swallows the prose between --
         * which then fails to parse and reports the wrong sample. Found by running it.
         */
        val JSON_FENCE = Regex(
            "^[ \\t]*```json[ \\t]*\\n(.*?)\\n[ \\t]*```",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.MULTILINE),
        )
    }
}
