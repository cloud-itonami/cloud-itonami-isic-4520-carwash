(ns carwashops.render-html
  "Build-time HTML renderer for `docs/samples/operator-console.html`.

  Closes flagship checklist item 2 for this repo: it previously had NO
  demo page and no generator at all. This namespace drives the REAL
  actor stack (`carwashops.operation` -> `carwashops.advisor` ->
  `carwashops.governor` -> `carwashops.phase` -> `carwashops.store`)
  through a scenario adapted from this repo's own `carwashops.sim` demo
  driver (`clojure -M:dev:run`).

  **Input provenance.** Every subject id below -- `ticket-1` ..
  `ticket-5` -- is literally present in `carwashops.store/demo-data`,
  checked against the seed before this file was written. This repo's
  own `sim.cljc` uses the same five ids, so it was safe to adapt rather
  than author a fresh scenario. Nothing here invents a ticket, a field
  or a number: every cell rendered below is either a field that exists
  on this repo's wash-ticket model, a value recomputed by this repo's
  own pure `carwashops.registry` functions, or a fact the real governor
  wrote to the append-only ledger during this run.

  **The gate / phase / scope tables are derived, not transcribed** --
  they read `carwashops.governor/allowed-ops`, `governor/high-stakes`,
  `governor/scope-excluded-terms` and `carwashops.phase/phases`
  directly, so they cannot drift away from what the actor actually
  does.

  Deterministic: no timestamps, no random, no wall-clock in the page
  content; byte-identical across reruns against the same seed (verify
  by diffing two consecutive runs).

  Usage: `clojure -M:dev:render-html [out-file]`
  (default `docs/samples/operator-console.html`)."
  (:require [jp-go-dds.skin]
            [kotoba.lang.text :as str]
            [carwashops.facts :as facts]
            [carwashops.governor :as governor]
            [carwashops.operation :as op]
            [carwashops.phase :as phase]
            [carwashops.registry :as registry]
            [carwashops.store :as store]
            [langgraph.graph :as g]))

(def ^:private operator
  "Same operator envelope `carwashops.sim` uses: phase 3 (supervised
  auto), which is the most permissive phase this actor has."
  {:actor-id "op-1" :actor-role :wash-bay-supervisor :phase 3})

(defn- exec! [actor tid request]
  (g/run* actor {:request request :context operator} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "op-1"}}
          {:thread-id tid :resume? true}))

(defn- reject! [actor tid]
  (g/run* actor {:approval {:status :rejected :by "op-1"}}
          {:thread-id tid :resume? true}))

(defn run-demo!
  "Runs a fresh seeded store through a scenario that reaches every
  disposition and EVERY HARD rule this governor has.

  Clean lifecycle -- ticket-1 (JPN, standard clear coat, soft cloth,
  permit current, reclaim claim 0.7 matching its own 700/1000 litres):
  intake (the only op any phase may auto-commit), effluent-plan
  verification, discharge-permit screening, wash-process application
  and vehicle return. The last two ALWAYS escalate to a human -- they
  are absent from every phase's `:auto` set and are members of
  `governor/high-stakes`, two independent layers asserting the same
  invariant -- and are approved here.

  HARD holds, each reached through a seeded ticket that exists to make
  exactly one rule reachable (see `store/demo-data`'s own docstring):

    ticket-2  `:no-spec-basis`                    jurisdiction \"ATL\" has no
                                                  effluent standard on file
    ticket-3  `:wash-process-forbidden-by-finish` matte finish + high-pressure
                                                  brush, recomputed from the
                                                  ticket's own two fields
    ticket-4  `:discharge-permit-not-current`     screening finds the permit
                                                  lapsed and holds on its own
                                                  finding
    ticket-4  `:evidence-incomplete`              a return attempt with no
                                                  verified effluent plan on
                                                  file (the held screening
                                                  above wrote nothing)
    ticket-5  `:reclaim-claim-mismatch`           claimed 0.8 vs the identity
                                                  300/1000 = 0.3
    ticket-1  `:op-not-allowed` + `:scope-excluded`
                                                  an op outside the closed
                                                  vocabulary whose own name
                                                  reaches for a roadworthiness
                                                  clearance -- both rules fire
    ticket-1  `:already-washed` / `:already-returned`
                                                  the two double-actuation
                                                  guards, off dedicated
                                                  booleans

  ticket-3 and ticket-5 get their effluent plan verified first, so that
  their actuation holds isolate the finish rule and the reclaim
  identity instead of also tripping `:evidence-incomplete`.

  No HARD hold ever reaches a human -- `phase/gate` keeps a governor
  HOLD a HOLD at every phase.

  One escalation is deliberately REJECTED rather than approved
  (ticket-3's discharge-permit screening). That exercises the other
  side of the human-in-the-loop gate and puts the third and last fact
  type this store actually persists -- `:approval-rejected` -- into the
  ledger, so the page renders no state it has not observed. It also
  demonstrates that the approval interrupt is real: the rejected
  screening writes NOTHING to the SSoT, which is why ticket-3's later
  wash attempt still has no permit screening on file.

  Note which facts reach the persisted ledger at all: only `:committed`
  (from the `:commit` node) and `:governor-hold` / `:approval-rejected`
  (from the `:hold` node). `:advisor-proposed`, `:approval-requested`
  and `:approval-granted` are emitted into the in-memory run audit and
  are NEVER appended by `store/append-ledger!` -- so nothing on this
  page may test the ledger for them. Returns the resulting store."
  []
  (let [db (store/seed-db)
        actor (op/build db)]
    ;; --- ticket-1: the full clean lifecycle ---
    (exec! actor "t1-intake" {:op :ticket/intake :subject "ticket-1"
                              :patch {:id "ticket-1" :customer "Sakura Tanaka"}})

    (exec! actor "t1-plan" {:op :effluent-plan/verify :subject "ticket-1"})
    (approve! actor "t1-plan")

    (exec! actor "t1-permit" {:op :discharge-permit/screen :subject "ticket-1"})
    (approve! actor "t1-permit")

    (exec! actor "t1-wash" {:op :actuation/apply-wash-process :subject "ticket-1"})
    (approve! actor "t1-wash")

    (exec! actor "t1-return" {:op :actuation/return-vehicle :subject "ticket-1"})
    (approve! actor "t1-return")

    ;; --- HARD: no spec-basis for jurisdiction "ATL" ---
    (exec! actor "t2-plan" {:op :effluent-plan/verify :subject "ticket-2"})

    ;; --- HARD: wash process forbidden by the vehicle's own finish ---
    (exec! actor "t3-plan" {:op :effluent-plan/verify :subject "ticket-3"})
    (approve! actor "t3-plan")
    ;; The human says NO. Governor-clean, so it escalated; the approver
    ;; declined, so it lands as :approval-rejected and commits nothing.
    (exec! actor "t3-permit" {:op :discharge-permit/screen :subject "ticket-3"})
    (reject! actor "t3-permit")
    (exec! actor "t3-wash" {:op :actuation/apply-wash-process :subject "ticket-3"})

    ;; --- HARD: lapsed discharge permit, then evidence incomplete ---
    (exec! actor "t4-permit" {:op :discharge-permit/screen :subject "ticket-4"})
    (exec! actor "t4-return" {:op :actuation/return-vehicle :subject "ticket-4"})

    ;; --- HARD: claimed reclaim rate vs the ticket's own litre counts ---
    (exec! actor "t5-plan" {:op :effluent-plan/verify :subject "ticket-5"})
    (approve! actor "t5-plan")
    (exec! actor "t5-wash" {:op :actuation/apply-wash-process :subject "ticket-5"})

    ;; --- HARD: op outside the closed vocabulary, reaching for an
    ;;     excluded decision (both rules fire on the one proposal) ---
    (exec! actor "x-scope" {:op :actuation/clear-roadworthiness :subject "ticket-1"})

    ;; --- HARD: the two double-actuation guards ---
    (exec! actor "x-rewash" {:op :actuation/apply-wash-process :subject "ticket-1"})
    (exec! actor "x-rereturn" {:op :actuation/return-vehicle :subject "ticket-1"})
    db))

;; ----------------------------- rendering -----------------------------

(defn- esc [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))

(defn- kw-str [k]
  (cond (keyword? k) (if-let [n (namespace k)] (str n "/" (name k)) (name k))
        :else (str k)))

(defn- fmt-rate
  "Round to 3 decimals with integer arithmetic -- `format`'s decimal
  separator is locale-dependent and this page must be byte-identical
  on any machine."
  [x]
  (if (nil? x) "&mdash;" (str (/ (Math/round (* (double x) 1000.0)) 1000.0))))

(defn- last-fact-for [ledger ticket-id]
  (last (filter #(= (:subject %) ticket-id) ledger)))

(defn- status-cell
  "The ticket's last PERSISTED fact. `carwashops.operation` appends to
  `store/append-ledger!` from exactly two nodes -- `:commit` (writing
  `:committed`) and `:hold` (writing whichever of `:governor-hold` /
  `:approval-rejected` the run produced) -- so those three are the only
  `:t` values that can ever appear here. `:advisor-proposed`,
  `:approval-requested` and `:approval-granted` go to the in-memory run
  audit only and are NEVER appended, so this function must not test for
  them; a branch on `:approval-granted` would be dead code that reads
  like a feature."
  [ledger ticket-id]
  (let [f (last-fact-for ledger ticket-id)]
    (cond
      (nil? f) "<span class=\"muted\">no activity</span>"
      (= :committed (:t f)) "<span class=\"ok\">committed</span>"
      (= :governor-hold (:t f))
      (str "<span class=\"critical\">HARD hold &middot; "
           (esc (str/join ", " (map kw-str (:basis f)))) "</span>")
      (= :approval-rejected (:t f))
      "<span class=\"warn\">approver declined &middot; nothing committed</span>"
      :else "<span class=\"muted\">in progress</span>")))

(defn- actuation-cell
  "Both actuation lifecycles off their OWN dedicated boolean (never a
  `:status` value), plus the registry number the commit produced."
  [{:keys [wash-applied? vehicle-returned? wash-number return-number]}]
  (str
   (if wash-applied?
     (str "<span class=\"ok\">washed &middot; <code>" (esc wash-number) "</code></span>")
     "<span class=\"muted\">not washed</span>")
   "<br>"
   (if vehicle-returned?
     (str "<span class=\"ok\">returned &middot; <code>" (esc return-number) "</code></span>")
     "<span class=\"muted\">not returned</span>")))

(defn- reclaim-cell
  "Claimed vs the rate `carwashops.registry/reclaim-rate` recomputes
  from this ticket's own litre counts -- an identity, not an estimate."
  [{:keys [claimed-reclaim-rate water-drawn-litres water-reclaimed-litres] :as t}]
  (let [actual (registry/reclaim-rate t)
        bad? (registry/reclaim-claim-mismatch? t)]
    (str "claimed " (fmt-rate claimed-reclaim-rate)
         " / recomputed " (fmt-rate actual)
         " <span class=\"muted\">(" (esc water-reclaimed-litres) "&nbsp;/&nbsp;"
         (esc water-drawn-litres) "&nbsp;L)</span> "
         (if bad?
           "<span class=\"critical\">mismatch</span>"
           "<span class=\"ok\">identity holds</span>"))))

(defn- permit-cell [{:keys [discharge-permit-not-current?]}]
  (if discharge-permit-not-current?
    "<span class=\"critical\">lapsed</span>"
    "<span class=\"ok\">current</span>"))

(defn- ticket-row
  [ledger {:keys [id customer vehicle finish proposed-wash-process jurisdiction] :as t}]
  (format "        <tr><td><code>%s</code></td><td>%s</td><td>%s</td><td>%s &rarr; <code>%s</code></td><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td></tr>"
          (esc id) (esc customer) (esc vehicle)
          (esc (kw-str finish)) (esc (kw-str proposed-wash-process))
          (esc jurisdiction)
          (reclaim-cell t)
          (permit-cell t)
          (actuation-cell t)
          (status-cell ledger id)))

(defn- ledger-row
  "One row per persisted fact. The three `:t` values this store can hold
  are styled apart rather than as ok/not-ok, because a declined
  approval is neither a success nor a governor hold: the governor was
  CLEAN and a human said no."
  [{:keys [t op subject basis]}]
  (format "        <tr><td>%s</td><td><code>%s</code></td><td><code>%s</code></td><td>%s</td></tr>"
          (case t
            :governor-hold     "<span class=\"critical\">governor-hold</span>"
            :approval-rejected "<span class=\"warn\">approval-rejected</span>"
            (str "<span class=\"ok\">" (esc (kw-str t)) "</span>"))
          (esc (kw-str (or op :n-a))) (esc subject)
          (esc (str/join ", " (map kw-str basis)))))

(defn- hold-rows
  "One row per violation the REAL governor raised in this run, carrying
  the governor's own `:detail` string. Nothing here is written by hand
  -- if a rule stops firing, its row disappears.

  Restricted to `:governor-hold` facts on purpose. An
  `:approval-rejected` fact also carries a `:violations` vector (the
  `:request-approval` node synthesises `[{:rule :approver-rejected}]`
  so the hold node has something to write), but `:approver-rejected` is
  a HUMAN's decision on a governor-clean proposal, not one of the
  governor's HARD rules -- listing it here would both overstate the
  governor and disagree with this section's own count."
  [ledger]
  (for [{:keys [t op subject violations]} ledger
        :when (and (= :governor-hold t) (seq violations))
        {:keys [rule detail]} violations]
    (format "        <tr><td><code>%s</code></td><td><code>%s</code></td><td><code>%s</code></td><td>%s</td></tr>"
            (esc (kw-str rule)) (esc (kw-str op)) (esc subject) (esc detail))))

(defn- gate-rows
  "DERIVED description of the actor's fixed op contract -- read live
  from `governor/allowed-ops`, `governor/high-stakes` and
  `phase/phases` / `phase/auto-eligible-ops`. This is a statement of
  permanent contract, NOT runtime telemetry: it says what the actor
  would do for any request, not what happened in the run above. It is
  derived rather than transcribed precisely so it cannot drift away
  from the vars it describes."
  []
  (let [auto (phase/auto-eligible-ops)
        phase3-writes (:writes (get phase/phases 3))]
    (for [o (sort-by kw-str governor/allowed-ops)]
      (format "        <tr><td><code>%s</code></td><td>%s</td></tr>"
              (esc (kw-str o))
              (cond
                (contains? governor/high-stakes o)
                "<span class=\"warn\">ALWAYS human approval &middot; never auto-eligible at any phase &middot; asserted twice (governor <code>high-stakes</code> + absent from every phase <code>:auto</code>)</span>"

                (contains? auto o)
                "<span class=\"ok\">phase-3 auto-commit when the governor is clean</span>"

                (contains? phase3-writes o)
                "<span class=\"warn\">phase-3 write &middot; human approval (never auto-eligible)</span>"

                :else
                "<span class=\"muted\">not writable at phase 3</span>")))))

(defn- phase-rows
  "DERIVED from `phase/phases` -- the rollout ladder as the actor
  actually holds it. Fixed contract, not telemetry."
  []
  (for [[n {:keys [label writes auto]}] (sort-by key phase/phases)]
    (format "        <tr><td>%s</td><td>%s</td><td>%s</td><td>%s</td></tr>"
            (esc n) (esc label)
            (if (seq writes)
              (str/join ", " (map #(str "<code>" (esc (kw-str %)) "</code>")
                                  (sort-by kw-str writes)))
              "<span class=\"muted\">none</span>")
            (if (seq auto)
              (str/join ", " (map #(str "<code>" (esc (kw-str %)) "</code>")
                                  (sort-by kw-str auto)))
              "<span class=\"muted\">none</span>"))))

(defn- jurisdiction-rows
  "DERIVED from `carwashops.facts/spec-basis-table`. A jurisdiction
  absent from this table has NO basis on file, which is why ticket-2's
  \"ATL\" is a HARD hold above rather than a warning."
  []
  (for [[iso3 {:keys [name legal-basis provenance required-evidence]}]
        (sort-by key facts/spec-basis-table)]
    (format "        <tr><td><code>%s</code></td><td>%s</td><td>%s</td><td>%s</td><td>%s</td></tr>"
            (esc iso3) (esc name) (esc legal-basis) (esc provenance)
            (esc (count required-evidence)))))

(defn- registry-rows
  "The actual records `carwashops.registry` drafted and the store
  committed during this run -- one per actuation."
  [db]
  (concat
   (for [r (store/wash-history db)]
     (format "        <tr><td>wash</td><td><code>%s</code></td><td><code>%s</code></td><td>%s</td></tr>"
             (esc (get r "wash_number")) (esc (get r "ticket_id"))
             (esc (get r "jurisdiction"))))
   (for [r (store/return-history db)]
     (format "        <tr><td>return</td><td><code>%s</code></td><td><code>%s</code></td><td>%s</td></tr>"
             (esc (get r "return_number")) (esc (get r "ticket_id"))
             (esc (get r "jurisdiction"))))))

(defn- scope-terms-html
  "DERIVED from `governor/scope-excluded-terms` -- the prose the
  governor scans every proposal for, whatever op it claims to be.
  Fixed contract, not telemetry."
  []
  (str/join ", " (map #(str "<code>" (esc %) "</code>") governor/scope-excluded-terms)))

(defn render
  "Renders the operator-console document from a store `db` that has
  already run `run-demo!` (or any other real scenario)."
  [db]
  (let [ledger (vec (store/ledger db))
        tickets (store/all-tickets db)
        holds (filter #(= :governor-hold (:t %)) ledger)]
    (str
     "<html><head><meta charset=\"utf-8\"><title>cloud-itonami-isic-4520-carwash &middot; vehicle washing</title><style>"
     (jp-go-dds.skin/dds+skin)
     "</style></head><body>\n"
     "<header class=\"bar\">\n"
     "  <h1>Vehicle washing and polishing (ISIC 4520) — Operator Console</h1>\n"
     "  <span class=\"badge\">read-only sample · governor-gated · wash application and vehicle return always human-approved</span>\n"
     "</header>\n"
     "<main>\n"

     "  <section class=\"card\">\n"
     "    <h2>Wash tickets</h2>\n"
     "    <p class=\"muted\">Demo snapshot — build-time-generated from <code>carwashops.store/demo-data</code> by driving the real actor (<code>carwashops.operation</code> → <code>carwashops.governor</code> → <code>carwashops.phase</code>) via <code>clojure -M:dev:render-html</code>. Every ticket id below exists in the seed; the reclaim column is recomputed by <code>carwashops.registry/reclaim-rate</code> from each ticket's own litre counts, never copied from the proposal.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Ticket</th><th>Customer</th><th>Vehicle</th><th>Finish → proposed process</th><th>Jurisdiction</th><th>Water reclaim</th><th>Discharge permit</th><th>Actuations</th><th>Last op status</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" (map (partial ticket-row ledger) tickets)) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     "  <section class=\"card\">\n"
     "    <h2>HARD holds reached in this run (" (count holds) " proposals)</h2>\n"
     "    <p class=\"muted\">Every row is a violation the real <code>carwashops.governor</code> raised during the run above, carrying the governor's own <code>:detail</code>. A HARD hold is never escalated to a human — <code>carwashops.phase/gate</code> keeps a governor HOLD a HOLD at every phase, so there is no approval that can override one.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Rule</th><th>Op</th><th>Ticket</th><th>Governor detail</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" (hold-rows ledger)) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     "  <section class=\"card\">\n"
     "    <h2>Action gate (Car Wash Governor)</h2>\n"
     "    <p class=\"muted\">Fixed contract, derived at build time from <code>governor/allowed-ops</code>, <code>governor/high-stakes</code> and <code>phase/phases</code> — not a record of this run. <code>allowed-ops</code> is the whole vocabulary: no op finalizes a roadworthiness clearance, a damage-liability decision or a discharge permit — those are absent, not merely gated.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Op</th><th>Gate</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" (gate-rows)) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "    <p class=\"muted\">Permanently out-of-scope prose, scanned on every proposal whatever op it claims to be: " (scope-terms-html) ".</p>\n"
     "  </section>\n"

     "  <section class=\"card\">\n"
     "    <h2>Rollout phases</h2>\n"
     "    <p class=\"muted\">Fixed contract, derived from <code>carwashops.phase/phases</code>. Note that <code>:actuation/apply-wash-process</code> and <code>:actuation/return-vehicle</code> appear in no phase's auto column — a permanent structural fact, not a rollout milestone still to come. This console ran at phase " (esc (:phase operator)) ".</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Phase</th><th>Label</th><th>May write</th><th>May auto-commit when governor-clean</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" (phase-rows)) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     "  <section class=\"card\">\n"
     "    <h2>Jurisdictional spec-basis coverage</h2>\n"
     "    <p class=\"muted\">" (esc (facts/coverage-summary)) "</p>\n"
     "    <table>\n"
     "      <thead><tr><th>ISO3</th><th>Jurisdiction</th><th>Legal basis</th><th>Provenance</th><th>Required evidence items</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" (jurisdiction-rows)) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     "  <section class=\"card\">\n"
     "    <h2>Actuation registry (this run)</h2>\n"
     "    <p class=\"muted\">The records <code>carwashops.registry</code> drafted and the store committed — one per real-world act, each behind its own sequence counter and its own double-actuation guard.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Kind</th><th>Number</th><th>Ticket</th><th>Jurisdiction</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" (registry-rows db)) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"

     "  <section class=\"card\">\n"
     "    <h2>Audit ledger (this run)</h2>\n"
     "    <p class=\"muted\">Append-only decision-fact log — every commit and every hold this scenario produced, in order. <code>:basis</code> is the proposal's own citations for a commit and the violated rule names for a hold.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Fact</th><th>Op</th><th>Ticket</th><th>Basis</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" (map ledger-row ledger)) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "</main>\n"
     "</body></html>\n")))

(defn -main [& args]
  (let [out (or (first args) "docs/samples/operator-console.html")
        db (run-demo!)
        html (render db)
        ledger (store/ledger db)]
    (spit out html)
    (println "wrote" out "(" (count ledger) "ledger facts,"
             (count (filter #(= :governor-hold (:t %)) ledger)) "HARD holds,"
             (count (store/wash-history db)) "wash applications,"
             (count (store/return-history db)) "vehicle returns )")))
