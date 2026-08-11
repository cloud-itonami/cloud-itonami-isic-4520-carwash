(ns carwashops.render-html
  "Build-time operator console renderer -- `clojure -M:dev:render-html`.

  This namespace renders NOTHING of its own invention. It drives the
  REAL actor stack (`carwashops.store/seed-db` ->
  `carwashops.operation/build` -> `langgraph.graph/run*`) exactly the
  way `carwashops.sim` does, then reads the resulting SSoT and
  append-only ledger back out through the `Store` protocol and prints
  what it found. Every wash ticket on the page is a seeded ticket from
  `store/demo-data`; every HARD hold on the page was produced by
  `carwashops.governor/check` recomputing ground truth from the ticket's
  own recorded fields.

  ## What can appear in the ledger, and what deliberately cannot

  `carwashops.operation` writes the store ledger from exactly two
  nodes: `:commit` appends `{:t :committed}`, and `:hold` appends the
  `:governor-hold` / `:approval-rejected` fact. `:advisor-proposed`,
  `:approval-requested` and `:approval-granted` are emitted to the
  in-memory `:audit` channel ONLY and never reach the store. So this
  renderer branches on `:committed`, `:governor-hold` and
  `:approval-rejected` and on nothing else -- an `:approval-granted`
  branch here would be a status that can never be shown, which is worse
  than no branch at all.

  ## Determinism

  `advisor/mock-advisor` is deterministic, `registry` is pure, and
  nothing here reads a clock, a UUID or the filesystem. Two runs of
  `-main` produce byte-identical HTML. Doubles are formatted with
  `Locale/ROOT` so the bytes do not depend on the machine's locale."
  (:require [clojure.string :as str]
            [carwashops.facts :as facts]
            [carwashops.governor :as governor]
            [carwashops.operation :as op]
            [carwashops.phase :as phase]
            [carwashops.registry :as registry]
            [carwashops.store :as store]
            [langgraph.graph :as g])
  (:import (java.io File)
           (java.util Locale)))

;; ----------------------------- driving the real actor -----------------------------

(def ^:private operator
  "The human on the other side of `interrupt-before #{:request-approval}`."
  {:actor-id "op-1" :actor-role :wash-bay-supervisor :phase phase/default-phase})

(defn- exec!
  "One operation = one graph run on its own thread-id."
  [actor tid request]
  (g/run* actor {:request request :context operator} {:thread-id tid}))

(defn- approve!
  "Resume an interrupted thread with a human approval."
  [actor tid]
  (g/run* actor {:approval {:status :approved :by "op-1"}}
          {:thread-id tid :resume? true}))

(defn- exec-approve!
  "The normal shape for every write op except `:ticket/intake`: the
  phase gate escalates it (`:phase-approval`), the operator approves,
  the `:commit` node writes the SSoT."
  [actor tid request]
  (exec! actor tid request)
  (approve! actor tid))

(defn run-demo!
  "Drive the real OperationActor over the seeded wash tickets.

  Every subject below is a ticket that already exists in
  `store/demo-data` -- nothing is invented, and no field is passed that
  the ticket model does not already carry.

  The hold scenarios are ordered so that each HARD rule fires in
  ISOLATION wherever possible: ticket-3 and ticket-5 get their effluent
  plan verified and their discharge permit screened FIRST, so that when
  the wash actuation is finally attempted the only thing left standing
  between the operator and the wash bay is the one physical/arithmetic
  fact the governor recomputed for itself."
  []
  (let [db    (store/seed-db)
        actor (op/build db)]

    ;; --- ticket-1: the clean path, end to end -------------------------
    ;; `:ticket/intake` is the ONLY op any phase may auto-commit.
    (exec! actor "t1" {:op :ticket/intake :subject "ticket-1"
                       :patch {:id "ticket-1" :customer "Sakura Tanaka"}})
    (exec-approve! actor "t2" {:op :effluent-plan/verify    :subject "ticket-1"})
    (exec-approve! actor "t3" {:op :discharge-permit/screen :subject "ticket-1"})
    ;; Both actuations are absent from every phase's :auto set, forever.
    (exec-approve! actor "t4" {:op :actuation/apply-wash-process :subject "ticket-1"})
    (exec-approve! actor "t5" {:op :actuation/return-vehicle     :subject "ticket-1"})

    ;; --- ticket-5: evidence-incomplete BEFORE its plan exists ---------
    ;; A JPN ticket has a spec-basis on file, so the spec-basis gate is
    ;; satisfied -- what is missing is the verified effluent plan itself.
    (exec! actor "h-evidence" {:op :actuation/return-vehicle :subject "ticket-5"})

    ;; --- ticket-3: everything compliant except the physics ------------
    (exec-approve! actor "t6" {:op :effluent-plan/verify    :subject "ticket-3"})
    (exec-approve! actor "t7" {:op :discharge-permit/screen :subject "ticket-3"})
    (exec! actor "h-finish" {:op :actuation/apply-wash-process :subject "ticket-3"})

    ;; --- ticket-5: everything compliant except the arithmetic ---------
    (exec-approve! actor "t8" {:op :effluent-plan/verify    :subject "ticket-5"})
    (exec-approve! actor "t9" {:op :discharge-permit/screen :subject "ticket-5"})
    (exec! actor "h-reclaim" {:op :actuation/apply-wash-process :subject "ticket-5"})

    ;; --- ticket-2: a jurisdiction with no effluent standard on file ---
    (exec! actor "h-basis" {:op :effluent-plan/verify :subject "ticket-2"})

    ;; --- ticket-4: a lapsed discharge permit, found by the screen -----
    (exec! actor "h-permit" {:op :discharge-permit/screen :subject "ticket-4"})

    ;; --- an op outside the closed vocabulary --------------------------
    (exec! actor "h-vocab" {:op :actuation/clear-roadworthiness :subject "ticket-1"})

    ;; --- the two double-actuation guards ------------------------------
    (exec! actor "h-washed"   {:op :actuation/apply-wash-process :subject "ticket-1"})
    (exec! actor "h-returned" {:op :actuation/return-vehicle     :subject "ticket-1"})

    db))

;; ----------------------------- html helpers -----------------------------

(defn- esc [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")
      (str/replace "\"" "&quot;")))

(defn- sfmt
  "`format` pinned to `Locale/ROOT` so the rendered bytes do not depend
  on the machine's default locale."
  [f & args]
  (String/format Locale/ROOT f (object-array args)))

(defn- code [v] (str "<code>" (esc v) "</code>"))

(defn- kws [coll]
  (if (seq coll) (str/join ", " (map str coll)) "-"))

(defn- rate [x] (if (number? x) (sfmt "%.3f" (double x)) "-"))

(defn- yn [b] (if b "yes" "no"))

;; ----------------------------- ledger views -----------------------------

(defn- holds
  "Every HARD hold the governor actually wrote to the ledger."
  [ledger]
  (filterv #(= :governor-hold (:t %)) ledger))

(defn- rules-fired
  "The governor rule names that fired in THIS run, read back off the
  ledger -- not a list this namespace keeps."
  [ledger]
  (into (sorted-set) (mapcat :basis (holds ledger))))

(defn- last-fact-for [ledger subject]
  (last (filter #(= subject (:subject %)) ledger)))

(defn- status-cell
  "Only the three fact types `carwashops.operation` actually appends to
  the store ledger are branched on here."
  [ledger subject]
  (let [f (last-fact-for ledger subject)]
    (cond
      (nil? f) "<span class=\"muted\">no ledger activity</span>"
      (= :committed (:t f))
      (str "<span class=\"ok\">committed</span> <span class=\"muted\">"
           (esc (:op f)) "</span>")
      (= :governor-hold (:t f))
      (str "<span class=\"critical\">HARD hold</span> <span class=\"muted\">"
           (esc (kws (:basis f))) "</span>")
      (= :approval-rejected (:t f))
      "<span class=\"critical\">approver rejected</span>"
      :else "<span class=\"muted\">unknown</span>")))

;; ----------------------------- sections -----------------------------

(defn- ticket-rows [db ledger]
  (->> (store/all-tickets db)
       (map (fn [{:keys [id customer vehicle jurisdiction finish
                         proposed-wash-process water-drawn-litres
                         water-reclaimed-litres claimed-reclaim-rate
                         discharge-permit-not-current?
                         wash-applied? vehicle-returned?] :as t}]
              (sfmt (str "        <tr><td>%s</td><td>%s</td><td>%s</td><td>%s</td>"
                         "<td>%s</td><td>%s</td><td class=\"num\">%s / %s</td>"
                         "<td class=\"num\">%s</td><td class=\"num\">%s</td>"
                         "<td>%s</td><td>%s</td><td>%s</td><td>%s</td></tr>")
                    (code id) (esc customer) (esc vehicle) (esc jurisdiction)
                    (code finish) (code proposed-wash-process)
                    (esc water-reclaimed-litres) (esc water-drawn-litres)
                    (esc (rate claimed-reclaim-rate))
                    (esc (rate (registry/reclaim-rate t)))
                    (if discharge-permit-not-current?
                      "<span class=\"critical\">lapsed</span>"
                      "<span class=\"ok\">current</span>")
                    (esc (yn wash-applied?)) (esc (yn vehicle-returned?))
                    (status-cell ledger id))))
       (str/join "\n")))

(defn- hold-rows
  "One row per violation of every HARD hold in the ledger. `:detail` is
  the governor's own sentence, carried on the fact -- not a string this
  renderer composes."
  [ledger]
  (->> (holds ledger)
       (mapcat (fn [{:keys [op subject violations confidence]}]
                 (map (fn [{:keys [rule detail]}]
                        (sfmt (str "        <tr><td>%s</td><td>%s</td><td>%s</td>"
                                   "<td>%s</td><td class=\"num\">%s</td></tr>")
                              (code subject) (code op)
                              (str "<span class=\"critical\">" (esc rule) "</span>")
                              (esc detail) (esc (rate confidence))))
                      violations)))
       (str/join "\n")))

(defn- gate-rows
  "Derived from `carwashops.phase/phases` and
  `carwashops.governor/high-stakes` -- if either changes, this table
  changes with it."
  []
  (let [{:keys [writes auto]} (get phase/phases phase/default-phase)]
    (->> (sort-by str governor/allowed-ops)
         (map (fn [o]
                (sfmt "        <tr><td>%s</td><td>%s</td><td>%s</td><td>%s</td></tr>"
                      (code o)
                      (esc (yn (contains? writes o)))
                      (if (contains? auto o)
                        "<span class=\"ok\">may auto-commit when governor-clean</span>"
                        "<span class=\"warn\">ALWAYS human approval</span>")
                      (if (contains? governor/high-stakes o)
                        "<span class=\"critical\">high-stakes actuation</span>"
                        "<span class=\"muted\">-</span>"))))
         (str/join "\n"))))

(defn- phase-rows []
  (->> (sort (keys phase/phases))
       (map (fn [p]
              (let [{:keys [label writes auto]} (get phase/phases p)]
                (sfmt "        <tr><td class=\"num\">%s</td><td>%s</td><td>%s</td><td>%s</td></tr>"
                      (esc p) (esc label) (esc (kws (sort-by str writes)))
                      (esc (kws (sort-by str auto)))))))
       (str/join "\n")))

(defn- jurisdiction-rows [db]
  (let [seen (frequencies (map :jurisdiction (store/all-tickets db)))]
    (->> (concat (sort (keys facts/spec-basis-table))
                 (sort (remove facts/covered? (keys seen))))
         distinct
         (map (fn [iso3]
                (let [sb (facts/spec-basis iso3)]
                  (sfmt "        <tr><td>%s</td><td>%s</td><td>%s</td><td class=\"num\">%s</td><td class=\"num\">%s</td></tr>"
                        (code iso3)
                        (if sb (esc (:name sb))
                            "<span class=\"critical\">NO spec-basis on file</span>")
                        (if sb (esc (:legal-basis sb)) "-")
                        (esc (count (facts/required-evidence iso3)))
                        (esc (get seen iso3 0))))))
         (str/join "\n"))))

(defn- finish-rows []
  (->> (sort-by str (keys registry/finish-forbidden-processes))
       (map (fn [f]
              (let [bad (get registry/finish-forbidden-processes f)]
                (sfmt "        <tr><td>%s</td><td>%s</td></tr>"
                      (code f)
                      (if (seq bad)
                        (str "<span class=\"critical\">" (esc (kws (sort-by str bad))) "</span>")
                        "<span class=\"muted\">none</span>")))))
       (str/join "\n")))

(defn- register-rows [records number-key]
  (if (seq records)
    (->> records
         (map (fn [r]
                (sfmt "        <tr><td>%s</td><td>%s</td><td>%s</td></tr>"
                      (code (get r number-key))
                      (code (get r "ticket_id"))
                      (esc (get r "jurisdiction")))))
         (str/join "\n"))
    "        <tr><td colspan=\"3\" class=\"muted\">no records</td></tr>"))

(defn- ledger-rows [ledger]
  (->> ledger
       (map-indexed
        (fn [i {:keys [t op subject disposition basis actor summary]}]
          (sfmt (str "        <tr><td class=\"num\">%s</td><td>%s</td><td>%s</td><td>%s</td>"
                     "<td>%s</td><td>%s</td><td>%s</td></tr>")
                (esc (inc i))
                (if (= :committed t)
                  (str "<span class=\"ok\">" (esc t) "</span>")
                  (str "<span class=\"critical\">" (esc t) "</span>"))
                (code op) (code subject) (esc actor)
                (esc (kws basis))
                (esc (or summary (str disposition))))))
       (str/join "\n")))

;; ----------------------------- page -----------------------------

(def ^:private css
  (str "body{font:14px/1.6 -apple-system,BlinkMacSystemFont,'Hiragino Sans',sans-serif;"
       "margin:0;color:#1a1a1a;background:#f4f5f7}"
       ".bar{background:#123040;color:#fff;padding:1.3rem 2rem}"
       ".bar h1{margin:0;font-size:1.15rem}.bar p{margin:.35rem 0 0;font-size:.8rem;opacity:.8}"
       "main{max-width:1180px;margin:1.5rem auto;padding:0 1rem}"
       ".card{background:#fff;border-radius:8px;padding:1.1rem 1.3rem;margin-bottom:1.1rem;"
       "box-shadow:0 1px 3px rgba(0,0,0,.08)}"
       ".card h2{margin:0 0 .2rem;font-size:1rem}"
       ".muted{color:#6b7280;font-size:.82rem}"
       "table{border-collapse:collapse;width:100%;font-size:.83rem;margin-top:.6rem}"
       "th,td{text-align:left;padding:.4rem .5rem;border-bottom:1px solid #eef0f2;vertical-align:top}"
       "th{font-weight:600;color:#555;white-space:nowrap}"
       "td.num,th.num{text-align:right;font-variant-numeric:tabular-nums}"
       ".ok{color:#0a7d33}.warn{color:#9a6700}.critical{color:#b41010;font-weight:600}"
       "code{background:#f0f1f3;padding:.08rem .3rem;border-radius:3px;font-size:.79rem}"
       "ul{margin:.4rem 0 0;padding-left:1.1rem;font-size:.83rem}"))

(defn render
  "Render the operator console from a driven store. Reads only through
  the `Store` protocol and the pure `facts`/`registry`/`phase`/`governor`
  tables."
  [db]
  (let [ledger  (vec (store/ledger db))
        fired   (rules-fired ledger)
        n-hold  (count (holds ledger))
        n-commit (count (filter #(= :committed (:t %)) ledger))
        scope?  (contains? fired :scope-excluded)]
    (str
     "<!doctype html>\n<html lang=\"en\"><head><meta charset=\"utf-8\">"
     "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
     "<title>cloud-itonami-isic-4520-carwash &mdash; operator console</title>"
     "<style>" css "</style></head><body>\n"
     "<header class=\"bar\"><h1>Vehicle wash operations (ISIC 4520) &mdash; <code>carwashops</code></h1>"
     "<p>Generated by <code>carwashops.render-html</code> from a real "
     "<code>carwashops.operation</code> actor run over <code>carwashops.store/seed-db</code>. "
     "No hand-written state.</p></header>\n<main>\n"

     ;; --- summary ---
     "<section class=\"card\"><h2>This run</h2>"
     "<p class=\"muted\">" (esc n-commit) " committed facts, " (esc n-hold)
     " HARD holds, " (esc (count fired)) " distinct governor rules exercised: "
     (esc (kws fired)) ".</p>"
     "<p class=\"muted\">The store ledger is append-only and holds exactly the facts "
     "<code>carwashops.operation</code> writes from its <code>:commit</code> and "
     "<code>:hold</code> nodes. <code>:advisor-proposed</code>, "
     "<code>:approval-requested</code> and <code>:approval-granted</code> live on the "
     "in-memory <code>:audit</code> channel only and are deliberately not shown as a "
     "ledger status here.</p>"
     "<p class=\"muted\">" (esc (facts/coverage-summary)) "</p>"
     (if scope?
       "<p class=\"muted\">The <code>:scope-excluded</code> gate fired in this run.</p>"
       (str "<p class=\"muted\">The <code>:scope-excluded</code> gate did NOT fire: no "
            "proposal drafted by the shipped deterministic advisor contains any of the "
            (esc (count governor/scope-excluded-terms))
            " permanently-out-of-scope terms it scans for (roadworthiness clearance, "
            "damage liability, self-issued discharge permit). It is listed here as an "
            "unexercised rule rather than staged with invented prose.</p>"))
     "</section>\n"

     ;; --- hard holds ---
     "<section class=\"card\"><h2>HARD holds &mdash; recomputed by the governor</h2>"
     "<p class=\"muted\">Each row is a violation map the governor itself put on the "
     "ledger fact. The rule and its detail sentence come from "
     "<code>carwashops.governor</code>, recomputed from fields already recorded on the "
     "ticket &mdash; never from the advisor's own report.</p>"
     "<table><thead><tr><th>Ticket</th><th>Op</th><th>Rule</th><th>Governor detail</th>"
     "<th class=\"num\">Advisor confidence</th></tr></thead><tbody>\n"
     (hold-rows ledger)
     "\n      </tbody></table></section>\n"

     ;; --- tickets ---
     "<section class=\"card\"><h2>Wash tickets</h2>"
     "<p class=\"muted\">Every ticket below is seeded in "
     "<code>carwashops.store/demo-data</code>. &quot;Reclaim (recomputed)&quot; is "
     "<code>carwashops.registry/reclaim-rate</code> applied to the ticket's own litre "
     "counts &mdash; the identity the governor compares the claim against.</p>"
     "<table><thead><tr><th>Ticket</th><th>Customer</th><th>Vehicle</th><th>Juris.</th>"
     "<th>Finish</th><th>Proposed process</th><th class=\"num\">Reclaimed / drawn L</th>"
     "<th class=\"num\">Reclaim (claimed)</th><th class=\"num\">Reclaim (recomputed)</th>"
     "<th>Discharge permit</th><th>Washed</th><th>Returned</th><th>Last ledger fact</th>"
     "</tr></thead><tbody>\n"
     (ticket-rows db ledger)
     "\n      </tbody></table></section>\n"

     ;; --- action gate ---
     "<section class=\"card\"><h2>Action gate at phase " (esc phase/default-phase) " ("
     (esc (:label (get phase/phases phase/default-phase))) ")</h2>"
     "<p class=\"muted\">Derived from <code>carwashops.phase/phases</code> and "
     "<code>carwashops.governor/high-stakes</code>. The two "
     "<code>:actuation/*</code> ops are absent from every phase's auto set at every "
     "phase &mdash; a permanent structural fact, not a rollout milestone. Governor "
     "confidence floor: " (esc (rate governor/confidence-floor)) ".</p>"
     "<table><thead><tr><th>Op</th><th>Write enabled</th><th>Auto-commit</th>"
     "<th>Stakes</th></tr></thead><tbody>\n"
     (gate-rows)
     "\n      </tbody></table>"
     "<table><thead><tr><th class=\"num\">Phase</th><th>Label</th><th>Writes</th>"
     "<th>Auto-commit</th></tr></thead><tbody>\n"
     (phase-rows)
     "\n      </tbody></table></section>\n"

     ;; --- finish / process table ---
     "<section class=\"card\"><h2>Physically forbidden wash processes</h2>"
     "<p class=\"muted\"><code>carwashops.registry/finish-forbidden-processes</code> is "
     "set membership, not a threshold &mdash; there is no knob to lower.</p>"
     "<table><thead><tr><th>Finish</th><th>Forbidden processes</th></tr></thead><tbody>\n"
     (finish-rows)
     "\n      </tbody></table></section>\n"

     ;; --- jurisdictions ---
     "<section class=\"card\"><h2>Jurisdictional spec-basis</h2>"
     "<p class=\"muted\">A jurisdiction outside "
     "<code>carwashops.facts/spec-basis-table</code> has NO effluent standard on file; "
     "the advisor reports that honestly with empty <code>:cites</code> and the governor "
     "turns it into a HARD hold.</p>"
     "<table><thead><tr><th>ISO3</th><th>Name</th><th>Legal basis</th>"
     "<th class=\"num\">Required records</th><th class=\"num\">Tickets in run</th>"
     "</tr></thead><tbody>\n"
     (jurisdiction-rows db)
     "\n      </tbody></table></section>\n"

     ;; --- actuation registers ---
     "<section class=\"card\"><h2>Actuation registers</h2>"
     "<p class=\"muted\">Drafted by <code>carwashops.registry</code> and written only by "
     "the <code>:commit</code> node, each behind its own sequence counter and its own "
     "double-actuation guard boolean.</p>"
     "<table><thead><tr><th>Wash number</th><th>Ticket</th><th>Jurisdiction</th>"
     "</tr></thead><tbody>\n"
     (register-rows (store/wash-history db) "wash_number")
     "\n      </tbody></table>"
     "<table><thead><tr><th>Return number</th><th>Ticket</th><th>Jurisdiction</th>"
     "</tr></thead><tbody>\n"
     (register-rows (store/return-history db) "return_number")
     "\n      </tbody></table></section>\n"

     ;; --- ledger ---
     "<section class=\"card\"><h2>Append-only audit ledger</h2>"
     "<p class=\"muted\">" (esc (count ledger)) " facts, in commit order.</p>"
     "<table><thead><tr><th class=\"num\">#</th><th>Fact</th><th>Op</th><th>Subject</th>"
     "<th>Actor</th><th>Basis</th><th>Summary / disposition</th></tr></thead><tbody>\n"
     (ledger-rows ledger)
     "\n      </tbody></table></section>\n"
     "</main></body></html>\n")))

(defn -main
  "Drive the actor and write the console. Optional arg: output path."
  [& args]
  (let [out    (or (first args) "docs/samples/operator-console.html")
        db     (run-demo!)
        ledger (store/ledger db)
        f      (File. ^String out)]
    (when-let [parent (.getParentFile f)] (.mkdirs parent))
    (spit f (render db))
    (println "wrote" out
             (str "(" (count ledger) " ledger facts, "
                  (count (holds ledger)) " HARD holds, rules: "
                  (kws (rules-fired ledger)) ")"))))
