package com.agentflow.common.error;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ErrorCodeTest {

    @Test
    void allCodesMatchPattern() {
        for (ErrorCode c : ErrorCode.values()) {
            assertTrue(c.code().matches("^[A-Z]{2,4}-[A-Z_]+-[0-9]{3}$"),
                    "malformed: " + c.code());
            assertTrue(c.problemType().startsWith("https://agentflow.example/problems/"));
        }
    }

    @Test
    void findByCode() {
        assertEquals(ErrorCode.WF_NOT_FOUND_001, ErrorCode.find("WF-NOT_FOUND-001").orElseThrow());
        assertTrue(ErrorCode.find("NOPE").isEmpty());
    }
}
