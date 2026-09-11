(ns carwashops.store-contract-test
  "The contract every `carwashops.store/Store` backend must satisfy.

  Today only `MemStore` implements it. The point of writing the contract
  as a separate namespace is that adding a `DatomicStore` later is a
  drop-in: run these same assertions against it and the actor, the
  governor and the ledger never learn which backend they are on."
  (:require [clojure.test :refer [deftest is testing]]
            [carwashops.facts :as facts]
            [carwashops.store :as store]))

(defn- db [] (store/seed-db))

(deftest the-seed-is-deterministic-and-covers-every-hard-check
  (let [ids (map :id (store/all-tickets (db)))]
    (is (= ["ticket-1" "ticket-2" "ticket-3" "ticket-4" "ticket-5"] ids))
    (testing "each ticket makes exactly one rule reachable"
      (let [st (db)]
        (is (= "JPN" (:jurisdiction (store/ticket st "ticket-1"))))
        (is (= "ATL" (:jurisdiction (store/ticket st "ticket-2")))
            "an unseeded jurisdiction, so spec-basis is unreachable")
        (is (= :matte (:finish (store/ticket st "ticket-3")))
            "forbidden-process check")
        (is (true? (:discharge-permit-not-current? (store/ticket st "ticket-4"))))
        (is (= 0.8 (:claimed-reclaim-rate (store/ticket st "ticket-5")))
            "vs 300/1000 -- the identity fails")))))

(deftest a-fresh-store-has-no-history-and-no-ledger
  (let [st (db)]
    (is (empty? (store/ledger st)))
    (is (empty? (store/wash-history st)))
    (is (empty? (store/return-history st)))
    (is (zero? (store/next-wash-sequence st "JPN")))
    (is (zero? (store/next-return-sequence st "JPN")))))

(deftest the-two-actuations-have-independent-histories-and-counters
  (let [st (db)]
    (store/commit-record! st {:effect :ticket/mark-washed :path ["ticket-1"]})
    (is (= 1 (count (store/wash-history st))))
    (is (empty? (store/return-history st)) "the return history is untouched")
    (is (= 1 (store/next-wash-sequence st "JPN")))
    (is (zero? (store/next-return-sequence st "JPN"))
        "the two sequence counters are independent")
    (store/commit-record! st {:effect :ticket/mark-returned :path ["ticket-1"]})
    (is (= 1 (count (store/return-history st))))
    (is (= 1 (store/next-return-sequence st "JPN")))))

(deftest the-guards-are-dedicated-booleans-not-a-status-value
  (let [st (db)]
    (is (not (store/vehicle-already-washed? st "ticket-1")))
    (store/commit-record! st {:effect :ticket/mark-washed :path ["ticket-1"]})
    (is (store/vehicle-already-washed? st "ticket-1"))
    (is (not (store/vehicle-already-returned? st "ticket-1"))
        "washing must not imply returning")
    (testing "the boolean is a field, and :status is not what is read"
      (is (true? (:wash-applied? (store/ticket st "ticket-1")))))))

(deftest wash-and-return-numbers-are-per-jurisdiction-and-sequential
  (let [st (db)]
    (store/commit-record! st {:effect :ticket/mark-washed :path ["ticket-1"]})
    (store/commit-record! st {:effect :ticket/mark-washed :path ["ticket-3"]})
    (is (= ["WASH-JPN-0001" "WASH-JPN-0002"]
           (mapv #(get % "wash_number") (store/wash-history st))))))

(deftest the-ledger-is-append-only
  (let [st (db)]
    (store/append-ledger! st {:t :committed :op :ticket/intake :subject "ticket-1"})
    (store/append-ledger! st {:t :governor-hold :op :actuation/apply-wash-process
                              :subject "ticket-3"})
    (is (= 2 (count (store/ledger st))))
    (is (= [:committed :governor-hold] (mapv :t (store/ledger st)))
        "insertion order is preserved")))

(deftest screenings-and-plans-round-trip
  (let [st (db)]
    (is (nil? (store/permit-screening-of st "ticket-1")))
    (is (nil? (store/effluent-plan-of st "ticket-1")))
    (store/commit-record! st {:effect :permit-screening/set :path ["ticket-1"]
                              :payload {:discharge-permit-not-current? false}})
    (store/commit-record! st {:effect :effluent-plan/set :path ["ticket-1"]
                              :payload {:jurisdiction "JPN"
                                        :checklist (facts/required-evidence "JPN")}})
    (is (false? (:discharge-permit-not-current? (store/permit-screening-of st "ticket-1"))))
    (is (facts/required-evidence-satisfied?
         "JPN" (:checklist (store/effluent-plan-of st "ticket-1"))))))

(deftest upsert-merges-rather-than-replaces
  (let [st (db)]
    (store/commit-record! st {:effect :ticket/upsert
                              :value {:id "ticket-1" :customer "Renamed"}})
    (let [t (store/ticket st "ticket-1")]
      (is (= "Renamed" (:customer t)))
      (is (= :standard-clear (:finish t)) "untouched fields survive"))))
