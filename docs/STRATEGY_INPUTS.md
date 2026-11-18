# Strategy candidate input policy

This pure policy prepares cash ceilings, eligibility and ordering only. It does not allocate principal,
calculate fees/interest or simulate schedules. Team 1 owns per-period principal caps, remaining-balance
proportions, one-won remainders and reallocation of existing extra cash after payoff; scheduled payments
must not be reinvested automatically. A sorted PARALLEL list is only deterministic input ordering.

Repeated funding uses recurringCap, never carry-in or allocationCapacity. Missing configured cash uses
the agreed 80/50/0 percentage (HALF_UP); an explicit zero remains zero. Required BULLET saving has
priority within the recurring cap. Current-month cash additionally respects allocationCapacity. Basic
payment or required-saving shortfalls withhold recommendation; missing facts retain null ceilings.
These ceilings are total monthly goals. The caller subtracts actual extra cash/saving once when preparing
remaining current-month engine amounts; it must not repeat this deduction in future months.

Only ACTIVE loans with positive outstanding principal participate. BULLET never receives direct extra
repayment. Grace installment loans enter direct eligibility on graceEnd, and unknown grace dates stay
ineligible. Risk ordering follows variable balance/rate/ID, then fixed interest/balance/ID. The policy does
not select report scope, generate a report or modify any data. Engine/adapter integration is separate work.
