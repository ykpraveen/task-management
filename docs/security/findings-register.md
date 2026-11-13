# Security Findings Register

The single tracked list of security findings from manual reviews
([threat-review.md](threat-review.md)) and from scanners (source, dependency, secret and
image scans). Every finding keeps its row after it is resolved. This file uses headings and
tables only.

## Fields

| Field | Meaning |
|-------|---------|
| ID | `SEC-NNN`, assigned in order and never reused |
| Source | `Manual review (<checkpoint task>)` or the scanner name and version |
| Severity | `Critical`, `High`, `Medium`, `Low` or `Informational` |
| Component | Service, module or path, plus the package and version or image digest where relevant |
| Evidence | Test name, probe description or scanner rule ID and report path; never secrets or tokens |
| Disposition | `Open`, `Fixed in <commit>`, `False positive` with a reason, or `Accepted` |
| Exception | For an accepted High or Critical finding only: rationale, owner, expiry date (YYYY-MM-DD) and mitigation |

An unresolved High or Critical finding without an unexpired exception blocks release.

## Findings

| ID | Source | Severity | Component | Evidence | Disposition | Exception |
|----|--------|----------|-----------|----------|-------------|-----------|
| | | | | | | |
