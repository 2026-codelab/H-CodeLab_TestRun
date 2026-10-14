package com.project.handongjudge.testrun.runner.judge;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class TimeLimitsTest {

    @Test
    void defaultOvershootIsMaxOfOneSecondAndTenPercent() {
        assertEquals(1.0, TimeLimits.overshoot(1.0, "1s|10%"), 1e-9);
        assertEquals(2.0, TimeLimits.overshoot(20.0, "1s|10%"), 1e-9);
        assertEquals(2.0, TimeLimits.hard(1.0, "1s|10%"), 1e-9);
    }

    @Test
    void supportsSumAndMinOperators() {
        assertEquals(3.0, TimeLimits.overshoot(20.0, "1s+10%"), 1e-9);
        assertEquals(1.0, TimeLimits.overshoot(20.0, "1s&10%"), 1e-9);
    }

    @Test
    void argumentMatchesTestcaseRunFormat() {
        assertEquals("1:2,1:2", TimeLimits.argument(1.0, 2.0));
        assertEquals("1.5:2.5,1.5:2.5", TimeLimits.argument(1.5, 2.5));
    }
}
