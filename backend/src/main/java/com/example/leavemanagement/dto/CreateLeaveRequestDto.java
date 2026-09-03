package com.example.leavemanagement.dto;

import com.example.leavemanagement.model.LeaveType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.time.LocalDate;

// Incoming payload for creating a leave request.
public class CreateLeaveRequestDto {

    @NotNull(message = "Employee is required")
    @Positive(message = "Employee id must be positive")
    private Long employeeId;

    @NotNull(message = "Leave type is required")
    private LeaveType type;

    @NotNull(message = "Start date is required")
    private LocalDate startDate;

    @NotNull(message = "End date is required")
    private LocalDate endDate;

    public Long getEmployeeId() { return employeeId; }
    public void setEmployeeId(Long employeeId) { this.employeeId = employeeId; }

    public LeaveType getType() { return type; }
    public void setType(LeaveType type) { this.type = type; }

    public LocalDate getStartDate() { return startDate; }
    public void setStartDate(LocalDate startDate) { this.startDate = startDate; }

    public LocalDate getEndDate() { return endDate; }
    public void setEndDate(LocalDate endDate) { this.endDate = endDate; }
}
