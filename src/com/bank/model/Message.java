package com.bank.model;

import java.time.LocalDateTime;

public class Message {

    public enum SenderType { CUSTOMER, ADMIN }

    private long messageId;
    private long customerId;
    private SenderType senderType;
    private String subject;
    private String message;
    private LocalDateTime createdAt;
    private boolean isRead;

    // Constructors
    public Message() {}

    public Message(long customerId, SenderType senderType, String subject, String message) {
        this.customerId = customerId;
        this.senderType = senderType;
        this.subject = subject;
        this.message = message;
        this.isRead = false;
    }

    // Getters & Setters
    public long getMessageId() { return messageId; }
    public void setMessageId(long messageId) { this.messageId = messageId; }

    public long getCustomerId() { return customerId; }
    public void setCustomerId(long customerId) { this.customerId = customerId; }

    public SenderType getSenderType() { return senderType; }
    public void setSenderType(SenderType senderType) { this.senderType = senderType; }

    public String getSubject() { return subject; }
    public void setSubject(String subject) { this.subject = subject; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public boolean isRead() { return isRead; }
    public void setRead(boolean read) { isRead = read; }
}