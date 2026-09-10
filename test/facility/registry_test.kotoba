(ns facility.registry-test
  (:require [clojure.test :refer [deftest is]]
            [facility.registry :as r]))

;; ----------------------------- occupancy-exceeds-capacity? -----------------------------

(deftest not-exceeded-when-at-or-below-capacity
  (is (not (r/occupancy-exceeds-capacity? {:current-occupancy 1000 :maximum-capacity 1000})))
  (is (not (r/occupancy-exceeds-capacity? {:current-occupancy 500 :maximum-capacity 1000}))))

(deftest exceeded-when-over-capacity
  (is (r/occupancy-exceeds-capacity? {:current-occupancy 1001 :maximum-capacity 1000}))
  (is (r/occupancy-exceeds-capacity? {:current-occupancy 1200 :maximum-capacity 1000})))

(deftest exceeds-is-false-on-missing-fields
  (is (not (r/occupancy-exceeds-capacity? {})))
  (is (not (r/occupancy-exceeds-capacity? {:current-occupancy 1200}))))

;; ----------------------------- register-facility-use-authorization -----------------------------

(deftest facility-use-authorization-is-a-draft-not-a-real-authorization
  (let [result (r/register-facility-use-authorization "facility-1" "JPN" 0)]
    (is (nil? (get-in result ["certificate" "proof"])))
    (is (= (get-in result ["certificate" "issued_by_registry"]) false))
    (is (= (get-in result ["certificate" "status"]) "draft-unsigned"))))

(deftest facility-use-authorization-assigns-authorization-number
  (let [result (r/register-facility-use-authorization "facility-1" "JPN" 7)]
    (is (= (get result "authorization_number") "JPN-AUT-000007"))
    (is (= (get-in result ["record" "facility_id"]) "facility-1"))
    (is (= (get-in result ["record" "kind"]) "facility-use-authorization-draft"))
    (is (= (get-in result ["record" "immutable"]) true))))

(deftest facility-use-authorization-validation-rules
  (is (thrown? Exception (r/register-facility-use-authorization "" "JPN" 0)))
  (is (thrown? Exception (r/register-facility-use-authorization "facility-1" "" 0)))
  (is (thrown? Exception (r/register-facility-use-authorization "facility-1" "JPN" -1))))

(deftest authorization-history-is-append-only
  (let [c1 (r/register-facility-use-authorization "facility-1" "JPN" 0)
        hist (r/append [] c1)
        c2 (r/register-facility-use-authorization "facility-2" "JPN" 1)
        hist2 (r/append hist c2)]
    (is (= 2 (count hist2)))
    (is (= "JPN-AUT-000000" (get-in hist2 [0 "record_id"])))
    (is (= "JPN-AUT-000001" (get-in hist2 [1 "record_id"])))))
