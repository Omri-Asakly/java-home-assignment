package com.example.leavemanagement.service;

import com.example.leavemanagement.dto.CreateLeaveRequestDto;
import com.example.leavemanagement.dto.VacationBalanceDto;
import com.example.leavemanagement.exception.InvalidLeaveRequestException;
import com.example.leavemanagement.exception.LeaveRequestConflictException;
import com.example.leavemanagement.model.Employee;
import com.example.leavemanagement.model.LeaveRequest;
import com.example.leavemanagement.model.LeaveStatus;
import com.example.leavemanagement.model.LeaveType;
import com.example.leavemanagement.repository.EmployeeRepository;
import com.example.leavemanagement.repository.LeaveRequestRepository;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Service
public class LeaveRequestService {

    private final EmployeeRepository employeeRepository;
    private final LeaveRequestRepository leaveRequestRepository;

    public LeaveRequestService(EmployeeRepository employeeRepository,
                               LeaveRequestRepository leaveRequestRepository) {
        this.employeeRepository = employeeRepository;
        this.leaveRequestRepository = leaveRequestRepository;
    }

    @Transactional(readOnly = true)
    public List<LeaveRequest> getAll() {
        return leaveRequestRepository.findAllByOrderByStartDateDesc();
    }

    @Transactional(readOnly = true)
    public List<LeaveRequest> search(String name) {
        return leaveRequestRepository.findByEmployee_NameContainingIgnoreCaseOrderByStartDateDesc(name);
    }

    @Transactional(readOnly = true)
    public VacationBalanceDto getVacationBalance(Long employeeId, int year) {
        Employee employee = employeeRepository.findById(employeeId)
                .orElseThrow(() -> new EntityNotFoundException("Employee not found"));
        LocalDate yearStart = LocalDate.of(year, 1, 1);
        LocalDate yearEnd = LocalDate.of(year, 12, 31);
        long usedDays = calculateApprovedVacationDaysForYear(employeeId, yearStart, yearEnd);

        return new VacationBalanceDto(
                employeeId,
                year,
                employee.getAnnualQuota(),
                usedDays,
                Math.max(employee.getAnnualQuota() - usedDays, 0));
    }

    @Transactional
    public LeaveRequest create(CreateLeaveRequestDto dto) {
        int requestedDays = validateAndCalculateDays(dto.getStartDate(), dto.getEndDate());
        Employee employee = employeeRepository.findById(dto.getEmployeeId())
                .orElseThrow(() -> new EntityNotFoundException("Employee not found"));

        if (dto.getType() == LeaveType.VACATION) {
            validateVacationBalance(employee, dto.getStartDate(), dto.getEndDate(),
                    new InvalidLeaveRequestException("Not enough vacation balance"));
        }

        LeaveRequest request = new LeaveRequest();
        request.setEmployeeId(dto.getEmployeeId());
        request.setType(dto.getType());
        request.setStartDate(dto.getStartDate());
        request.setEndDate(dto.getEndDate());
        request.setDays(requestedDays);
        request.setStatus(LeaveStatus.PENDING);

        return leaveRequestRepository.save(request);
    }

    @Transactional
    public LeaveRequest approve(Long requestId) {
        Long employeeId = leaveRequestRepository.findEmployeeIdByRequestId(requestId)
                .orElseThrow(() -> new EntityNotFoundException("Leave request not found"));

        // The first query returns only a scalar employee id, so no LeaveRequest entity is
        // cached before this lock. Under PostgreSQL READ COMMITTED, the following findById
        // therefore sees any approval committed while this transaction waited for the lock.
        Employee employee = employeeRepository.findByIdForUpdate(employeeId)
                .orElseThrow(() -> new EntityNotFoundException("Employee not found"));
        LeaveRequest request = leaveRequestRepository.findById(requestId)
                .orElseThrow(() -> new EntityNotFoundException("Leave request not found"));

        if (request.getStatus() != LeaveStatus.PENDING) {
            throw new LeaveRequestConflictException("Only pending leave requests can be approved");
        }

        validateAndCalculateDays(request.getStartDate(), request.getEndDate());
        if (request.getType() == LeaveType.VACATION) {
            validateVacationBalance(employee, request.getStartDate(), request.getEndDate(),
                    new LeaveRequestConflictException("Not enough vacation balance to approve this request"));
        }

        request.setStatus(LeaveStatus.APPROVED);
        return leaveRequestRepository.save(request);
    }

    private int validateAndCalculateDays(LocalDate startDate, LocalDate endDate) {
        if (startDate == null || endDate == null) {
            throw new InvalidLeaveRequestException("Start date and end date are required");
        }
        if (startDate.isAfter(endDate)) {
            throw new InvalidLeaveRequestException("Start date must not be after end date");
        }

        long days = ChronoUnit.DAYS.between(startDate, endDate) + 1;
        if (days > Integer.MAX_VALUE) {
            throw new InvalidLeaveRequestException("Leave request date range is too large");
        }
        return (int) days;
    }

    private void validateVacationBalance(Employee employee,
                                          LocalDate startDate,
                                          LocalDate endDate,
                                          RuntimeException insufficientBalanceException) {
        LocalDate yearStart = LocalDate.of(startDate.getYear(), 1, 1);

        while (!yearStart.isAfter(endDate)) {
            LocalDate currentYearStart = yearStart;
            LocalDate yearEnd = LocalDate.of(currentYearStart.getYear(), 12, 31);
            long requestedDays = calculateOverlapDays(startDate, endDate, currentYearStart, yearEnd);
            long usedDays = calculateApprovedVacationDaysForYear(
                    employee.getId(), currentYearStart, yearEnd);

            if (usedDays + requestedDays > employee.getAnnualQuota()) {
                throw insufficientBalanceException;
            }

            if (!yearEnd.isBefore(endDate)) {
                break;
            }
            yearStart = yearEnd.plusDays(1);
        }
    }

    private long calculateApprovedVacationDaysForYear(Long employeeId,
                                                       LocalDate yearStart,
                                                       LocalDate yearEnd) {
        return leaveRequestRepository.findOverlapping(
                        employeeId, LeaveType.VACATION, LeaveStatus.APPROVED, yearStart, yearEnd)
                .stream()
                .mapToLong(request -> calculateOverlapDays(
                        request.getStartDate(), request.getEndDate(), yearStart, yearEnd))
                .sum();
    }

    private long calculateOverlapDays(LocalDate requestStart,
                                      LocalDate requestEnd,
                                      LocalDate periodStart,
                                      LocalDate periodEnd) {
        LocalDate overlapStart = requestStart.isAfter(periodStart) ? requestStart : periodStart;
        LocalDate overlapEnd = requestEnd.isBefore(periodEnd) ? requestEnd : periodEnd;

        return overlapStart.isAfter(overlapEnd)
                ? 0
                : ChronoUnit.DAYS.between(overlapStart, overlapEnd) + 1;
    }
}
