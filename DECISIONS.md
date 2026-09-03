# DECISIONS

## 1. Architecture and design

- **Leave-request flow:** I moved validation, quota calculation, creation, and approval from `LeaveRequestsController` into `LeaveRequestService`. For this flow, the structure is Controller -> Service -> Repository: the controller handles HTTP, the service owns business rules and transaction boundaries, and repositories own persistence queries.
- **Deliberately small design:** I did not introduce service interfaces, mapper frameworks, CQRS, events, NgRx, or similar abstractions. They would add indirection without improving this take-home's small use case. The read-only employee lookup remains simple and accesses its repository directly.
- **HTTP errors:** Bean Validation and a small `RestControllerAdvice` return 400 for invalid input, 404 for missing resources, and 409 for approval conflicts. The shared `{ "message": "..." }` body keeps frontend error handling straightforward.
- **Frontend:** Leave requests and numeric API enums are typed instead of using `any`, and `HttpClient` calls live in a typed API service. The component uses a Reactive Form with required-field and date-order validation. It tracks submission and approval state separately, displays success/errors without browser alerts, and updates the affected local item after create or approve instead of reloading the full list.

## 2. Vacation quota

The starter code calculated approved usage but did not include it in the quota comparison, so it effectively checked only whether the new request exceeded the employee's full quota.

Vacation quota is now evaluated per calendar year. Only `APPROVED` `VACATION` requests for the same employee consume quota; pending, sick, and unpaid requests do not. Creation performs the check to give useful early feedback, while approval repeats it because the balance may have changed and approval-time state is authoritative.

A vacation request spanning calendar years remains one `LeaveRequest`. For quota calculation only, the service determines the inclusive date overlap with each affected year and validates that year's portion independently. Existing approved cross-year requests are counted using the same overlap calculation, rather than charging their full stored day count to one year. If any affected year lacks sufficient balance, the create or approve operation fails as a whole.

## 3. Approval and concurrency

Two pending requests can each fit when checked separately but exceed the quota together. `@Transactional` alone does not protect this aggregate invariant because concurrent transactions can read the same approved balance.

Approval therefore uses the employee row as the database synchronization point:

1. Query the employee ID associated with the leave-request ID without loading the request entity.
2. Acquire a JPA `PESSIMISTIC_WRITE` lock on that employee row.
3. Re-read the leave request and confirm it is still `PENDING` while the lock is held.
4. Recalculate approved vacation usage for every calendar year touched by the request.
5. Approve and save in the same transaction.

Approvals for the same employee serialize on one row, including two attempts to approve the same request. Approvals for different employees lock different rows and can proceed independently. Missing requests return 404; already approved or rejected requests and insufficient approval-time quota return 409.

## 4. Security

The starter employee-name search concatenated user-controlled text into native SQL, allowing the input to alter the query structure. I replaced it with the Spring Data query method `findByEmployee_NameContainingIgnoreCaseOrderByStartDateDesc(String name)`, where the value is parameter-bound. Parameterization removes the SQL injection path; this does not claim that SQL `LIKE` wildcard characters are escaped.

Authentication and authorization remain a production limitation; approval would need an authenticated manager or HR role.

## 5. Testing

Backend integration tests use Spring Boot, MockMvc, and PostgreSQL 16 through Testcontainers. The 20 test executions cover quota success, rejection above the remaining quota, exact remaining quota, prior-year isolation, date and required-field validation, missing employees, approval states, approval-time quota revalidation, parameterized search behavior, and concurrent approvals. Cross-year tests cover successful allocation, failure in either affected year, atomic rejection, correct allocation of existing approved cross-year leave, and approval-time revalidation.

The concurrency test starts two approvals together for requests that fit individually but not jointly, and asserts one approval and one conflict.

The existing Angular smoke test passes, and the production Angular build succeeds with strict TypeScript/template checking. Frontend behavioral unit-test coverage remains limited.

## 6. Deferred work

- **Authentication and authorization:** add identity and restrict approval to an appropriate manager or HR role.
- **Schema migrations and enum persistence:** replace `ddl-auto: update` with Flyway and migrate ordinal enums through a versioned compatibility plan.
- **Frontend tests:** add focused tests for form validation, create errors, per-row approval state, local updates, and 409 feedback.
- **API configuration:** move the hard-coded local API base URL to environment-based or same-origin configuration.
- **Leave policy:** confirm overlap, business-day, weekend, and holiday rules with product/HR before implementing them.
- **Pagination:** add pageable list queries if expected data volume and UI requirements justify it.

With another day, I would prioritize frontend behavioral tests, authorization around approval, deployable API configuration, and versioned schema migrations. This submission addresses the take-home scope; it is not presented as production-ready.

## 7. AI usage

### Example 1: quota and concurrency analysis

**Prompt excerpt**

> "Identify the intended vacation balance bug ... race conditions when two leave requests for the same employee are approved concurrently ... why `@Transactional` alone may or may not solve the race ... [and] the simplest correct PostgreSQL/JPA locking strategy."

**What the AI suggested**

The analysis identified that approved usage was ignored, calendar-year filtering was missing, and transactions without a shared lock left an approval race. It proposed moving the rules into a service and using a pessimistic employee-row lock.

**What I actually did**

I kept the Controller -> Service -> Repository approach and refined the approval ordering: obtain only the employee ID, lock that employee, then reload and validate the request so no pre-lock request entity is trusted.

### Example 2: revising the cross-year quota design

**Prompt excerpt**

> "The previous design was to reject VACATION requests that span multiple calendar years and require the user to submit separate requests. I do NOT want that design. Instead, implement cross-year vacation validation by splitting the requested leave logically across the calendar years it touches, validating each year independently, while keeping the request itself atomic."

**What the AI suggested**

The initial analysis proposed calculating cross-year vacation separately for each affected calendar year. I initially chose a simpler restriction instead because the required business policy was not explicit.

**What I actually did**

I later reconsidered that restriction because it unnecessarily prevented a valid date range. I refined the design so one request can span multiple years while its quota impact is calculated independently for each year. Existing approved cross-year requests use the same overlap logic. If any year lacks balance, the entire operation fails atomically. This was an iterative decision rather than blindly accepting either the first proposal or my first interpretation.

### Example 3: keeping the frontend scoped

**Prompt excerpt**

> "Remove `any` ... create [a] typed API service ... add [a] new leave request form ... prevent duplicate approval clicks ... avoid blind full reload after approval ... Do NOT introduce NgRx."

**What the AI suggested**

It suggested typed API models, one HTTP service, a Reactive Form, per-request approval state, and local updates from returned objects.

**What I actually did**

I applied those focused changes in the existing standalone component. I did not add NgRx, a UI framework, new dependencies, or a wider frontend rewrite because they were unnecessary for the assignment.

## 8. Running the project

The commands in `README.md` are unchanged.
