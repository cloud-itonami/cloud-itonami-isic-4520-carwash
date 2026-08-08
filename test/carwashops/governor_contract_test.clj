(ns carwashops.governor-contract-test
  "Every HARD check must actually fire, and each must fire for its own
  reason. A gate that cannot be shown to refuse is theatre."
  (:require [clojure.test :refer [deftest is testing]]
            [carwashops.governor :as governor]
            [carwashops.registry :as registry]
            [carwashops.facts :as facts]
            [carwashops.store :as store]))

(def ctx {:actor-id "op-1" :actor-role :wash-bay-supervisor :phase 3})

(defn- db [] (store/seed-db))

(defn- clean-proposal [op subject]
  {:op op :summary "ok" :rationale "ok"
   :cites ["水質汚濁防止法 (Water Pollution Prevention Act) 第12条"]
   :effect :noop :value {:ticket-id subject} :confidence 0.9})

(defn- rules-of [verdict] (set (map :rule (:violations verdict))))

;; ----------------------------- the closed vocabulary -----------------------------

(deftest an-op-outside-the-allowlist-is-hard-held
  (let [v (governor/check {:op :actuation/clear-roadworthiness :subject "ticket-1"}
                          ctx (clean-proposal :actuation/clear-roadworthiness "ticket-1") (db))]
    (is (:hard? v))
    (is (contains? (rules-of v) :op-not-allowed))))

(deftest the-vocabulary-has-no-clearance-or-liability-or-permit-op
  (doseq [absent [:actuation/clear-roadworthiness :actuation/settle-damage-claim
                  :actuation/issue-discharge-permit]]
    (is (not (contains? governor/allowed-ops absent))
        "these are absent from the vocabulary, not merely gated")))

;; ----------------------------- spec basis -----------------------------

(deftest a-proposal-with-no-cites-is-hard-held
  (let [p (assoc (clean-proposal :effluent-plan/verify "ticket-2") :cites [])
        v (governor/check {:op :effluent-plan/verify :subject "ticket-2"} ctx p (db))]
    (is (:hard? v))
    (is (contains? (rules-of v) :no-spec-basis))))

(deftest an-unseeded-jurisdiction-can-never-satisfy-its-evidence
  (is (nil? (facts/required-evidence-satisfied? "ATL" ["anything"]))
      "a missing spec-basis is not 'no requirements'"))

;; ----------------------------- ground-truth recomputations -----------------------------

(deftest a-forbidden-process-is-recomputed-from-the-ticket-not-the-proposal
  ;; ticket-3 is matte + high-pressure-brush. The proposal is clean and
  ;; well-cited; the hold comes only from the ticket's own two fields.
  (let [v (governor/check {:op :actuation/apply-wash-process :subject "ticket-3"}
                          ctx (clean-proposal :actuation/apply-wash-process "ticket-3") (db))]
    (is (:hard? v))
    (is (contains? (rules-of v) :wash-process-forbidden-by-finish)))
  (testing "and it is set membership, so there is no threshold to lower"
    (is (registry/wash-process-forbidden-by-finish?
         {:finish :matte :proposed-wash-process :high-pressure-brush}))
    (is (not (registry/wash-process-forbidden-by-finish?
              {:finish :standard-clear :proposed-wash-process :high-pressure-brush})))))

(deftest a-reclaim-claim-that-disagrees-with-its-own-litres-is-hard-held
  (let [v (governor/check {:op :actuation/apply-wash-process :subject "ticket-5"}
                          ctx (clean-proposal :actuation/apply-wash-process "ticket-5") (db))]
    (is (:hard? v))
    (is (contains? (rules-of v) :reclaim-claim-mismatch)))
  (testing "it is an identity, computed from the ticket alone"
    (is (registry/reclaim-claim-mismatch?
         {:water-drawn-litres 1000 :water-reclaimed-litres 300 :claimed-reclaim-rate 0.8}))
    (is (not (registry/reclaim-claim-mismatch?
              {:water-drawn-litres 1000 :water-reclaimed-litres 700 :claimed-reclaim-rate 0.7})))))

;; ----------------------------- permit currency -----------------------------

(deftest a-lapsed-permit-holds-from-the-screening-ops-own-finding
  (let [p (assoc (clean-proposal :discharge-permit/screen "ticket-4")
                 :value {:ticket-id "ticket-4" :discharge-permit-not-current? true})
        v (governor/check {:op :discharge-permit/screen :subject "ticket-4"} ctx p (db))]
    (is (:hard? v))
    (is (contains? (rules-of v) :discharge-permit-not-current))))

(deftest a-lapsed-permit-already-on-file-holds-an-actuation
  (let [st (db)]
    (store/commit-record! st {:effect :permit-screening/set :path ["ticket-1"]
                              :payload {:ticket-id "ticket-1"
                                        :discharge-permit-not-current? true}})
    (let [v (governor/check {:op :actuation/apply-wash-process :subject "ticket-1"}
                            ctx (clean-proposal :actuation/apply-wash-process "ticket-1") st)]
      (is (:hard? v))
      (is (contains? (rules-of v) :discharge-permit-not-current)))))

;; ----------------------------- evidence -----------------------------

(deftest an-actuation-without-a-verified-effluent-plan-is-hard-held
  (let [v (governor/check {:op :actuation/return-vehicle :subject "ticket-1"}
                          ctx (clean-proposal :actuation/return-vehicle "ticket-1") (db))]
    (is (:hard? v))
    (is (contains? (rules-of v) :evidence-incomplete))))

(deftest a-complete-effluent-plan-clears-the-evidence-check
  (let [st (db)]
    (store/commit-record! st {:effect :effluent-plan/set :path ["ticket-1"]
                              :payload {:jurisdiction "JPN"
                                        :checklist (facts/required-evidence "JPN")}})
    (let [v (governor/check {:op :actuation/return-vehicle :subject "ticket-1"}
                            ctx (clean-proposal :actuation/return-vehicle "ticket-1") st)]
      (is (not (contains? (rules-of v) :evidence-incomplete))))))

;; ----------------------------- double actuation -----------------------------

(deftest the-same-vehicle-cannot-be-washed-or-returned-twice
  (let [st (db)]
    (store/commit-record! st {:effect :effluent-plan/set :path ["ticket-1"]
                              :payload {:jurisdiction "JPN"
                                        :checklist (facts/required-evidence "JPN")}})
    (store/commit-record! st {:effect :ticket/mark-washed :path ["ticket-1"]})
    (store/commit-record! st {:effect :ticket/mark-returned :path ["ticket-1"]})
    (is (contains? (rules-of (governor/check
                              {:op :actuation/apply-wash-process :subject "ticket-1"}
                              ctx (clean-proposal :actuation/apply-wash-process "ticket-1") st))
                   :already-washed))
    (is (contains? (rules-of (governor/check
                              {:op :actuation/return-vehicle :subject "ticket-1"}
                              ctx (clean-proposal :actuation/return-vehicle "ticket-1") st))
                   :already-returned))))

;; ----------------------------- scope exclusion -----------------------------

(deftest prose-reaching-for-an-excluded-decision-is-hard-held
  (let [p (assoc (clean-proposal :ticket/intake "ticket-1")
                 :rationale "vehicle inspected and confirmed roadworthy")
        v (governor/check {:op :ticket/intake :subject "ticket-1"} ctx p (db))]
    (is (:hard? v))
    (is (contains? (rules-of v) :scope-excluded))))

;; ----------------------------- high stakes -----------------------------

(deftest high-stakes-is-decided-on-the-op-not-the-advisors-self-report
  (let [st (db)]
    (store/commit-record! st {:effect :effluent-plan/set :path ["ticket-1"]
                              :payload {:jurisdiction "JPN"
                                        :checklist (facts/required-evidence "JPN")}})
    (testing "an actuation escalates even when the advisor declares no stake"
      (let [p (dissoc (clean-proposal :actuation/return-vehicle "ticket-1") :stake)
            v (governor/check {:op :actuation/return-vehicle :subject "ticket-1"} ctx p st)]
        (is (:high-stakes? v))
        (is (:escalate? v))
        (is (not (:ok? v)))))
    (testing "and intake does not escalate on stakes"
      (let [v (governor/check {:op :ticket/intake :subject "ticket-1"} ctx
                              (clean-proposal :ticket/intake "ticket-1") st)]
        (is (not (:high-stakes? v)))
        (is (:ok? v))))))

(deftest low-confidence-escalates-without-being-hard
  (let [st (db)
        p (assoc (clean-proposal :ticket/intake "ticket-1") :confidence 0.1)
        v (governor/check {:op :ticket/intake :subject "ticket-1"} ctx p st)]
    (is (not (:hard? v)))
    (is (:escalate? v))))
