package com.bank.fraudring.domain;

import java.util.Objects;

/**
 * One explainable contribution to a risk score — the XAI unit of the module.
 * Mirrors {@code TriggeredRule} of the transaction Fraud Detection Engine
 * (code, description, points) but adds the Persian explanation the admin panel
 * shows verbatim, so a non-English reviewer can read WHY a score is high.
 *
 * <p>Points are always ≥ 0: the engine is additive, and the capped sum is
 * produced by the caller, not by the reasons.
 *
 * @param code              stable machine code, e.g. {@code MONEY_CYCLE} — joined with '+' into
 *                         the audit trail exactly like the transaction engine's rule codes
 * @param persianTitle      short Persian headline, e.g. «حلقهٔ چرخش پول»
 * @param points            weight this reason contributed to the final score
 * @param persianDetail    full Persian sentence with the concrete numbers,
 *                         e.g. «در ۲ حلقهٔ انتقال پول شرکت دارد؛ کل گردش حلقه‌ها ۱۲٬۵۰۰٫۰۰»
 */
public record ScoredReason(String code,
                           String persianTitle,
                           int points,
                           String persianDetail) {

    public ScoredReason {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(persianTitle, "persianTitle");
        Objects.requireNonNull(persianDetail, "persianDetail");
        if (code.isBlank()) throw new IllegalArgumentException("code must not be blank");
        if (points < 0) throw new IllegalArgumentException("points must not be negative");
        if (points > 100) throw new IllegalArgumentException("points must not exceed 100");
        persianTitle = persianTitle.trim();
        persianDetail = persianDetail.trim();
    }

    /** Audit-line form: code + points, no Persian (logs stay ASCII-safe). */
    public String auditForm() {
        return code + "(+" + points + ")";
    }
}
