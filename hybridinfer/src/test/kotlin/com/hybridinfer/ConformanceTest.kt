package com.hybridinfer

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Loads the shared conformance vectors (wired in as a test resource from
 * ../conformance in build.gradle.kts) and asserts the Kotlin implementation
 * passes every case - the same file the Python suite runs against.
 */
class ConformanceTest {

    @Test
    fun passesSharedVectors() {
        val stream = javaClass.classLoader.getResourceAsStream("vectors.json")
            ?: error("vectors.json not found on the test classpath")
        val json = stream.bufferedReader().use { it.readText() }
        val failures = Conformance.run(json)
        assertTrue(
            "conformance failures (${failures.size}):\n" + failures.joinToString("\n"),
            failures.isEmpty(),
        )
    }
}
