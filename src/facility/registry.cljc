(ns facility.registry
  "Pure-function facility-use-authorization record construction -- an
  append-only sports-facility book-of-record draft.

  Like every sibling actor's registry, there is no single
  international check-digit standard for a facility-use-authorization
  reference number -- every venue/jurisdiction assigns its own
  reference format. This namespace does NOT invent one; it builds a
  jurisdiction-scoped sequence number and validates the record's
  required fields, the same honest, non-fabricating discipline
  `facility.facts` uses.

  `occupancy-exceeds-capacity?` generalizes this fleet's MAXIMUM-
  ceiling direction (established for TEMPORAL grounds by `eldercare.
  registry/care-plan-review-overdue?`, `museum.registry/provenance-
  gap-exceeds-threshold?` and `salon.registry/patch-test-window-
  exceeded?`) to its FIRST NON-TEMPORAL ground truth: a facility's own
  `:current-occupancy` must not exceed its own `:maximum-capacity`.
  Like `parksafety.registry/operators-sufficient?`'s generalization of
  the MINIMUM-threshold family to a two-field-on-one-entity
  comparison, this compares TWO permanent fields on the SAME entity
  (a real, foundational assembly-venue-safety concept -- fire-code/
  life-safety occupancy limits are set per venue, not by a single
  shared constant) rather than a field against a shared constant.

  This namespace is pure data + pure functions -- no I/O, no network
  call to any real facility-management system. It builds the RECORD a
  venue operator would keep, not the act of authorizing facility use
  itself (that is `facility.operation`'s `:facility/authorize-use`,
  always human-gated -- see README `Actuation`)."
  (:require [clojure.string :as str]))

(defn- unsigned-certificate
  "Every certificate this actor produces is UNSIGNED -- signature is the
  venue operator's own act, not this actor's. See README `Actuation`."
  [kind subject record-id]
  {"@context" ["https://www.w3.org/ns/credentials/v2"]
   "type" ["VerifiableCredential" kind]
   "credentialSubject" {"id" subject "record" record-id}
   "proof" nil
   "issued_by_registry" false
   "status" "draft-unsigned"})

(defn- zero-pad [n w]
  (let [s (str n)]
    (str (apply str (repeat (max 0 (- w (count s))) "0")) s)))

(defn occupancy-exceeds-capacity?
  "Does `facility`'s own `:current-occupancy` exceed its own
  `:maximum-capacity`? A pure ground-truth check comparing TWO
  permanent fields on the same entity -- see ns docstring for how this
  generalizes the MAXIMUM-ceiling family to its first non-temporal
  ground truth (this R0 does not model a full fire-code/life-safety
  engineering review -- only whether the literal occupancy count
  itself exceeds the facility's own recorded capacity limit)."
  [{:keys [current-occupancy maximum-capacity]}]
  (and (number? current-occupancy) (number? maximum-capacity)
       (> current-occupancy maximum-capacity)))

(defn register-facility-use-authorization
  "Validate + construct the FACILITY-USE-AUTHORIZATION registration
  DRAFT -- the venue operator's own legal act of authorizing real
  facility use during or after a flagged safety condition. Pure
  function -- does not touch any real facility-management system; it
  builds the RECORD a venue operator would keep. `facility.governor`
  independently re-verifies the facility's own occupancy sufficiency
  and inspection-flag status, and blocks a double-authorization of the
  same facility's use, before this is ever allowed to commit."
  [facility-id jurisdiction sequence]
  (when-not (and facility-id (not= facility-id ""))
    (throw (ex-info "facility-use-authorization: facility_id required" {})))
  (when-not (and jurisdiction (not= jurisdiction ""))
    (throw (ex-info "facility-use-authorization: jurisdiction required" {})))
  (when (< sequence 0)
    (throw (ex-info "facility-use-authorization: sequence must be >= 0" {})))
  (let [authorization-number (str (str/upper-case jurisdiction) "-AUT-" (zero-pad sequence 6))
        record {"record_id" authorization-number
                "kind" "facility-use-authorization-draft"
                "facility_id" facility-id
                "jurisdiction" jurisdiction
                "immutable" true}]
    {"record" record "authorization_number" authorization-number
     "certificate" (unsigned-certificate "FacilityUseAuthorization" authorization-number authorization-number)}))

(defn append [history result]
  (conj (vec history) (get result "record")))
