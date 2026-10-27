<!--
Sync Impact Report (temporary review material; remove before committing):
- Version change: 1.1.0 -> 1.2.0 (new mandatory UI accessibility and EAA compliance principle).
- Modified principles: none.
- Added sections: VI. Accessible User Interfaces.
- Modified sections: Development Workflow and Quality Gates (accessibility verification gate).
- Removed sections: none.
- Follow-up TODOs: none. Dependent plans and tasks must incorporate the accessibility
  requirements at their next affected review; they are not modified by this workflow.
-->
# Task Management Constitution

## Core Principles

### I. Bounded Microservices

The application MUST use a microservices architecture with explicit business boundaries,
documented contracts, and independently deployable services. Each service MUST own its
business rules and data access; another service MUST NOT bypass its contract to modify
its data. Shared infrastructure MAY be used with separate ownership and access controls.
New service boundaries MUST have a stated business or operational justification rather
than creating a service for every entity.

User management and authentication MUST remain separate from task management, using
Supabase as the selected identity service. Task management MUST accept a valid authenticated
identity without separate enrollment and MUST enforce its own task authorization.
Email delivery MUST remain replaceable and independently disableable without breaking
task operations or in-app notifications.

### II. Reproducible Container Environments

Application services MUST be packaged with Docker, and Docker Compose MUST provide the
documented local multi-service environment. Required runtime versions, configuration,
startup commands, persistent volumes, and service health checks MUST be declared.
Secrets MUST be supplied at runtime and MUST NOT be embedded in images or committed files.

Before container-based validation, contributors and agents MUST check that the Docker
daemon and Docker Compose are usable, not merely that the Docker executable exists.
If Docker is unavailable on Windows, they MUST check WSL and its installed distributions
for a working Docker daemon and Compose before declaring container validation blocked.
They MUST NOT install software, alter WSL configuration, or start unrelated services
without authorization. If neither environment works, they MUST report the exact blocker
and MUST NOT claim container validation passed.

### III. Security and Privacy by Design

Every service MUST authenticate external requests and enforce authorization at its own
trust boundary. Internal network placement MUST NOT be treated as proof of trust.
Task content, comments, attachments, history, and notifications MUST be accessible only
to authorized current participants under the feature specification.

Services MUST use least-privilege credentials, validate untrusted input and uploads,
protect secrets, and avoid logging credentials or private task content. Privileged
Supabase credentials MUST NOT be exposed to clients. Production connections carrying
credentials or private content MUST use encrypted transport. Plans MUST identify trust
boundaries, sensitive data, and abuse cases, with authorization and negative-path tests
for affected behavior.

### IV. Mandatory Vulnerability Checks

Changes MUST undergo security review appropriate to their affected trust boundaries.
Automated checks MUST include source security analysis, dependency vulnerability scanning,
secret detection, and container-image vulnerability scanning before release.
Dependency changes MUST trigger dependency checks; image or base-image changes MUST
trigger image checks. A clean dependency scan alone MUST NOT be presented as a complete
security assessment.

Findings MUST record severity, affected component, evidence, and remediation or disposition.
Unresolved Critical or High findings MUST block merge and release unless a maintainer
explicitly accepts a time-limited exception with rationale, owner, expiration date, and
mitigation. Lower-severity findings MUST have a recorded disposition. Failed or unavailable
scanners MUST be reported as incomplete checks, never as clean results.

### V. Verifiable Contracts and Reliable Operations

Service contracts and changes to them MUST be documented and covered by contract or
integration tests. Tests MUST cover relevant business rules, authorization denials,
dependency failures, and retry behavior. Changes to assignment, status, deadlines,
notifications, or uploads MUST preserve their specified history and access controls.

Every Java service MUST include automated JUnit test cases. New or changed Java behavior
MUST have corresponding JUnit tests, including regression tests for bug fixes.
Unit tests MUST cover business rules and boundary cases; Spring integration tests using
JUnit MUST cover affected API contracts, authorization failures, persistence behavior,
and dependency failure or retry paths. Database-dependent behavior MUST be tested against
PostgreSQL rather than relying solely on mocks or an incompatible in-memory database.
Frontend, Socket.IO, and end-to-end tests complement but MUST NOT replace Java JUnit tests.

Cross-service calls MUST have explicit timeouts and visible failure handling. Retried
mutations and deliveries MUST prevent duplicate effects. Services MUST expose health
signals and actionable operational errors without leaking sensitive data. A successful
task action MUST NOT be rolled back solely because optional email delivery fails.
Breaking contract changes MUST include a compatibility or migration plan.

### VI. Accessible User Interfaces

Every user interface MUST comply with the European Accessibility Act (EAA) requirements
applicable to the service and with applicable harmonized accessibility standards, including
EN 301 549. Web content MUST meet WCAG 2.1 Level AA at minimum. Accessibility MUST be
considered in design, implementation, and acceptance criteria, not deferred to a final
polish pass.

All user tasks MUST be operable with a keyboard and expose visible, logical focus. Controls,
forms, validation errors, status changes, and dynamic content MUST have programmatically
determinable names, roles, states, and relationships that work with assistive technologies.
Text and interactive content MUST meet required contrast, zoom, and reflow expectations.
Information MUST NOT be conveyed by color alone. Equivalent text alternatives MUST be
provided for meaningful non-text content.

Every user-facing feature MUST receive automated accessibility checks and manual
keyboard and screen-reader review of its core workflows. Automated tools alone MUST NOT
be treated as proof of compliance. Accessibility defects MUST be tracked and resolved
before release; any legal applicability assessment or exception requires documented
maintainer approval and rationale.

## Architecture and Environment Constraints

- Plans MUST document service responsibilities, data ownership, communication contracts,
  failure handling, and deployment dependencies before implementation.
- Docker Compose MUST distinguish application services from external dependencies such as
  managed Supabase. It MUST document required external configuration without implying
  that managed services run locally.
- Local startup instructions MUST cover native Docker and the Windows WSL fallback,
  including how to verify service health. Runtime data MUST survive ordinary container
  recreation where persistence is required.
- Container services MUST run without unnecessary privilege, expose only required ports,
  and use maintained base images with reproducible version references.
- Destructive volume cleanup, production changes, and environment-wide configuration
  changes MUST require explicit authorization; they are not implicit setup steps.

## Development Workflow and Quality Gates

1. Specifications MUST state user outcomes, scope, permissions, and measurable acceptance
   criteria. Plans MUST include an explicit constitution-compliance review and identify
   any proposed exceptions before implementation.
2. Implementation MUST include tests for changed behavior and affected service contracts.
   Contributors MUST run the smallest relevant tests, build/type checks, and required
   security and accessibility checks, then expand validation when the impact warrants it.
   User-facing changes MUST include automated accessibility checks and manual keyboard
   and screen-reader verification of affected workflows. Java builds and CI MUST execute
   applicable JUnit unit and integration test suites and fail on test failures. Required
   suites MUST NOT be silently skipped; blocked execution MUST be reported as incomplete
   validation.
3. Before release, the composed environment MUST start successfully and pass health and
   cross-service smoke checks. Release evidence MUST include all mandatory vulnerability
   checks and any accepted exceptions.
4. Review records MUST distinguish completed checks from blocked or unexecuted checks.
   Missing tooling MUST NOT justify silent skipping or a claim of successful validation.
5. Changes to service contracts, environment configuration, or operational procedures
   MUST update their directly related documentation.

## Governance

This constitution takes precedence over conflicting project plans and implementation
conventions. Every plan and code review MUST assess applicable principles; deviations
MUST be explicitly approved by a maintainer and documented with rationale, scope,
mitigation, owner, and review or expiration date.

Amendments MUST state the proposed change, its rationale, and its impact on existing
specifications, plans, services, and operating procedures. Maintainer approval is required
before an amendment takes effect. Dependent artifacts MUST be reviewed at the next
affected planning or implementation step; they are not automatically rewritten.

Versions MUST follow semantic versioning: MAJOR for incompatible principle removals or
redefinitions, MINOR for new principles or materially expanded obligations, and PATCH
for clarifications that do not change obligations. The original ratification date MUST
be preserved; the last-amended date MUST reflect each adopted amendment.

The Sync Impact Report is temporary review material, not governance, and MUST be removed
before committing an adopted amendment.

**Version**: 1.2.0 | **Ratified**: 2026-10-27 | **Last Amended**: 2026-10-27
