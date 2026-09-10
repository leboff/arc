package com.daydreamvr.player.state

import org.junit.Assert.assertEquals
import org.junit.Test

class MediaListScrollReducerTest {
    @Test fun windowRetainsSevenRowsWhenFocusCrossesEighth() {
        assertEquals(0, AppStateMachine.ensureVisible(7, 0, 20, 8))
        assertEquals(1, AppStateMachine.ensureVisible(8, 0, 20, 8))
        assertEquals(0, AppStateMachine.ensureVisible(0, 9, 20, 8))
    }

    @Test fun emptyAndShortListsClampToZero() {
        assertEquals(0, AppStateMachine.ensureVisible(0, 4, 0, 8))
        assertEquals(0, AppStateMachine.ensureVisible(4, 4, 5, 8))
    }
}
