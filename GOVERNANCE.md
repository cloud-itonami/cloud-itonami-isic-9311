# Governance

`cloud-itonami-9311` is an OSS open-business blueprint for operation of sports facilities -- stadiums, arenas, gyms and other athletic facility operation.
Governance covers both the capability layer and the operator model.

## Maintainers

Maintainers may merge changes that preserve these invariants:

- the Facility Safety Governor remains independent of the advisor.
- hard policy violations (fabricated inspection/eligibility record, incomplete
  records) cannot be overridden by human approval.
- authorizing facility use during or after a flagged safety condition always escalates to a human -- never automated.
- every hold, approval and operational-action path is auditable.
- patron/member/donor personal data stay outside Git.

## Decision Records

Architecture decisions live in `docs/adr/`. Changes to the trust model,
storage contract, public business model, operator certification or license
should add or update an ADR.

## Operator Governance

Anyone may fork and operate independently. itonami.cloud certification is a
separate trust mark and should require security, audit and data-flow review.

Certified operators can lose certification for:

- bypassing the Facility Safety Governor's policy checks
- mishandling patron/member/donor data
- misrepresenting certification status
- failing to respond to security incidents
- hiding material changes to customer-facing operation
