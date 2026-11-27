package com.bank.tools;

import com.bank.fraudring.domain.*;
import com.bank.graph.domain.CustomerIdentity;
import java.io.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.*;

/** Offline synthetic graph experiment. Never connects to the banking database. */
public final class SyntheticGraphBenchmark {
    public static final int SNAPSHOTS = 8, CUSTOMERS = 250, ANOMALIES = 24;
    public static final String FEATURE_HEADER = "log_out_count,log_in_count,log_out_amount,log_in_amount,log_out_neighbors,log_in_neighbors,log_reciprocal,log_shared_phones,log_accounts,log_age,age_missing,betweenness,log_pagerank,log_cycles,community_density,log_max_pair_count";
    private SyntheticGraphBenchmark() {}
    private record Pair(int from, int to) {}
    private static final class Flow { long count, cents; }
    private static void add(Map<Pair, Flow> flows, int from, int to, int count, long cents) {
        if (from == to) return;
        Flow f = flows.computeIfAbsent(new Pair(from, to), k -> new Flow());
        f.count += count; f.cents += cents;
    }
    public static void generate(Path output, long seed) throws IOException {
        Files.createDirectories(output.toAbsolutePath().getParent());
        try (BufferedWriter w = Files.newBufferedWriter(output, StandardCharsets.UTF_8)) {
            w.write("snapshot,split,customer_id,label,scenario,java_if,java_risk,reason_codes,cycle_truncated," + FEATURE_HEADER + "\n");
            for (int snap=0; snap<SNAPSHOTS; snap++) writeSnapshot(w, seed, snap);
        }
    }
    private static void writeSnapshot(BufferedWriter w, long seed, int snap) throws IOException {
        Random rng = new Random(seed + 104729L*snap);
        List<Integer> shuffled = new ArrayList<>();
        for (int i=0; i<CUSTOMERS; i++) shuffled.add(i);
        Collections.shuffle(shuffled, rng);
        String[] scenario = new String[CUSTOMERS]; Arrays.fill(scenario, "ordinary");
        for (int i=0;i<ANOMALIES;i++) scenario[shuffled.get(i)] = i<12 ? "ring" : i<18 ? "velocity" : "collector";
        for (int i=ANOMALIES;i<ANOMALIES+18;i++) scenario[shuffled.get(i)] = "benign_high_activity";
        CustomerIdentity[] ids = new CustomerIdentity[CUSTOMERS];
        Map<Long, CustomerRiskFact> facts = new TreeMap<>();
        Map<Pair, Flow> edges = new TreeMap<>(Comparator.comparingInt(Pair::from).thenComparingInt(Pair::to));
        List<List<Integer>> blocks = new ArrayList<>();
        for (int i=0;i<12;i+=4) blocks.add(new ArrayList<>(shuffled.subList(i,i+4)));
        for (int i=12;i<CUSTOMERS-2;i++) blocks.add(List.of(shuffled.get(i)));
        Collections.shuffle(blocks,rng);
        List<Integer> order = new ArrayList<>(); order.add(shuffled.get(CUSTOMERS-2));
        blocks.forEach(order::addAll); order.add(shuffled.get(CUSTOMERS-1));
        int[] rank = new int[CUSTOMERS];
        for (int i=0;i<CUSTOMERS;i++) rank[order.get(i)] = i;
        for (int i=0;i<CUSTOMERS;i++) {
            ids[i] = new CustomerIdentity(i+1L, "Synthetic-"+(i+1), false);
            facts.put(i+1L, CustomerRiskFact.of(i+1L, 1+rng.nextInt(3), 10+rng.nextInt(900), 0));
            int degree = 2+rng.nextInt(4);
            if (scenario[i].equals("benign_high_activity")) degree += 4+rng.nextInt(6);
            for (int j=0;j<degree;j++) {
                if (rank[i]==CUSTOMERS-1) break;
                int to=order.get(j==0?rank[i]+1:rank[i]+1+rng.nextInt(CUSTOMERS-rank[i]-1));
                int count=1+rng.nextInt(5);
                add(edges,i,to,count,(long)count*(1000+rng.nextInt(90000)));
            }
        }
        for (int i=0;i<12;i++) {
            int from=shuffled.get(i), to=shuffled.get((i/4)*4+(i+1)%4);
            int n=4+rng.nextInt(15); add(edges,from,to,n,n*(50000L+rng.nextInt(250000)));
        }
        for (int i=12;i<18;i++) {
            int from=shuffled.get(i);
            var eligible=shuffled.subList(24,CUSTOMERS).stream().filter(x->rank[x]>rank[from]).toList();
            int to=eligible.get(rng.nextInt(eligible.size()));
            int n=10+rng.nextInt(36); add(edges,from,to,n,n*(2000L+rng.nextInt(50000)));
        }
        for (int i=18;i<24;i++) {
            int to=shuffled.get(i);
            var eligible=shuffled.subList(24,CUSTOMERS).stream().filter(x->rank[x]<rank[to]).toList();
            int sources=8+rng.nextInt(5);
            for (int j=0;j<sources;j++) {
                int from=eligible.get(rng.nextInt(eligible.size()));
                add(edges,from,to,1+rng.nextInt(3),20000+rng.nextInt(130000));
            }
        }
        List<FraudRingFlow> flows = new ArrayList<>();
        edges.forEach((p,f)->flows.add(new FraudRingFlow(ids[p.from],ids[p.to],true,f.count,BigDecimal.valueOf(f.cents,2),LocalDateTime.of(2026,1,1,0,0).plusDays(snap))));
        FraudRingGraph graph=FraudRingAnalyzer.analyze(flows,Map.of(),facts,FraudRingCriteria.full());
        var features=GraphAnomalyDetector.featureVectors(graph);
        var anomaly=GraphAnomalyDetector.analyze(graph);
        var risk=RiskScoringEngine.assess(graph);
        if (graph.nodeCount()!=CUSTOMERS || !anomaly.active()) throw new IllegalStateException("Incomplete synthetic graph");
        String split=snap<4?"train":snap<6?"validation":"test";
        for (long id:graph.sortedNodeIds()) {
            String kind=scenario[(int)id-1];
            int label=kind.equals("ordinary")||kind.equals("benign_high_activity")?0:1;
            var r=risk.customer(id);
            w.write(snap+","+split+","+id+","+label+","+kind+","+anomaly.scoreFor(id)+","+r.score()+","+r.reasonCodes()+","+graph.cycleDetectionTruncated());
            for(double x:features.get(id)) w.write(","+x);
            w.newLine();
        }
    }
    public static void main(String[] args) throws IOException {
        if(args.length<1 || args.length>2) throw new IllegalArgumentException("Usage: SyntheticGraphBenchmark output.csv [seed]");
        generate(Path.of(args[0]),args.length==2?Long.parseLong(args[1]):42L);
        System.out.println("Exported "+SNAPSHOTS*CUSTOMERS+" synthetic customer snapshots to "+args[0]);
    }
}
