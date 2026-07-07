# cloud-itonami-isic-9311

Open Business Blueprint for **ISIC Rev.5 9311**: Operation of sports
facilities. This repository publishes a sports-facility actor --
facility intake, jurisdiction assessment, post-hold inspection
screening and facility-use authorization -- as an OSS business that
any qualified, licensed venue operator can fork, deploy, run, improve
and sell.

Built on this workspace's
[`langgraph-clj`](https://github.com/com-junkawasaki/langgraph-clj)
StateGraph runtime (portable `.cljc`, supervised superstep loop,
interrupts, Datomic/in-mem checkpoints) -- the same actor pattern as
every prior actor in this fleet
([`cloud-itonami-isic-6511`](https://github.com/cloud-itonami/cloud-itonami-isic-6511),
[`6512`](https://github.com/cloud-itonami/cloud-itonami-isic-6512),
[`6621`](https://github.com/cloud-itonami/cloud-itonami-isic-6621),
[`6622`](https://github.com/cloud-itonami/cloud-itonami-isic-6622),
[`6629`](https://github.com/cloud-itonami/cloud-itonami-isic-6629),
[`6520`](https://github.com/cloud-itonami/cloud-itonami-isic-6520),
[`6530`](https://github.com/cloud-itonami/cloud-itonami-isic-6530),
[`6820`](https://github.com/cloud-itonami/cloud-itonami-isic-6820),
[`6612`](https://github.com/cloud-itonami/cloud-itonami-isic-6612),
[`6492`](https://github.com/cloud-itonami/cloud-itonami-isic-6492),
[`6920`](https://github.com/cloud-itonami/cloud-itonami-isic-6920),
[`6611`](https://github.com/cloud-itonami/cloud-itonami-isic-6611),
[`7120`](https://github.com/cloud-itonami/cloud-itonami-isic-7120),
[`8620`](https://github.com/cloud-itonami/cloud-itonami-isic-8620),
[`8530`](https://github.com/cloud-itonami/cloud-itonami-isic-8530),
[`9200`](https://github.com/cloud-itonami/cloud-itonami-isic-9200),
[`7500`](https://github.com/cloud-itonami/cloud-itonami-isic-7500),
[`9603`](https://github.com/cloud-itonami/cloud-itonami-isic-9603),
[`9521`](https://github.com/cloud-itonami/cloud-itonami-isic-9521),
[`9321`](https://github.com/cloud-itonami/cloud-itonami-isic-9321),
[`8730`](https://github.com/cloud-itonami/cloud-itonami-isic-8730),
[`9102`](https://github.com/cloud-itonami/cloud-itonami-isic-9102),
[`9103`](https://github.com/cloud-itonami/cloud-itonami-isic-9103),
[`9602`](https://github.com/cloud-itonami/cloud-itonami-isic-9602),
[`9000`](https://github.com/cloud-itonami/cloud-itonami-isic-9000),
[`8890`](https://github.com/cloud-itonami/cloud-itonami-isic-8890),
[`8610`](https://github.com/cloud-itonami/cloud-itonami-isic-8610)) --
a second leisure-attractions vertical (ISIC division 93) in this
fleet, alongside `9321`'s amusement parks, but for gyms/stadiums/
arenas rather than mechanical rides. Here it is **FacilityOps-LLM ⊣
Facility Safety Governor**.

> **Why an actor layer at all?** An LLM is great at drafting a
> facility-intake summary, normalizing records, and checking whether a
> facility's own current occupancy count actually exceeds its own
> recorded capacity limit -- but it has **no notion of which
> jurisdiction's assembly-venue/occupancy-safety requirements are
> official, no license to authorize real facility use, and no way to
> know on its own whether a facility's own post-hold inspection
> actually passed**. Letting it authorize facility use directly
> invites fabricated jurisdiction citations, an occupancy count
> quietly exceeding the venue's own fire-code capacity limit, and a
> failed post-hold inspection being waved through -- and liability, and
> patron-safety risk, for whoever runs it. This project seals the
> FacilityOps-LLM into a single node and wraps it with an independent
> **Facility Safety Governor**, a human **approval workflow**, and an
> immutable **audit ledger**.

## Scope: what this actor does and does not do

This actor covers facility intake through jurisdiction assessment,
post-hold inspection screening and facility-use authorization. It does
**not**, by itself, hold any license required to operate a sports
facility in a given jurisdiction, and it does not claim to. It also
does **not** model a full fire-code/life-safety engineering review --
no structural-load analysis, no means-of-egress engineering
calculations, no full crowd-dynamics modeling (see `facility.registry/
occupancy-exceeds-capacity?`'s own docstring for the honest
simplification this makes: a single occupancy-count-vs-capacity-limit
comparison, not a full life-safety engineering review). Whoever
deploys and operates a live instance (a licensed venue operator)
supplies any jurisdiction-specific license, the real facility-safety/
engineering expertise and the real facility-management-system
integrations, and bears that jurisdiction's liability -- the software
supplies the governed, spec-cited, audited execution scaffold so that
operator does not have to build the compliance layer from scratch for
every new market.

### Actuation

**Authorizing real facility use during or after a flagged safety
condition is never autonomous, at any phase, by construction.** Two
independent layers enforce this (`facility.governor`'s `:actuation/
authorize-facility-use` high-stakes gate and `facility.phase`'s phase
table, which never puts `:facility/authorize-use` in any phase's
`:auto` set) -- see `facility.phase`'s docstring and `test/facility/
phase_test.clj`'s `facility-authorize-use-never-auto-at-any-phase`.
The actor may draft, check and recommend; a human venue operator is
always the one who actually authorizes facility use. Like `6511`/
`6621`/`6629`/`6612`/`6492`/`7120`/`8620`/`7500`/`9603`/`9321`/`9602`/
`9000`, this actor has ONE actuation event.

## The core contract

```
facility intake + jurisdiction facts (facility.facts, spec-cited)
        |
        v
   ┌──────────────┐   proposal      ┌───────────────────────┐
   │ FacilityOps- │ ─────────────▶ │ Facility                    │  (independent system)
   │ LLM (sealed) │  + citations    │ Safety Governor: spec-basis ·│
   └──────────────┘                 │ evidence-incomplete ·        │
                             commit ◀────┼──────────▶ hold │ occupancy-exceeds-
                                 │             │           │ capacity (non-temporal
                           record + ledger  escalate ─▶ human   MAXIMUM-ceiling) ·
                                             (ALWAYS for         inspection-not-passed
                                              :facility/            (unconditional) ·
                                              authorize-use)          already-authorized
```

**The FacilityOps-LLM never authorizes facility use the Facility
Safety Governor would reject, and never does so without a human sign-
off.** Hard violations (fabricated jurisdiction requirements;
unsupported safety evidence; an occupancy count exceeding the
facility's own capacity limit; a failed post-hold inspection; a double
authorization) force **hold** and *cannot* be approved past; a clean
authorization proposal still always routes to a human.

## Run

```bash
clojure -M:dev:run     # walk one clean lifecycle (facility-use authorization) + four HARD-hold cases through the actor
clojure -M:dev:test    # governor contract · phase invariants · store parity · registry conformance · facts coverage
clojure -M:lint        # clj-kondo (errors fail; CI mirrors this)
```

## Robotics premise

All cloud-itonami verticals are designed on the premise that a **robot
performs the physical domain work**. Here a facility-inspection robot
performs physical safety-condition checks, under the actor, gated by
the independent **Facility Safety Governor**. The governor never
dispatches hardware itself; `:high`/`:safety-critical` actions require
human sign-off.

## Open business

This repository is not only source code. It is a public, forkable
business model:

| Layer | What is open |
|---|---|
| OSS core | Actor runtime, Facility Safety Governor, facility-use-authorization draft records, audit ledger |
| Business blueprint | Customer, offer, pricing, unit economics, sales motion |
| Operator playbook | How to fork, license, deploy and support the service in a jurisdiction |
| Trust controls | Governance, security reporting, actuation invariant, audit requirements |

See [`docs/business-model.md`](docs/business-model.md) and
[`docs/operator-guide.md`](docs/operator-guide.md) to start this as an
open business on itonami.cloud, and
[`docs/adr/0001-architecture.md`](docs/adr/0001-architecture.md) for the
full architecture and decision record.

## Capability layer

This blueprint resolves its technology stack via
[`kotoba-lang/industry`](https://github.com/kotoba-lang/industry) (ISIC
`9311`). Like `6920`/`7120`/`8620`/`8530`/`9200`/`7500`/`9603`/`9521`/
`9321`/`8730`/`9102`/`9103`/`9602`/`9000`/`8890`/`8610`, this
vertical's facility records are practice-specific rather than a shared
cross-operator data contract, so `facility.*` runs on the generic
identity/forms/dmn/bpmn/audit-ledger stack only -- no bespoke domain
capability lib to reference at all.

## Layout

| File | Role |
|---|---|
| `src/facility/store.cljc` | **Store** protocol -- `MemStore` ‖ `DatomicStore` (`langchain.db`) + append-only audit ledger + facility-use-authorization history. No dynamically-filed sub-record -- the actuation op acts directly on a pre-seeded facility, and the double-authorization guard checks a dedicated `:authorized?` boolean rather than a `:status` value |
| `src/facility/registry.cljc` | Facility-use-authorization draft records, plus `occupancy-exceeds-capacity?` -- generalizes this fleet's MAXIMUM-ceiling family to its FIRST NON-TEMPORAL ground truth (occupancy count vs. capacity limit), reusing `parksafety.registry/operators-sufficient?`'s two-field-on-one-entity comparison shape |
| `src/facility/facts.cljc` | Per-jurisdiction assembly-venue/occupancy-safety catalog with an official spec-basis citation per entry, honest coverage reporting |
| `src/facility/facilityopsllm.cljc` | **FacilityOps-LLM Advisor** -- `mock-advisor` ‖ `llm-advisor`; intake/assessment/inspection-screening/facility-use-authorization proposals |
| `src/facility/governor.cljc` | **Facility Safety Governor** -- 4 HARD checks (spec-basis · evidence-incomplete · occupancy-exceeds-capacity, pure ground-truth two-field recompute · inspection-not-passed, unconditional evaluation, the SEVENTEENTH grounding of this discipline and SECOND specifically for the post-hold-inspection concept after `parksafety`) + already-authorized guard + 1 soft (confidence/actuation gate) |
| `src/facility/phase.cljc` | **Phase 0→3** -- read-only → assisted intake → assisted assess → supervised (authorization always human; facility intake is the ONLY auto-eligible op, no direct capital risk) |
| `src/facility/operation.cljc` | **OperationActor** -- langgraph-clj StateGraph |
| `src/facility/sim.cljc` | demo driver |
| `test/facility/*_test.clj` | governor contract · phase invariants · store parity · registry conformance · facts coverage |

## Business-process coverage (honest)

This actor covers facility intake through jurisdiction assessment,
post-hold inspection screening and facility-use authorization -- the
core governed lifecycle this blueprint's own `docs/business-model.md`
names as its Offer:

| Covered | Not covered (out of scope for this R0) |
|---|---|
| Facility intake + per-jurisdiction assembly-venue/occupancy-safety checklisting, HARD-gated on an official spec-basis citation (`:facility/intake`/`:jurisdiction/assess`) | A full fire-code/life-safety engineering review (structural-load analysis, means-of-egress engineering calculations, full crowd-dynamics modeling -- see `occupancy-exceeds-capacity?`'s docstring) |
| Post-hold inspection screening, evaluated unconditionally so the screening op itself can HARD-hold on its own finding (`:inspection/screen`) | Real facility-management-system integration, booking/scheduling workflows |
| Facility-use authorization, HARD-gated on occupancy not exceeding the facility's own capacity limit and a double-authorization guard (`:facility/authorize-use`) | Ongoing maintenance/upkeep workflows themselves |
| Immutable audit ledger for every intake/assessment/screening/authorization decision | |

Extending coverage is additive: add the next gate (e.g. a structural-
inspection-currency check) as its own governed op with its own HARD
checks and tests, following the SAME "an independent governor re-
verifies against the actor's own records before any real-world act"
pattern this repo's flagship op already establishes.

## Jurisdiction coverage (honest)

`facility.facts/coverage` reports how many requested jurisdictions
actually have an official spec-basis in `facility.facts/catalog` --
currently 4 seeded (JPN, USA, GBR, DEU) out of ~194 jurisdictions
worldwide. This is a starting catalog to prove the governor contract
end-to-end, not a claim of global coverage. Adding a jurisdiction is
additive: one map entry in `facility.facts/catalog`, citing a real
official source -- never fabricate a jurisdiction's requirements to make
coverage look bigger.

## Maturity

`:implemented` -- `FacilityOps-LLM` + `Facility Safety Governor` run
as real, tested code (see `Run` above), promoted from the originally-
published `:blueprint`-tier scaffold, modeled closely on the twenty-
seven prior actors' architecture. See `docs/adr/0001-architecture.md`
for the history and design.

## License

Code and implementation templates are AGPL-3.0-or-later.
