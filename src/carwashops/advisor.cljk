(ns carwashops.advisor
  "The **contained intelligence**: a CarWashAdvisor that drafts proposals
  and nothing else. It has no authority whatsoever -- every proposal it
  produces is censored by `carwashops.governor` and gated by
  `carwashops.phase` before anything reaches the SSoT.

  A proposal is:

    {:op         kw             ; must be in governor/allowed-ops
     :summary    str            ; human-readable
     :rationale  str            ; why -- SCANNED by the scope-exclusion gate
     :cites      [kw|str ..]    ; facts/sources used -- SCANNED by the spec-basis gate
     :effect     kw             ; which store effect a commit would apply
     :value      map            ; the drafted payload
     :confidence double}

  The mock advisor below is deterministic and offline, so the demo, the
  tests and the 営み OS surface all produce the same bytes. A real LLM
  advisor swaps in via the `Advisor` protocol -- the governor does not
  change, which is the entire point of the pattern.

  **The advisor reports missing basis honestly.** When a jurisdiction has
  no spec-basis on file it emits empty `:cites` and does NOT raise its
  confidence -- fabricating a standard would make the governor's
  spec-basis gate unreachable, which is worse than a hold."
  (:require [carwashops.facts :as facts]
            [carwashops.store :as store]))

(defprotocol Advisor
  (-advise [a store request] "Draft a proposal for this request."))

(defn trace
  "The audit fact recording that the advisor was consulted. Written
  whatever the disposition turns out to be -- the ledger must show what
  was proposed even when it was held."
  [request proposal]
  {:t          :advisor-proposed
   :op         (:op request)
   :subject    (:subject request)
   :summary    (:summary proposal)
   :cites      (:cites proposal)
   :confidence (:confidence proposal)})

;; ----------------------------- proposal generators -----------------------------

(defn- propose-intake
  "`:patch` arrives at the TOP level of the request, not nested under a
  `:payload` key -- `cloud-itonami.os.adapters.standard/->request` merges
  the OS envelope's payload into the request map, and every sibling's
  `sim.cljc` passes `:patch` the same way. Reading `(:patch payload)`
  here would silently draft an empty ticket."
  [_st {:keys [subject patch confidence]}]
  {:op :ticket/intake
   :summary (str subject " の洗車受付票を起票")
   :rationale "受付の記録行為。資本リスクも物理作用も無い。"
   :cites (vec (keys patch))
   :effect :ticket/upsert
   :value (merge {:id subject} patch)
   :confidence (or confidence 0.9)})

(defn- propose-effluent-plan [st {:keys [subject]}]
  (let [t (store/ticket st subject)
        iso3 (:jurisdiction t)
        sb (facts/spec-basis iso3)]
    (if-not sb
      ;; Honest: no basis on file. Empty cites, confidence NOT raised.
      {:op :effluent-plan/verify
       :summary (str iso3 " の公式spec-basis(排水基準)が見つかりません")
       :rationale (str iso3 " は台帳に無い法域。排水基準を推測で作らない。")
       :cites []
       :effect :effluent-plan/set
       :value {:jurisdiction iso3 :checklist [] :spec-basis nil}
       :confidence 0.2}
      {:op :effluent-plan/verify
       :summary (str subject " の排水計画を " iso3 " 基準で検証")
       :rationale (str (:legal-basis sb) " に基づく必要書類の充足確認。")
       :cites [(:legal-basis sb) (:provenance sb)]
       :effect :effluent-plan/set
       :value {:jurisdiction iso3
               :checklist (facts/required-evidence iso3)
               :spec-basis (:provenance sb)}
       :confidence 0.88})))

(defn- propose-permit-screen [st {:keys [subject]}]
  (let [t (store/ticket st subject)
        lapsed? (true? (:discharge-permit-not-current? t))]
    {:op :discharge-permit/screen
     :summary (str subject " の排水許可の現況を確認"
                   (if lapsed? " -- 失効を検出" " -- 有効"))
     :rationale "排水許可の現況は台帳の事実から読む。提案者の自己申告では判定しない。"
     :cites [:discharge-permit-check]
     :effect :permit-screening/set
     :value {:ticket-id subject :discharge-permit-not-current? lapsed?}
     :confidence 0.9}))

(defn- propose-wash [st {:keys [subject]}]
  (let [t (store/ticket st subject)
        iso3 (:jurisdiction t)
        sb (facts/spec-basis iso3)]
    {:op :actuation/apply-wash-process
     :summary (str subject " に洗車工程(" (:proposed-wash-process t) ")を適用")
     :rationale "受付・排水計画・許可確認を経た洗車工程の適用。実施は人の承認を要する。"
     :cites (if sb [(:legal-basis sb) subject] [])
     :effect :ticket/mark-washed
     :value {:ticket-id subject :spec-basis (:provenance sb)}
     :confidence 0.85}))

(defn- propose-return [st {:keys [subject]}]
  (let [t (store/ticket st subject)
        iso3 (:jurisdiction t)
        sb (facts/spec-basis iso3)]
    {:op :actuation/return-vehicle
     :summary (str subject " の車両を返却")
     :rationale "預り車両の返却。所有権は動かず占有だけが戻る行為で、人の承認を要する。"
     :cites (if sb [(:legal-basis sb) subject] [])
     :effect :ticket/mark-returned
     :value {:ticket-id subject :spec-basis (:provenance sb)}
     :confidence 0.85}))

(defn infer
  "Route to the correct proposal generator. An op outside the allowlist
  gets a `:noop` proposal with zero confidence -- the governor's
  `op-not-allowed` check is what actually refuses it, so this branch
  never becomes the decision."
  [st {:keys [op] :as request}]
  (case op
    :ticket/intake                  (propose-intake st request)
    :effluent-plan/verify           (propose-effluent-plan st request)
    :discharge-permit/screen        (propose-permit-screen st request)
    :actuation/apply-wash-process   (propose-wash st request)
    :actuation/return-vehicle       (propose-return st request)
    {:op op :summary "未対応の操作" :rationale (str op)
     :cites [] :effect :noop :value {} :confidence 0.0}))

(defn mock-advisor
  "Deterministic offline advisor. Same request -> same proposal, which
  is what makes the demo surface byte-reproducible."
  []
  (reify Advisor
    (-advise [_ st request] (infer st request))))
