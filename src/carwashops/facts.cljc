(ns carwashops.facts
  "Jurisdictional spec-basis table for vehicle-washing operations.

  A jurisdiction that is NOT in this table has **no spec-basis, full
  stop** -- the advisor must report that honestly (empty `:cites`) and
  the Car Wash Governor turns it into a HARD hold. Never invent a
  jurisdiction's effluent standard.

  The regulated concern here is **wastewater discharge**: a vehicle
  wash releases detergent, road oil and heavy metals, so the operating
  basis a real operator needs is the discharge standard and the permit
  regime, not a vehicle-safety rule. (Vehicle roadworthiness belongs to
  the parent actor `cloud-itonami-isic-4520`, and even there it is a
  permanent scope exclusion.)

  `:required-evidence` mirrors the records a discharge inspector
  actually asks for. `required-evidence-satisfied?` is what the
  governor calls -- a missing jurisdiction can never be satisfied."
  (:require [clojure.string :as str]))

(def spec-basis-table
  "iso3 -> requirement map. Seeded with the jurisdictions this fleet
  already carries spec-basis for elsewhere. Adding a jurisdiction is a
  data addition, never a code change."
  {"JPN" {:name "Japan"
          :legal-basis "水質汚濁防止法 (Water Pollution Prevention Act) 第12条"
          :provenance "e-Gov 法令検索 昭和45年法律第138号"
          :discharge-permit-required? true
          :required-evidence ["排水基準適合記録 (effluent-compliance-record)"
                              "特定施設届出番号 (facility-notification-number)"
                              "油水分離槽点検記録 (oil-separator-inspection-record)"
                              "洗車工程記録 (wash-process-record)"]}
   "USA" {:name "United States"
          :legal-basis "Clean Water Act s.402 (NPDES)"
          :provenance "40 CFR Part 122"
          :discharge-permit-required? true
          :required-evidence ["Effluent compliance record"
                              "NPDES permit number"
                              "Oil/water separator inspection record"
                              "Wash process record"]}
   "DEU" {:name "Germany"
          :legal-basis "Wasserhaushaltsgesetz (WHG) SS 57"
          :provenance "Bundesgesetzblatt WHG 2009"
          :discharge-permit-required? true
          :required-evidence ["Abwasser-Konformitatsprotokoll (effluent-compliance-record)"
                              "Einleitungserlaubnis-Nummer (discharge-permit-number)"
                              "Olabscheider-Prufprotokoll (oil-separator-inspection-record)"
                              "Waschprotokoll (wash-process-record)"]}})

(defn spec-basis
  "The jurisdiction's requirement map, or nil -- nil means NO
  spec-basis. Callers must treat nil as 'cannot proceed', never as
  'no requirements'."
  [iso3]
  (get spec-basis-table (some-> iso3 str/upper-case)))

(defn covered?
  "Is this jurisdiction seeded? Never report a missing jurisdiction as
  covered."
  [iso3]
  (some? (spec-basis iso3)))

(defn coverage-summary
  "Honest coverage statement for an operator reading the console."
  []
  (str (count spec-basis-table)
       " jurisdictions seeded with an official spec-basis. "
       "A jurisdiction outside this set has NO basis on file and every "
       "proposal touching it is held."))

(defn required-evidence
  [iso3]
  (:required-evidence (spec-basis iso3) []))

(defn required-evidence-satisfied?
  "Does `submitted` cover every evidence item listed for `iso3`?
  A missing spec-basis can NEVER be satisfied -- returns nil, which is
  falsey, so the governor holds."
  [iso3 submitted]
  (when-let [{:keys [required-evidence]} (spec-basis iso3)]
    (let [need (count required-evidence)
          have (count (filter (set submitted) required-evidence))]
      (= need have))))
