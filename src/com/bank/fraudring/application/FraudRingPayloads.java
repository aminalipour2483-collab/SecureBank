package com.bank.fraudring.application;

import com.bank.fraudring.domain.CustomerRiskScore;
import com.bank.fraudring.domain.FraudRingGraph;
import com.bank.fraudring.domain.GroupRiskScore;
import com.bank.fraudring.domain.RiskAssessment;
import com.bank.fraudring.domain.ScoredReason;

/**
 * Hand-rolled JSON serialisation of the analysis payloads (Phase 3). No JSON
 * library exists on this classpath, and the payloads are flat and
 * schema-stable — the same approach as Phase 2's {@code serialiseGraphStats},
 * now covering the full scored output.
 *
 * <p><b>Layout contract</b> (the Phase 4 admin UI renders this):
 * <pre>{@code
 * top_customers: {"phase":3,"customersScored":N,"bandCounts":{...},"customers":[
 *   {"customerId":1,"displayName":"A One","score":85,"band":"CRITICAL",
 *    "communityId":0,"cycleCount":1,"degree":3,"betweenness":0.5,"pageRank":0.06,
 *    "reasons":[{"code":"MONEY_CYCLE","title":"…","points":35,"detail":"…"}]}]}
 * top_groups:   {"phase":3,"groupsScored":N,"bandCounts":{...},"groups":[
 *   {"communityId":0,"size":3,"members":[1,2,3],"score":100,"band":"CRITICAL",
 *    "density":1.0,"internalVolume":"1200.00","reasons":[…]}]}
 * }</pre>
 *
 * <p>Persian reason text is stored verbatim; the UI renders it as-is. All
 * numbers stay ASCII in the JSON (band counts, scores) — Persian digits only
 * live inside human-readable reason strings.
 */
final class FraudRingPayloads {

    private FraudRingPayloads() { }

    /** graph_stats: structural summary of the run (same shape Phase 2 wrote, phase field added). */
    static String graphStats(FraudRingGraph graph, RiskAssessment assessment, boolean truncated) {
        StringBuilder sb = new StringBuilder(256);
        sb.append("{\"phase\":3")
          .append(",\"nodeCount\":").append(graph.nodeCount())
          .append(",\"edgeCount\":").append(graph.edgeCount())
          .append(",\"communityCount\":").append(graph.communities().size())
          .append(",\"cycleCount\":").append(graph.cycleCount())
          .append(",\"cycleDetectionTruncated\":").append(graph.cycleDetectionTruncated())
          .append(",\"truncated\":").append(truncated)
          .append(",\"anomalyModel\":\"").append(assessment.anomalyModelStatus().name()).append('"')
          .append(",\"anomalyBaselineSize\":").append(assessment.anomalyBaselineSize())
          .append(",\"scope\":\"").append(escape(graph.criteria().scope().name())).append('"');
        if (graph.criteria().lookbackDays() != null) {
            sb.append(",\"lookbackDays\":").append(graph.criteria().lookbackDays());
        }
        sb.append('}');
        return sb.toString();
    }

    /** top_customers: every scored customer, ranked, with XAI reasons. */
    static String topCustomers(FraudRingGraph graph, RiskAssessment assessment) {
        StringBuilder sb = new StringBuilder(512);
        sb.append("{\"phase\":3,\"customersScored\":").append(assessment.customerCount());
        sb.append(",\"bandCounts\":");
        bandCounts(sb, assessment);
        sb.append(",\"customers\":[");
        boolean first = true;
        for (CustomerRiskScore c : assessment.customers()) {
            if (!first) sb.append(',');
            first = false;
            appendCustomer(sb, graph, c);
        }
        sb.append("]}");
        return sb.toString();
    }

    private static void appendCustomer(StringBuilder sb, FraudRingGraph graph, CustomerRiskScore c) {
        sb.append("{\"customerId\":").append(c.customerId())
          .append(",\"displayName\":\"").append(escape(c.displayName())).append('"')
          .append(",\"score\":").append(c.score())
          .append(",\"band\":\"").append(c.band().name()).append('"')
          .append(",\"persianBand\":\"").append(escape(c.band().persianLabel())).append('"')
          .append(",\"communityId\":").append(graph.communityOf(c.customerId()))
          .append(",\"cycleCount\":").append(graph.cyclesInvolving(c.customerId()).size())
          .append(",\"degree\":").append(graph.centralityOf(c.customerId()).degree())
          .append(",\"betweenness\":").append(graph.centralityOf(c.customerId()).betweenness())
          .append(",\"pageRank\":").append(graph.centralityOf(c.customerId()).pageRank())
          .append(",\"reasons\":[");
        boolean firstReason = true;
        for (ScoredReason r : c.reasons()) {
            if (!firstReason) sb.append(',');
            firstReason = false;
            appendReason(sb, r);
        }
        sb.append("]}");
    }

    /** top_groups: every scored community, ranked, with XAI reasons. */
    static String topGroups(FraudRingGraph graph, RiskAssessment assessment) {
        StringBuilder sb = new StringBuilder(512);
        sb.append("{\"phase\":3,\"groupsScored\":").append(assessment.groupCount());
        sb.append(",\"bandCounts\":");
        groupBandCounts(sb, assessment);
        sb.append(",\"groups\":[");
        boolean first = true;
        for (GroupRiskScore g : assessment.groups()) {
            if (!first) sb.append(',');
            first = false;
            CommunityLike community = communityById(graph, g.communityId());
            appendGroup(sb, graph, g, community);
        }
        sb.append("]}");
        return sb.toString();
    }

    private static void appendGroup(StringBuilder sb, FraudRingGraph graph,
                                   GroupRiskScore g, CommunityLike community) {
        sb.append("{\"communityId\":").append(g.communityId())
          .append(",\"size\":").append(g.memberIds().size())
          .append(",\"members\":[");
        boolean firstMember = true;
        for (Long id : g.memberIds()) {
            if (!firstMember) sb.append(',');
            firstMember = false;
            sb.append(id);
        }
        sb.append(']')
          .append(",\"score\":").append(g.score())
          .append(",\"band\":\"").append(g.band().name()).append('"')
          .append(",\"persianBand\":\"").append(escape(g.band().persianLabel())).append('"')
          .append(",\"density\":").append(community == null ? 0 : community.density)
          .append(",\"internalVolume\":\"").append(community == null ? "0.00" : community.volume).append('"')
          .append(",\"reasons\":[");
        boolean firstReason = true;
        for (ScoredReason r : g.reasons()) {
            if (!firstReason) sb.append(',');
            firstReason = false;
            appendReason(sb, r);
        }
        sb.append("]}");
    }

    private static void appendReason(StringBuilder sb, ScoredReason r) {
        sb.append("{\"code\":\"").append(escape(r.code())).append('"')
          .append(",\"title\":\"").append(escape(r.persianTitle())).append('"')
          .append(",\"points\":").append(r.points())
          .append(",\"detail\":\"").append(escape(r.persianDetail())).append("\"}");
    }

    private static void bandCounts(StringBuilder sb, RiskAssessment assessment) {
        sb.append("{\"LOW\":").append(assessment.countBand(com.bank.fraudring.domain.RiskBand.LOW))
          .append(",\"MEDIUM\":").append(assessment.countBand(com.bank.fraudring.domain.RiskBand.MEDIUM))
          .append(",\"HIGH\":").append(assessment.countBand(com.bank.fraudring.domain.RiskBand.HIGH))
          .append(",\"CRITICAL\":").append(assessment.countBand(com.bank.fraudring.domain.RiskBand.CRITICAL))
          .append('}');
    }

    private static void groupBandCounts(StringBuilder sb, RiskAssessment assessment) {
        sb.append("{\"LOW\":").append(assessment.groupCountBand(com.bank.fraudring.domain.RiskBand.LOW))
          .append(",\"MEDIUM\":").append(assessment.groupCountBand(com.bank.fraudring.domain.RiskBand.MEDIUM))
          .append(",\"HIGH\":").append(assessment.groupCountBand(com.bank.fraudring.domain.RiskBand.HIGH))
          .append(",\"CRITICAL\":").append(assessment.groupCountBand(com.bank.fraudring.domain.RiskBand.CRITICAL))
          .append('}');
    }

    /** Minimal view of a community the group payload needs. */
    private record CommunityLike(int id, double density, String volume) { }

    private static CommunityLike communityById(FraudRingGraph graph, int communityId) {
        for (com.bank.fraudring.domain.Community c : graph.communities()) {
            if ((int) c.communityId() == communityId) {
                return new CommunityLike(communityId, c.density(),
                        c.internalVolume().toPlainString());
            }
        }
        return null;
    }

    /** JSON string escaping — Persian text passes through untouched. */
    static String escape(String raw) {
        if (raw == null) return "";
        StringBuilder sb = new StringBuilder(raw.length() + 8);
        for (char c : raw.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }
}
