package com.agentflow.workflow.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** 用量占比口径：进度条只认 [0,1]，超额不能超出量程。 */
class AdminUsageControllerTest {

    @Test
    void ratioIsExactForNormalUsage() {
        assertEquals(0.5, AdminUsageController.ratio(500, 1000));
        assertEquals(0.0, AdminUsageController.ratio(0, 1000));
    }

    @Test
    void ratioIsClampedWhenOverQuota() {
        assertEquals(1.0, AdminUsageController.ratio(1500, 1000));
        assertEquals(1.0, AdminUsageController.ratio(10_000_000, 1000));
    }

    @Test
    void ratioIsZeroWhenNoLimitIsSet() {
        assertEquals(0.0, AdminUsageController.ratio(900, Long.MAX_VALUE));
        assertEquals(0.0, AdminUsageController.ratio(900, Integer.MAX_VALUE));
        assertEquals(0.0, AdminUsageController.ratio(900, 0));
    }
}
