package com.clarity.life.ui.talk

import com.clarity.life.data.entity.PromiseRow
import com.clarity.life.ui.plan.PromisesViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TalkHomeTextTest {

    @Test
    fun theStepLabelDoesNotSayTheUnitTwice() {
        assertEquals("CAF lectures", stepLabel("lectures", "CAF lectures"))
        assertEquals("CAF lectures", stepLabel("lecture", "CAF lectures"))
        assertEquals("Reading", stepLabel("", "Reading"))
        assertEquals("pages · Reading", stepLabel("pages", "Reading"))
        // A word that only starts with the unit is not the unit: "min" is not in "Mindfulness".
        assertEquals("min · Mindfulness", stepLabel("min", "Mindfulness"))
    }

    @Test
    fun aDuePromiseIsNotAlsoTheNextPromise() {
        fun item(id: Long) = PromisesViewModel.Item(
            PromiseRow(id = id, text = "promise $id", dueAt = 0, dueText = "", createdAt = 0),
            kicker = "",
            time = "",
        )
        // Due now (it has the due card at the top) and nothing later: no Next promise row.
        assertNull(nextPromise(PromisesViewModel.State(due = listOf(item(1)))))
        // Due now and one later: the row shows the later one.
        assertEquals(2L, nextPromise(PromisesViewModel.State(due = listOf(item(1)), coming = listOf(item(2))))?.row?.id)
    }
}
