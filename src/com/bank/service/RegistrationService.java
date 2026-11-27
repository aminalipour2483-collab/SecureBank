package com.bank.service;

import com.bank.model.Account;
import com.bank.model.Account.AccountType;
import java.math.BigDecimal;
import java.time.LocalDate;

/** Public registration use case. Creates a brand-new customer and their first account atomically. */
public interface RegistrationService {
    Account registerNewCustomer(String nationalId, String firstName, String lastName,
            LocalDate birthDate, String phone, String email, String address,
            String postalCode, String occupation, AccountType accountType,
            String plainTextPin, BigDecimal initialDeposit);
}
