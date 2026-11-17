package com.agentflow.workflow.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 评测断言语义（纯函数）。 */
class EvalAssertionsTest {

    @Test
    void exactTreatsIntAndDoubleAsEqual() {
        var result = EvalAssertions.evaluate(Map.of("mode", "EXACT"),
                Map.of("score", 12, "ok", true),
                Map.of("score", 12.0, "ok", true));
        assertTrue(result.passed(), result.message());
    }

    @Test
    void exactReportsFirstMismatch() {
        var result = EvalAssertions.evaluate(Map.of("mode", "EXACT"),
                Map.of("answer", "北京"), Map.of("answer", "上海"));
        assertFalse(result.passed());
        assertTrue(result.message().contains("exact mismatch"));
    }

    @Test
    void containsChecksEveryExpectedText() {
        var ok = EvalAssertions.evaluate(Map.of("mode", "CONTAINS"),
                List.of("检索", "要点"),
                Map.of("answer", "先给出要点，再补充检索结果"));
        assertTrue(ok.passed(), ok.message());

        var missing = EvalAssertions.evaluate(Map.of("mode", "CONTAINS"),
                List.of("引用"), Map.of("answer", "没有提到来源"));
        assertFalse(missing.passed());
        assertTrue(missing.message().contains("引用"));
    }

    @Test
    void existsWalksDottedPath() {
        Map<String, Object> actual = Map.of("data", Map.of("items", List.of(Map.of("id", "x"))));
        assertTrue(EvalAssertions.evaluate(Map.of("mode", "EXISTS", "path", "data.items.0.id"),
                null, actual).passed());
        assertFalse(EvalAssertions.evaluate(Map.of("mode", "EXISTS", "path", "data.missing"),
                null, actual).passed());
    }

    @Test
    void numericHonoursBounds() {
        Map<String, Object> actual = Map.of("meta", Map.of("confidence", 0.86));
        assertTrue(EvalAssertions.evaluate(
                Map.of("mode", "NUMERIC", "path", "meta.confidence", "min", 0.8),
                null, actual).passed());
        assertFalse(EvalAssertions.evaluate(
                Map.of("mode", "NUMERIC", "path", "meta.confidence", "min", 0.9),
                null, actual).passed());
        assertFalse(EvalAssertions.evaluate(
                Map.of("mode", "NUMERIC", "path", "meta.label"), null, actual).passed());
    }

    @Test
    void unknownModeFailsLoudly() {
        var result = EvalAssertions.evaluate(Map.of("mode", "FUZZY"), "a", "a");
        assertFalse(result.passed());
        assertTrue(result.message().contains("unknown assertion mode"));
    }

    @Test
    void emptyContainsExpectationIsAFailure() {
        var result = EvalAssertions.evaluate(Map.of("mode", "CONTAINS"), List.of(), Map.of());
        assertFalse(result.passed());
        assertEquals("contains assertion needs non-empty expected text", result.message());
    }
}
