# ADR-0001: A vehicle-wash actor as a satellite of ISIC 4520

**Status**: accepted
**Date**: 2026-08-08
**Superproject record**: `com-junkawasaki/root` ADR-2800004000 §3

## Context

The 営み OS work of 2026-08-08 connected seven verticals in the
laundry/cleaning/repair cluster and, in doing so, established three
things by measurement:

1. **ISIC 4520 includes "washing, polishing."** Read from this
   workspace's own spec mirror `cloud-itonami/org-un-isic`
   (`data/classes/4520.json`), not from memory.
2. **The parent actor `cloud-itonami-isic-4520` implements none of it.**
   Its vocabulary is `:log-service-record` /
   `:schedule-dispatch-operation`-shaped repair-shop coordination; there
   is no wash op in its schema and no wash field in its store.
3. **The reason to defer creating this repo has expired.** ADR-2800004000
   §3 originally declined to scaffold the three missing cleaning
   industries because the cluster already had "actors that exist but are
   not on the surface," and adding more would have grown that pile. That
   pile is now empty.

## Decision

### 1. A satellite, not a re-scoping of the parent

This is `cloud-itonami-isic-4520-carwash`, following the fleet's existing
satellite form (`-facade` under 8129, `-cryptoexchange` under 6611). The
parent keeps repair-shop coordination; this repo takes the washing half.
Neither re-promotes the other's registry entry.

Why not widen the parent instead: the parent's governor carries a
**permanent, un-overridable roadworthiness-clearance exclusion**, and its
whole check set is built around service records and bay scheduling.
Adding wastewater checks to it would put two unrelated regulatory
regimes behind one governor, and the first thing to go wrong would be an
effluent rule silently weakening a roadworthiness invariant.

### 2. The regulated concern is wastewater

The operating basis is the **effluent standard and permit regime**, not a
vehicle-safety rule. `carwashops.facts` seeds JPN / USA / DEU with legal
basis, provenance and the required-evidence list. A jurisdiction outside
that table has **no basis on file**; `required-evidence-satisfied?`
returns `nil` for it, which is falsey, so the governor holds. A missing
jurisdiction is never "no requirements."

### 3. Two ground-truth recomputations, chosen because they cannot be loosened

The fleet's proven pattern is that the strongest governor checks are the
ones recomputed from facts already on the record, with no threshold:

- **`wash-process-forbidden-by-finish?`** — set membership over the
  vehicle's own recorded finish and its own proposed process. A matte or
  vinyl-wrapped panel is destroyed by a high-pressure rotating brush;
  an acid wheel cleaner etches a ceramic coating. There is no threshold
  to lower, only a set to belong to. (Same shape as 9601's care-label
  check.)
- **`reclaim-claim-mismatch?`** — the ticket's claimed water-reclaim rate
  against the rate recomputed from its own litre counts. An identity.
  The 0.005 tolerance is on the float comparison, **not on the rule**.

### 4. `high-stakes` is a set of OPS, not of advisor-reported stakes

This fleet has two vocabularies for the same invariant, recorded in
superproject ADR-2800004000:

- **op names** — `cloud-itonami-isic-9601`
- **the advisor's self-reported `:stake` value** —
  `cloud-itonami-isic-9521` / `9512` / `9522` / `9523`

**This actor follows 9601 deliberately.** A permanent invariant must not
depend on the censored party's own report: if the advisor omits or
mislabels `:stake`, an op-name set still holds and a `:stake` set does
not. `governor_contract_test.clj` asserts this directly — an actuation
proposal with `:stake` removed still escalates.

### 5. The request key is `:subject`

Superproject ADR-2800004000 records a fleet debt: four connected actors
receive their subject under a name other than `:subject`
(`5229 :target-id`, `4759 :store-id`, `8121`/`8129 :site-id`), so their
営み OS adapters need a translation line. **This actor uses `:subject`**,
so its future adapter is the plain three-line shim. Do not introduce a
second name here.

## Consequences

### What this buys

- The washing half of ISIC 4520 has an implementation, and the claim is
  checkable: `clojure -M:dev:run` prints 5 commits and 6 governor holds,
  each naming its own rule.
- Standard-form from the first commit, so connecting it to the 営み OS
  later is a shim plus a declaration — no repo-side rework.

### What it costs, stated rather than hidden

- **`DatomicStore` does not exist here.** Only `MemStore`. The contract
  test is written so adding one is a drop-in, but claiming a Datomic
  backend before writing it would be the exact kind of unverified
  assertion this fleet's ADRs forbid.
- **Not on the shared surface.** Being forkable and being declared in
  `os.edn` are different claims; this repo makes only the first.
- **One more repo in the `cloud-itonami` family.** The family is already
  ~460 repos. The justification is that the industry is real, the ISIC
  basis is measured, and the parent demonstrably does not cover it — not
  that a new repo is cheap.
- **The `finish` table is small.** Five finishes. It is a data table, so
  extending it is a data change, but a finish absent from it currently
  forbids nothing — the check is silent rather than cautious for unknown
  finishes. A future revision should decide whether an unrecorded finish
  should hold instead.
