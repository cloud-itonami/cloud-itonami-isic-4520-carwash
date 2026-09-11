(ns carwashops.store
  "SSoT for the vehicle-washing actor, behind a `Store` protocol so the
  backend is a swap, not a rewrite -- the same seam every prior
  `cloud-itonami-isic-*` actor in this fleet uses.

    - `MemStore` -- atom of EDN. The deterministic default for
                    dev/tests/demo (no deps).

  **`DatomicStore` is not implemented here yet.** The sibling actors
  (9601 / 9522 / 9523) carry a `langchain.db`-backed implementation and
  a shared contract test; this repo ships the protocol and the contract
  test so that adding one is a drop-in, but claiming a Datomic backend
  before writing it would be exactly the kind of unverified assertion
  this fleet's ADRs forbid. See `docs/adr/0001-architecture.md`.

  This actor has TWO actuation events (wash application, vehicle
  return) acting on the SAME entity (a wash ticket), each with its OWN
  history collection, sequence counter and dedicated double-actuation
  guard boolean (`:wash-applied?` / `:vehicle-returned?`, **never a
  `:status` value** -- a status is a view, a boolean is a fact).

  The ledger stays append-only: 'which ticket was screened for a lapsed
  discharge permit, which wash was applied, which vehicle was handed
  back, on what jurisdictional basis, approved by whom' is always a
  query over an immutable log."
  (:require [carwashops.registry :as registry]))

(defprotocol Store
  (ticket [s id])
  (all-tickets [s])
  (permit-screening-of [s ticket-id] "committed discharge-permit screening verdict, or nil")
  (effluent-plan-of [s ticket-id] "committed effluent-plan verification, or nil")
  (ledger [s])
  (wash-history [s])
  (return-history [s])
  (next-wash-sequence [s jurisdiction])
  (next-return-sequence [s jurisdiction])
  (vehicle-already-washed? [s ticket-id])
  (vehicle-already-returned? [s ticket-id])
  (commit-record! [s record])
  (append-ledger! [s fact])
  (with-tickets [s tickets]))

;; ----------------------------- demo data -----------------------------

(defn demo-data
  "A small, self-contained wash-ticket set covering both actuation
  lifecycles plus every HARD check, so the actor + tests run offline.

  Each ticket exists to make exactly one governor rule reachable:
    ticket-1  the clean path (standard clear coat, permit current,
              reclaim claim matches its own litre counts)
    ticket-2  jurisdiction \"ATL\" -- no spec-basis on file
    ticket-3  matte finish + high-pressure brush -- physically forbidden
    ticket-4  discharge permit lapsed
    ticket-5  claimed reclaim rate 0.80 vs 300/1000 = 0.30 -- identity fails"
  []
  {:tickets
   {"ticket-1" {:id "ticket-1" :customer "Sakura Tanaka"
                :vehicle "Kei van (weekly exterior wash)"
                :finish :standard-clear :proposed-wash-process :soft-cloth
                :water-drawn-litres 1000 :water-reclaimed-litres 700
                :claimed-reclaim-rate 0.7
                :discharge-permit-not-current? false
                :wash-applied? false :vehicle-returned? false
                :jurisdiction "JPN" :status :intake}
    "ticket-2" {:id "ticket-2" :customer "Atlantis Doe"
                :vehicle "Sedan (full detail)"
                :finish :standard-clear :proposed-wash-process :soft-cloth
                :water-drawn-litres 1000 :water-reclaimed-litres 700
                :claimed-reclaim-rate 0.7
                :discharge-permit-not-current? false
                :wash-applied? false :vehicle-returned? false
                :jurisdiction "ATL" :status :intake}
    "ticket-3" {:id "ticket-3" :customer "鈴木一郎"
                :vehicle "Matte-wrapped coupe (exterior wash)"
                :finish :matte :proposed-wash-process :high-pressure-brush
                :water-drawn-litres 1000 :water-reclaimed-litres 700
                :claimed-reclaim-rate 0.7
                :discharge-permit-not-current? false
                :wash-applied? false :vehicle-returned? false
                :jurisdiction "JPN" :status :intake}
    "ticket-4" {:id "ticket-4" :customer "田中花子"
                :vehicle "Minivan (undercarriage wash)"
                :finish :standard-clear :proposed-wash-process :soft-cloth
                :water-drawn-litres 1000 :water-reclaimed-litres 700
                :claimed-reclaim-rate 0.7
                :discharge-permit-not-current? true
                :wash-applied? false :vehicle-returned? false
                :jurisdiction "JPN" :status :intake}
    "ticket-5" {:id "ticket-5" :customer "佐藤次郎"
                :vehicle "Pickup (mud removal)"
                :finish :standard-clear :proposed-wash-process :soft-cloth
                :water-drawn-litres 1000 :water-reclaimed-litres 300
                :claimed-reclaim-rate 0.8
                :discharge-permit-not-current? false
                :wash-applied? false :vehicle-returned? false
                :jurisdiction "JPN" :status :intake}}})

;; ----------------------------- shared commit logic -----------------------------

(defn- apply-wash-process!
  "Backend-agnostic `:ticket/mark-washed` -- looks the ticket up via the
  protocol, drafts the wash record and returns
  {:result .. :ticket-patch ..} for the caller to persist."
  [s ticket-id]
  (let [t (ticket s ticket-id)
        seq-n (next-wash-sequence s (:jurisdiction t))
        result (registry/register-wash-application ticket-id (:jurisdiction t) seq-n)]
    {:result result
     :ticket-patch {:wash-applied? true
                    :wash-number (get result "wash_number")}}))

(defn- return-vehicle!
  [s ticket-id]
  (let [t (ticket s ticket-id)
        seq-n (next-return-sequence s (:jurisdiction t))
        result (registry/register-vehicle-return ticket-id (:jurisdiction t) seq-n)]
    {:result result
     :ticket-patch {:vehicle-returned? true
                    :return-number (get result "return_number")}}))

;; ----------------------------- MemStore -----------------------------

(defrecord MemStore [a]
  Store
  (ticket [_ id] (get-in @a [:tickets id]))
  (all-tickets [_] (sort-by :id (vals (:tickets @a))))
  (permit-screening-of [_ id] (get-in @a [:permit-screenings id]))
  (effluent-plan-of [_ id] (get-in @a [:effluent-plans id]))
  (ledger [_] (:ledger @a))
  (wash-history [_] (:washes @a))
  (return-history [_] (:returns @a))
  (next-wash-sequence [_ j] (get-in @a [:wash-sequences j] 0))
  (next-return-sequence [_ j] (get-in @a [:return-sequences j] 0))
  (vehicle-already-washed? [_ id] (boolean (get-in @a [:tickets id :wash-applied?])))
  (vehicle-already-returned? [_ id] (boolean (get-in @a [:tickets id :vehicle-returned?])))
  (commit-record! [s {:keys [effect path value payload]}]
    (case effect
      :ticket/upsert
      (swap! a update-in [:tickets (:id value)] merge value)

      :effluent-plan/set
      (swap! a assoc-in [:effluent-plans (first path)] payload)

      :permit-screening/set
      (swap! a assoc-in [:permit-screenings (first path)] payload)

      :ticket/mark-washed
      (let [ticket-id (first path)
            {:keys [result ticket-patch]} (apply-wash-process! s ticket-id)
            j (:jurisdiction (ticket s ticket-id))]
        (swap! a (fn [st]
                   (-> st
                       (update-in [:wash-sequences j] (fnil inc 0))
                       (update-in [:tickets ticket-id] merge ticket-patch)
                       (update :washes registry/append result))))
        result)

      :ticket/mark-returned
      (let [ticket-id (first path)
            {:keys [result ticket-patch]} (return-vehicle! s ticket-id)
            j (:jurisdiction (ticket s ticket-id))]
        (swap! a (fn [st]
                   (-> st
                       (update-in [:return-sequences j] (fnil inc 0))
                       (update-in [:tickets ticket-id] merge ticket-patch)
                       (update :returns registry/append result))))
        result)
      nil)
    s)
  (append-ledger! [_ fact] (swap! a update :ledger conj fact) fact)
  (with-tickets [s tickets] (when (seq tickets) (swap! a assoc :tickets tickets)) s))

(defn seed-db
  "A MemStore seeded with the demo wash-ticket set. The deterministic
  default -- same shape every sibling actor exposes, which is what lets
  the 営み OS adapter be a three-line shim."
  []
  (->MemStore (atom (assoc (demo-data)
                           :effluent-plans {} :permit-screenings {}
                           :ledger [] :wash-sequences {} :washes []
                           :return-sequences {} :returns []))))
