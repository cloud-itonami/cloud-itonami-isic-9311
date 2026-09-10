(ns facility.governor-contract-test
  "The governor contract as executable tests -- the sports-facility
  analog of `cloud-itonami-isic-6512`'s `casualty.governor-contract-
  test`. The single invariant under test:

    FacilityOps-LLM never authorizes facility use the Facility Safety
    Governor would reject, `:facility/authorize-use` NEVER auto-
    commits at any phase, `:facility/intake` (no direct capital risk)
    MAY auto-commit when clean, and every decision (commit OR hold)
    leaves exactly one ledger fact."
  (:require [clojure.test :refer [deftest is testing]]
            [langgraph.graph :as g]
            [facility.store :as store]
            [facility.operation :as op]))

(defn- fresh []
  (let [db (store/seed-db)]
    [db (op/build db)]))

(def operator {:actor-id "op-1" :actor-role :venue-operator :phase 3})

(defn- exec-op [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "op-1"}} {:thread-id tid :resume? true}))

(defn- assess!
  "Walks `subject` through assess -> approve, leaving an assessment on
  file. Uses distinct thread-ids per call site by suffixing
  `tid-prefix`."
  [actor tid-prefix subject]
  (exec-op actor (str tid-prefix "-assess") {:op :jurisdiction/assess :subject subject} operator)
  (approve! actor (str tid-prefix "-assess")))

(defn- screen!
  "Walks `subject` through inspection screening -> approve, leaving a
  screening on file. Only safe to call for a facility whose inspection
  has already passed -- a failed inspection HARD-holds the screen
  itself (see `inspection-not-passed-is-held-and-unoverridable`)."
  [actor tid-prefix subject]
  (exec-op actor (str tid-prefix "-screen") {:op :inspection/screen :subject subject} operator)
  (approve! actor (str tid-prefix "-screen")))

(deftest clean-intake-auto-commits
  (let [[db actor] (fresh)
        res (exec-op actor "t1"
                  {:op :facility/intake :subject "facility-1"
                   :patch {:id "facility-1" :facility-name "Sakura Community Gymnasium"}} operator)]
    (is (= :commit (get-in res [:state :disposition])))
    (is (= "Sakura Community Gymnasium" (:facility-name (store/facility db "facility-1"))) "SSoT actually updated")
    (is (= 1 (count (store/ledger db))))))

(deftest jurisdiction-assess-always-needs-approval
  (testing "assess is never in any phase's :auto set -- always human approval, even when clean"
    (let [[db actor] (fresh)
          res (exec-op actor "t2" {:op :jurisdiction/assess :subject "facility-1"} operator)]
      (is (= :interrupted (:status res)))
      (let [r2 (approve! actor "t2")]
        (is (= :commit (get-in r2 [:state :disposition])))
        (is (some? (store/assessment-of db "facility-1")))))))

(deftest fabricated-jurisdiction-is-held
  (testing "a jurisdiction/assess proposal with no official spec-basis -> HOLD, never reaches a human"
    (let [[db actor] (fresh)
          res (exec-op actor "t3"
                    {:op :jurisdiction/assess :subject "facility-1" :no-spec? true} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:no-spec-basis} (-> (store/ledger db) first :basis)))
      (is (nil? (store/assessment-of db "facility-1")) "no assessment written"))))

(deftest facility-authorize-use-without-assessment-is-held
  (testing "facility/authorize-use before any jurisdiction assessment -> HOLD (evidence incomplete)"
    (let [[db actor] (fresh)
          res (exec-op actor "t4" {:op :facility/authorize-use :subject "facility-1"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:evidence-incomplete} (-> (store/ledger db) first :basis))))))

(deftest occupancy-exceeds-capacity-is-held
  (testing "a facility whose current-occupancy exceeds its own maximum-capacity -> HOLD"
    (let [[db actor] (fresh)
          _ (assess! actor "t5pre" "facility-3")
          res (exec-op actor "t5" {:op :facility/authorize-use :subject "facility-3"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:occupancy-exceeds-capacity} (-> (store/ledger db) last :basis)))
      (is (empty? (store/authorization-history db))))))

(deftest inspection-not-passed-is-held-and-unoverridable
  (testing "a failed post-hold inspection on a facility -> HOLD, and never reaches request-approval -- exercised via :inspection/screen DIRECTLY, not via the actuation op against an unscreened facility (see this actor's governor ns docstring / parksafety's ADR-2607071922 Decision 5 / eldercare's, museum's, conservation's, salon's, entertainment's, casework's and hospital's ADR-0001s)"
    (let [[db actor] (fresh)
          res (exec-op actor "t6" {:op :inspection/screen :subject "facility-4"} operator)]
      (is (= :hold (get-in res [:state :disposition])) "settles immediately, no interrupt")
      (is (not= :interrupted (:status res)))
      (is (some #{:inspection-not-passed} (-> (store/ledger db) first :basis)))
      (is (nil? (store/inspection-of db "facility-4")) "no clearance written"))))

(deftest facility-authorize-use-always-escalates-then-human-decides
  (testing "a clean, fully-assessed, within-capacity, inspection-passed facility still ALWAYS interrupts for human approval -- actuation/authorize-facility-use is never auto"
    (let [[db actor] (fresh)
          _ (assess! actor "t7pre" "facility-1")
          _ (screen! actor "t7pre2" "facility-1")
          r1 (exec-op actor "t7" {:op :facility/authorize-use :subject "facility-1"} operator)]
      (is (= :interrupted (:status r1)) "pauses for human approval even when governor-clean")
      (testing "approve -> commit, authorization record drafted"
        (let [r2 (approve! actor "t7")]
          (is (= :commit (get-in r2 [:state :disposition])))
          (is (true? (:authorized? (store/facility db "facility-1"))))
          (is (= 1 (count (store/authorization-history db))) "one draft authorization record"))))))

(deftest facility-authorize-use-double-authorization-is-held
  (testing "authorizing the same facility's use twice -> HOLD on the second attempt"
    (let [[db actor] (fresh)
          _ (assess! actor "t8pre" "facility-1")
          _ (screen! actor "t8pre2" "facility-1")
          _ (exec-op actor "t8a" {:op :facility/authorize-use :subject "facility-1"} operator)
          _ (approve! actor "t8a")
          res (exec-op actor "t8" {:op :facility/authorize-use :subject "facility-1"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:already-authorized} (-> (store/ledger db) last :basis)))
      (is (= 1 (count (store/authorization-history db))) "still only the one earlier authorization"))))

(deftest every-decision-leaves-one-ledger-fact
  (testing "write-only-through-ledger: N operations -> N ledger facts"
    (let [[db actor] (fresh)]
      (exec-op actor "a" {:op :facility/intake :subject "facility-1"
                          :patch {:id "facility-1" :facility-name "Sakura Community Gymnasium"}} operator)
      (exec-op actor "b" {:op :jurisdiction/assess :subject "facility-1" :no-spec? true} operator)
      (is (= 2 (count (store/ledger db)))
          "one commit + one hold, both recorded"))))
