package cz.teply.sheetset.pdf

import org.junit.Assert.assertThrows
import org.junit.Test

class PdfExportBudgetTest {
    @Test
    fun budgetIncludesAnExtraLiveBitmapAndRejectsTheNextPage() {
        val budget = PdfExportBudget(640_000L)
        budget.include(100, 100)
        assertThrows(PdfExportLimitException::class.java) { budget.include(1, 1) }
    }

    @Test
    fun hugePageDimensionsStayBoundedAndNegativeAvailableMemoryRejects() {
        val budget = PdfExportBudget(Long.MAX_VALUE)
        repeat(4) { budget.include(Int.MAX_VALUE, Int.MAX_VALUE) }
        assertThrows(PdfExportLimitException::class.java) {
            budget.include(Int.MAX_VALUE, Int.MAX_VALUE)
        }
        assertThrows(PdfExportLimitException::class.java) { PdfExportBudget(-1L).include(1, 1) }
    }
}
