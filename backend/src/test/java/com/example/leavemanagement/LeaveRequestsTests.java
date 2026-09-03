package com.example.leavemanagement;

import com.example.leavemanagement.dto.CreateLeaveRequestDto;
import com.example.leavemanagement.exception.LeaveRequestConflictException;
import com.example.leavemanagement.model.Employee;
import com.example.leavemanagement.model.LeaveRequest;
import com.example.leavemanagement.model.LeaveStatus;
import com.example.leavemanagement.model.LeaveType;
import com.example.leavemanagement.repository.EmployeeRepository;
import com.example.leavemanagement.repository.LeaveRequestRepository;
import com.example.leavemanagement.service.LeaveRequestService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static java.util.Collections.frequency;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class LeaveRequestsTests {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private EmployeeRepository employees;

    @Autowired
    private LeaveRequestRepository leaveRequests;

    @Autowired
    private LeaveRequestService leaveRequestService;

    @BeforeEach
    void cleanDatabase() {
        leaveRequests.deleteAllInBatch();
        employees.deleteAllInBatch();
    }

    @Test
    void createVacationWithinQuotaSucceeds() throws Exception {
        Employee employee = saveEmployee(20);

        create(createDto(employee, LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 3)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.days").value(3))
                .andExpect(jsonPath("$.status").value(LeaveStatus.PENDING.ordinal()));

        assertEquals(1, leaveRequests.count());
    }

    @Test
    void createVacationExceedingRemainingQuotaFails() throws Exception {
        Employee employee = saveEmployee(20);
        saveRequest(employee, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 18),
                LeaveType.VACATION, LeaveStatus.APPROVED);

        create(createDto(employee, LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 3)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Not enough vacation balance"));

        assertEquals(1, leaveRequests.count());
    }

    @Test
    void createVacationUsingExactlyRemainingQuotaSucceeds() throws Exception {
        Employee employee = saveEmployee(20);
        saveRequest(employee, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 18),
                LeaveType.VACATION, LeaveStatus.APPROVED);

        create(createDto(employee, LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 2)))
                .andExpect(status().isOk());

        assertEquals(2, leaveRequests.count());
    }

    @Test
    void approvedVacationFromPreviousYearDoesNotConsumeCurrentYearQuota() throws Exception {
        Employee employee = saveEmployee(20);
        saveRequest(employee, LocalDate.of(2025, 1, 1), LocalDate.of(2025, 1, 20),
                LeaveType.VACATION, LeaveStatus.APPROVED);

        create(createDto(employee, LocalDate.of(2026, 2, 1), LocalDate.of(2026, 2, 20)))
                .andExpect(status().isOk());

        assertEquals(2, leaveRequests.count());
    }

    @Test
    void crossYearVacationIsRejected() throws Exception {
        Employee employee = saveEmployee(20);

        create(createDto(employee, LocalDate.of(2026, 12, 30), LocalDate.of(2027, 1, 2)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        "Vacation requests spanning multiple calendar years must be submitted as separate requests"));

        assertEquals(0, leaveRequests.count());
    }

    @Test
    void invalidDateOrderReturnsBadRequest() throws Exception {
        Employee employee = saveEmployee(20);

        create(createDto(employee, LocalDate.of(2026, 3, 3), LocalDate.of(2026, 3, 1)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Start date must not be after end date"));

        assertEquals(0, leaveRequests.count());
    }

    @Test
    void missingRequiredFieldsReturnBadRequest() throws Exception {
        mockMvc.perform(post("/api/leave-requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.message").isString());

        assertEquals(0, leaveRequests.count());
    }

    @Test
    void malformedRequestBodyReturnsConsistentBadRequest() throws Exception {
        mockMvc.perform(post("/api/leave-requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"employeeId\": 1, \"startDate\": \"not-a-date\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.message").value("Malformed request body"));
    }

    @Test
    void employeeNotFoundReturnsNotFound() throws Exception {
        CreateLeaveRequestDto dto = new CreateLeaveRequestDto();
        dto.setEmployeeId(Long.MAX_VALUE);
        dto.setType(LeaveType.VACATION);
        dto.setStartDate(LocalDate.of(2026, 3, 1));
        dto.setEndDate(LocalDate.of(2026, 3, 2));

        create(dto)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Employee not found"));
    }

    @Test
    void pendingRequestCanBeApproved() throws Exception {
        Employee employee = saveEmployee(20);
        LeaveRequest pending = saveRequest(employee, LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 3),
                LeaveType.VACATION, LeaveStatus.PENDING);

        approve(pending.getId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(LeaveStatus.APPROVED.ordinal()));

        assertEquals(LeaveStatus.APPROVED, leaveRequests.findById(pending.getId()).orElseThrow().getStatus());
    }

    @Test
    void approvingNonexistentRequestReturnsNotFound() throws Exception {
        approve(Long.MAX_VALUE)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Leave request not found"));
    }

    @ParameterizedTest
    @EnumSource(value = LeaveStatus.class, names = {"APPROVED", "REJECTED"})
    void approvingNonPendingRequestReturnsConflict(LeaveStatus statusValue) throws Exception {
        Employee employee = saveEmployee(20);
        LeaveRequest request = saveRequest(employee, LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 2),
                LeaveType.VACATION, statusValue);

        approve(request.getId())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Only pending leave requests can be approved"));

        assertEquals(statusValue, leaveRequests.findById(request.getId()).orElseThrow().getStatus());
    }

    @Test
    void approvalRevalidatesVacationQuota() throws Exception {
        Employee employee = saveEmployee(5);
        LeaveRequest pending = leaveRequestService.create(
                createDto(employee, LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 5)));
        saveRequest(employee, LocalDate.of(2026, 2, 1), LocalDate.of(2026, 2, 1),
                LeaveType.VACATION, LeaveStatus.APPROVED);

        approve(pending.getId())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Not enough vacation balance to approve this request"));

        assertEquals(LeaveStatus.PENDING, leaveRequests.findById(pending.getId()).orElseThrow().getStatus());
    }

    @Test
    void searchParameterCannotChangeQueryStructure() throws Exception {
        Employee employee = saveEmployee(20);
        employee.setName("Searchable Employee");
        employees.save(employee);
        saveRequest(employee, LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 1),
                LeaveType.SICK, LeaveStatus.PENDING);

        mockMvc.perform(get("/api/leave-requests/search").param("name", "' OR 1=1 --"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void concurrentApprovalsCannotJointlyExceedQuota() throws Exception {
        Employee employee = saveEmployee(5);
        LeaveRequest first = saveRequest(employee, LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 5),
                LeaveType.VACATION, LeaveStatus.PENDING);
        LeaveRequest second = saveRequest(employee, LocalDate.of(2026, 4, 1), LocalDate.of(2026, 4, 5),
                LeaveType.VACATION, LeaveStatus.PENDING);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<ApprovalOutcome> firstResult = executor.submit(approvalTask(first.getId(), ready, start));
            Future<ApprovalOutcome> secondResult = executor.submit(approvalTask(second.getId(), ready, start));

            assertTrue(ready.await(5, TimeUnit.SECONDS), "Approval tasks did not become ready in time");
            start.countDown();

            List<ApprovalOutcome> outcomes = List.of(
                    firstResult.get(10, TimeUnit.SECONDS),
                    secondResult.get(10, TimeUnit.SECONDS));

            assertEquals(1, frequency(outcomes, ApprovalOutcome.APPROVED));
            assertEquals(1, frequency(outcomes, ApprovalOutcome.CONFLICT));
            assertEquals(5, leaveRequests.sumDaysForYear(
                    employee.getId(), LeaveType.VACATION, LeaveStatus.APPROVED,
                    LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31)));
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    private Callable<ApprovalOutcome> approvalTask(Long requestId,
                                                    CountDownLatch ready,
                                                    CountDownLatch start) {
        return () -> {
            ready.countDown();
            if (!start.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Concurrent approval start timed out");
            }
            try {
                leaveRequestService.approve(requestId);
                return ApprovalOutcome.APPROVED;
            } catch (LeaveRequestConflictException exception) {
                return ApprovalOutcome.CONFLICT;
            }
        };
    }

    private Employee saveEmployee(int annualQuota) {
        Employee employee = new Employee();
        employee.setName("Test Employee");
        employee.setAnnualQuota(annualQuota);
        return employees.save(employee);
    }

    private LeaveRequest saveRequest(Employee employee,
                                     LocalDate startDate,
                                     LocalDate endDate,
                                     LeaveType type,
                                     LeaveStatus status) {
        LeaveRequest request = new LeaveRequest();
        request.setEmployeeId(employee.getId());
        request.setType(type);
        request.setStartDate(startDate);
        request.setEndDate(endDate);
        request.setDays((int) ChronoUnit.DAYS.between(startDate, endDate) + 1);
        request.setStatus(status);
        return leaveRequests.save(request);
    }

    private CreateLeaveRequestDto createDto(Employee employee, LocalDate startDate, LocalDate endDate) {
        CreateLeaveRequestDto dto = new CreateLeaveRequestDto();
        dto.setEmployeeId(employee.getId());
        dto.setType(LeaveType.VACATION);
        dto.setStartDate(startDate);
        dto.setEndDate(endDate);
        return dto;
    }

    private ResultActions create(CreateLeaveRequestDto dto) throws Exception {
        return mockMvc.perform(post("/api/leave-requests")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(dto)));
    }

    private ResultActions approve(Long requestId) throws Exception {
        return mockMvc.perform(post("/api/leave-requests/{id}/approve", requestId));
    }

    private enum ApprovalOutcome {
        APPROVED,
        CONFLICT
    }
}
