package com.giantbomb.tv.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** The lift follows the ring's progress, so a resting card is always back at 1.0. */
class CardFocusLiftTest {

    @Test
    fun restingCardIsNotScaled() {
        assertEquals(1f, CardFocus.liftScale(0f, CardFocus.FOCUSED_SCALE), 0f)
        assertEquals(1f, CardFocus.liftScale(0f, 1.06f), 0f)
    }

    @Test
    fun focusedCardReachesItsFullLift() {
        assertEquals(1.10f, CardFocus.liftScale(1f, CardFocus.FOCUSED_SCALE), 1e-6f)
        assertEquals(1.06f, CardFocus.liftScale(1f, 1.06f), 1e-6f)
    }

    @Test
    fun noLiftCardsStayAtOne() {
        // Downloads rows ring their cards without lifting them.
        assertEquals(1f, CardFocus.liftScale(0.5f, 1f), 0f)
    }

    @Test
    fun liftIsInStepWithTheRing() {
        assertEquals(1.05f, CardFocus.liftScale(0.5f, CardFocus.FOCUSED_SCALE), 1e-6f)
    }
}
