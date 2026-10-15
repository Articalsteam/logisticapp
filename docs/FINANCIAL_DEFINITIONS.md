# Financial definitions — Articals Logistics

Money in this system has exactly one meaning per term. If a number appears on a
dashboard, a report or an invoice, it is one of the definitions below, and it is
stored as a whole number of minor units (kobo) with a `*Minor` suffix.

## The four facts recorded per delivery

| Field | Meaning | Set by |
| --- | --- | --- |
| `estimatedCustomerChargeMinor` | What the pricing engine computed from the rules | `calculateDeliveryQuote`, recomputed again by `createOrder` |
| `customerChargeMinor` | What the company decided to charge (equals the estimate unless a manager overrode it) | `createOrder` |
| `estimatedRiderCostMinor` | What the rules expect to pay the rider | pricing engine |
| `estimatedAdditionalCostsMinor` | Sum of delivery-level extra costs entered at creation time | `createOrder` |

After the delivery closes, `recordDeliveryActuals` records what really happened:
`actualCustomerChargeMinor`, `actualRiderCostMinor`, `actualAdditionalCostsMinor`.

## Derived numbers

- **Estimated profit** = `estimatedCustomerChargeMinor − estimatedRiderCost − estimatedAdditionalCosts`.
  This is a *delivery contribution*: it does not include fuel bought in bulk, rent,
  salaries or any other overhead.
- **Actual profit** = `actualCustomerChargeMinor − actualRiderCost − actualAdditionalCosts`.
  Negative is allowed and shown as negative: a delivery can cost more than it earned.
- **Variance** = `actualProfit − estimatedProfit`. It answers "did the rules predict
  this delivery well?", which is the number a manager uses to tune the rate card.
- **Margin** is stored in basis points (`estimatedMarginBps`, `actualMarginBps`),
  where 10 000 bps = 100 %. It is `null` when the charge is zero, because a margin
  against nothing is not a number.
- **Average profit per delivery** = actual profit ÷ deliveries with final amounts,
  rounded half-up. Deliveries still awaiting final amounts are excluded, never
  treated as zero.
- **Net profit** = `deliveryProfit − operatingExpenses` for the period.
  Operating expenses are expenses with `kind: "operating"`; a `kind: "delivery"`
  expense must name an order and is *excluded* here so it cannot be counted twice.

## Expenses

- **Operating expense** — overhead: fuel bought in bulk, rent, salaries, airtime,
  repairs, government levies. Recorded with a category and a date; never attached
  to one delivery's profit.
- **Delivery expense** — money spent on one specific delivery that was not known
  when the order was created (a second trip, a waiting fee). It requires an
  `orderId`, and it is what `actualAdditionalCostsMinor` is made of.
- Adding a delivery expense to an order that has already closed is refused
  (`expense.order_closed_use_actuals`): use `recordDeliveryActuals` so the delivery's
  profit and the company's roll-ups stay consistent.

## Subscription revenue (platform side)

Company subscriptions are classified into three buckets that are never added
together:

- **Automated** — a subscription whose state is `active_paid`. This is revenue the
  pilot *would* be charged through automated billing.
- **Offline** — state `active_offline`: an invoice was settled by bank transfer,
  cash or cheque, and a platform administrator recorded it with a reason, an
  external reference and the period it covers.
- **Non-revenue** — trials, billing bypasses, grace periods and complimentary
  access. These are shown separately so a trial can never be reported as income.

An **offline payment** is recorded, never collected. The app does not take
payments (ADR-009): there is no payment SDK in this codebase, and the company
view deliberately exposes only the fact that a period was paid, not why or on
what evidence.

A **billing bypass** is administrator-granted free access with a mandatory reason,
an expiry date, a ledger entry and an audit log entry. Bypasses longer than 90 days
require the elevated administrator claim. A bypass never hides a suspension: a
suspended account stays read-only until it is reinstated.

## Rounding

Rounding happens once, at the end of the pricing pipeline, after any minimum and
maximum clamp, and the engine reports both the increment used and the value before
and after (`rounding: { incrementMinor, beforeMinor, afterMinor }`). A rounded
price is also surfaced as a warning so a dispatcher can explain the number.

## What the app never does

- It never computes a customer charge or a rider cost (ADR-002).
- It never stores a price the backend did not produce.
- It never labels a trial, bypass or grace period as revenue.
- It never shows a rider or a dispatcher the company's financial totals unless
  their role carries `reports.view_financial` — the field comes back `null`, not
  filtered on the device.
