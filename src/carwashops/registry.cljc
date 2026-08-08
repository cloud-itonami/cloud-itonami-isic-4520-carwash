(ns carwashops.registry
  "Pure, backend-agnostic record drafting + the two ground-truth
  recomputations the Car Wash Governor relies on.

  Nothing here reads a proposal. Everything takes the facts already
  recorded on the wash ticket, which is exactly why the governor can
  use it to check the advisor instead of believing it."
  (:require [clojure.string :as str]))

;; ----------------------------- finish / process compatibility -----------------------------

(def finish-forbidden-processes
  "Vehicle finish -> wash processes that must never be applied to it.

  This is a **data table of physical incompatibility**, not a policy
  knob: a matte or vinyl-wrapped finish is destroyed by a high-pressure
  rotating brush, and an acid wheel cleaner etches a ceramic coating.
  A caller cannot loosen it by lowering a threshold, because there is
  no threshold -- only set membership."
  {:matte         #{:high-pressure-brush :acid-wheel-cleaner :clay-bar}
   :vinyl-wrap    #{:high-pressure-brush :acid-wheel-cleaner :hot-wax}
   :ceramic-coat  #{:acid-wheel-cleaner :clay-bar}
   :single-stage  #{:clay-bar}
   :standard-clear #{}})

(defn wash-process-forbidden-by-finish?
  "Independently recompute, from the two permanent ground-truth fields
  already on the vehicle record, whether its own proposed wash process
  is forbidden by its own recorded finish. Needs no proposal at all."
  [{:keys [proposed-wash-process finish]}]
  (boolean (and proposed-wash-process finish
                (contains? (get finish-forbidden-processes finish #{})
                           proposed-wash-process))))

;; ----------------------------- reclaim-rate identity -----------------------------

(defn- abs*
  "Portable absolute value -- `clojure.core/abs` is 1.11+ on the JVM and
  not uniformly available in every `.cljc` host this repo targets."
  [x]
  (if (neg? x) (- x) x))

(defn reclaim-rate
  "Reclaimed water / total water drawn, as a ratio, or nil when either
  figure is missing. An identity, not an estimate."
  [{:keys [water-drawn-litres water-reclaimed-litres]}]
  (when (and (number? water-drawn-litres) (number? water-reclaimed-litres)
             (pos? water-drawn-litres))
    (/ (double water-reclaimed-litres) (double water-drawn-litres))))

(defn reclaim-claim-mismatch?
  "Does the ticket's own claimed reclaim rate disagree with the rate
  recomputed from its own litre counts? Compared with a tolerance of
  0.005 because both sides are doubles -- **the tolerance is on the
  float comparison, not on the rule**."
  [{:keys [claimed-reclaim-rate] :as ticket}]
  (when-let [actual (reclaim-rate ticket)]
    (and (number? claimed-reclaim-rate)
         (> (abs* (- (double claimed-reclaim-rate) actual)) 0.005))))

;; ----------------------------- record drafting -----------------------------

(defn- seq->number [prefix jurisdiction seq-n]
  (str prefix "-" (str/upper-case (or jurisdiction "XXX")) "-"
       (str/join (repeat (max 0 (- 4 (count (str (inc seq-n))))) "0"))
       (inc seq-n)))

(defn register-wash-application
  "Draft the wash-application record. Pure: same inputs -> same record."
  [ticket-id jurisdiction seq-n]
  {"wash_number" (seq->number "WASH" jurisdiction seq-n)
   "ticket_id" ticket-id
   "jurisdiction" jurisdiction})

(defn register-vehicle-return
  "Draft the vehicle-return record. Pure: same inputs -> same record."
  [ticket-id jurisdiction seq-n]
  {"return_number" (seq->number "RET" jurisdiction seq-n)
   "ticket_id" ticket-id
   "jurisdiction" jurisdiction})

(defn append
  "Append to a history collection, keeping insertion order."
  [coll record]
  (conj (vec coll) record))
