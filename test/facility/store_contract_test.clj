(ns facility.store-contract-test
  "The Store contract, run against BOTH backends. Proving MemStore and
  the Datomic-backed (langchain.db) store satisfy the same contract is
  what makes 'swap the SSoT for Datomic / kotoba-server' a configuration
  change, not a rewrite -- see `cloud-itonami-isic-6511`'s
  `underwriting.store-contract-test` for the same pattern on the sibling
  actor."
  (:require [clojure.test :refer [deftest is testing]]
            [facility.store :as store]))

(defn- backends []
  [["MemStore" (store/seed-db)] ["DatomicStore" (store/datomic-seed-db)]])

(deftest read-parity
  (doseq [[label s] (backends)]
    (testing label
      (is (= "Sakura Community Gymnasium" (:facility-name (store/facility s "facility-1"))))
      (is (= "JPN" (:jurisdiction (store/facility s "facility-1"))))
      (is (= 500 (:current-occupancy (store/facility s "facility-1"))))
      (is (= 1000 (:maximum-capacity (store/facility s "facility-1"))))
      (is (true? (:inspection-passed? (store/facility s "facility-1"))))
      (is (= 1200 (:current-occupancy (store/facility s "facility-3"))))
      (is (false? (:inspection-passed? (store/facility s "facility-4"))))
      (is (false? (:authorized? (store/facility s "facility-1"))))
      (is (= ["facility-1" "facility-2" "facility-3" "facility-4"]
             (mapv :id (store/all-facilities s))))
      (is (nil? (store/inspection-of s "facility-1")))
      (is (nil? (store/assessment-of s "facility-1")))
      (is (= [] (store/ledger s)))
      (is (= [] (store/authorization-history s)))
      (is (zero? (store/next-sequence s "JPN")))
      (is (false? (store/facility-already-authorized? s "facility-1"))))))

(deftest write-and-ledger-parity
  (doseq [[label s] (backends)]
    (testing label
      (testing "partial upsert merges, preserving untouched fields"
        (store/commit-record! s {:effect :facility/upsert
                                 :value {:id "facility-1" :facility-name "Sakura Community Gymnasium"}})
        (is (= "Sakura Community Gymnasium" (:facility-name (store/facility s "facility-1"))))
        (is (= 500 (:current-occupancy (store/facility s "facility-1"))) "unrelated field preserved"))
      (testing "assessment / inspection-screening payloads commit and read back"
        (store/commit-record! s {:effect :assessment/set :path ["facility-1"]
                                 :payload {:jurisdiction "JPN" :checklist ["a" "b"]}})
        (is (= {:jurisdiction "JPN" :checklist ["a" "b"]} (store/assessment-of s "facility-1")))
        (store/commit-record! s {:effect :inspection-screening/set :path ["facility-1"]
                                 :payload {:facility-id "facility-1" :verdict :passed}})
        (is (= {:facility-id "facility-1" :verdict :passed} (store/inspection-of s "facility-1"))))
      (testing "facility-use authorization drafts an authorization record and advances the sequence"
        (store/commit-record! s {:effect :facility/mark-authorized :path ["facility-1"]})
        (is (= "JPN-AUT-000000" (get (first (store/authorization-history s)) "record_id")))
        (is (= "facility-use-authorization-draft" (get (first (store/authorization-history s)) "kind")))
        (is (true? (:authorized? (store/facility s "facility-1"))))
        (is (= 1 (count (store/authorization-history s))))
        (is (= 1 (store/next-sequence s "JPN")))
        (is (true? (store/facility-already-authorized? s "facility-1")))
        (is (false? (store/facility-already-authorized? s "facility-2"))))
      (testing "ledger is append-only and order-preserving"
        (store/append-ledger! s {:op :a :disposition :commit})
        (store/append-ledger! s {:op :b :disposition :hold})
        (is (= [:commit :hold] (mapv :disposition (store/ledger s))))))))

(deftest datomic-empty-store-is-usable
  (let [s (store/datomic-store)]
    (is (nil? (store/facility s "nope")))
    (is (= [] (store/all-facilities s)))
    (is (= [] (store/ledger s)))
    (is (= [] (store/authorization-history s)))
    (is (zero? (store/next-sequence s "JPN")))
    (store/with-facilities s {"x" {:id "x" :facility-name "n" :current-occupancy 100
                                   :maximum-capacity 200 :inspection-passed? true
                                   :authorized? false :jurisdiction "JPN" :status :intake}})
    (is (= "n" (:facility-name (store/facility s "x"))))))
