# Budget cash arithmetic

`BudgetCashMath` handles cash arithmetic only. It has no DB, HTTP, scheduling or repayment-engine implementation.
Call it only when the required inputs are known; callers must preserve missing actuals as null rather than supplying zero.

Planning B subtracts expenses and the full scheduled/previous-outstanding burden, net of prior savings used for due items.
Already-paid scheduled cash is not subtracted a second time. Recurring capacity is max(B,0), current capacity is max(B+carry,0).
Carry is not income or a recurring strategy budget. Negative B is retained with a separate deficit.

Actual cash uses received income, confirmed expenses, real cash paid and real savings added; cash paid already excludes
prior savings used. The caller decides whether observations are sufficient. Open or unobserved months do not settle.
For a closed observed month, reserve_settled=min(reserve,max(actual+carry,0)); carry_out is the remaining surplus.
Reserve, general savings, emergency funds and loan savings remain distinct.

Arithmetic is exact, with unsupported overflow and negative source amounts rejected rather than wrapped.
The subsequent monthly-budget adapter will use this single arithmetic implementation and retain ownership/transaction rules.
