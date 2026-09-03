package com.example.leavemanagement.controller;

import com.example.leavemanagement.exception.InvalidLeaveRequestException;
import com.example.leavemanagement.exception.LeaveRequestConflictException;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(EntityNotFoundException.class)
    public ResponseEntity<ApiError> handleNotFound(EntityNotFoundException exception) {
        return ResponseEntity.status(404).body(new ApiError(exception.getMessage()));
    }

    @ExceptionHandler(InvalidLeaveRequestException.class)
    public ResponseEntity<ApiError> handleInvalidRequest(InvalidLeaveRequestException exception) {
        return ResponseEntity.badRequest().body(new ApiError(exception.getMessage()));
    }

    @ExceptionHandler(LeaveRequestConflictException.class)
    public ResponseEntity<ApiError> handleConflict(LeaveRequestConflictException exception) {
        return ResponseEntity.status(409).body(new ApiError(exception.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException exception) {
        String message = exception.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(error -> error.getDefaultMessage())
                .orElse("Invalid request");
        return ResponseEntity.badRequest().body(new ApiError(message));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleUnreadableRequest() {
        return ResponseEntity.badRequest().body(new ApiError("Malformed request body"));
    }

    public record ApiError(String message) {}
}
