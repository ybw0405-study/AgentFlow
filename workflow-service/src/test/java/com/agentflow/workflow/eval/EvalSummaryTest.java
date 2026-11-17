package com.agentflow.workflow.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.agentflow.workflow.eval.EvalMapper.RunStats;
import org.junit.jupiter.api.Test;

/** A/B 汇总规则：通过率优先，其次耗时，再次 token。 */
class EvalSummaryTest {

    @Test
    void higherPassRateWins() {
        var summary = EvalService.summarise(
                new RunStats(10, 8, 2, 120.0, 300.0),
                new RunStats(10, 9, 1, 500.0, 900.0));
        assertEquals("B", summary.get("winner"));
        assertEquals(0.1, summary.get("deltaPassRate"));
    }

    @Test
    void withinHalfPointFallsBackToLatency() {
        var summary = EvalService.summarise(
                new RunStats(10, 9, 1, 400.0, 300.0),
                new RunStats(10, 9, 1, 120.0, 800.0));
        assertEquals("B", summary.get("winner"));
    }

    @Test
    void equalLatencyFallsBackToTokens() {
        var summary = EvalService.summarise(
                new RunStats(10, 9, 1, 100.0, 900.0),
                new RunStats(10, 9, 1, 120.0, 300.0));
        assertEquals("B", summary.get("winner"));
    }

    @Test
    void identicalRunsTie() {
        var summary = EvalService.summarise(
                new RunStats(5, 5, 0, 100.0, 200.0),
                new RunStats(5, 5, 0, 100.0, 200.0));
        assertEquals("TIE", summary.get("winner"));
    }

    @Test
    void emptyRunsDoNotDivideByZero() {
        var summary = EvalService.summarise(new RunStats(0, 0, 0, null, null),
                new RunStats(0, 0, 0, null, null));
        assertEquals(0.0, summary.get("passRateA"));
        assertEquals("TIE", summary.get("winner"));
    }
}
