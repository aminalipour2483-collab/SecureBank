package com.bank.fraudring.domain;

import com.bank.graph.domain.CustomerIdentity;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The risk-scoring half of the fraud-ring analysis (Phase 3). Pure domain,
 * no infrastructure — it reads a finished {@link FraudRingGraph} and produces
 * a fully explainable {@link RiskAssessment}.
 *
 * <h2>Scoring model</h2>
 * Additive weighted rules, capped at 100, using the same risk-band scale as
 * the transaction Fraud Detection Engine so administrators can read both
 * modules consistently.
 * Every point comes with a Persian reason sentence (XAI: the score is never
 * a black box; it is the sum of readable weights).
 *
 * <h3>Customer rules</h3>
 * <table border summary="customer weights">
 *   <tr><th>code</th><th>points</th><th>trigger</th></tr>
 *   <tr><td>MONEY_CYCLE</td><td>+35</td><td>customer sits on ≥ 1 money cycle (per-ring fact in the detail)</td></tr>
 *   <tr><td>HUB_DEGREE</td><td>+25</td><td>undirected degree is the graph maximum and ≥ 3</td></tr>
 *   <tr><td>COURIER_BETWEENNESS</td><td>+20</td><td>betweenness is the strict graph maximum and &gt; 0</td></tr>
 *   <tr><td>COLLECTOR_PAGERANK</td><td>+15</td><td>PageRank is the graph maximum and ≥ 2× the mean</td></tr>
 *   <tr><td>SHARED_PHONE</td><td>+15</td><td>shares ≥ 1 normalised phone with another customer</td></tr>
 *   <tr><td>YOUNG_ACCOUNTS</td><td>+10</td><td>newest account younger than 30 days (the mule-staging signal)</td></tr>
 *   <tr><td>BANNED_MEMBER</td><td>+10</td><td>already banned (prior enforcement: corroborating signal)</td></tr>
 *   <tr><td>VELOCITY</td><td>+10</td><td>≥ 10 completed transfers on one pair direction in the window</td></tr>
 *   <tr><td>GRAPH_ANOMALY</td><td>+10</td><td>Isolation Forest score ≥ 0.65 with at least 32 complete-snapshot peers</td></tr>
 * </table>
 * The Isolation Forest score is an unsupervised peer-anomaly indicator, not a
 * calibrated probability of fraud. It is disabled for small or truncated snapshots.
 * Structural and behavioural signals deliberately overlap — a hub that also
 * cycles money should score higher than either signal alone; the cap keeps
 * the scale at 100.
 *
 * <h3>Group rules</h3>
 * <table border summary="group weights">
 *   <tr><th>code</th><th>points</th><th>trigger</th></tr>
 *   <tr><td>GROUP_CYCLE</td><td>+40</td><td>≥ 1 money cycle fully inside the community</td></tr>
 *   <tr><td>GROUP_VOLUME</td><td>+25</td><td>internal transfer volume ≥ 100,000 (whole currency units)</td></tr>
 *   <tr><td>GROUP_DENSITY</td><td>+15</td><td>internal density ≥ 0.8 (dense ring topology)</td></tr>
 *   <tr><td>GROUP_SHARED_PHONE</td><td>+15</td><td>≥ 2 members share a phone number</td></tr>
 *   <tr><td>GROUP_BANNED</td><td>+10</td><td>≥ 1 member already banned</td></tr>
 *   <tr><td>GROUP_SIZE</td><td>+10</td><td>≥ 5 members (working ring size)</td></tr>
 * </table>
 *
 * <h2>Determinism</h2>
 * The unsupervised forest uses a fixed pseudorandom seed; reasons iterate sorted
 * ids, thresholds are constants, and numbers are formatted through
 * {@link #money(BigDecimal)}. Re-running on the same graph yields identical
 * output (verified by tests).
 */
public final class RiskScoringEngine {

    private RiskScoringEngine() { }

    // ── thresholds (package-visible for tests; same naming style as the engine) ──
    static final int HUB_DEGREE_MIN = 3;
    static final int VELOCITY_TRANSFERS = 10;
    static final int YOUNG_ACCOUNT_DAYS = 30;
    static final int GROUP_VOLUME_THRESHOLD = 100_000;
    static final int GROUP_DENSITY_MIN = 8_000;      // 0.8 as basis points of 10,000
    static final int GROUP_SIZE_MIN = 5;
    static final int PAGERANK_FACTOR = 2;

    // ─────────────────────────────────────────────────────────────────────────
    // Entry point
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Scores every customer and every community of one analysis graph.
     *
     * @param graph a finished {@link FraudRingGraph} (any scope)
     * @return the ranked assessment; customers and groups are ranked by score desc, id asc
     */
    public static RiskAssessment assess(FraudRingGraph graph) {
        return assess(graph, true);
    }

    /** Disable unsupervised peer scoring when the source snapshot was truncated. */
    public static RiskAssessment assess(FraudRingGraph graph, boolean completeSnapshot) {
        Objects.requireNonNull(graph, "graph");

        Map<Long, CentralityMeasures> centrality = graph.centrality();
        GraphIsolationForest.Result anomalyResult = GraphAnomalyDetector.analyze(graph, completeSnapshot);
        int maxDegree = centrality.values().stream().mapToInt(CentralityMeasures::degree).max().orElse(0);
        double maxBetweenness = centrality.values().stream().mapToDouble(CentralityMeasures::betweenness).max().orElse(0);
        double maxPageRank = centrality.values().stream().mapToDouble(CentralityMeasures::pageRank).max().orElse(0);
        double meanPageRank = centrality.values().stream().mapToDouble(CentralityMeasures::pageRank).average().orElse(0);

        Map<Long, Long> maxPairTransfers = new java.util.HashMap<>();
        for (FraudRingFlow flow : graph.flows()) {
            maxPairTransfers.merge(flow.customer().id(), flow.transferCount(), Math::max);
            maxPairTransfers.merge(flow.counterpart().id(), flow.transferCount(), Math::max);
        }
        Map<Long, List<MoneyCycle>> cyclesByCustomer = new java.util.HashMap<>();
        for (MoneyCycle cycle : graph.cycles()) {
            for (Long memberId : cycle.memberIds()) {
                cyclesByCustomer.computeIfAbsent(memberId, ignored -> new ArrayList<>()).add(cycle);
            }
        }
        Map<Long, Integer> communityByCustomer = new java.util.HashMap<>();
        for (Community community : graph.communities()) {
            for (Long memberId : community.memberIds()) communityByCustomer.put(memberId, (int) community.communityId());
        }
        Map<Integer, Long> cyclesByCommunity = new java.util.HashMap<>();
        for (MoneyCycle cycle : graph.cycles()) {
            Integer communityId = null;
            boolean internal = true;
            for (Long memberId : cycle.memberIds()) {
                Integer currentCommunity = communityByCustomer.get(memberId);
                if (currentCommunity == null || (communityId != null && !communityId.equals(currentCommunity))) {
                    internal = false;
                    break;
                }
                communityId = currentCommunity;
            }
            if (internal && communityId != null) cyclesByCommunity.merge(communityId, 1L, Long::sum);
        }

        List<CustomerRiskScore> customers = new ArrayList<>();
        for (Long id : graph.sortedNodeIds()) {
            CustomerIdentity who = graph.identity(id);
            if (who == null) continue;
            customers.add(scoreCustomer(graph, id, who, centrality.getOrDefault(id, CentralityMeasures.isolated(id)),
                    maxDegree, maxBetweenness, maxPageRank, meanPageRank,
                    anomalyResult.active() ? anomalyResult.scoreFor(id) : Double.NaN,
                    anomalyResult.active(), anomalyResult.baselineSize(),
                    maxPairTransfers.getOrDefault(id, 0L), cyclesByCustomer.getOrDefault(id, List.of())));
        }

        List<GroupRiskScore> groups = new ArrayList<>();
        for (Community community : graph.communities()) {
            if (community.size() < Community.MIN_RING_SIZE) continue;   // singletons are not rings
            groups.add(scoreGroup(community, cyclesByCommunity.getOrDefault((int) community.communityId(), 0L)));
        }
        return new RiskAssessment(customers, groups, anomalyResult.status(), anomalyResult.baselineSize());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Customer scoring
    // ─────────────────────────────────────────────────────────────────────────

    private static CustomerRiskScore scoreCustomer(FraudRingGraph graph, long id, CustomerIdentity who,
                                                   CentralityMeasures measures,
                                                   int maxDegree, double maxBetweenness,
                                                   double maxPageRank, double meanPageRank,
                                                   double anomalyScore, boolean anomalyModelActive,
                                                   int anomalyBaselineSize, long maxPairTransfers,
                                                   List<MoneyCycle> cycles) {
        List<ScoredReason> reasons = new ArrayList<>();

        // ── MONEY_CYCLE (+35): the strongest single signal — money came back ──
        if (!cycles.isEmpty()) {
            BigDecimal ringTotal = BigDecimal.ZERO;
            for (MoneyCycle cycle : cycles) ringTotal = ringTotal.add(cycle.totalAmount());
            reasons.add(new ScoredReason("MONEY_CYCLE", "حلقهٔ چرخش پول", 35,
                    "در " + fa(cycles.size()) + " حلقهٔ انتقال پول شرکت دارد (بلندترین: "
                            + fa(cycles.stream().mapToInt(MoneyCycle::hopCount).max().orElse(0))
                            + " گره)؛ مجموع گردش این حلقه‌ها " + money(ringTotal) + " است."));
        }

        // ── HUB_DEGREE (+25): talks to the most counterparties ──
        if (measures.degree() == maxDegree && maxDegree >= HUB_DEGREE_MIN) {
            reasons.add(new ScoredReason("HUB_DEGREE", "همسایهٔ پرتعداد", 25,
                    "با " + fa(measures.degree()) + " مشتری دیگر ارتباط انتقال دارد؛ بیشترین تعداد در این تحلیل."));
        }

        // ── COURIER_BETWEENNESS (+20): sits BETWEEN clusters ──
        if (maxBetweenness > 0 && measures.betweenness() == maxBetweenness) {
            reasons.add(new ScoredReason("COURIER_BETWEENNESS", "نقش واسطهٔ مسیر پول", 20,
                    "بیشترین «بینابینی» گراف را دارد (" + fa4(measures.betweenness())
                            + ")؛ پول بین خوشه‌های غیرمرتبط از این مشتری عبور می‌کند."));
        }

        // ── COLLECTOR_PAGERANK (+15): where many hubs point at ──
        if (maxPageRank > 0 && measures.pageRank() == maxPageRank
                && measures.pageRank() >= PAGERANK_FACTOR * meanPageRank) {
            reasons.add(new ScoredReason("COLLECTOR_PAGERANK", "مقصد نهایی جریان پول", 15,
                    "بالاترین رتبهٔ صفحه در گراف جهت‌دار (" + fa6(measures.pageRank())
                            + ") یعنی بیشترِ جریان‌ها در نهایت به این مشتری می‌رسد."));
        }

        // ── SHARED_PHONE (+15): identity sharing ──
        CustomerRiskFact fact = graph.factOf(id);
        if (fact.sharedPhoneCount() > 0) {
            reasons.add(new ScoredReason("SHARED_PHONE", "شمارهٔ موبایل مشترک", 15,
                    "شمارهٔ تلفن نرمال‌شدهٔ این مشتری با " + fa(fact.sharedPhoneCount())
                            + " مشتری دیگر یکسان است؛ نشانهٔ هویت مشترک یا حساب‌های صوری."));
        }

        // ── YOUNG_ACCOUNTS (+10): mule staging ──
        if (fact.newestAccountAgeDays() != null && fact.newestAccountAgeDays() < YOUNG_ACCOUNT_DAYS) {
            reasons.add(new ScoredReason("YOUNG_ACCOUNTS", "حساب تازه‌کار", 10,
                    "جدیدترین حساب این مشتری فقط " + fa(fact.newestAccountAgeDays())
                            + " روز عمر دارد؛ الگوی راه‌اندازی حساب‌های صوری (مول)."));
        }

        // ── BANNED_MEMBER (+10): prior enforcement ──
        if (who.banned()) {
            reasons.add(new ScoredReason("BANNED_MEMBER", "سابقهٔ مسدودیتی", 10,
                    "این مشتری هم‌اکنون مسدود (BANNED) است؛ تکرار الگوی پرچم‌گذاری‌شدهٔ قبلی."));
        }

        // ── GRAPH_ANOMALY (+10): unsupervised peer anomaly, not a fraud probability ──
        if (anomalyModelActive && Double.isFinite(anomalyScore)
                && anomalyScore >= GraphIsolationForest.ANOMALY_THRESHOLD) {
            reasons.add(new ScoredReason("GRAPH_ANOMALY", "رفتار ساختاری غیرمعمول", 10,
                    "مدل بدون‌ناظر Isolation Forest امتیاز ناهنجاری "
                            + String.format(java.util.Locale.ROOT, "%.3f", anomalyScore)
                            + " را در مقایسه با " + fa(anomalyBaselineSize)
                            + " مشتری حاضر در همین تحلیل ثبت کرده است؛ این عدد احتمال تقلب نیست."));
        }

        // ── VELOCITY (+10): same-pair burst ──
        if (maxPairTransfers >= VELOCITY_TRANSFERS) {
            reasons.add(new ScoredReason("VELOCITY", "انفجار تراکنش در یک مسیر", 10,
                    "در یک جفت مشتری مشخص " + fa(maxPairTransfers)
                            + " انتقال کامل‌شده در بازهٔ تحلیل ثبت شده است."));
        }

        int score = capScore(reasons);
        return new CustomerRiskScore(id, who.displayName(), score, RiskBand.of(score), reasons);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Group scoring
    // ─────────────────────────────────────────────────────────────────────────

    private static GroupRiskScore scoreGroup(Community community, long cyclesInside) {
        List<ScoredReason> reasons = new ArrayList<>();
        List<Long> memberIds = community.memberIds();

        // ── GROUP_CYCLE (+40): a ring inside the candidate ring ──
        if (cyclesInside > 0) {
            reasons.add(new ScoredReason("GROUP_CYCLE", "حلقهٔ کامل پول داخل گروه", 40,
                    "این گروه " + fa(cyclesInside) + " حلقهٔ چرخش پول «کاملاً داخلی» دارد؛ پول بدون خروج از گروه به ابتدا برمی‌گردد."));
        }

        // ── GROUP_VOLUME (+25): internal money moved ──
        BigDecimal internalVolume = community.internalVolume();
        if (internalVolume.compareTo(BigDecimal.valueOf(GROUP_VOLUME_THRESHOLD)) >= 0) {
            reasons.add(new ScoredReason("GROUP_VOLUME", "حجم بالای گردش داخلی", 25,
                    "مجموع انتقال‌های داخلی گروه " + money(internalVolume) + " است؛ از آستانهٔ "
                            + money(BigDecimal.valueOf(GROUP_VOLUME_THRESHOLD)) + " عبور کرده."));
        }

        // ── GROUP_DENSITY (+15): dense topology ──
        if (Math.round(community.density() * 10_000) >= GROUP_DENSITY_MIN) {
            reasons.add(new ScoredReason("GROUP_DENSITY", "توپولوژی متراکم", 15,
                    "چگالی ارتباطات داخلی گروه " + fa4(community.density()) + " است؛ تقریباً هر عضو با هر عضو در ارتباط است."));
        }

        // ── GROUP_SHARED_PHONE (+15): shared identity inside the group ──
        if (!community.sharedPhones().isEmpty()) {
            reasons.add(new ScoredReason("GROUP_SHARED_PHONE", "هویت مشترک داخل گروه", 15,
                    "دو یا چند عضو گروه " + fa(community.sharedPhones().size())
                            + " شمارهٔ تلفن مشترک دارند؛ نشانهٔ قوی شبکهٔ حساب‌های صوری."));
        }

        // ── GROUP_BANNED (+10): prior enforcement inside ──
        long bannedInside = community.members().stream().filter(CustomerIdentity::banned).count();
        if (bannedInside > 0) {
            reasons.add(new ScoredReason("GROUP_BANNED", "عضو مسدودشده", 10,
                    fa(bannedInside) + " عضو گروه هم‌اکنون مسدود (BANNED) است؛ الگوی تخلف قبلی درون همین شبکه."));
        }

        // ── GROUP_SIZE (+10): working ring size ──
        if (community.size() >= GROUP_SIZE_MIN) {
            reasons.add(new ScoredReason("GROUP_SIZE", "اندازهٔ کاری حلقه", 10,
                    "گروه " + fa(community.size()) + " عضو دارد؛ اندازهٔ معمول حلقه‌های عملیاتی کلاهبرداری."));
        }

        int score = capScore(reasons);
        return new GroupRiskScore((int) community.communityId(), memberIds, score, RiskBand.of(score), reasons);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // helpers
    // ─────────────────────────────────────────────────────────────────────────

    /** Additive cap: the sum of weights, never above 100. */
    private static int capScore(List<ScoredReason> reasons) {
        int sum = reasons.stream().mapToInt(ScoredReason::points).sum();
        return Math.min(100, sum);
    }

    /** Money format for Persian reason sentences: 1,234,567.89 (digits stay ASCII for RTL readability). */
    static String money(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    // Persian digits keep the reason sentences native; the JSON payload stores
    // them verbatim and the admin panel renders them as-is.
    private static final char[] FA_DIGITS = {'۰', '۱', '۲', '۳', '۴', '۵', '۶', '۷', '۸', '۹'};

    static String fa(long n) { return fa(String.valueOf(n)); }
    static String fa(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (char c : s.toCharArray()) {
            sb.append(c >= '0' && c <= '9' ? FA_DIGITS[c - '0'] : c);
        }
        return sb.toString();
    }
    static String fa4(double v) { return fa(String.format(java.util.Locale.ROOT, "%.4f", v)); }
    static String fa6(double v) { return fa(String.format(java.util.Locale.ROOT, "%.6f", v)); }
}
