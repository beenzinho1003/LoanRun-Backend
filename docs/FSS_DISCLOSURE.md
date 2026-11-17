# FSS disclosure client

`loanproduct.client.FssCatalogClient` normalizes public disclosure responses for bank group `020000`:

- `creditLoanProductsSearch.json`
- `mortgageLoanProductsSearch.json`
- `rentHouseLoanProductsSearch.json`

The client is independent of persistence and HTTP controllers. It is not registered as a runtime bean or scheduler
in this change, so application startup does not contact FSS. Catalog storage/refresh and the product endpoints follow separately.
No production fixture or sample product list is installed.

All pages and categories must succeed before a snapshot is returned. API, transport, malformed data and pagination errors
throw a generic exception without the original request URI or cause, protecting the `auth` query parameter.
Credit subtype `2` (credit line) and named overdrafts are excluded. Unknown credit subtypes are excluded;
missing required subtype data fails the fetch rather than becoming a successful empty catalog.
Repayment methods use matching institution/product/disclosure-month options only; missing credit repayment terms remain empty.
Disclosure expiry uses the injected KST Clock, including products through their last disclosure day.

Only public product fields are handled. Borrower/session data is never sent, and advertised products do not supply a user's
contract rate or fee. Personal rates remain manual. A later caller must fetch outside DB/user-lock transactions and replace
the cache atomically only on success. It must configure connection/read timeouts before enabling real requests.

Tests use synthetic data and a mock HTTP server. A real key, endpoint/account availability and live response compatibility
still require verification when the user is ready; no key issuance or live requests were performed.

Official references: [credit disclosure](https://finlife.fss.or.kr/finlife/api/indvlCrdtApi/list.do?menuNo=700057),
[mortgage disclosure](https://finlife.fss.or.kr/finlife/api/houseMrtgApi/list.do?menuNo=700055),
[jeonse disclosure](https://finlife.fss.or.kr/finlife/api/lfstsFundsApi/list.do?menuNo=700056).
