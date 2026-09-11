(ns carwashops.governor
  "The **Car Wash Governor** -- an independent censor, a different system
  than the advisor it censors. It never asks the advisor whether a
  proposal is safe; it recomputes what it can from the ticket the
  operator already recorded, and holds when it cannot.

  ## The closed op vocabulary

  `allowed-ops` is the whole vocabulary. **No op in it finalizes a
  roadworthiness clearance, a damage-liability decision, or a discharge
  permit.** Those are not gated -- they are absent. A vehicle-wash
  operator does not certify that a car is safe to drive (that is the
  parent actor `cloud-itonami-isic-4520`'s domain, and even there it is
  a permanent scope exclusion), does not adjudicate who pays for a
  scratch, and does not issue its own effluent permit.

  ## The checks, and why each one cannot be talked out of

    1. Spec basis missing      -- a jurisdiction with no effluent standard
                                  on file cannot be operated in. HARD.
    2. Evidence incomplete     -- for either actuation, the jurisdiction's
                                  required records must actually be present.
                                  Not the advisor's confidence -- the records.
    3. Discharge permit lapsed -- reported by this proposal OR already on
                                  file for the ticket. HARD, un-overridable.
    4. Process forbidden by    -- recomputed from the vehicle's own recorded
       finish                     finish and its own proposed process, via
                                  `registry/wash-process-forbidden-by-finish?`.
                                  **Set membership, so there is no threshold
                                  to lower.**
    5. Reclaim claim mismatch  -- the ticket's claimed water-reclaim rate vs
                                  the rate recomputed from its own litre
                                  counts. An identity, not an estimate.
    6/7. Already washed /      -- double-actuation guards off dedicated
       already returned           booleans, never a `:status` value.

  ## high-stakes is a set of OPS, not of advisor-reported stakes

  This fleet has two vocabularies for this: 9601 puts **op names** in
  `high-stakes`, while 9522/9523 put the advisor's self-reported
  `:stake` value there. **This actor deliberately follows 9601.** A
  permanent invariant must not depend on the censored party's own
  report -- if the advisor omits or mislabels `:stake`, an op-name set
  still holds and a `:stake` set does not. (superproject ADR-2800004000
  records the divergence.)"
  (:require [kotoba.lang.text :as str]
            [carwashops.facts :as facts]
            [carwashops.registry :as registry]
            [carwashops.store :as store]))

(def confidence-floor 0.6)

(def allowed-ops
  "The closed proposal-op allowlist -- an op outside this set is a scope
  violation by construction."
  #{:ticket/intake :effluent-plan/verify :discharge-permit/screen
    :actuation/apply-wash-process :actuation/return-vehicle})

(def high-stakes
  "Stakes grave enough to always require a human, even when clean.
  Applying a real wash process to a real vehicle and handing a real
  vehicle back are the two real-world acts this actor performs."
  #{:actuation/apply-wash-process :actuation/return-vehicle})

(def scope-excluded-terms
  "Case-insensitive substrings that mark a proposal as reaching for a
  permanently out-of-scope decision -- clearing a vehicle as roadworthy,
  settling a damage claim, or self-issuing a discharge permit. Checked
  against the advisor's own prose so an otherwise-legitimate op cannot
  smuggle one in."
  ["roadworthy" "roadworthiness" "safe to drive" "車検に合格"
   "damage claim approved" "damage liability accepted" "損害賠償を認め"
   "permit issued" "permit granted" "排水許可を発行" "許可を交付"])

;; ----------------------------- checks -----------------------------

(defn- spec-basis-violations
  "An `:effluent-plan/verify` or actuation proposal with no spec-basis
  citation is a HARD violation -- never invent a jurisdiction's
  effluent standard."
  [{:keys [op]} proposal]
  (when (contains? #{:effluent-plan/verify
                     :actuation/apply-wash-process
                     :actuation/return-vehicle} op)
    (let [value (:value proposal)]
      (when (or (empty? (:cites proposal))
                (and (contains? value :spec-basis) (nil? (:spec-basis value))))
        [{:rule :no-spec-basis
          :detail "公式spec-basis(排水基準)の引用が無い提案は洗車事業の運営基準として扱えない"}]))))

(defn- evidence-incomplete-violations
  "For either actuation, the jurisdiction's required records must
  actually be satisfied -- do not trust the advisor's self-reported
  confidence."
  [{:keys [op subject]} st]
  (when (contains? #{:actuation/apply-wash-process :actuation/return-vehicle} op)
    (let [t (store/ticket st subject)
          plan (store/effluent-plan-of st subject)]
      (when-not (and plan
                     (facts/required-evidence-satisfied?
                      (:jurisdiction t) (:checklist plan)))
        [{:rule :evidence-incomplete
          :detail "法域の必要書類(排水基準適合記録/施設届出/油水分離槽点検記録/洗車工程記録等)が充足していない"}]))))

(defn- discharge-permit-not-current-violations
  "A lapsed discharge permit -- reported by THIS proposal (e.g. a
  `:discharge-permit/screen` that just found it lapsed) or already on
  file for the ticket -- is a HARD, un-overridable hold. Evaluated
  UNCONDITIONALLY so the screening op can hold on its own finding."
  [{:keys [op subject]} proposal st]
  (let [hit-in-proposal? (true? (get-in proposal [:value :discharge-permit-not-current?]))
        ticket-id (when (contains? #{:discharge-permit/screen
                                     :actuation/apply-wash-process
                                     :actuation/return-vehicle} op)
                    subject)
        hit-on-file? (and ticket-id
                          (true? (:discharge-permit-not-current?
                                  (store/permit-screening-of st ticket-id))))]
    (when (or hit-in-proposal? hit-on-file?)
      [{:rule :discharge-permit-not-current
        :detail "排水許可が最新でない状態での提案は進められない"}])))

(defn- process-forbidden-by-finish-violations
  "For `:actuation/apply-wash-process`, INDEPENDENTLY recompute whether
  the vehicle's own proposed process is forbidden by its own recorded
  finish. Inputs are permanent ground-truth fields already on the
  ticket, so no proposal inspection is needed at all."
  [{:keys [op subject]} st]
  (when (= op :actuation/apply-wash-process)
    (let [t (store/ticket st subject)]
      (when (registry/wash-process-forbidden-by-finish? t)
        [{:rule :wash-process-forbidden-by-finish
          :detail (str subject " の提案洗車工程(" (:proposed-wash-process t)
                       ")が塗装/仕上げ" (:finish t) "の禁止工程に含まれている")}]))))

(defn- reclaim-claim-mismatch-violations
  "For `:actuation/apply-wash-process`, the ticket's claimed water
  reclaim rate must equal the rate recomputed from its own litre
  counts. An identity -- there is nothing to loosen."
  [{:keys [op subject]} st]
  (when (= op :actuation/apply-wash-process)
    (let [t (store/ticket st subject)]
      (when (registry/reclaim-claim-mismatch? t)
        [{:rule :reclaim-claim-mismatch
          :detail (str subject " の申告再利用率(" (:claimed-reclaim-rate t)
                       ")が取水量/再利用量から再計算した値と一致しない")}]))))

(defn- already-washed-violations
  [{:keys [op subject]} st]
  (when (= op :actuation/apply-wash-process)
    (when (store/vehicle-already-washed? st subject)
      [{:rule :already-washed :detail (str subject " は既に洗車処理済み")}])))

(defn- already-returned-violations
  [{:keys [op subject]} st]
  (when (= op :actuation/return-vehicle)
    (when (store/vehicle-already-returned? st subject)
      [{:rule :already-returned :detail (str subject " は既に返却済み")}])))

(defn- scope-exclusion-violations
  "A proposal whose own prose reaches for a permanently excluded
  decision is HARD-held, whatever op it claims to be."
  [proposal]
  (let [text (str (:summary proposal) " " (:rationale proposal))
        lower (str/lower text)]
    (when-let [hit (first (filter #(str/includes? lower (str/lower (str %)))
                                  scope-excluded-terms))]
      [{:rule :scope-excluded
        :detail (str "恒久的にスコープ外の判断に触れる文言を含む: " hit)}])))

(defn- op-not-allowed-violations
  [{:keys [op]}]
  (when-not (contains? allowed-ops op)
    [{:rule :op-not-allowed
      :detail (str op " はこの actor の語彙に存在しない")}]))

(defn check
  "Censors a CarWashAdvisor proposal against the governor rules.
  Returns {:ok? bool :violations [..] :confidence c :escalate? bool
  :high-stakes? bool :hard? bool}."
  [request _context proposal st]
  (let [hard (into []
                   (concat (op-not-allowed-violations request)
                           (spec-basis-violations request proposal)
                           (evidence-incomplete-violations request st)
                           (discharge-permit-not-current-violations request proposal st)
                           (process-forbidden-by-finish-violations request st)
                           (reclaim-claim-mismatch-violations request st)
                           (already-washed-violations request st)
                           (already-returned-violations request st)
                           (scope-exclusion-violations proposal)))
        conf (:confidence proposal 0.0)
        low? (< conf confidence-floor)
        ;; op-name based, NOT `(:stake proposal)` -- see this ns's docstring.
        stakes? (boolean (high-stakes (:op request)))
        hard? (boolean (seq hard))]
    {:ok?          (and (not hard?) (not low?) (not stakes?))
     :violations   hard
     :confidence   conf
     :hard?        hard?
     :escalate?    (and (not hard?) (or low? stakes?))
     :high-stakes? stakes?}))

(defn hold-fact
  "The audit fact written when a proposal is rejected (HOLD)."
  [request context verdict]
  {:t           :governor-hold
   :op          (:op request)
   :actor       (:actor-id context)
   :subject     (:subject request)
   :disposition :hold
   :basis       (mapv :rule (:violations verdict))
   :violations  (:violations verdict)
   :confidence  (:confidence verdict)})
