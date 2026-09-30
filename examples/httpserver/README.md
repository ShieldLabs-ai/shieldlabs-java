# Example: signup guard and webhook receiver (`com.sun.net.httpserver`)

A small backend on the JDK's built-in HTTP server, with no framework, that uses the ShieldLabs Java SDK
for both halves of an integration:

- `POST /signup` reads the `requestId` your page got from the browser SDK, waits for the verdict with
  `client.identifications().get(requestId)` and refuses the signup when the identification is missing,
  reused, older than 5 minutes, rate limited (Risk Score 999), has no usable device signals, shows
  browser automation or JavaScript disabled, or falls in the dangerous band (60-100).
- `POST /webhooks/shieldlabs` verifies `X-Shield-Signature` on the raw body, answers `200` right away
  and logs each `identification.scored` event once per request ID. A bad signature gets `401`.

## Run it

Build and install the SDK from the repository root, then start the example:

```bash
mvn -q install -DskipTests
cd examples/httpserver
export SHIELDLABS_API_KEY=sec_your_private_key
export SHIELDLABS_WEBHOOK_SECRET=whsec_your_signing_secret
mvn -q compile exec:java
```

The server listens on port 8080 (`PORT` overrides it). Try it:

```bash
curl -s -X POST localhost:8080/signup \
  -H 'Content-Type: application/json' \
  -d '{"requestId":"3f2b8c1e-9d4a-4e6b-8a7c-2d1e0f9b6a53","email":"user@example.com"}'
```

A request ID that has no identification yet comes back as `{"ok":false,"reason":"missing"}` after
the SDK has waited 10 seconds for the verdict.

To receive webhooks locally, expose port 8080 with a tunnel of your choice, add the public URL
`https://<your-tunnel>/webhooks/shieldlabs` as an endpoint in the analytics dashboard
(https://app.shieldlabs.ai) and use its signing secret as `SHIELDLABS_WEBHOOK_SECRET`. The **Verify**
button sends a `webhook.ping`; **Test** sends a sample `identification.scored` event.

## Notes

- Both endpoints read at most 64 KiB of request body and answer `413` for anything larger. Webhook
  deliveries and signup posts are a few KB, and the webhook endpoint accepts requests from anyone
  until the signature has been checked, so keep a cap like this in your own handlers.
- Today ShieldLabs delivers each identification once per endpoint, with a 1-second timeout and no
  retries. A later server release adds retries that resend identical bytes, so the receiver skips
  request IDs (`data.request_id`) it has already processed. For a decision that must not depend on a
  delivery, read the History API, as `/signup` does.
- The used request IDs and processed deliveries live in memory here. In production keep them in a
  shared store with an atomic "add if absent" and an expiry, so every instance of your backend sees
  them.
- The policy is a starting point: tune `EvaluateOptions` (blocking bands and flags, freshness window)
  to your traffic, and consider a review or step-up path for the suspicious band.
