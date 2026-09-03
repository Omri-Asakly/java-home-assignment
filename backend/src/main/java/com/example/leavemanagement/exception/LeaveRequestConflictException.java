package com.example.leavemanagement.exception;

public class LeaveRequestConflictException extends RuntimeException {

    public LeaveRequestConflictException(String message) {
        super(message);
    }
}
