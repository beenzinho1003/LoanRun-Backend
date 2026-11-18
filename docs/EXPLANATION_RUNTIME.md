# Explanation client runtime

The explanation bean uses the official https://api.openai.com/v1 endpoint with a dedicated named transport,
3-second connect and 10-second read timeouts. One request is attempted per explanation, with no automatic
retry. The existing client accepts only validated explanation strings; it never sets amounts or strategy codes.

OPENAI_ENABLED defaults to false even when a key/model are present. Missing key/model or disabled calls
return the existing fixed FALLBACK without network activity. OPENAI_MODEL has no guessed default. A user
must prepare credentials, verify model availability/billing and explicitly enable usage before any live check.
Do not put keys in Git, chat, request logs or this document. No startup probe or scheduler calls OpenAI.

Runtime configuration tests use MockRestServiceServer for every enabled and disabled case. Existing client
tests cover structured output/refusal/error validation. Passing these tests does not establish real account access.
Report job admission/worker rate limits and the future calculation adapter are separate integration steps.

Checked against the official [Responses API](https://developers.openai.com/api/reference/resources/responses/methods/create)
and [Structured Outputs](https://developers.openai.com/api/docs/guides/structured-outputs) documentation.
