package net.singularity.jetta.test

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class ClassificationTest {

    private fun xfail(status: TestStatus) = XfailEntry("dummy", status, "REASON:id", "")

    @Test
    fun `pass without xfail is PASS`() {
        assertEquals(Classification.PASS, classify(TestStatus.PASS, null))
    }

    @Test
    fun `pass with xfail is UNEXPECTED_PASS`() {
        assertEquals(Classification.UNEXPECTED_PASS, classify(TestStatus.PASS, xfail(TestStatus.ASSERT_FAIL)))
        assertEquals(Classification.UNEXPECTED_PASS, classify(TestStatus.PASS, xfail(TestStatus.RUN_EXCEPTION)))
        assertEquals(Classification.UNEXPECTED_PASS, classify(TestStatus.PASS, xfail(TestStatus.COMPILE_FAIL)))
    }

    @Test
    fun `fail without xfail is UNEXPECTED_FAIL`() {
        assertEquals(Classification.UNEXPECTED_FAIL, classify(TestStatus.ASSERT_FAIL, null))
        assertEquals(Classification.UNEXPECTED_FAIL, classify(TestStatus.RUN_EXCEPTION, null))
        assertEquals(Classification.UNEXPECTED_FAIL, classify(TestStatus.COMPILE_FAIL, null))
    }

    @Test
    fun `fail with matching xfail is EXPECTED_FAIL`() {
        assertEquals(Classification.EXPECTED_FAIL, classify(TestStatus.ASSERT_FAIL, xfail(TestStatus.ASSERT_FAIL)))
        assertEquals(Classification.EXPECTED_FAIL, classify(TestStatus.RUN_EXCEPTION, xfail(TestStatus.RUN_EXCEPTION)))
        assertEquals(Classification.EXPECTED_FAIL, classify(TestStatus.COMPILE_FAIL, xfail(TestStatus.COMPILE_FAIL)))
    }

    @Test
    fun `fail with mismatched xfail is REGRESSION`() {
        assertEquals(Classification.REGRESSION, classify(TestStatus.ASSERT_FAIL, xfail(TestStatus.COMPILE_FAIL)))
        assertEquals(Classification.REGRESSION, classify(TestStatus.RUN_EXCEPTION, xfail(TestStatus.ASSERT_FAIL)))
        assertEquals(Classification.REGRESSION, classify(TestStatus.COMPILE_FAIL, xfail(TestStatus.RUN_EXCEPTION)))
    }
}
