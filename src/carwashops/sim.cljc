(ns carwashops.sim
  "Demo driver -- `clojure -M:dev:run`. Walks a clean ticket through
  intake -> effluent-plan verification -> discharge-permit screening ->
  wash application (escalate/approve/commit) -> vehicle return
  (escalate/approve/commit), then shows every HARD-hold scenario this
  actor has:

    - a jurisdiction with no spec-basis on file (ticket-2, \"ATL\")
    - a wash process forbidden by the vehicle's own finish (ticket-3)
    - a lapsed discharge permit (ticket-4, screened directly via
      `:discharge-permit/screen`, never via an actuation against an
      unscreened ticket)
    - a claimed water-reclaim rate that disagrees with the ticket's own
      litre counts (ticket-5)
    - an op outside the closed vocabulary
    - a proposal whose prose reaches for a permanently excluded decision

  Everything runs offline against `store/seed-db`, so the output is
  deterministic."
  (:require [langgraph.graph :as g]
            [carwashops.store :as store]
            [carwashops.operation :as op]))

(def operator {:actor-id "op-1" :actor-role :wash-bay-supervisor :phase 3})

(defn- exec-op [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "op-1"}} {:thread-id tid :resume? true}))

(defn -main [& _]
  (let [db (store/seed-db)
        actor (op/build db)]
    (println "== ticket/intake ticket-1 (JPN, clean) ==")
    (println (exec-op actor "t1" {:op :ticket/intake :subject "ticket-1"
                                  :patch {:id "ticket-1" :customer "Sakura Tanaka"}}
                      operator))

    (println "\n== effluent-plan/verify ticket-1 (escalates -- human approves) ==")
    (println (exec-op actor "t2" {:op :effluent-plan/verify :subject "ticket-1"} operator))
    (println (approve! actor "t2"))

    (println "\n== discharge-permit/screen ticket-1 (valid; escalates -- approves) ==")
    (println (exec-op actor "t3" {:op :discharge-permit/screen :subject "ticket-1"} operator))
    (println (approve! actor "t3"))

    (println "\n== actuation/apply-wash-process ticket-1 (never auto -- approves) ==")
    (println (exec-op actor "t4" {:op :actuation/apply-wash-process :subject "ticket-1"} operator))
    (println (approve! actor "t4"))

    (println "\n== actuation/return-vehicle ticket-1 (never auto -- approves) ==")
    (println (exec-op actor "t5" {:op :actuation/return-vehicle :subject "ticket-1"} operator))
    (println (approve! actor "t5"))

    (println "\n== HOLD: no spec-basis (ticket-2, ATL) ==")
    (println (exec-op actor "h1" {:op :effluent-plan/verify :subject "ticket-2"} operator))

    (println "\n== HOLD: wash process forbidden by finish (ticket-3, matte + brush) ==")
    (println (exec-op actor "h2" {:op :actuation/apply-wash-process :subject "ticket-3"} operator))

    (println "\n== HOLD: discharge permit lapsed (ticket-4) ==")
    (println (exec-op actor "h3" {:op :discharge-permit/screen :subject "ticket-4"} operator))

    (println "\n== HOLD: reclaim claim mismatch (ticket-5, 0.80 claimed vs 300/1000) ==")
    (println (exec-op actor "h4" {:op :actuation/apply-wash-process :subject "ticket-5"} operator))

    (println "\n== HOLD: op outside the closed vocabulary ==")
    (println (exec-op actor "h5" {:op :actuation/clear-roadworthiness :subject "ticket-1"} operator))

    (println "\n== HOLD: double wash (ticket-1 already washed above) ==")
    (println (exec-op actor "h6" {:op :actuation/apply-wash-process :subject "ticket-1"} operator))

    (println "\n== ledger (append-only) ==")
    (doseq [f (store/ledger db)]
      (println " " (:t f) (:op f) (:subject f) (or (:basis f) "")))))
