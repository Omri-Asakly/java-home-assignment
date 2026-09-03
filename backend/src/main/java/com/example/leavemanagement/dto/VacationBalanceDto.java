package com.example.leavemanagement.dto;

public record VacationBalanceDto(
        Long employeeId,
        int year,
        int annualQuota,
        long used,
        long remaining) {
}
