package com.bank.service;

import com.bank.exception.AccessDeniedException;
import com.bank.security.AdminPrincipal;
import com.bank.service.impl.*;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static com.bank.testkit.TestKit.*;

/**
 * Sweeps every public method of the five service interfaces. Any method that takes an
 * {@link AdminPrincipal} must refuse a null principal with {@link AccessDeniedException}
 * BEFORE touching its dependencies (they are all null here, so a missing guard shows up as
 * a NullPointerException instead). A new administrator method with no guard cannot slip through,
 * and the expected inventory below must be updated deliberately.
 */
public class AdminOperationsGuardTest {

    static final Set<String> EXPECTED = new TreeSet<>(List.of(
            "AccountService.getAccountsByCustomer", "AccountService.getAllAccounts", "AccountService.getTransactionHistory", "AccountService.setAccountStatus",
            "CustomerService.archiveCustomer", "CustomerService.banCustomer", "CustomerService.deleteCustomer", "CustomerService.findCustomerByEmail", "CustomerService.findCustomerById", "CustomerService.findCustomerByNationalId", "CustomerService.getAllCustomers", "CustomerService.getArchivedCustomers", "CustomerService.getBannedCustomers",
            "CustomerService.restoreArchivedAccount", "CustomerService.restoreArchivedCustomer", "CustomerService.searchCustomersByName", "CustomerService.unbanCustomer", "CustomerService.updateCustomerInfo",
            "FraudDetectionService.getAllAlerts", "FraudDetectionService.getAlertsByStatus", "FraudDetectionService.reviewAlert",
            "MessageService.getAllMessages", "MessageService.getUnreadCount", "MessageService.markAsRead",
            "MessageService.sendMessageFromAdmin",
            "TransferService.findAll", "TransferService.updateTransferStatus"));

    public static void main(String[] args) throws Exception {
        section("[1] Every admin-only service method rejects a missing principal");
        Object[][] pairs = {
                {AccountService.class, new AccountServiceImpl(null, null, null, null)},
                {CustomerService.class, new CustomerServiceImpl(null, null)},
                {FraudDetectionService.class, new FraudDetectionServiceImpl(null, null, null)},
                {MessageService.class, new MessageServiceImpl(null, null)},
                {TransferService.class, new TransferServiceImpl(null, null, null, null)},
        };
        Set<String> found = new TreeSet<>();
        List<String> failures = new ArrayList<>();
        for (Object[] pair : pairs) {
            Class<?> iface = (Class<?>) pair[0];
            Object impl = pair[1];
            for (Method m : iface.getMethods()) {
                Parameter[] ps = m.getParameters();
                if (ps.length == 0 || ps[0].getType() != AdminPrincipal.class) continue;
                String id = iface.getSimpleName() + "." + m.getName();
                found.add(id);
                Object[] callArgs = new Object[ps.length];
                for (int i = 1; i < ps.length; i++) callArgs[i] = sample(ps[i].getType());
                try {
                    m.invoke(impl, callArgs);
                    failures.add(id + " accepted a null principal");
                } catch (InvocationTargetException e) {
                    if (!(e.getCause() instanceof AccessDeniedException)) failures.add(id + " threw " + e.getCause());
                }
            }
        }
        check("all " + found.size() + " admin-only methods deny a null principal", failures.isEmpty(), failures.toString());
        checkEquals("inventory of admin-only methods is exactly the reviewed list", EXPECTED, found);

        section("[2] Customer-sensitive service operations require CustomerPrincipal overloads");
        check("AccountService.login remains the credential-authentication entry point",
                hasMethod(AccountService.class, "login", String.class, String.class));
        check("AccountService.deposit has a principal-bound overload",
                hasPrincipalOverload(AccountService.class, "deposit"));
        check("AccountService.withdraw has a principal-bound overload",
                hasPrincipalOverload(AccountService.class, "withdraw"));
        check("AccountService.changePin has a principal-bound overload",
                hasPrincipalOverload(AccountService.class, "changePin"));
        check("AccountService.getAccountBalance has a principal-bound overload",
                hasPrincipalOverload(AccountService.class, "getAccountBalance"));
        check("AccountService.getTransactionHistory has a principal-bound overload",
                hasPrincipalOverload(AccountService.class, "getTransactionHistory"));
        check("TransferService.processTransfer has a principal-bound overload",
                hasPrincipalOverload(TransferService.class, "processTransfer"));
        check("CustomerService.findCustomerById has a customer-principal overload",
                hasPrincipalOverload(CustomerService.class, "findCustomerById"));
        check("MessageService.sendMessageFromCustomer has a customer-principal overload",
                hasPrincipalOverload(MessageService.class, "sendMessageFromCustomer"));
        check("MessageService.getMessagesForCustomer has a customer-principal overload",
                hasPrincipalOverload(MessageService.class, "getMessagesForCustomer"));
        check("MessageService.markAsReadByCustomer has a customer-principal overload",
                hasPrincipalOverload(MessageService.class, "markAsReadByCustomer"));
        check("MessageService.getUnreadCountForCustomer has a customer-principal overload",
                hasPrincipalOverload(MessageService.class, "getUnreadCountForCustomer"));
        check("legacy customer ID lookup is deprecated",
                legacyDeprecated(CustomerService.class, "findCustomerById"));
        check("legacy national-ID lookup is deprecated",
                legacyStringLookupDeprecated(CustomerService.class, "findCustomerByNationalId"));
        check("legacy email lookup is deprecated",
                legacyStringLookupDeprecated(CustomerService.class, "findCustomerByEmail"));
        check("legacy unguarded deposit is explicitly deprecated",
                legacyDeprecated(AccountService.class, "deposit"));
        check("legacy unguarded transfer is explicitly deprecated",
                legacyDeprecated(TransferService.class, "processTransfer"));
        check("CustomerService identity lookups require an administrator principal",
                hasAdminOverload(CustomerService.class, "findCustomerByNationalId")
                        && hasAdminOverload(CustomerService.class, "findCustomerByEmail"));
        finish("AdminOperationsGuardTest");
    }


    static boolean hasPrincipalOverload(Class<?> iface, String name) {
        for (Method m : iface.getMethods()) {
            if (m.getName().equals(name) && m.getParameterCount() > 0
                    && m.getParameters()[0].getType().getName().equals("com.bank.security.CustomerPrincipal")) return true;
        }
        return false;
    }


    static boolean hasAdminOverload(Class<?> iface, String name) {
        for (Method m : iface.getMethods()) {
            if (m.getName().equals(name) && m.getParameterCount() > 0
                    && m.getParameters()[0].getType() == AdminPrincipal.class) return true;
        }
        return false;
    }

    static boolean legacyStringLookupDeprecated(Class<?> iface, String name) {
        for (Method m : iface.getMethods()) {
            if (m.getName().equals(name) && m.getParameterCount() > 0
                    && m.getParameters()[0].getType() == String.class
                    && m.isAnnotationPresent(Deprecated.class)) return true;
        }
        return false;
    }

    static boolean legacyDeprecated(Class<?> iface, String name) {
        for (Method m : iface.getMethods()) {
            if (m.getName().equals(name) && m.getParameterCount() > 0
                    && m.getParameters()[0].getType() == long.class
                    && m.isAnnotationPresent(Deprecated.class)) return true;
        }
        return false;
    }

    static boolean hasMethod(Class<?> iface, String name, Class<?>... types) {
        try { iface.getMethod(name, types); return true; }
        catch (NoSuchMethodException e) { return false; }
    }

    static Object sample(Class<?> t) {
        if (t == long.class) return 1L;
        if (t == int.class) return 1;
        if (t == boolean.class) return false;
        if (t == String.class) return "x";
        if (t.isEnum()) return t.getEnumConstants()[0];
        return null;
    }
}
