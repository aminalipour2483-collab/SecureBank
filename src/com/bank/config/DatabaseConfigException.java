package com.bank.config;

/** Thrown when the database connection settings are missing or invalid. Messages never contain the password. */
public class DatabaseConfigException extends IllegalStateException {
    public DatabaseConfigException(String message) { super(message); }
    public DatabaseConfigException(String message, Throwable cause) { super(message, cause); }
}
