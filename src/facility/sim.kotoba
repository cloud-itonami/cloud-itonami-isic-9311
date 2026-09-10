(ns facility.sim
  "Demo driver -- `clojure -M:dev:run`. Walks a clean facility through
  intake -> jurisdiction assessment -> inspection screening ->
  facility-use-authorization proposal (always escalates) -> human
  approval -> commit, then shows four HARD holds (a jurisdiction with
  no spec-basis, an occupancy count exceeding the facility's own
  capacity limit, a failed post-hold inspection screened directly via
  `:inspection/screen` [never via the actuation op against an
  unscreened facility -- see this actor's own governor ns docstring /
  the lesson `parksafety`'s ADR-2607071922 Decision 5, `eldercare`'s,
  `museum`'s, `conservation`'s, `salon`'s, `entertainment`'s,
  `casework`'s and `hospital`'s ADR-0001s already recorded], and a
  double authorization of an already-processed facility) that never
  reach a human at all, and prints the audit ledger + the draft
  facility-use-authorization records."
  (:require [langgraph.graph :as g]
            [facility.store :as store]
            [facility.operation :as op]))

(def operator {:actor-id "op-1" :actor-role :venue-operator :phase 3})

(defn- exec! [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "op-1"}} {:thread-id tid :resume? true}))

(defn -main [& _]
  (let [db (store/seed-db)
        actor (op/build db)]
    (println "== facility/intake facility-1 (JPN, clean; 500/1000 occupancy, inspection passed) ==")
    (println (exec! actor "t1" {:op :facility/intake :subject "facility-1"
                                :patch {:id "facility-1" :facility-name "Sakura Community Gymnasium"}} operator))

    (println "== jurisdiction/assess facility-1 (escalates -- human approves) ==")
    (println (exec! actor "t2" {:op :jurisdiction/assess :subject "facility-1"} operator))
    (println (approve! actor "t2"))

    (println "== inspection/screen facility-1 (clean; escalates -- human approves) ==")
    (println (exec! actor "t3" {:op :inspection/screen :subject "facility-1"} operator))
    (println (approve! actor "t3"))

    (println "== facility/authorize-use facility-1 (always escalates -- actuation/authorize-facility-use) ==")
    (let [r (exec! actor "t4" {:op :facility/authorize-use :subject "facility-1"} operator)]
      (println r)
      (println "-- human venue operator approves --")
      (println (approve! actor "t4")))

    (println "== jurisdiction/assess facility-2 (no spec-basis -> HARD hold) ==")
    (println (exec! actor "t5" {:op :jurisdiction/assess :subject "facility-2" :no-spec? true} operator))

    (println "== jurisdiction/assess facility-3 (escalates -- human approves; sets up the occupancy test) ==")
    (println (exec! actor "t6" {:op :jurisdiction/assess :subject "facility-3"} operator))
    (println (approve! actor "t6"))

    (println "== facility/authorize-use facility-3 (1200/1000 occupancy over capacity -> HARD hold) ==")
    (println (exec! actor "t7" {:op :facility/authorize-use :subject "facility-3"} operator))

    (println "== inspection/screen facility-4 (failed inspection -> HARD hold, never reaches a human) ==")
    (println (exec! actor "t8" {:op :inspection/screen :subject "facility-4"} operator))

    (println "== facility/authorize-use facility-1 AGAIN (double-authorization -> HARD hold) ==")
    (println (exec! actor "t9" {:op :facility/authorize-use :subject "facility-1"} operator))

    (println "== audit ledger ==")
    (doseq [f (store/ledger db)] (println f))

    (println "== draft facility-use-authorization records ==")
    (doseq [r (store/authorization-history db)] (println r))))
