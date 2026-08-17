package leyline.detekt

/**
 * Callees that declare one leaf test.
 *
 * `test` is Kotest's own FunSpec builder. `session` is the session-tier spec
 * base's builder — it wraps `test` with harness lifecycle and failure
 * diagnostics, so rules that inspect a test body must look through it.
 */
internal val KOTEST_TEST_CALLEES = setOf("test", "session")
