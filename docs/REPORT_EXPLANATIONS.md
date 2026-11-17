# Report explanations

`report.client.ExplanationClient` describes server-computed results. It never calculates amounts, chooses strategies
or modifies saved report fields. Only constrained risk/strategy codes, observed-month count and aggregate ratios are sent;
unexpected category strings are rejected before a request. No borrower, bank, loan or session identifiers are accepted.

The request follows the official [Responses API](https://developers.openai.com/api/reference/resources/responses/methods/create)
and [Structured Outputs](https://developers.openai.com/api/docs/guides/structured-outputs) contracts:
`POST /v1/responses`, `store=false`, a strict `text.format` JSON schema with only `summary` and `action_note` strings,
and a bounded output token count. No model name is defaulted; verify an available model when the account is ready.
`store=false` disables later response retrieval and does not assert a zero-retention service policy.

This change supplies only the client and mock tests. It does not register a runtime bean, start workers or expose report APIs.
Disabled clients or missing key/model settings return fixed server explanations without an HTTP request.
Refusal, incomplete/error responses, invalid schemas and unsafe text also return that fallback without logging payloads,
tokens or the original exception. Callers must configure connect/read timeouts and invoke it outside DB/user-lock transactions.

Only two bounded, nonempty strings are accepted. Validation rejects numeric characters/quantity expressions, markup,
control/format characters, strategy-code text and selected guarantee claims. The checks are deliberately conservative;
they do not prove every possible natural-language statement correct. Evaluate real model responses before enabling it.
Numeric and strategy fields must always remain the server's immutable values, regardless of explanation source.

All tests use synthetic facts and a mock HTTP server, including transport errors. No API key was issued, no model account
availability was claimed and no paid call was performed. Runtime wiring and persisted fallback status belong to the report feature.
