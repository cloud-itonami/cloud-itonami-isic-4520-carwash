# cloud-itonami-isic-4520-carwash

Open Business Blueprint for **vehicle washing and polishing** — a
role-suffix satellite of
[`cloud-itonami-isic-4520`](https://github.com/cloud-itonami/cloud-itonami-isic-4520)
(ISIC 4520: maintenance and repair of motor vehicles), following the same
satellite pattern this fleet already uses for
[`cloud-itonami-isic-8129-facade`](https://github.com/cloud-itonami/cloud-itonami-isic-8129-facade)
under ISIC 8129 and `cloud-itonami-isic-6611-cryptoexchange` under 6611.

This repository publishes a vehicle-wash actor — wash-ticket intake,
per-jurisdiction effluent-standard assessment, discharge-permit
screening, wash-process application and vehicle return — as an OSS
business that any qualified operator can fork, deploy, run, improve and
sell, so an independent wash bay never surrenders its customer records
and its discharge evidence to a closed SaaS.

## Why this repo exists, measured rather than assumed

**ISIC 4520 explicitly includes "washing, polishing".** That is not an
inference — it is the `includes` list of class 4520 in this workspace's
own spec mirror (`cloud-itonami/org-un-isic`, `data/classes/4520.json`),
read on 2026-08-08.

The parent actor `cloud-itonami-isic-4520` scoped itself to **repair-shop
operations coordination** (service-record logging, bay/technician
scheduling, safety-concern surfacing, parts-order coordination) and has
no wash op anywhere in its schema. So the washing half of 4520 had no
implementation at all. This repo is that half, and nothing more.

## Scope (read this before anything else)

This actor coordinates **washing a vehicle that a customer left with the
operator**. That is a bailment: the customer keeps ownership while the
operator holds possession, so both real-world acts — applying a wash
process to a real car, handing a real car back — are irreversible and
always a human call.

**What is absent from the vocabulary, not merely gated:**

| Not here | Whose it is |
|---|---|
| roadworthiness / safe-to-drive clearance | the parent actor's domain, and a permanent exclusion even there |
| damage-liability adjudication | the operator, their insurer and the customer |
| issuing a discharge permit | the environmental authority |

`carwashops.governor/allowed-ops` is the whole vocabulary — five ops.
There is no op that finalizes any of the three above, so a compromised or
off-spec advisor cannot reach them by being confident.

## The regulated concern here is wastewater, not vehicle safety

A vehicle wash discharges detergent, road oil and heavy metals. The
operating basis a real operator needs is the **effluent standard and the
permit regime** — so that, not a vehicle-safety rule, is what
`carwashops.facts` carries (JPN 水質汚濁防止法 第12条 / USA Clean Water Act
s.402 NPDES / DEU WHG §57). A jurisdiction outside that table has **no
basis on file**, the advisor says so honestly with empty `:cites`, and
the governor turns it into a HARD hold. Adding a jurisdiction is a data
addition, never a code change.

## Architecture

Built on this workspace's
[`langgraph`](https://github.com/kotoba-lang/langgraph) StateGraph
runtime (portable `.cljc`, supervised superstep loop, interrupts,
in-mem checkpoints) — the same actor pattern as every prior actor in this
fleet:

```
intake → advise → govern → decide → commit | hold | request-approval
                                                        ↑
                                          human operator resumes here
```

- **`carwashops.advisor`** — the contained intelligence. Drafts proposals,
  holds no authority. Reports missing basis honestly instead of inventing one.
- **`carwashops.governor`** — the **Car Wash Governor**, an independent
  censor. Recomputes what it can from the ticket the operator already
  recorded; holds when it cannot.
- **`carwashops.phase`** — the 0→3 rollout gate. Can only add caution.
- **`carwashops.store`** — SSoT behind a protocol, append-only ledger.

### The governor's checks, and why none can be talked out of

| Check | Basis |
|---|---|
| spec basis missing | jurisdiction not in the effluent table → HARD |
| evidence incomplete | the jurisdiction's required records must actually be present |
| discharge permit lapsed | reported by this proposal **or** already on file |
| **process forbidden by finish** | recomputed from the vehicle's own recorded finish + own proposed process. **Set membership — no threshold to lower** |
| **reclaim-claim mismatch** | the ticket's claimed water-reclaim rate vs the rate recomputed from its own litre counts. **An identity, not an estimate** |
| already washed / already returned | dedicated booleans, never a `:status` value |
| scope excluded | the advisor's own prose reaching for one of the three absent decisions |

`high-stakes` is a set of **op names**, not of advisor-reported `:stake`
values. This fleet has both vocabularies (9601 uses op names; 9522/9523
use `:stake`); this actor deliberately follows 9601, because a permanent
invariant must not depend on the censored party's own report. See
`docs/adr/0001-architecture.md`.

## Run it

```bash
kbb -M:dev:run          # the demo: 5 commits and 6 distinct governor holds
kbb -M:dev:test         # 31 tests / 91 assertions
kbb -M:dev:render-html  # regenerate docs/samples/operator-console.html
```

[`docs/samples/operator-console.html`](docs/samples/operator-console.html)
is not a mockup: `carwashops.render-html` drives this same actor stack
against the same seeded tickets and renders whatever comes back — 16
ledger facts (7 commits, 8 governor holds, 1 declined approval),
**every one of the governor's 9 HARD rules reached**, and
the gate / phase / scope tables derived at build time from
`governor/allowed-ops`, `governor/high-stakes`, `phase/phases` and
`governor/scope-excluded-terms` rather than transcribed. It contains no
timestamp and no random value, so two runs are byte-identical.

The demo is deterministic and offline. Its ledger ends like this —
every hold names its own rule:

```
:committed     :ticket/intake                 ticket-1
:committed     :effluent-plan/verify          ticket-1
:committed     :discharge-permit/screen       ticket-1
:committed     :actuation/apply-wash-process  ticket-1
:committed     :actuation/return-vehicle      ticket-1
:governor-hold :effluent-plan/verify          ticket-2  [:no-spec-basis]
:governor-hold :actuation/apply-wash-process  ticket-3  [:evidence-incomplete :wash-process-forbidden-by-finish]
:governor-hold :discharge-permit/screen       ticket-4  [:discharge-permit-not-current]
:governor-hold :actuation/apply-wash-process  ticket-5  [:evidence-incomplete :reclaim-claim-mismatch]
:governor-hold :actuation/clear-roadworthiness ticket-1 [:op-not-allowed :scope-excluded]
:governor-hold :actuation/apply-wash-process  ticket-1  [:already-washed]
```

## Honest state

- **`DatomicStore` is not implemented.** The protocol and the contract
  test are here so adding one is a drop-in, but the only backend today is
  `MemStore`. Sibling actors (9601 / 9522 / 9523) carry a `langchain.db`
  implementation; this one does not yet.
- **Not connected to the 営み OS yet.** `cloud-itonami-isic-4520-carwash`
  is standard-form (single namespace, `phase/{read,write}-ops`,
  `operation/build` on a langgraph StateGraph, `store/seed-db`,
  `governor`, all `.cljc`), which is what the OS adapter requires — but a
  declaration in `network-awai/cloud-itonami`'s `os.edn` is a separate,
  deliberate step. Being forkable and being on the shared surface are two
  different claims.
- **No robotics.** `blueprint.edn` says `:robotics false`. A real
  automated wash tunnel is a physical-dispatch concern; this actor
  coordinates records around it and never actuates equipment.

## License

AGPL-3.0-or-later. See `LICENSE`.
