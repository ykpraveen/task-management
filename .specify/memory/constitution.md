<!--
Sync Impact Report (temporary review material; remove before committing):
- Version change: unratified scaffold -> 1.0.0 (initial adoption).
- Modified principles: five unnamed scaffold slots replaced with:
  I. Bounded Microservices; II. Reproducible Container Environments;
  III. Security and Privacy by Design; IV. Mandatory Vulnerability Checks;
  V. Verifiable Contracts and Reliable Operations.
- Added sections: Architecture and Environment Constraints;
  Development Workflow and Quality Gates; populated Governance.
- Removed sections: none; scaffold placeholders and examples replaced.
- Follow-up TODOs: none. Feature artifacts must be checked against this constitution
  during planning; they are not modified by this workflow.
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

Cross-service calls MUST have explicit timeouts and visible failure handling. Retried
mutations and deliveries MUST prevent duplicate effects. Services MUST expose health
signals and actionable operational errors without leaking sensitive data. A successful
task action MUST NOT be rolled back solely because optional email delivery fails.
Breaking contract changes MUST include a compatibility or migration plan.

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
   security checks, then expand validation when the impact warrants it.
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

**Version**: 1.0.0 | **Ratified**: 2026-10-27 | **Last Amended**: 2026-10-27
