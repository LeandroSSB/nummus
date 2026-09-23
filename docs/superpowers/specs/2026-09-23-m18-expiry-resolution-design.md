# M18 — Expiry Resolution Design

**Goal:** Expiry resolves the in-flight network instruction instead of
abandoning it — for payouts and refunds symmetrically. A lazy read of a
past-expiry hold consults the network first: an already-executed instruction
settles (the money moved), a still-pending one is cancelled and the hold
returns, a failed one returns. `CANCELLED` becomes a terminal network state;
late unaccounted pays become impossible.

**Spec status:** approved design — the settle-on-succeeded semantic was
validated with the project owner; retroactivity is a documented bound.

## Scope

1. **Poll-then-decide expiry.** The lazy resolution of a REQUESTED
   payout/refund whose `expiresAt` has passed reads the network instruction
   BEFORE acting:
   - `SUCCEEDED` → execute (final legs, `markSettled`, `settledAt` = the
     resolution instant, event `*.settled`). The money physically left;
     accounting must follow it.
   - `PENDING` → cancel the instruction on the network, then post the
     compensating hold return, `markExpired`, event `*.expired`.
   - `FAILED` → the existing path unchanged (return legs, `markFailed`,
     event `*.failed`).
2. **Cancel is a network terminal transition.** `ChargeStatus` gains
   `CANCELLED`. `PaymentNetwork` gains `cancelPayoutTransfer(UUID)` and
   `cancelChargeRefund(UUID)`, each returning the instruction's post-attempt
   state: CANCELLED when the cancel won, SUCCEEDED when a concurrent pay won
   (the caller then settles), FAILED/… if already terminal. Simulator REST:
   `POST /simulator/transfers/{id}/cancel` and
   `POST /simulator/refunds/{id}/cancel`, guarded like pay/fail (409 on
   non-PENDING, 404 unknown). Charges never cancel.
3. **The cancel-vs-pay race closes by construction.** Whichever terminal
   transition the network applies first wins (single row, status-guarded
   `where status = 'PENDING'`); the loser sees the winner's state in the
   cancel response and branches. The payments side keeps its post-legs →
   guarded-mark → throw-on-loss shape; the events fire after won guards as
   today. Unexpired reads keep today's behavior exactly (poll →
   SUCCEEDED execute / FAILED return / PENDING stay).
4. **The stranded-remainder bound dies for new cycles.** A cancelled
   instruction no longer counts toward the network caps
   (`Σ PENDING+SUCCEEDED`), so an expired-then-cancelled refund releases the
   network remainder exactly as the payments side already does; the same
   holds for payout transfers. The M17 backlog bound's "late `payRefund`
   unaccounted" hole closes because CANCELLED is terminal.

## Non-goals (bounds, deliberate)

- **No retroactive repair** — pre-M18 EXPIRED rows keep their state even if
  their orphaned network instructions later pay; orphaned PENDING
  instructions from before M18 stay as-is (operators may cancel them through
  the simulator routes manually). No reconciliation job.
- **No early abort surface** — cancelling before `expiresAt` stays internal
  to the resolution path; a merchant-facing void/abort is future product
  work if ever needed.
- **No new merchant API** — expiry remains lazy on read; no new routes, no
  new event vocabulary (only which event fires changes).
- **Charges never cancel** — money-in has no expiry-abandon problem
  (intents expire before money moves; a paid charge settles).

## Resolution algorithm (both lifecycles, on `get` of a REQUESTED row)

```
now ≤ expiresAt:                      today's behavior, unchanged
now >  expiresAt:  poll instruction
                     SUCCEEDED → execute  (settle; money moved)
                     PENDING   → cancel
                                  CANCELLED → return legs + EXPIRED + *.expired
                                  SUCCEEDED → execute (cancel lost the race)
                                  FAILED    → return legs + FAILED + *.failed
                     FAILED    → return legs + FAILED + *.failed
```

Every money-moving branch keeps the house race shape: post legs first, then
the status-guarded mark (`where status = 'REQUESTED'`), throwing
`Concurrent*Exception` on loss so the posting rolls back; events publish
only after a won guard.

**An observed CANCELLED (from a poll or a cancel response) behaves like
FAILED:** return legs + a terminal mark + event — `EXPIRED`/`*.expired` when
the row is past expiry (the normal resolution path), `FAILED`/`*.failed`
when a pre-expiry read observes it (reachable when a resolver's own
transaction rolled back after its network cancel won, or after an operator
cancel — the hold must still come home). No state is left strandable.

## Schema (V20 — one migration)

`V20__expiry_resolution.sql`: drop and re-add the status CHECK constraints
on `psp_simulator.payout_transfer` and `psp_simulator.charge_refund` to
include `'CANCELLED'` (constraints cannot be altered in place). No new
tables, no grant changes, no ledger changes.

## Module placement

`payments` (both lifecycles' resolution branches) and `psp_simulator`
(status, cancel transitions, routes, stores). The `PaymentNetwork` port
gains two methods; `FakePaymentNetwork` (test fake) follows. ArchUnit
unchanged — every dependency lands inside existing edges.

## Testing strategy

TDD per plan task, remote CI only (megalan), house idioms, sweeps carried.
Coverage:

- Network: cancel transitions PENDING → CANCELLED; pay-after-cancel and
  cancel-after-pay → 409; cancel idempotence at terminal states → 409 with
  the not-pending vocabulary; caps release cancelled amounts (a cancelled
  transfer/refund frees the network remainder — the M17 strand test's
  mirror); routes + port exposure.
- Payout lifecycle: expired+PENDING → cancel + return legs + `payout.expired`
  (and the reserved funds fully released both sides); expired+SUCCEEDED
  (deterministic: pay via the simulator route, THEN SQL-backdate
  `expires_at`, then read) → SETTLED with execution legs + `payout.settled`
  at the resolution instant; expired+FAILED unchanged; terminal stability
  and post-terminal immunity preserved.
- Refund lifecycle: the same three pins; plus the end-to-end M17-strand
  scenario now resolving: full-amount refund expires → cancelled on the
  network → a fresh refund of the same amount SUCCEEDS (the 422-pin from
  M17's fix wave inverts into a success pin).
- Cancel-lost race: pin the execute branch when cancel returns SUCCEEDED —
  via the test fake (test-only boundary double, production paths untouched)
  or direct simulator state manipulation; plan's judgment.
- Concurrency: the existing mixed-holds and serialization pins stay green
  (no lock changes).

## Migration / rollout notes

- V20 is a constraint swap on two simulator tables — additive semantics, no
  data change; pre-M18 rows are untouched (bound above).
- The resolution change is code-only beyond V20: no merchant-facing contract
  change except the semantic of expired-reads settling when the network
  already executed.
