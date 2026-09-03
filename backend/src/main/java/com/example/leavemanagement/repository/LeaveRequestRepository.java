package com.example.leavemanagement.repository;

import com.example.leavemanagement.model.LeaveRequest;
import com.example.leavemanagement.model.LeaveStatus;
import com.example.leavemanagement.model.LeaveType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface LeaveRequestRepository extends JpaRepository<LeaveRequest, Long> {

    List<LeaveRequest> findAllByOrderByStartDateDesc();

    List<LeaveRequest> findByEmployee_NameContainingIgnoreCaseOrderByStartDateDesc(String name);

    @Query("select request.employeeId from LeaveRequest request where request.id = :requestId")
    Optional<Long> findEmployeeIdByRequestId(@Param("requestId") Long requestId);

    @Query("""
            select request
            from LeaveRequest request
            where request.employeeId = :employeeId
              and request.type = :type
              and request.status = :status
              and request.startDate <= :yearEnd
              and request.endDate >= :yearStart
            """)
    List<LeaveRequest> findOverlapping(@Param("employeeId") Long employeeId,
                                       @Param("type") LeaveType type,
                                       @Param("status") LeaveStatus status,
                                       @Param("yearStart") LocalDate yearStart,
                                       @Param("yearEnd") LocalDate yearEnd);
}
