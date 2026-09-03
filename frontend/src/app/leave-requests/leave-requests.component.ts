import { HttpErrorResponse } from '@angular/common/http';
import { CommonModule } from '@angular/common';
import { Component, DestroyRef, OnInit } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import {
  AbstractControl,
  FormControl,
  FormGroup,
  ReactiveFormsModule,
  ValidationErrors,
  Validators
} from '@angular/forms';
import { finalize, forkJoin } from 'rxjs';
import {
  ApiError,
  CreateLeaveRequest,
  Employee,
  LeaveRequest,
  LeaveStatus,
  LeaveType
} from '../models/leave-request.model';
import { LeaveRequestsApiService } from './leave-requests-api.service';

@Component({
  selector: 'app-leave-requests',
  standalone: true,
  imports: [CommonModule, ReactiveFormsModule],
  templateUrl: './leave-requests.component.html',
  styleUrls: ['./leave-requests.component.css']
})
export class LeaveRequestsComponent implements OnInit {
  readonly LeaveStatus = LeaveStatus;
  readonly leaveTypes = [
    { value: LeaveType.Vacation, label: 'Vacation' },
    { value: LeaveType.Sick, label: 'Sick' },
    { value: LeaveType.Unpaid, label: 'Unpaid' }
  ];

  readonly requestForm = new FormGroup({
    employeeId: new FormControl<number | null>(null, Validators.required),
    type: new FormControl<LeaveType | null>(null, Validators.required),
    startDate: new FormControl('', { nonNullable: true, validators: Validators.required }),
    endDate: new FormControl('', { nonNullable: true, validators: Validators.required })
  }, { validators: dateOrderValidator });

  requests: LeaveRequest[] = [];
  employees: Employee[] = [];
  loading = false;
  submitting = false;
  loadError = '';
  formError = '';
  successMessage = '';
  approvalErrors: Record<number, string> = {};
  approvingRequestIds = new Set<number>();

  private readonly typeLabels: Record<LeaveType, string> = {
    [LeaveType.Vacation]: 'Vacation',
    [LeaveType.Sick]: 'Sick',
    [LeaveType.Unpaid]: 'Unpaid'
  };
  private readonly statusLabels: Record<LeaveStatus, string> = {
    [LeaveStatus.Pending]: 'Pending',
    [LeaveStatus.Approved]: 'Approved',
    [LeaveStatus.Rejected]: 'Rejected'
  };

  constructor(
    private readonly api: LeaveRequestsApiService,
    private readonly destroyRef: DestroyRef
  ) {}

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.loading = true;
    this.loadError = '';

    forkJoin({
      requests: this.api.listLeaveRequests(),
      employees: this.api.listEmployees()
    }).pipe(
      takeUntilDestroyed(this.destroyRef),
      finalize(() => this.loading = false)
    ).subscribe({
      next: ({ requests, employees }) => {
        this.employees = employees;
        this.requests = requests;
      },
      error: (error: unknown) => {
        this.loadError = this.errorMessage(error, 'Unable to load leave requests.');
      }
    });
  }

  submitRequest(): void {
    this.formError = '';
    this.successMessage = '';

    if (this.requestForm.invalid) {
      this.requestForm.markAllAsTouched();
      return;
    }

    const value = this.requestForm.getRawValue();
    if (value.employeeId === null || value.type === null) {
      return;
    }

    const request: CreateLeaveRequest = {
      employeeId: value.employeeId,
      type: value.type,
      startDate: value.startDate,
      endDate: value.endDate
    };

    this.submitting = true;
    this.api.createLeaveRequest(request).pipe(
      takeUntilDestroyed(this.destroyRef),
      finalize(() => this.submitting = false)
    ).subscribe({
      next: created => {
        this.requests = [this.withEmployee(created), ...this.requests]
          .sort((left, right) => right.startDate.localeCompare(left.startDate));
        this.successMessage = 'Leave request created successfully.';
        this.requestForm.reset({
          employeeId: null,
          type: null,
          startDate: '',
          endDate: ''
        });
      },
      error: (error: unknown) => {
        this.formError = this.errorMessage(error, 'Unable to create the leave request.');
      }
    });
  }

  approve(id: number): void {
    if (this.approvingRequestIds.has(id)) {
      return;
    }

    this.successMessage = '';
    this.approvalErrors = { ...this.approvalErrors, [id]: '' };
    this.approvingRequestIds = new Set(this.approvingRequestIds).add(id);

    this.api.approveLeaveRequest(id).pipe(
      takeUntilDestroyed(this.destroyRef),
      finalize(() => {
        const remainingIds = new Set(this.approvingRequestIds);
        remainingIds.delete(id);
        this.approvingRequestIds = remainingIds;
      })
    ).subscribe({
      next: approved => {
        this.requests = this.requests.map(request =>
          request.id === approved.id ? this.withEmployee(approved) : request
        );
        this.successMessage = `Request #${approved.id} approved successfully.`;
      },
      error: (error: unknown) => {
        this.approvalErrors = {
          ...this.approvalErrors,
          [id]: this.errorMessage(error, 'Unable to approve this request.')
        };
      }
    });
  }

  typeLabel(type: LeaveType): string {
    return this.typeLabels[type];
  }

  statusLabel(status: LeaveStatus): string {
    return this.statusLabels[status];
  }

  employeeName(request: LeaveRequest): string {
    return request.employee?.name
      ?? this.employees.find(employee => employee.id === request.employeeId)?.name
      ?? 'Unknown employee';
  }

  private withEmployee(request: LeaveRequest): LeaveRequest {
    if (request.employee) {
      return request;
    }

    return {
      ...request,
      employee: this.employees.find(employee => employee.id === request.employeeId) ?? null
    };
  }

  private errorMessage(error: unknown, fallback: string): string {
    if (!(error instanceof HttpErrorResponse)) {
      return fallback;
    }

    if (isApiError(error.error)) {
      return error.error.message;
    }

    if (typeof error.error === 'string' && error.error.trim()) {
      return error.error;
    }

    return fallback;
  }
}

function dateOrderValidator(control: AbstractControl): ValidationErrors | null {
  const startDate = control.get('startDate')?.value;
  const endDate = control.get('endDate')?.value;

  if (typeof startDate !== 'string' || typeof endDate !== 'string' || !startDate || !endDate) {
    return null;
  }

  return startDate <= endDate ? null : { dateOrder: true };
}

function isApiError(value: unknown): value is ApiError {
  return typeof value === 'object'
    && value !== null
    && 'message' in value
    && typeof value.message === 'string';
}
