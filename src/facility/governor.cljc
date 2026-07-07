(ns facility.governor
  "Facility Safety Governor -- the independent compliance layer that
  earns the FacilityOps-LLM the right to commit. The LLM has no
  notion of jurisdictional assembly-venue/occupancy-safety law,
  whether a facility's own current occupancy actually exceeds its own
  recorded capacity limit, whether a facility's own post-hold
  inspection actually passed, or when an act stops being a draft and
  becomes a real-world authorization of facility use, so this MUST be
  a separate system able to *reject* a proposal and fall back to HOLD
  -- the sports-facility analog of `cloud-itonami-isic-6512`'s
  CasualtyGovernor.

  Four checks, in priority order, ALL HARD violations: a human
  approver CANNOT override them (you don't get to approve your way
  past a fabricated jurisdiction spec-basis, incomplete safety
  evidence, an occupancy count exceeding the facility's own capacity
  limit, a failed post-hold inspection, or a double authorization).
  The confidence/actuation gate is SOFT: it asks a human to look (low
  confidence / actuation), and the human may approve -- but see
  `facility.phase`: for `:stake :actuation/authorize-facility-use` (a
  real authorization of facility use) NO phase ever allows auto-commit
  either. Two independent layers agree that actuation is always a
  human call.

    1. Spec-basis                  -- did the jurisdiction proposal cite
                                       an OFFICIAL source (`facility.
                                       facts`), or invent one? Like
                                       `parksafety.governor`'s/
                                       `clinic.governor`'s actuation
                                       ops, `:facility/authorize-use`
                                       acts directly on a pre-seeded
                                       facility (see `facility.store`'s
                                       own docstring) -- there is no
                                       'facility is missing' failure
                                       mode to guard against here.
    2. Evidence incomplete         -- for `:facility/authorize-use`,
                                       has the jurisdiction actually
                                       been assessed with a full
                                       safety-evidence checklist on
                                       file?
    3. Occupancy exceeds capacity  -- for `:facility/authorize-use`,
                                       INDEPENDENTLY recompute whether
                                       the facility's own `:current-
                                       occupancy` exceeds its own
                                       `:maximum-capacity` (`facility.
                                       registry/occupancy-exceeds-
                                       capacity?`) -- needs no proposal
                                       inspection or stored-verdict
                                       lookup at all. The FIRST NON-
                                       TEMPORAL instance of this
                                       fleet's MAXIMUM-ceiling family
                                       (`eldercare.governor`/`museum.
                                       governor`/`salon.governor`
                                       established the first three,
                                       all temporal), and reuses
                                       `parksafety.governor/operators-
                                       insufficient-violations`'s two-
                                       field-on-one-entity comparison
                                       shape.
    4. Inspection not passed       -- reported by THIS proposal itself
                                       (an `:inspection/screen` that
                                       just found a failure), or
                                       already on file for the facility
                                       (`:inspection/screen`/
                                       `:facility/authorize-use`).
                                       Evaluated UNCONDITIONALLY (not
                                       scoped to a specific op), the
                                       SAME discipline `casualty.
                                       governor/sanctions-violations`/
                                       .../`parksafety.governor/
                                       inspection-not-passed-
                                       violations`/.../`hospital.
                                       governor/credential-not-current-
                                       violations` established -- the
                                       SEVENTEENTH distinct application
                                       of this exact discipline
                                       overall, and the SECOND
                                       specifically for the post-hold-
                                       inspection concept (after
                                       `parksafety`, applied there to
                                       amusement rides and here to
                                       assembly venues). Like the
                                       fourteen most recent siblings'
                                       equivalent checks, this is
                                       exercised in tests/demo via
                                       `:inspection/screen` DIRECTLY,
                                       not via the actuation op against
                                       an unscreened facility -- see
                                       this ns's own test suite.
    5. Confidence floor / actuation
       gate                          -- LLM confidence below threshold,
                                       OR the op is `:facility/
                                       authorize-use` (a REAL, public-
                                       safety-critical act) -> escalate.

  One more guard, double-authorization prevention, is enforced but NOT
  listed as a numbered HARD check above because it needs no upstream
  comparison at all -- `already-authorized-violations` refuses to
  authorize the SAME facility's use twice, off a dedicated
  `:authorized?` fact (never a `:status` value) -- the SAME 'check a
  dedicated boolean, not status' discipline `accounting.governor`'s/
  `marketadmin.governor`'s/`testlab.governor`'s/`parksafety.
  governor`'s guards establish, informed by `cloud-itonami-isic-6492`'s
  status-lifecycle bug (ADR-2607071320)."
  (:require [facility.facts :as facts]
            [facility.registry :as registry]
            [facility.store :as store]))

(def confidence-floor 0.6)

(def high-stakes
  "Stakes grave enough to always require a human, even when clean.
  Authorizing real facility use during or after a flagged safety
  condition is the ONE real-world actuation event this actor performs
  -- a single-member set, matching `cloud-itonami-isic-6511`'s/
  `6621`'s/`6629`'s/`6612`'s/`6492`'s/`7120`'s/`8620`'s/`7500`'s/
  `9603`'s/`9321`'s/`9602`'s/`9000`'s single-actuation shape."
  #{:actuation/authorize-facility-use})

;; ----------------------------- checks -----------------------------

(defn- spec-basis-violations
  "A `:jurisdiction/assess` (or `:facility/authorize-use`) proposal
  with no spec-basis citation is a HARD violation -- never invent a
  jurisdiction's assembly-venue/occupancy-safety requirements."
  [{:keys [op]} proposal]
  (when (contains? #{:jurisdiction/assess :facility/authorize-use} op)
    (let [value (:value proposal)]
      (when (or (empty? (:cites proposal))
                (and (contains? value :spec-basis) (nil? (:spec-basis value))))
        [{:rule :no-spec-basis
          :detail "公式spec-basisの引用が無い提案は法域要件として扱えない"}]))))

(defn- evidence-incomplete-violations
  "For `:facility/authorize-use`, the jurisdiction's required safety-
  inspection/occupancy-certification/egress-plan/insurance evidence
  must actually be satisfied -- do not trust the advisor's self-
  reported confidence alone."
  [{:keys [op subject]} st]
  (when (= op :facility/authorize-use)
    (let [f (store/facility st subject)
          assessment (store/assessment-of st subject)]
      (when-not (and assessment
                     (facts/required-evidence-satisfied?
                      (:jurisdiction f) (:checklist assessment)))
        [{:rule :evidence-incomplete
          :detail "法域の必要書類(安全点検報告書/収容人員証明書/避難計画書等)が充足していない状態での提案"}]))))

(defn- occupancy-exceeds-capacity-violations
  "For `:facility/authorize-use`, INDEPENDENTLY recompute whether the
  facility's own current-occupancy exceeds its own maximum-capacity
  via `facility.registry/occupancy-exceeds-capacity?` -- needs no
  proposal inspection or stored-verdict lookup at all, since its
  inputs are permanent ground-truth fields already on the facility."
  [{:keys [op subject]} st]
  (when (= op :facility/authorize-use)
    (let [f (store/facility st subject)]
      (when (registry/occupancy-exceeds-capacity? f)
        [{:rule :occupancy-exceeds-capacity
          :detail (str subject " の現在収容人数(" (:current-occupancy f)
                      ")が定員上限(" (:maximum-capacity f) ")を超過している")}]))))

(defn- inspection-not-passed-violations
  "A not-passed post-hold inspection -- reported by THIS proposal
  (e.g. an `:inspection/screen` that itself just found a failure), or
  already on file in the store for the facility (`:inspection/
  screen`/`:facility/authorize-use`) -- is a HARD, un-overridable
  hold. Evaluated UNCONDITIONALLY (not scoped to a specific op) so the
  screening op itself can HARD-hold on its own finding."
  [{:keys [op subject]} proposal st]
  (let [hit-in-proposal? (= :failed (get-in proposal [:value :verdict]))
        facility-id (when (contains? #{:inspection/screen :facility/authorize-use} op) subject)
        hit-on-file? (and facility-id (= :failed (:verdict (store/inspection-of st facility-id))))]
    (when (or hit-in-proposal? hit-on-file?)
      [{:rule :inspection-not-passed
        :detail "安全点検に合格していない施設の利用許可提案は進められない"}])))

(defn- already-authorized-violations
  "For `:facility/authorize-use`, refuses to authorize the SAME
  facility's use twice, off a dedicated `:authorized?` fact (never a
  `:status` value)."
  [{:keys [op subject]} st]
  (when (= op :facility/authorize-use)
    (when (store/facility-already-authorized? st subject)
      [{:rule :already-authorized
        :detail (str subject " は既に利用許可済み")}])))

(defn check
  "Censors a FacilityOps-LLM proposal against the governor rules.
  Returns {:ok? bool :violations [..] :confidence c :escalate? bool
  :high-stakes? bool :hard? bool}."
  [request _context proposal st]
  (let [hard (into []
                   (concat (spec-basis-violations request proposal)
                           (evidence-incomplete-violations request st)
                           (occupancy-exceeds-capacity-violations request st)
                           (inspection-not-passed-violations request proposal st)
                           (already-authorized-violations request st)))
        conf (:confidence proposal 0.0)
        low? (< conf confidence-floor)
        stakes? (boolean (high-stakes (:stake proposal)))
        hard? (boolean (seq hard))]
    {:ok?          (and (not hard?) (not low?) (not stakes?))
     :violations   hard
     :confidence   conf
     :hard?        hard?
     :escalate?    (and (not hard?) (or low? stakes?))
     :high-stakes? stakes?}))

(defn hold-fact
  "The audit fact written when a proposal is rejected (HOLD)."
  [request context verdict]
  {:t          :governor-hold
   :op         (:op request)
   :actor      (:actor-id context)
   :subject    (:subject request)
   :disposition :hold
   :basis      (mapv :rule (:violations verdict))
   :violations (:violations verdict)
   :confidence (:confidence verdict)})
