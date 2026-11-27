package com.bank.fraud.outbox;

import com.bank.infrastructure.jdbc.JdbcTransactionExecutor;
import com.bank.exception.BankException;
import com.bank.model.FraudAlert.RiskLevel;
import com.bank.model.Transaction.TransactionType;
import com.bank.service.FraudDetectionService.FraudAssessment;
import com.bank.service.FraudDetectionService.TriggeredRule;
import java.lang.reflect.Proxy;
import java.lang.reflect.InvocationHandler;
import java.sql.*;
import java.time.LocalDateTime;
import java.math.BigDecimal;
import java.util.*;
import static com.bank.testkit.TestKit.*;

/** Exercises production SQL branches and transaction boundaries with JDBC doubles, not MySQL. */
public final class JdbcFraudOutboxRepositoryTest {
    private static final LocalDateTime TIME=LocalDateTime.of(2026,11,25,10,0);
    private static FraudOutboxEvent event(int attempt,int cycle) {
        return new FraudOutboxEvent(42,7,9,TransactionType.DEPOSIT,BigDecimal.TEN,BigDecimal.ZERO,88L,null,TIME,attempt,cycle);
    }
    private static FraudAssessment score(RiskLevel level) {
        return new FraudAssessment(7,9,TransactionType.DEPOSIT,BigDecimal.TEN,level==RiskLevel.LOW?0:40,level,
                level==RiskLevel.LOW?List.of():List.of(new TriggeredRule("LARGE_AMOUNT","large",40,"test")));
    }
    public static void main(String[] args) {
        Fixture expired=new Fixture(); expired.attempt=3;
        check("expired final attempt is not claimed again",expired.repo().claimNext().isEmpty());
        check("final attempt history closed",expired.has("SET status='FAILED',error_message='Worker lease expired"));
        check("expired event moves to FAILED",expired.has("SET status='FAILED',locked_at=NULL"));
        check("no fourth attempt",!expired.has("INSERT INTO fraud_outbox_attempts"));
        check("final recovery commits",expired.committed && !expired.rolledBack);
        check("expired third attempt eligible for recovery",expired.sql.get(0).contains("OR (status='PROCESSING'"));
        Fixture reclaim=new Fixture(); reclaim.attempt=2;
        var third=reclaim.repo().claimNext().orElseThrow();
        check("stale attempt reclaimed as third",third.attemptCount()==3 && third.retryCycle()==4);
        check("previous attempt closed before new one",reclaim.index("SET status='FAILED',error_message")<reclaim.index("INSERT INTO fraud_outbox_attempts"));
        Fixture stale=new Fixture(); stale.fence=0;
        stale.repo().complete(event(2,4),score(RiskLevel.MEDIUM));
        check("stale complete writes nothing after fence",stale.sql.size()==1);
        check("completion fence includes cycle, attempt, state and lease",stale.sql.get(0).contains("retry_cycle=? AND attempt_count=? AND status='PROCESSING' AND locked_at >="));
        check("fence parameters bind identity",stale.bindings.get(0).equals(Map.of(1,42L,2,4,3,2)));
        Fixture lateFail=new Fixture(); lateFail.fence=0;
        lateFail.repo().fail(event(3,3),new IllegalStateException("late"));
        check("stale failure cannot modify newer history",lateFail.sql.size()==1);
        check("failure also fenced",lateFail.sql.get(0).contains("retry_cycle=? AND attempt_count=?"));
        Fixture low=new Fixture(); low.repo().complete(event(1,4),score(RiskLevel.LOW));
        check("LOW result recorded",low.has("INSERT INTO fraud_event_results"));
        check("LOW creates no alert",!low.has("INSERT INTO fraud_alerts"));
        check("result insert needs no UPDATE grant",!low.has("ON DUPLICATE KEY UPDATE"));
        check("LOW completion commits",low.committed && !low.rolledBack);
        Fixture high=new Fixture(); high.repo().complete(event(1,4),score(RiskLevel.MEDIUM));
        check("alert inserted after result under same transaction",high.index("INSERT INTO fraud_event_results")<high.index("INSERT INTO fraud_alerts"));
        check("one connection for result alert and history",high.connections==1 && high.committed);
        Fixture broken=new Fixture(); broken.failAlert=true;
        checkThrows("alert write failure propagated",BankException.class,()->broken.repo().complete(event(1,4),score(RiskLevel.MEDIUM)));
        check("failed alert rolls back result and status",broken.rolledBack && !broken.committed);
        Fixture existing=new Fixture(); existing.legacy=true;
        existing.repo().complete(event(1,4),score(RiskLevel.MEDIUM));
        check("matching legacy alert preserved",existing.committed && !existing.has("INSERT INTO fraud_alerts"));
        Fixture mismatch=new Fixture(); mismatch.legacy=true;
        checkThrows("inconsistent legacy alert rejected",IllegalStateException.class,()->mismatch.repo().complete(event(1,4),score(RiskLevel.LOW)));
        check("legacy mismatch rolls back",mismatch.rolledBack && !mismatch.committed);
        Fixture failed=new Fixture(); failed.repo().fail(event(3,4),new IllegalStateException("test"));
        check("third ordinary failure terminal", "FAILED".equals(failed.bindings.get(0).get(1)) && failed.committed);
        Fixture retry=new Fixture(); retry.repo().fail(event(1,4),new IllegalStateException("test"));
        check("first failure waits one second", "RETRY_WAIT".equals(retry.bindings.get(0).get(1)) && Integer.valueOf(1).equals(retry.bindings.get(0).get(3)));
        Fixture second=new Fixture(); second.repo().fail(event(2,4),new IllegalStateException("test"));
        check("second failure waits five seconds", Integer.valueOf(5).equals(second.bindings.get(0).get(3)));
        finish("JdbcFraudOutboxRepositoryTest");
    }
    private static final class Fixture {
        int attempt=3,fence=1,connections; boolean committed,rolledBack,failAlert,legacy;
        List<String> sql=new ArrayList<>(); List<Map<Integer,Object>> bindings=new ArrayList<>();
        boolean has(String text){return index(text)>=0;}
        int index(String text){for(int i=0;i<sql.size();i++)if(sql.get(i).contains(text))return i;return -1;}
        JdbcFraudOutboxRepository repo(){return new JdbcFraudOutboxRepository(new JdbcTransactionExecutor(this::connection,c->{}));}
        Connection connection(){connections++;return proxy(Connection.class,(p,m,a)->switch(m.getName()){
            case "getAutoCommit"->true; case "commit"->{committed=true;yield null;} case "rollback"->{rolledBack=true;yield null;}
            case "prepareStatement"->statement((String)a[0]); default->null;
        });}
        PreparedStatement statement(String q){sql.add(q);Map<Integer,Object> bind=new HashMap<>();bindings.add(bind);
            return proxy(PreparedStatement.class,(p,m,a)->{
                String n=m.getName();if(n.startsWith("set")){bind.put((Integer)a[0],a[1]);return null;}
                if(n.equals("executeUpdate")){
                    if(q.startsWith("UPDATE fraud_outbox SET")&&q.contains("retry_cycle=? AND attempt_count=?"))return fence;
                    if(failAlert&&q.startsWith("INSERT INTO fraud_alerts"))throw new SQLException("injected alert failure");return 1;
                }
                if(n.equals("executeQuery"))return result(q.startsWith("SELECT * FROM fraud_outbox")||legacy,q.startsWith("SELECT * FROM fraud_outbox"));
                return null;
            });}
        ResultSet result(boolean row,boolean claim){boolean[] next={row};return proxy(ResultSet.class,(p,m,a)->switch(m.getName()){
            case "next"->{boolean v=next[0];next[0]=false;yield v;}
            case "getInt"->switch((String)a[0]){case "attempt_count"->attempt;case "retry_cycle"->4;case "risk_score"->40;default->0;};
            case "getLong"->switch((String)a[0]){case "outbox_id"->42L;case "account_id"->7L;case "customer_id"->9L;case "transaction_id"->88L;default->0L;};
            case "getString"->switch((String)a[0]){case "status"->"PROCESSING";case "transaction_type"->"DEPOSIT";case "risk_level"->"MEDIUM";case "rule_code"->"LARGE_AMOUNT";default->null;};
            case "getBigDecimal"->BigDecimal.TEN;case "getTimestamp"->Timestamp.valueOf(TIME);case "wasNull"->true;default->null;
        });}
        @SuppressWarnings("unchecked") static <T> T proxy(Class<T> type,InvocationHandler h){return (T)Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},h);}
    }
}
