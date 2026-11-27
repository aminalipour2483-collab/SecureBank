package com.bank.view.admin;

import com.bank.fraud.outbox.FraudEventSummary;
import com.bank.security.AdminPrincipal;
import com.bank.security.ConfiguredAdminAuthenticationService;
import com.bank.exception.AccessDeniedException;
import com.bank.util.BCryptUtil;
import javax.swing.*;
import java.awt.*;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import static com.bank.testkit.TestKit.*;

public final class FraudProcessingPanelTest {
    static <T extends Component> T find(Container c, Class<T> type, java.util.function.Predicate<T> p) {
        for (Component x:c.getComponents()) {
            if(type.isInstance(x) && p.test(type.cast(x))) return type.cast(x);
            if(x instanceof Container sub) { T v=find(sub,type,p); if(v!=null)return v; }
        }
        return null;
    }
    static void waitForRows(JTable table, JButton refresh, int count) throws Exception {
        long deadline=System.nanoTime()+5_000_000_000L;
        while(System.nanoTime()<deadline) {
            AtomicBoolean done=new AtomicBoolean();
            SwingUtilities.invokeAndWait(()->done.set(table.getRowCount()==count && refresh.isEnabled()));
            if(done.get())return;
            Thread.sleep(20);
        }
        throw new AssertionError("Timed out waiting for fraud event rows");
    }
    public static void main(String[] args) throws Exception {
        AdminPrincipal admin = new ConfiguredAdminAuthenticationService("test-admin", BCryptUtil.hash("pw"), Clock.systemUTC()).authenticate("test-admin","pw").orElseThrow();
        checkThrows("admin required", AccessDeniedException.class, ()->new FraudProcessingPanel(null,(a,b)->List.of(),(a,b)->{}));
        var calls=new CopyOnWriteArrayList<Boolean>();
        var principals=new CopyOnWriteArrayList<AdminPrincipal>();
        AtomicBoolean onEdt=new AtomicBoolean();
        FraudEventSummary failed=new FraudEventSummary(10,3,"DEPOSIT","FAILED",3,0,null,null,null,null,"simulated failure");
        FraudEventSummary low=new FraudEventSummary(11,3,"WITHDRAW","COMPLETED",1,0,0,"LOW","","No alert",null);
        FraudProcessingPanel[] panel=new FraudProcessingPanel[1];
        SwingUtilities.invokeAndWait(()->panel[0]=new FraudProcessingPanel(admin,(a,filter)->{
            principals.add(a);calls.add(filter);onEdt.set(SwingUtilities.isEventDispatchThread());
            return filter?List.of(failed):List.of(low,failed);
        },(a,id)->{throw new AssertionError("No retry requested by this test");}));
        JTable table=find(panel[0],JTable.class,x->true);
        JButton refresh=find(panel[0],JButton.class,x->"Refresh".equals(x.getText()));
        JButton retry=find(panel[0],JButton.class,x->x.getText().startsWith("Retry"));
        JCheckBox filter=find(panel[0],JCheckBox.class,x->true);
        check("no initial DB call", calls.isEmpty());
        SwingUtilities.invokeAndWait(()->refresh.doClick());
        waitForRows(table,refresh,2);
        checkEquals("admin propagated",admin,principals.get(0));
        check("loader off EDT",!onEdt.get());
        checkEquals("includes completed LOW result","LOW",table.getModel().getValueAt(0,7));
        SwingUtilities.invokeAndWait(()->table.setRowSelectionInterval(0,0));
        check("completed cannot retry",!retry.isEnabled());
        SwingUtilities.invokeAndWait(()->table.setRowSelectionInterval(1,1));
        check("failed can retry",retry.isEnabled());
        SwingUtilities.invokeAndWait(()->filter.doClick());
        waitForRows(table,refresh,1);
        checkEquals("failed-only query forwarded",List.of(false,true),calls);
        checkEquals("failed row shown",10L,table.getModel().getValueAt(0,0));
        finish("FraudProcessingPanelTest");
    }
}
