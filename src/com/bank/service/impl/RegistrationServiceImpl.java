package com.bank.service.impl;

import com.bank.dao.RegistrationDAO;
import com.bank.exception.BankException;
import com.bank.model.Account;
import com.bank.model.Customer;
import com.bank.service.RegistrationService;
import com.bank.util.BCryptUtil;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDate;
import java.time.Period;
import java.util.Locale;
import java.util.Objects;

/** Validates a public new-customer registration before invoking one atomic DAO operation. */
public final class RegistrationServiceImpl implements RegistrationService {
    private static final BigDecimal MAX_BALANCE = new BigDecimal("9999999999999999.99");
    private static final SecureRandom RANDOM = new SecureRandom();
    private final RegistrationDAO registrationDAO;
    private final Clock clock;

    public RegistrationServiceImpl(RegistrationDAO registrationDAO) {
        this(registrationDAO, Clock.systemDefaultZone());
    }

    public RegistrationServiceImpl(RegistrationDAO registrationDAO, Clock clock) {
        this.registrationDAO = Objects.requireNonNull(registrationDAO, "registrationDAO");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public Account registerNewCustomer(String nationalId, String firstName, String lastName,
            LocalDate birthDate, String phone, String email, String address,
            String postalCode, String occupation, Account.AccountType accountType,
            String plainTextPin, BigDecimal initialDeposit) {
        String id = required(nationalId, "National ID");
        String first = required(firstName, "First name");
        String last = required(lastName, "Last name");
        String mobile = required(phone, "Phone number");
        String normalizedEmail = required(email, "E-mail").toLowerCase(Locale.ROOT);
        String street = required(address, "Address");
        String postal = required(postalCode, "Postal code");
        String job = required(occupation, "Occupation");

        maxLength(id, 20, "National ID");
        maxLength(first, 60, "First name");
        maxLength(last, 60, "Last name");
        maxLength(mobile, 20, "Phone number");
        maxLength(normalizedEmail, 120, "E-mail");
        maxLength(postal, 20, "Postal code");
        maxLength(job, 100, "Occupation");
        if (birthDate == null || !birthDate.isBefore(LocalDate.now(clock))) {
            throw new BankException("Birth date must be in the past.");
        }
        int age = Period.between(birthDate, LocalDate.now(clock)).getYears();
        if (age < 18) throw new BankException("Customer must be at least 18 years old.");
        if (accountType == null) throw new BankException("Account type is required.");
        if (plainTextPin == null || !plainTextPin.matches("\\d{4,6}")) {
            throw new BankException("PIN must be 4 to 6 digits.");
        }
        if (initialDeposit == null || initialDeposit.signum() < 0) {
            throw new BankException("Initial deposit cannot be negative or null.");
        }
        if (initialDeposit.compareTo(MAX_BALANCE) > 0) {
            throw new BankException("Opening balance exceeds the database's supported range.");
        }
        if (initialDeposit.stripTrailingZeros().scale() > 2) {
            throw new BankException("Initial deposit cannot have more than two decimal places.");
        }

        Customer customer = new Customer(id, first, last, birthDate, mobile,
                normalizedEmail, street, postal, job);
        String accountNumber = "10" + String.format(Locale.ROOT, "%010d", RANDOM.nextLong(10_000_000_000L));
        String cardNumber = "40" + String.format(Locale.ROOT, "%010d", RANDOM.nextLong(10_000_000_000L));
        String pinHash = BCryptUtil.hash(plainTextPin);
        Account firstAccount = new Account(0L, accountNumber, cardNumber, pinHash, accountType, initialDeposit);
        return registrationDAO.registerNewCustomerWithFirstAccount(customer, firstAccount);
    }

    private String required(String value, String label) {
        if (value == null || value.trim().isEmpty()) throw new BankException(label + " is required.");
        return value.trim();
    }

    private void maxLength(String value, int max, String label) {
        if (value.length() > max) throw new BankException(label + " must not exceed " + max + " characters.");
    }
}
