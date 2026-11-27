package com.bank.dao;

import com.bank.model.Account;
import com.bank.model.Customer;

/** Persists a new customer, their first account, and any opening ledger entry atomically. */
public interface RegistrationDAO {
    Account registerNewCustomerWithFirstAccount(Customer customer, Account firstAccount);
}
