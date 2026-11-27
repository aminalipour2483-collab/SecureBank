package com.bank.fraudring.domain;

/**
 * The four risk bands of the fraud-ring analysis — the same 0–100 cut-points
 * the transaction Fraud Detection Engine uses (LOW &lt; 25 ≤ MEDIUM &lt; 50 ≤ HIGH
 * &lt; 75 ≤ CRITICAL), so the admin panel reads both modules with one mental model.
 */
public enum RiskBand {

    LOW(0, 24, "کم"),
    MEDIUM(25, 49, "متوسط"),
    HIGH(50, 74, "زیاد"),
    CRITICAL(75, 100, "بحرانی");

    private final int minScore;
    private final int maxScore;
    private final String persianLabel;

    RiskBand(int minScore, int maxScore, String persianLabel) {
        this.minScore = minScore;
        this.maxScore = maxScore;
        this.persianLabel = persianLabel;
    }

    public int minScore() { return minScore; }
    public int maxScore() { return maxScore; }
    public String persianLabel() { return persianLabel; }

    /** Maps a 0–100 score onto the band (same cut-points as the transaction engine). */
    public static RiskBand of(int score) {
        if (score < 0 || score > 100) {
            throw new IllegalArgumentException("score must be 0..100, got " + score);
        }
        if (score >= 75) return CRITICAL;
        if (score >= 50) return HIGH;
        if (score >= 25) return MEDIUM;
        return LOW;
    }

    /** Persian band label with the numeric range, for audit lines and JSON payloads. */
    public String describe() {
        return persianLabel + " (" + minScore + "–" + maxScore + ")";
    }
}
