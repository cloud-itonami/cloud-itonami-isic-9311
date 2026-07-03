# cloud-itonami-9311

Open Business Blueprint for **ISIC Rev.5 9311**: Operation of sports facilities.

This repository designs a forkable OSS business for operation of sports facilities -- stadiums, arenas, gyms and other athletic facility operation -- run by a qualified, licensed operator so a community or
independent operator never surrenders patron/collection data and ledgers to a
closed SaaS.

## Robotics premise

All cloud-itonami verticals are designed on the premise that a **robot performs
the physical domain work**. Here a facility-inspection robot performs physical safety-condition checks,
under an actor that proposes actions and an independent **Facility Safety Governor**
that gates them. The governor never dispatches hardware itself;
`:high`/`:safety-critical` actions require human sign-off.

## Core Contract

```text
intake + identity + operational records
        |
        v
FacilityOps-LLM -> Facility Safety Governor -> hold, proceed, or human approval
        |
        v
operational ledger + evidence record + audit
```

No automated proposal, by itself, can complete the following without governor
approval and audit evidence: authorizing facility use during or after a flagged safety condition.

## Capability layer

This blueprint resolves its technology stack via
[`kotoba-lang/industry`](https://github.com/kotoba-lang/industry) (ISIC
`9311`). This vertical's operational records are practice-specific
rather than a shared cross-operator data contract, so it runs on the generic
identity/forms/dmn/bpmn/audit-ledger stack -- no bespoke domain capability lib.

See [`docs/business-model.md`](docs/business-model.md) and
[`docs/operator-guide.md`](docs/operator-guide.md).

## Maturity

`:blueprint` -- this repository is the published business/operator design.
The governed actor implementation (`FacilityOps-LLM` + `Facility Safety Governor` as
running code) is a follow-up, same as any other `:blueprint`-tier
`cloud-itonami-*` entry in `kotoba-lang/industry`'s registry.

## License

Code and implementation templates are AGPL-3.0-or-later.
