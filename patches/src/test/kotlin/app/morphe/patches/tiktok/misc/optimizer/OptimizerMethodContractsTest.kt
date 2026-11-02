package app.morphe.patches.tiktok.misc.optimizer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OptimizerMethodContractsTest {
    @Test
    fun `only reviewed splash service shapes are accepted`() {
        assertTrue(isReviewedSplashGateShape(listOf(4, 30, 4)))
        assertTrue(isReviewedSplashGateShape(listOf(4, 4)))
        assertFalse(isReviewedSplashGateShape(listOf(4, 30)))
        assertFalse(isReviewedSplashGateShape(listOf(4, 4, 30, 3)))
    }
}
