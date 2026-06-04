package com.nousresearch.hermes.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ConfidenceCalibratorTest {

    @Test
    void high_confidence_with_tools() {
        ConfidenceCalibrator cc = new ConfidenceCalibrator();
        String text = "The capital of France is Paris. Population: 2.1 million.";
        var result = cc.calibrate(text, 2, true);
        assertEquals(ConfidenceCalibrator.Action.DIRECT, result.action());
        assertTrue(result.score() >= 0.8);
        assertEquals(text, result.adjustedText());
    }

    @Test
    void medium_confidence_with_mild_hedge() {
        ConfidenceCalibrator cc = new ConfidenceCalibrator();
        String text = "The result is probably around 42.";
        var result = cc.calibrate(text, 0, false);
        assertEquals(ConfidenceCalibrator.Action.CAUTION, result.action());
        assertTrue(result.score() >= 0.5 && result.score() < 0.8);
        assertTrue(result.adjustedText().contains("Confidence"));
    }

    @Test
    void low_confidence_uncertain() {
        ConfidenceCalibrator cc = new ConfidenceCalibrator();
        String text = "I'm not sure about this, but perhaps it could be something like that.";
        var result = cc.calibrate(text, 0, false);
        assertEquals(ConfidenceCalibrator.Action.VERIFY, result.action());
        assertTrue(result.score() < 0.5);
        assertTrue(result.adjustedText().contains("not fully confident"));
    }

    @Test
    void disabled_returns_input_unchanged() {
        // When calibrator is disabled (via config), empty/null input returns neutral
        ConfidenceCalibrator cc = new ConfidenceCalibrator();
        var result = cc.calibrate(null, 0, false);
        assertEquals(0.5, result.score());
        assertEquals(ConfidenceCalibrator.Action.DIRECT, result.action());
    }

    @Test
    void empty_input_returns_neutral() {
        ConfidenceCalibrator cc = new ConfidenceCalibrator();
        var result = cc.calibrate("", 0, false);
        assertEquals(0.5, result.score());
    }
}
