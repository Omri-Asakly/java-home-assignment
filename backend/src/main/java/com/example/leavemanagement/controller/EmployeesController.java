package com.example.leavemanagement.controller;

import com.example.leavemanagement.dto.VacationBalanceDto;
import com.example.leavemanagement.model.Employee;
import com.example.leavemanagement.repository.EmployeeRepository;
import com.example.leavemanagement.service.LeaveRequestService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

// Read-only lookup so the UI can offer an employee picker.
@RestController
@RequestMapping("/api/employees")
public class EmployeesController {

    private final EmployeeRepository employeeRepository;
    private final LeaveRequestService leaveRequestService;

    public EmployeesController(EmployeeRepository employeeRepository,
                               LeaveRequestService leaveRequestService) {
        this.employeeRepository = employeeRepository;
        this.leaveRequestService = leaveRequestService;
    }

    // GET /api/employees
    @GetMapping
    public ResponseEntity<List<Employee>> getAll() {
        return ResponseEntity.ok(employeeRepository.findAll());
    }

    @GetMapping("/{employeeId}/vacation-balance")
    public ResponseEntity<VacationBalanceDto> getVacationBalance(@PathVariable Long employeeId,
                                                                 @RequestParam int year) {
        return ResponseEntity.ok(leaveRequestService.getVacationBalance(employeeId, year));
    }
}
