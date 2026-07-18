(ns facility.render-html
  "Build-time HTML renderer for `docs/samples/operator-console.html`.

  Closes flagship checklist item 2 (com-junkawasaki/root ADR-2607189300,
  Wave5 rollout ledger seq 11): this repo previously had NO demo page and
  no generator at all. This namespace drives the REAL actor stack
  (`facility.operation` -> `facility.governor` -> `facility.store`)
  through a scenario adapted from this repo's own `facility.sim` demo
  driver (`clojure -M:dev:run`, confirmed by actually running it before
  this file was written -- this repo's own sim driver uses ids that DO
  match `facility.store/demo-data`'s seeded facilities exactly
  (\"facility-1\".. \"facility-4\"), and every disposition it produces
  (commit / escalate+approve / HARD hold, and the exact `:rule` on each
  hold) matches `facility.governor`'s own documented checks precisely --
  confirmed by running `clojure -M:dev:run` and diffing the printed
  audit ledger against the governor's rule names before writing this
  file, unlike `cloud-itonami-isic-851`'s known-broken `schoolops.sim`),
  trimmed to a representative subset (one clean phase-3 auto-commit, the
  full jurisdiction-assessment/inspection-screening/facility-use-
  authorization lifecycle for one facility -- the last of which ALWAYS
  escalates, never auto, at any phase -- and three distinct HARD-hold
  reasons that never reach a human) and rendered deterministically -- no
  invented numbers, no timestamps in the page content, byte-identical
  across reruns against the same seed (verified by diffing two
  consecutive runs before shipping).

  Usage: `clojure -M:dev:render-html [out-file]`
  (default `docs/samples/operator-console.html`)."
  (:require [clojure.string :as str]
            [facility.store :as store]
            [facility.operation :as op]
            [langgraph.graph :as g]))

;; ----------------------------- harness (unchanged across every repo
;; in this cluster -- do not rewrite, only copy) -----------------------

(def ^:private operator
  {:actor-id "op-1" :actor-role :venue-operator :phase 3})

(defn- exec! [actor tid request]
  (g/run* actor {:request request :context operator} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "op-1"}}
          {:thread-id tid :resume? true}))

(defn run-demo!
  "Runs a fresh seeded store through a scenario mixing every disposition
  this actor can reach, using ONLY real facility ids from
  `facility.store/demo-data`:

  facility-1 (\"Sakura Community Gymnasium\", JPN, clean, 500/1000
  occupancy, inspection-passed? true) walks the full clean lifecycle: a
  `:facility/intake` directory-normalization patch is a phase-3, no-
  capital-risk auto-commit (governor clean, `:facility/intake` is the
  ONLY op in phase 3's `:auto` set -- see `facility.phase`);
  `:jurisdiction/assess` (JPN has a real spec-basis in `facility.facts`,
  the Fire and Disaster Management Agency) and `:inspection/screen`
  (clean, on-file post-hold inspection verdict `:passed`) each ALWAYS
  escalate (neither op is ever auto-eligible, at any phase) and are
  approved by a human venue operator; `:facility/authorize-use` -- the
  ONE real-world, public-safety-critical actuation event this actor
  performs (authorizing real facility use) -- ALSO ALWAYS escalates (the
  governor's own `high-stakes` gate on `:actuation/authorize-facility-
  use` AND the phase table agree, independently, that this op is never
  auto, at any phase) and is approved, producing one draft facility-
  use-authorization record (`JPN-AUT-000000`).

  Then three DISTINCT HARD-hold reasons, none of which ever reach a
  human (a human approver cannot override a HARD violation):
    - facility-2 (jurisdiction ATL, not in `facility.facts/catalog`):
      `:jurisdiction/assess` HARD-holds on `:no-spec-basis` -- the
      advisor may not invent a jurisdiction's assembly-venue safety
      requirements.
    - facility-3 (JPN, current-occupancy 1200 > maximum-capacity 1000):
      assessed first (clean escalate+approve, so evidence is on file
      and this HARD hold below is isolated to the occupancy check
      alone), then `:facility/authorize-use` HARD-holds on
      `:occupancy-exceeds-capacity` -- the governor independently
      recomputes current-occupancy vs. maximum-capacity off the
      facility's own permanent fields, never trusting the advisor's
      confidence.
    - facility-4 (JPN, seeded with `:inspection-passed? false`):
      `:inspection/screen` HARD-holds on `:inspection-not-passed` --
      screened directly (never via the actuation op against an
      unscreened facility), the un-overridable, unconditionally
      evaluated post-hold-inspection check.

  Returns the resulting store -- every field `render` below reads is
  real governor/store output, not a hand-typed copy."
  []
  (let [db (store/seed-db)
        actor (op/build db)]

    ;; facility-1: clean directory-normalization patch -- phase-3
    ;; auto-commit, no capital risk yet.
    (exec! actor "f1-intake" {:op :facility/intake :subject "facility-1"
                               :patch {:id "facility-1" :facility-name "Sakura Community Gymnasium"}})

    ;; facility-1: jurisdiction assembly-venue safety-evidence assessment
    ;; (JPN has a real spec-basis) -- ALWAYS escalates, approved by a human.
    (exec! actor "f1-assess" {:op :jurisdiction/assess :subject "facility-1"})
    (approve! actor "f1-assess")

    ;; facility-1: post-hold inspection screening, clean -- ALWAYS
    ;; escalates, approved by a human.
    (exec! actor "f1-screen" {:op :inspection/screen :subject "facility-1"})
    (approve! actor "f1-screen")

    ;; facility-1: REAL facility-use authorization (actuation/authorize-
    ;; facility-use, a real public-safety-critical act) -- ALWAYS
    ;; escalates regardless of phase or confidence, approved by a human
    ;; venue operator.
    (exec! actor "f1-authorize" {:op :facility/authorize-use :subject "facility-1"})
    (approve! actor "f1-authorize")

    ;; facility-2 (ATL): no official spec-basis in facility.facts -> HARD
    ;; hold on :no-spec-basis, never reaches a human.
    (exec! actor "f2-assess" {:op :jurisdiction/assess :subject "facility-2" :no-spec? true})

    ;; facility-3: assess JPN first (clean escalate+approve) so evidence
    ;; is on file and the occupancy-exceeds-capacity hold below is
    ;; isolated to the occupancy check alone.
    (exec! actor "f3-assess" {:op :jurisdiction/assess :subject "facility-3"})
    (approve! actor "f3-assess")

    ;; facility-3: current-occupancy 1200 > maximum-capacity 1000 -> HARD
    ;; hold on :occupancy-exceeds-capacity, never reaches a human.
    (exec! actor "f3-authorize" {:op :facility/authorize-use :subject "facility-3"})

    ;; facility-4: seeded with inspection-passed? false -> HARD hold on
    ;; :inspection-not-passed, never reaches a human.
    (exec! actor "f4-screen" {:op :inspection/screen :subject "facility-4"})

    db))

;; ----------------------------- rendering -----------------------------

(defn- esc [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))

(defn- last-fact-for [ledger subject-id]
  (last (filter #(= (:subject %) subject-id) ledger)))

(defn- status-cell [ledger subject-id]
  (let [f (last-fact-for ledger subject-id)]
    (cond
      (nil? f) "<span class=\"muted\">no activity</span>"
      (= :committed (:t f)) "<span class=\"ok\">committed</span>"
      (= :approval-granted (:t f)) "<span class=\"ok\">approved &amp; committed</span>"
      (= :governor-hold (:t f))
      (let [rule (-> f :violations first :rule)]
        (str "<span class=\"critical\">HARD hold &middot; " (esc (name (or rule :unknown))) "</span>"))
      (= :approval-requested (:t f)) "<span class=\"warn\">awaiting approval</span>"
      :else "<span class=\"muted\">in progress</span>")))

(defn- facility-row [ledger {:keys [id facility-name jurisdiction current-occupancy maximum-capacity
                                     inspection-passed? authorized? authorization-number]}]
  (format "        <tr><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td></tr>"
          (esc id) (esc facility-name) (esc jurisdiction)
          (esc current-occupancy) (esc maximum-capacity)
          (if inspection-passed? "<span class=\"ok\">passed</span>" "<span class=\"critical\">not passed</span>")
          (if authorized?
            (str "<span class=\"ok\">authorized &middot; " (esc authorization-number) "</span>")
            "<span class=\"muted\">not authorized</span>")
          (status-cell ledger id)))

(defn- ledger-row [{:keys [t op subject disposition basis]}]
  (format "        <tr><td>%s</td><td><code>%s</code></td><td>%s</td><td>%s</td></tr>"
          (esc (name t)) (esc (name (or op :n-a))) (esc subject)
          (esc (or (some->> basis (map #(if (keyword? %) (name %) %)) (str/join ", "))
                    (some-> disposition name) ""))))

(defn- authorization-row [{:strs [record_id facility_id jurisdiction immutable]}]
  (format "        <tr><td>%s</td><td>%s</td><td>%s</td><td>%s</td></tr>"
          (esc record_id) (esc facility_id) (esc jurisdiction)
          (if immutable "<span class=\"ok\">immutable draft</span>" "<span class=\"muted\">n/a</span>")))

(def ^:private action-gate-rows
  ;; Static description of this actor's own op contract
  ;; (`facility.governor`/`facility.phase`) -- documentation of fixed
  ;; behavior, not runtime telemetry, so it is legitimately hand-
  ;; described rather than derived from a live run.
  ["        <tr><td><code>:facility/intake</code></td><td><span class=\"ok\">phase-3 auto-commit when clean, no capital risk yet -- the ONLY auto-eligible op in this domain</span></td></tr>"
   "        <tr><td><code>:jurisdiction/assess</code></td><td><span class=\"warn\">ALWAYS human approval &middot; spec-basis independently checked against <code>facility.facts</code>, never fabricated</span></td></tr>"
   "        <tr><td><code>:inspection/screen</code></td><td><span class=\"warn\">ALWAYS human approval when clean &middot; a not-passed post-hold inspection (reported now, or already on file) is a HARD, un-overridable hold instead</span></td></tr>"
   "        <tr><td><code>:facility/authorize-use</code></td><td><span class=\"warn\">ALWAYS human approval &middot; real public-safety-critical act (actuation/authorize-facility-use) &middot; evidence-completeness + independently recomputed occupancy-vs-capacity + double-authorization guard enforced, never auto at any phase</span></td></tr>"])

(defn render
  "Renders the full operator-console.html document from a store `db`
  that has already run `run-demo!` (or any other real scenario)."
  [db]
  (let [ledger (vec (store/ledger db))
        facilities (store/all-facilities db)
        facility-rows (str/join "\n" (map (partial facility-row ledger) facilities))
        ledger-rows (str/join "\n" (map ledger-row ledger))
        authorization-rows (str/join "\n" (map authorization-row (store/authorization-history db)))]
    (str
     "<html><head><meta charset=\"utf-8\"><title>cloud-itonami-isic-9311 &middot; operation of sports facilities</title><style>\n"
     "table { width: 100%; border-collapse: collapse; font-size: 14px; }\n"
     ".ok { color: #137a3f; }\n"
     "body { font-family: system-ui,-apple-system,sans-serif; margin: 0; color: #1a1a1a; background: #fafafa; }\n"
     "header.bar { display: flex; align-items: center; gap: 12px; padding: 12px 20px; background: #fff; border-bottom: 1px solid #e5e5e5; }\n"
     "th, td { text-align: left; padding: 8px 10px; border-bottom: 1px solid #f0f0f0; }\n"
     "h2 { margin-top: 0; font-size: 15px; }\n"
     ".warn { color: #b25c00; background: #fff8e1; padding: 2px 6px; border-radius: 4px; }\n"
     "main { max-width: 980px; margin: 24px auto; padding: 0 20px; }\n"
     "header.bar h1 { font-size: 18px; margin: 0; font-weight: 600; }\n"
     ".muted { color: #888; font-size: 13px; }\n"
     ".critical { color: #fff; background: #b3261e; padding: 2px 6px; border-radius: 4px; font-weight: 600; }\n"
     ".card { background: #fff; border: 1px solid #e5e5e5; border-radius: 8px; padding: 16px; margin-bottom: 16px; }\n"
     ".err { color: #b3261e; background: #fbe9e7; padding: 2px 6px; border-radius: 4px; }\n"
     "th { font-weight: 600; color: #555; font-size: 12px; text-transform: uppercase; letter-spacing: 0.04em; }\n"
     "header.bar .badge { margin-left: auto; font-size: 12px; color: #666; }\n"
     "code { font-size: 12px; background: #f4f4f4; padding: 1px 4px; border-radius: 3px; }\n"
     "</style></head><body>\n"
     "<header class=\"bar\">\n"
     "  <h1>Operation of sports facilities (ISIC 9311) — Operator Console</h1>\n"
     "  <span class=\"badge\">read-only sample · governor-gated · facility-use authorization always human-approved</span>\n"
     "</header>\n"
     "<main>\n"
     "  <section class=\"card\">\n"
     "    <h2>Facilities</h2>\n"
     "    <p class=\"muted\">Demo snapshot — build-time-generated from <code>facility.store</code> via <code>facility.render-html</code> (<code>clojure -M:dev:render-html</code>), regenerated nightly.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Facility</th><th>Name</th><th>Jurisdiction</th><th>Current occupancy</th><th>Maximum capacity</th><th>Inspection</th><th>Authorization</th><th>Last op status</th></tr></thead>\n"
     "      <tbody>\n"
     facility-rows "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "  <section class=\"card\">\n"
     "    <h2>Draft facility-use-authorization records</h2>\n"
     "    <p class=\"muted\">Unsigned drafts only — the venue operator's own act of signing is outside this actor's authority (see README <code>Actuation</code>).</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Record id</th><th>Facility</th><th>Jurisdiction</th><th>Status</th></tr></thead>\n"
     "      <tbody>\n"
     authorization-rows "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "  <section class=\"card\">\n"
     "    <h2>Action gate (Facility Safety Governor)</h2>\n"
     "    <p class=\"muted\">HARD holds cannot be overridden by a human approver. Jurisdiction spec-basis, safety-evidence completeness, occupancy-vs-capacity and post-hold inspection status are independently recomputed, never trusted from the advisor's proposal; a real facility-use authorization is always a human venue operator's call, at every rollout phase.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Op</th><th>Gate</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" action-gate-rows) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "  <section class=\"card\">\n"
     "    <h2>Audit ledger (this run)</h2>\n"
     "    <p class=\"muted\">Append-only decision-fact log — every proposal, hold and commit this scenario produced.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Fact</th><th>Op</th><th>Subject</th><th>Basis</th></tr></thead>\n"
     "      <tbody>\n"
     ledger-rows "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "</main>\n"
     "</body></html>\n")))

(defn -main [& args]
  (let [out (or (first args) "docs/samples/operator-console.html")
        db (run-demo!)
        html (render db)]
    (spit out html)
    (println "wrote" out "(" (count (store/ledger db)) "ledger facts,"
             (count (store/authorization-history db)) "authorization drafts )")))
