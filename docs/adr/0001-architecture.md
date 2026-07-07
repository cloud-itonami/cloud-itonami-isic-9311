# ADR-0001: cloud-itonami-isic-9311 -- FacilityOps-LLM as a contained intelligence node

- Status: Accepted (2026-07-07)
- Related: `cloud-itonami-isic-6511`/`6512`/`6621`/`6622`/`6629`/`6520`/
  `6530`/`6820`/`6612`/`6492`/`6920`/`6611`/`7120`/`8620`/`8530`/`9200`/
  `7500`/`9603`/`9521`/`9321`/`8730`/`9102`/`9103`/`9602`/`9000`/`8890`/
  `8610` ADR-0001s (the pattern this ADR ports); ADR-2607071250/
  ADR-2607071320/ADR-2607071351/ADR-2607071618/ADR-2607071640/
  ADR-2607071654/ADR-2607071717/ADR-2607071732/ADR-2607071752/
  ADR-2607071819/ADR-2607071849/ADR-2607071922/ADR-2607072715/
  ADR-2607072730/ADR-2607072745/ADR-2607072800/ADR-2607072815/
  ADR-2607072830/ADR-2607072845 (`6612`/`6492`/`6920`/`6611`/`7120`/
  `8620`/`8530`/`9200`/`7500`/`9603`/`9521`/`9321`/`8730`/`9102`/
  `9103`/`9602`/`9000`/`8890`/`8610`, the nineteen verticals built
  outside ADR-2607032000's original insurance/real-estate batch --
  this is the twentieth)
- Context: Continuing the standing "pick a new ISIC blueprint
  vertical" direction past `8610`, this ADR deepens `cloud-itonami-
  isic-9311` (operation of sports facilities) from `:blueprint` to
  `:implemented`, the twenty-eighth actor in this fleet -- a SECOND
  leisure-attractions vertical (ISIC division 93) alongside `9321`'s
  amusement parks, but for gyms/stadiums/arenas rather than mechanical
  rides.

## Problem

A sports facility's use-authorization workflow bundles several
distinct concerns under one governed workflow:

1. **Jurisdiction assembly-venue/occupancy-safety correctness** -- an
   official spec-basis citation from a real regulator (総務省消防庁/
   the National Fire Protection Association/the Sports Grounds Safety
   Authority/state Bauaufsichtsbehörden), never fabricated.
2. **Occupancy sufficiency** -- does a facility's own current
   occupancy exceed its own recorded capacity limit? The FIRST NON-
   TEMPORAL instance of this fleet's MAXIMUM-ceiling family
   (`eldercare.governor`/`museum.governor`/`salon.governor`
   established the first three, all temporal), reusing `parksafety.
   registry/operators-sufficient?`'s two-field-on-one-entity
   comparison shape.
3. **Post-hold inspection verification** -- has a facility's own post-
   hold inspection actually passed before facility use is authorized?
   The facility-specific reuse of the unconditional-evaluation
   screening discipline this fleet's `casualty.governor/sanctions-
   violations` originally established -- a SEVENTEENTH distinct
   grounding overall, and the SECOND specifically for the post-hold-
   inspection concept (after `parksafety`, applied there to amusement
   rides and here to assembly venues).
4. **Real, high-stakes actuation, once** -- authorizing real facility
   use during or after a flagged safety condition is a single
   actuation event with direct public-safety stakes.

An LLM has no authority or grounding for any of these. The design
problem is therefore not "run a sports facility with an LLM" but "seal
the LLM inside a trust boundary and layer evidence-sufficiency,
occupancy verification, post-hold-inspection verification, audit and
human-approval on top of it, while structurally fixing the one real
actuation event as human-only."

## Decision

### 1. FacilityOps-LLM is sealed into the bottom node; it never authorizes facility use directly

`facility.facilityopsllm` returns exactly four kinds of proposal:
intake normalization, jurisdiction assembly-venue safety checklist,
inspection screening, and facility-use-authorization draft. No
proposal writes the SSoT or commits a real authorization directly.

### 2. OperationActor = langgraph-clj StateGraph, 1 run = 1 facility operation

`facility.operation/build` is the SAME StateGraph shape as every
sibling actor's operation namespace, copied verbatim.

### 3. `occupancy-exceeds-capacity?` is the FIRST non-temporal instance of the MAXIMUM-ceiling family

`eldercare.registry/care-plan-review-overdue?`, `museum.registry/
provenance-gap-exceeds-threshold?` and `salon.registry/patch-test-
window-exceeded?` established this fleet's MAXIMUM-ceiling direction,
all on TEMPORAL grounds (elapsed time exceeding a ceiling).
`occupancy-exceeds-capacity?` generalizes the direction to its first
NON-TEMPORAL ground truth: a facility's own current occupancy must not
exceed its own capacity limit. It simultaneously reuses `parksafety.
registry/operators-sufficient?`'s two-field-on-one-entity comparison
shape (different facilities genuinely have different capacity limits,
not a single shared constant).

### 4. Inspection-not-passed screening reuses the unconditional-evaluation discipline for a seventeenth distinct grounding, and a second specifically for this concept

`inspection-not-passed-violations` reuses `casualty.governor/
sanctions-violations`'s fix (evaluated unconditionally, not scoped to
a specific op, so the screening op itself can HARD-hold on its own
finding) for `:inspection/screen` AND `:facility/authorize-use` -- the
SEVENTEENTH distinct application of this exact discipline in this
fleet overall, and the SECOND specifically for the post-hold-
inspection concept (`parksafety` established it for amusement rides;
this reuses it verbatim for assembly venues -- the identical real-
world safety concern applies to both physical structures).

### 5. The unconditional-evaluation check is tested via the SCREENING op directly, per the lesson already recorded by `parksafety` itself and seven later siblings

`inspection-not-passed-is-held-and-unoverridable` calls `:inspection/
screen` directly against `facility-4` (a failed inspection), NOT
`:facility/authorize-use` against an unscreened facility -- because a
failing screen is itself a HARD hold whose payload never persists to
the store, so the actuation op alone could never discover the bad
ground-truth flag through this check family without the screening op
having actually been run first. This build applied that lesson
PROACTIVELY for an eighth consecutive vertical (after `eldercare`,
`museum`, `conservation`, `salon`, `entertainment`, `casework` and
`hospital`), further reinforcing that lessons recorded in this fleet's
ADRs transfer forward reliably -- notably, this is the FIRST time a
lesson has been applied back to the SAME domain concept
(`parksafety`'s own inspection-not-passed bug) in a different
vertical, closing the loop on the original mistake.

### 6. Single actuation, matching `6511`/`6621`/`6629`/`6612`/`6492`/`7120`/`8620`/`7500`/`9603`/`9321`/`9602`/`9000`'s shape

`facility.governor`'s `high-stakes` set has exactly one member
(`:actuation/authorize-facility-use`) -- this domain has ONE distinct
real-world, public-safety-critical act (authorizing facility use after
a flagged condition), not several independently-gated acts, matching
the blueprint's own stated scope.

### 7. Double-authorization guard checks a dedicated boolean, not `:status`

`already-authorized-violations` checks `:authorized?`, a dedicated
boolean set once and never cleared, rather than a `:status` value that
could legitimately advance past a checked state (the exact trap
`cloud-itonami-isic-6492`'s ADR-0001 documents in detail, explicitly
avoided BY DESIGN in every sibling actor's equivalent guard since).
This actor's `:status` never needs to encode "has this actuation
already happened" at all -- a deliberate architectural choice applied
here for an eighteenth consecutive time.

### 8. No bespoke capability lib

Like `6920`/`7120`/`8620`/`8530`/`9200`/`7500`/`9603`/`9521`/`9321`/
`8730`/`9102`/`9103`/`9602`/`9000`/`8890`/`8610`, and unlike most
other actors in this fleet, this vertical's facility records are
practice-specific rather than a shared cross-operator data contract --
`facility.*` runs on the generic identity/forms/dmn/bpmn/audit-ledger
stack only, per the blueprint's own explicit statement.

## Consequences

- (+) Sports-facility operation gets the same governed, auditable-
  actor treatment as the twenty-seven prior actors, and this fleet now
  has a TWENTIETH concrete precedent for extending past
  ADR-2607032000's original scope, deepening leisure-attractions
  coverage (ISIC division 93) alongside `9321`'s amusement parks with
  a genuinely different physical-safety concern (occupancy/capacity
  vs. ride mechanics).
- (+) `occupancy-exceeds-capacity?` is a genuine structural
  contribution: the first non-temporal instance of the MAXIMUM-
  ceiling family, and a further application of the two-field-on-one-
  entity comparison shape `parksafety` established.
- (+) The actuation invariant (governor + phase, two layers) is
  regression-tested by `test/facility/phase_test.clj`'s `facility-
  authorize-use-never-auto-at-any-phase`.
- (+) `MemStore` ‖ `DatomicStore` parity is proven by `test/facility/
  store_contract_test.clj`, the same `:db-api`-driven swap pattern
  every sibling actor uses.
- (+) The inspection-not-passed test/demo correctly applied the
  established SCREENING-op-directly pattern for an eighth consecutive
  vertical, and is notably the first time this specific lesson (from
  `parksafety`'s own ADR-2607071922 Decision 5) has been applied back
  to the SAME underlying screening concept in a different vertical.
- (-) This R0 seeds only 4 jurisdictions (JPN, USA, GBR, DEU) with an
  official spec-basis, out of ~194 worldwide; `facility.facts/
  coverage` reports this honestly rather than claiming broader
  coverage.
- (-) `occupancy-exceeds-capacity?` models only a single occupancy-
  count-vs-capacity-limit comparison, not a full fire-code/life-safety
  engineering review (structural-load analysis, means-of-egress
  engineering calculations, full crowd-dynamics modeling are out of
  scope -- see that fn's own docstring); real facility-management-
  system integration and ongoing maintenance/upkeep workflows are all
  out of scope for this OSS actor -- each operator's responsibility
  (see README's coverage table).
- 30 tests / 127 assertions, lint clean.

## Alternatives considered

| Option | Verdict | Reason |
|---|---|---|
| Add this as an addendum to any prior post-batch ADR | ❌ | All nineteen of those ADRs' titles and scopes are explicitly `cloud-itonami-isic-6612`/`6492`/`6920`/`6611`/`7120`/`8620`/`8530`/`9200`/`7500`/`9603`/`9521`/`9321`/`8730`/`9102`/`9103`/`9602`/`9000`/`8890`/`8610`; mixing a different sub-domain into any would blur scope boundaries even where the ISIC division (93) overlaps with `9321` |
| Keep `cloud-itonami-isic-9311` at `:blueprint` only | ❌ | The standing direction continues past `8610`; sports-facility operation is a natural, well-precedented next domain, deepening this fleet's leisure-attractions coverage with a genuinely different physical-safety concern than `9321`'s mechanical rides |
| Model `occupancy-exceeds-capacity?` as a new check family rather than an extension of the MAXIMUM-ceiling direction | ❌ | The actual comparison shape (`>` against a ceiling, two fields on one entity) is identical to the established family; honestly framing this as the first NON-TEMPORAL instance, not a brand-new family, keeps the fleet's check-family taxonomy accurate |
| Test `inspection-not-passed-violations` via the actuation op against an unscreened facility (the shape `parksafety`'s ORIGINAL, buggy test used) | ❌ | Already proven wrong by `parksafety`'s own ADR-2607071922 Decision 5 and reconfirmed by seven later siblings' ADR-0001s -- a failing screen never persists its payload to the store, so the actuation op alone cannot discover the bad ground-truth flag through this check family; this build tested the SCREENING op directly from the start, closing the loop on the SAME concept's original bug |
| Reference a capability lib (e.g. a hypothetical `kotoba-lang/facility`) for consistency with most prior actors | ❌ | The blueprint itself explicitly states this vertical's records are practice-specific, not a shared cross-operator contract -- inventing a capability lib reference where the blueprint says none exists would misrepresent the domain, the same reasoning established by every "no bespoke capability lib" sibling's ADR |

## References

- ADR-2607071250/ADR-2607071320/ADR-2607071351/ADR-2607071618/
  ADR-2607071640/ADR-2607071654/ADR-2607071717/ADR-2607071732/
  ADR-2607071752/ADR-2607071819/ADR-2607071849/ADR-2607071922/
  ADR-2607072715/ADR-2607072730/ADR-2607072745/ADR-2607072800/
  ADR-2607072815/ADR-2607072830/ADR-2607072845 (`6612`/`6492`/`6920`/
  `6611`/`7120`/`8620`/`8530`/`9200`/`7500`/`9603`/`9521`/`9321`/
  `8730`/`9102`/`9103`/`9602`/`9000`/`8890`/`8610`, first nineteen
  post-batch verticals)
- ADR-2607032000 (original insurance/real-estate batch, Addenda 1-7)
- `cloud-itonami-isic-9311/docs/adr/0001-architecture.md` (this ADR)
- `kotoba-lang/industry` `resources/kotoba/industry/registry.edn`
  (fleet-wide maturity registry)
