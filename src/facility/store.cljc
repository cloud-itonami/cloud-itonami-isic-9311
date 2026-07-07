(ns facility.store
  "SSoT for the sports-facility actor, behind a `Store` protocol so
  the backend is a swap, not a rewrite -- the same seam every prior
  `cloud-itonami-isic-*` actor in this fleet uses:

    - `MemStore`     -- atom of EDN. The deterministic default for
                        dev/tests/demo (no deps).
    - `DatomicStore` -- backed by `langchain.db`, a Datomic-API-compatible
                        EAV store (datalog q / pull / upsert). Pure `.cljc`,
                        so it runs offline AND can be pointed at a real
                        Datomic Local or a kotoba-server pod by swapping
                        `langchain.db`'s `:db-api` (see langchain.kotoba-db).

  Both implement the same protocol and pass the same contract
  (test/facility/store_contract_test.clj), which is the whole point:
  the actor, the Facility Safety Governor and the audit ledger never
  know which SSoT they run on.

  Like `parksafety.store`'s simpler entities, a FACILITY is acted on
  directly by the ONE actuation op -- no dynamically-filed sub-record,
  and the double-authorization guard checks a dedicated `:authorized?`
  boolean rather than a `:status` value, the same discipline
  `accounting.governor`'s/`marketadmin.governor`'s/`testlab.
  governor`'s/`parksafety.governor`'s guards establish.

  The ledger stays append-only on every backend: 'which facility was
  screened for a passed post-hold inspection, which facility's use was
  authorized, on what jurisdictional basis, approved by whom' is
  always a query over an immutable log -- the audit trail a patron
  trusting a venue needs, and the evidence an operator needs if an
  authorization is later disputed."
  (:require #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])
            [facility.registry :as registry]
            [langchain.db :as d]))

(defprotocol Store
  (facility [s id])
  (all-facilities [s])
  (inspection-of [s facility-id] "committed post-hold inspection screening verdict for a facility, or nil")
  (assessment-of [s facility-id] "committed jurisdiction assessment, or nil")
  (ledger [s])
  (authorization-history [s] "the append-only facility-use-authorization history (facility.registry drafts)")
  (next-sequence [s jurisdiction] "next facility-use-authorization-number sequence for a jurisdiction")
  (facility-already-authorized? [s facility-id] "has this facility's use already been authorized?")
  (commit-record! [s record] "apply a committed op's record to the SSoT")
  (append-ledger! [s fact]   "append one immutable decision fact")
  (with-facilities [s facilities] "replace/seed the facility directory (map id->facility)"))

;; ----------------------------- demo data -----------------------------

(defn demo-data
  "A small, self-contained facility set so the actor + tests run
  offline."
  []
  {:facilities
   {"facility-1" {:id "facility-1" :facility-name "Sakura Community Gymnasium"
                  :current-occupancy 500 :maximum-capacity 1000 :inspection-passed? true
                  :authorized? false :jurisdiction "JPN" :status :intake}
    "facility-2" {:id "facility-2" :facility-name "Unregistered Arena"
                  :current-occupancy 500 :maximum-capacity 1000 :inspection-passed? true
                  :authorized? false :jurisdiction "ATL" :status :intake}
    "facility-3" {:id "facility-3" :facility-name "Overcrowded Stadium"
                  :current-occupancy 1200 :maximum-capacity 1000 :inspection-passed? true
                  :authorized? false :jurisdiction "JPN" :status :intake}
    "facility-4" {:id "facility-4" :facility-name "Inspection-Flagged Pool"
                  :current-occupancy 500 :maximum-capacity 1000 :inspection-passed? false
                  :authorized? false :jurisdiction "JPN" :status :intake}}})

;; ----------------------------- shared commit logic -----------------------------

(defn- authorize-facility-use!
  "Backend-agnostic `:facility/mark-authorized` -- looks up the
  facility via the protocol and drafts the facility-use-authorization
  record, and returns {:result .. :facility-patch ..} for the caller
  to persist."
  [s facility-id]
  (let [f (facility s facility-id)
        seq-n (next-sequence s (:jurisdiction f))
        result (registry/register-facility-use-authorization facility-id (:jurisdiction f) seq-n)]
    {:result result
     :facility-patch {:authorized? true
                      :authorization-number (get result "authorization_number")}}))

;; ----------------------------- MemStore (default) -----------------------------

(defrecord MemStore [a]
  Store
  (facility [_ id] (get-in @a [:facilities id]))
  (all-facilities [_] (sort-by :id (vals (:facilities @a))))
  (inspection-of [_ id] (get-in @a [:inspections id]))
  (assessment-of [_ facility-id] (get-in @a [:assessments facility-id]))
  (ledger [_] (:ledger @a))
  (authorization-history [_] (:authorizations @a))
  (next-sequence [_ jurisdiction] (get-in @a [:sequences jurisdiction] 0))
  (facility-already-authorized? [_ facility-id] (boolean (get-in @a [:facilities facility-id :authorized?])))
  (commit-record! [s {:keys [effect path value payload]}]
    (case effect
      :facility/upsert
      (swap! a update-in [:facilities (:id value)] merge value)

      :assessment/set
      (swap! a assoc-in [:assessments (first path)] payload)

      :inspection-screening/set
      (swap! a assoc-in [:inspections (first path)] payload)

      :facility/mark-authorized
      (let [facility-id (first path)
            {:keys [result facility-patch]} (authorize-facility-use! s facility-id)
            jurisdiction (:jurisdiction (facility s facility-id))]
        (swap! a (fn [state]
                   (-> state
                       (update-in [:sequences jurisdiction] (fnil inc 0))
                       (update-in [:facilities facility-id] merge facility-patch)
                       (update :authorizations registry/append result))))
        result)
      nil)
    s)
  (append-ledger! [_ fact] (swap! a update :ledger conj fact) fact)
  (with-facilities [s facilities] (when (seq facilities) (swap! a assoc :facilities facilities)) s))

(defn seed-db
  "A MemStore seeded with the demo facility set. The deterministic
  default."
  []
  (->MemStore (atom (assoc (demo-data)
                           :assessments {} :inspections {} :ledger [] :sequences {}
                           :authorizations []))))

;; ----------------------------- DatomicStore (langchain.db) -----------------------------

(def ^:private schema
  "DataScript/Datomic-style schema: only constraint attrs are declared.
  Map/compound values (assessment/inspection payloads, ledger facts,
  authorization records) are stored as EDN strings so `langchain.db`
  doesn't expand them into sub-entities -- the same convention every
  sibling actor's store uses."
  {:facility/id                {:db/unique :db.unique/identity}
   :assessment/facility-id      {:db/unique :db.unique/identity}
   :inspection-screening/facility-id {:db/unique :db.unique/identity}
   :ledger/seq                  {:db/unique :db.unique/identity}
   :authorization/seq           {:db/unique :db.unique/identity}
   :sequence/jurisdiction        {:db/unique :db.unique/identity}})

(defn- enc [v] (pr-str v))
(defn- dec* [s] (when s (edn/read-string s)))

(defn- facility->tx [{:keys [id facility-name current-occupancy maximum-capacity
                             inspection-passed? authorized? jurisdiction status authorization-number]}]
  (cond-> {:facility/id id}
    facility-name              (assoc :facility/facility-name facility-name)
    current-occupancy          (assoc :facility/current-occupancy current-occupancy)
    maximum-capacity           (assoc :facility/maximum-capacity maximum-capacity)
    (some? inspection-passed?) (assoc :facility/inspection-passed? inspection-passed?)
    (some? authorized?)        (assoc :facility/authorized? authorized?)
    jurisdiction               (assoc :facility/jurisdiction jurisdiction)
    status                     (assoc :facility/status status)
    authorization-number       (assoc :facility/authorization-number authorization-number)))

(def ^:private facility-pull
  [:facility/id :facility/facility-name :facility/current-occupancy :facility/maximum-capacity
   :facility/inspection-passed? :facility/authorized? :facility/jurisdiction :facility/status
   :facility/authorization-number])

(defn- pull->facility [m]
  (when (:facility/id m)
    {:id (:facility/id m) :facility-name (:facility/facility-name m)
     :current-occupancy (:facility/current-occupancy m)
     :maximum-capacity (:facility/maximum-capacity m)
     :inspection-passed? (boolean (:facility/inspection-passed? m))
     :authorized? (boolean (:facility/authorized? m))
     :jurisdiction (:facility/jurisdiction m) :status (:facility/status m)
     :authorization-number (:facility/authorization-number m)}))

(defrecord DatomicStore [conn]
  Store
  (facility [_ id]
    (pull->facility (d/pull (d/db conn) facility-pull [:facility/id id])))
  (all-facilities [_]
    (->> (d/q '[:find [?id ...] :where [?e :facility/id ?id]] (d/db conn))
         (map #(pull->facility (d/pull (d/db conn) facility-pull [:facility/id %])))
         (sort-by :id)))
  (inspection-of [_ id]
    (dec* (d/q '[:find ?p . :in $ ?fid
                :where [?k :inspection-screening/facility-id ?fid] [?k :inspection-screening/payload ?p]]
              (d/db conn) id)))
  (assessment-of [_ facility-id]
    (dec* (d/q '[:find ?p . :in $ ?fid
                :where [?a :assessment/facility-id ?fid] [?a :assessment/payload ?p]]
              (d/db conn) facility-id)))
  (ledger [_]
    (->> (d/q '[:find ?s ?f :where [?e :ledger/seq ?s] [?e :ledger/fact ?f]] (d/db conn))
         (sort-by first)
         (mapv (comp dec* second))))
  (authorization-history [_]
    (->> (d/q '[:find ?s ?r :where [?e :authorization/seq ?s] [?e :authorization/record ?r]] (d/db conn))
         (sort-by first)
         (mapv (comp dec* second))))
  (next-sequence [_ jurisdiction]
    (or (d/q '[:find ?n . :in $ ?j
              :where [?e :sequence/jurisdiction ?j] [?e :sequence/next ?n]]
            (d/db conn) jurisdiction)
        0))
  (facility-already-authorized? [s facility-id]
    (boolean (:authorized? (facility s facility-id))))
  (commit-record! [s {:keys [effect path value payload]}]
    (case effect
      :facility/upsert
      (d/transact! conn [(facility->tx value)])

      :assessment/set
      (d/transact! conn [{:assessment/facility-id (first path) :assessment/payload (enc payload)}])

      :inspection-screening/set
      (d/transact! conn [{:inspection-screening/facility-id (first path) :inspection-screening/payload (enc payload)}])

      :facility/mark-authorized
      (let [facility-id (first path)
            {:keys [result facility-patch]} (authorize-facility-use! s facility-id)
            jurisdiction (:jurisdiction (facility s facility-id))
            next-n (inc (next-sequence s jurisdiction))]
        (d/transact! conn
                     [(facility->tx (assoc facility-patch :id facility-id))
                      {:sequence/jurisdiction jurisdiction :sequence/next next-n}
                      {:authorization/seq (count (authorization-history s)) :authorization/record (enc (get result "record"))}])
        result)
      nil)
    s)
  (append-ledger! [s fact]
    (d/transact! conn [{:ledger/seq (count (ledger s)) :ledger/fact (enc fact)}])
    fact)
  (with-facilities [s facilities]
    (when (seq facilities) (d/transact! conn (mapv facility->tx (vals facilities)))) s))

(defn datomic-store
  "A DatomicStore (langchain.db backend) seeded from `data`
  ({:facilities ..}); empty when omitted."
  ([] (datomic-store {}))
  ([{:keys [facilities]}]
   (let [s (->DatomicStore (d/create-conn schema))]
     (with-facilities s facilities))))

(defn datomic-seed-db
  "A DatomicStore seeded with the demo facility set -- the Datomic-
  backed analog of `seed-db`, used to prove protocol parity."
  []
  (datomic-store (demo-data)))
