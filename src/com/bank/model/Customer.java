package com.bank.model;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Customer — mirrors the 'customers' table.
 *
 * Folder in NetBeans:
 *   BankManagementSystem/src/com/bank/model/Customer.java
 */
public class Customer {

    // -------------------------------------------------------------------------
    // ENUM mirrors the customer lifecycle states in MySQL (ACTIVE/BANNED/ARCHIVED).
    // -------------------------------------------------------------------------

    public enum CustomerStatus {
        ACTIVE,
        BANNED,
        ARCHIVED;

        /** Parse a DB string safely, e.g. rs.getString("status"). */
        public static CustomerStatus from(String value) {
            return (value == null || value.isBlank())
                    ? ACTIVE
                    : CustomerStatus.valueOf(value.toUpperCase());
        }
    }

    // -------------------------------------------------------------------------
    // FIELDS  (one per table column, matching the exact data type)
    // -------------------------------------------------------------------------

    /** AUTO_INCREMENT primary key — 0 means "not yet saved to DB". */
    private long customerid;

    /** Government-issued ID — unique per person. */
    private String nationalId;

    private String firstName;
    private String lastName;

    /**
     * DATE column → LocalDate (year + month + day, no time component).
     * Never use java.util.Date for a DATE-only column; LocalDate is exact.
     */
    private LocalDate birthDate;

    private String phone;
    private String email;

    /** Current lifecycle state: ACTIVE, BANNED, or ARCHIVED. */
    private CustomerStatus status = CustomerStatus.ACTIVE;

    /** Why the customer was banned — shown at login and in the admin panel. */
    private String bannedReason;

    /** When the ban was applied. NULL while the customer is ACTIVE. */
    private LocalDateTime bannedAt;

    /** When the customer was archived; null for non-archived customers. */
    private LocalDateTime archivedAt;

    /** Free-text street / mailing address — maps to TEXT column 'address'. */
    private String address;

    /** Postal / ZIP code — maps to VARCHAR(20) column 'postal_code'. */
    private String postalCode;

    /** Customer's occupation / profession — maps to VARCHAR(100) column 'occupation'. */
    private String occupation;

    /**
     * DATETIME column → LocalDateTime (date + time, no timezone).
     * MySQL DATETIME has no timezone information, so LocalDateTime is the
     * correct mapping.  Do NOT use ZonedDateTime here.
     */
    private LocalDateTime createdAt;

    // -------------------------------------------------------------------------
    // CONSTRUCTORS
    // -------------------------------------------------------------------------

    /** No-arg constructor — required by many frameworks and DAO result mappers. */
    public Customer() {}

    /**
     * Full constructor — use when loading a row that already exists in the DB
     * (customer_id and created_at are already known).
     */
    public Customer(long customerId,
                    String nationalId,
                    String firstName,
                    String lastName,
                    LocalDate birthDate,
                    String phone,
                    String email,
                    String address,
                    String postalCode,
                    String occupation,
                    LocalDateTime createdAt) {
        this.customerid  = customerId;
        this.nationalId  = nationalId;
        this.firstName   = firstName;
        this.lastName    = lastName;
        this.birthDate   = birthDate;
        this.phone       = phone;
        this.email       = email;
        this.address     = address;
        this.postalCode  = postalCode;
        this.occupation  = occupation;
        this.createdAt   = createdAt;
    }

    /**
     * Insert constructor — use when creating a NEW customer before saving.
     * customer_id and created_at are omitted because MySQL generates them.
     */
    public Customer(String nationalId,
                    String firstName,
                    String lastName,
                    LocalDate birthDate,
                    String phone,
                    String email,
                    String address,
                    String postalCode,
                    String occupation) {
        this.nationalId = nationalId;
        this.firstName  = firstName;
        this.lastName   = lastName;
        this.birthDate  = birthDate;
        this.phone      = phone;
        this.email      = email;
        this.address    = address;
        this.postalCode = postalCode;
        this.occupation = occupation;
    }

    // -------------------------------------------------------------------------
    // GETTERS & SETTERS
    // -------------------------------------------------------------------------

    public long getCustomerId()                  { return customerid; }
    public void setCustomerId(long customerId)   { this.customerid = customerId; }

    public String getNationalId()                { return nationalId; }
    public void setNationalId(String nationalId) { this.nationalId = nationalId; }

    public String getFirstName()                 { return firstName; }
    public void setFirstName(String firstName)   { this.firstName = firstName; }

    public String getLastName()                  { return lastName; }
    public void setLastName(String lastName)     { this.lastName = lastName; }

    public LocalDate getBirthDate()              { return birthDate; }
    public void setBirthDate(LocalDate birthDate){ this.birthDate = birthDate; }

    public String getPhone()                     { return phone; }
    public void setPhone(String phone)           { this.phone = phone; }

    public String getEmail()                     { return email; }
    public void setEmail(String email)           { this.email = email; }

    public CustomerStatus getStatus()                         { return status; }
    public void setStatus(CustomerStatus status)              { this.status = status; }

    public String getBannedReason()                     { return bannedReason; }
    public void setBannedReason(String bannedReason)    { this.bannedReason = bannedReason; }

    public LocalDateTime getBannedAt()                       { return bannedAt; }
    public void setBannedAt(LocalDateTime bannedAt)          { this.bannedAt = bannedAt; }

    public LocalDateTime getArchivedAt()                     { return archivedAt; }
    public void setArchivedAt(LocalDateTime archivedAt)     { this.archivedAt = archivedAt; }

    /** True when an administrator has banned this customer. */
    public boolean isBanned() { return status == CustomerStatus.BANNED; }

    /** Archived customers retain history but cannot authenticate or transact. */
    public boolean isArchived() { return status == CustomerStatus.ARCHIVED; }

    public String getAddress()                   { return address; }
    public void setAddress(String address)       { this.address = address; }

    public String getPostalCode()                { return postalCode; }
    public void setPostalCode(String postalCode) { this.postalCode = postalCode; }

    public String getOccupation()                { return occupation; }
    public void setOccupation(String occupation) { this.occupation = occupation; }

    public LocalDateTime getCreatedAt()                    { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt)      { this.createdAt = createdAt; }

    // -------------------------------------------------------------------------
    // UTILITY
    // -------------------------------------------------------------------------

    /** Convenience: "Alice Johnson" — useful for combo-boxes and table cells. */
    public String getFullName() {
        return firstName + " " + lastName;
    }

    @Override
    public String toString() {
        return "Customer{"
                + "customerId=" + customerid
                + ", nationalId='" + nationalId + '\''
                + ", fullName='" + getFullName() + '\''
                + ", email='" + email + '\''
                + ", postalCode='" + postalCode + '\''
                + ", occupation='" + occupation + '\''
                + ", status=" + status
                + '}';
    }
}
