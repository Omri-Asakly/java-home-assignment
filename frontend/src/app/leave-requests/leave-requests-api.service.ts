import { HttpClient } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import {
  CreateLeaveRequest,
  Employee,
  LeaveRequest
} from '../models/leave-request.model';

@Injectable({ providedIn: 'root' })
export class LeaveRequestsApiService {
  private readonly apiUrl = 'http://localhost:5080/api';

  constructor(private readonly http: HttpClient) {}

  listLeaveRequests(): Observable<LeaveRequest[]> {
    return this.http.get<LeaveRequest[]>(`${this.apiUrl}/leave-requests`);
  }

  createLeaveRequest(request: CreateLeaveRequest): Observable<LeaveRequest> {
    return this.http.post<LeaveRequest>(`${this.apiUrl}/leave-requests`, request);
  }

  approveLeaveRequest(id: number): Observable<LeaveRequest> {
    return this.http.post<LeaveRequest>(`${this.apiUrl}/leave-requests/${id}/approve`, {});
  }

  listEmployees(): Observable<Employee[]> {
    return this.http.get<Employee[]>(`${this.apiUrl}/employees`);
  }
}
