# ShieldLabs Java SDK

Server-side Java client for ShieldLabs: read the verdict for an identification from the History API, verify signed webhooks and turn the Risk Score into a decision.

[![CI](https://github.com/ShieldLabs-ai/shieldlabs-java/actions/workflows/ci.yml/badge.svg)](https://github.com/ShieldLabs-ai/shieldlabs-java/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
[![Maven Central](https://img.shields.io/maven-central/v/ai.shieldlabs/shieldlabs-java.svg?label=Maven%20Central)](https://central.sonatype.com/artifact/ai.shieldlabs/shieldlabs-java)

## How it fits

```
 Browser                          Your backend (this SDK)                 Decision
 ShieldLabs agent  --requestId--> identifications().get(requestId) -----> Risk.evaluate(...)
 (browser SDK)     with signup,   or a signed identification.scored       allow / step up /
                   login, ...     webhook (Webhooks.constructEvent)       review / refuse
```

1. **Browser.** The ShieldLabs agent, loaded by the browser SDK from `cdn.shieldlabs.ai`, runs an identification and hands your page a `requestId`. The browser never sees a Risk Score, a visitor ID or a device ID.
2. **Your backend.** It receives the `requestId` together with the protected action (signup, login, checkout, withdrawal) and reads the verdict for it with this SDK, or receives the verdict by a signed webhook.
3. **Decision.** Your backend acts on `risk_score`, the three risk bands (trusted 0-29, suspicious 30-59, dangerous 60-100), the detection flags and the identifiers, for example how many accounts one device ID has.

## Install

Maven:

```xml
<dependency>
  <groupId>ai.shieldlabs</groupId>
  <artifactId>shieldlabs-java</artifactId>
  <version>1.0.0</version>
</dependency>
```

Gradle:

```kotlin
implementation("ai.shieldlabs:shieldlabs-java:1.0.0")
```

Java 11 or later. The only runtime dependency is Jackson Databind.

You need the domain's **Private API Key** (`sec_...`) for the History API and, for webhooks, the endpoint's **signing secret** (`whsec_...`). Both are in the analytics dashboard at [app.shieldlabs.ai](https://app.shieldlabs.ai). New to ShieldLabs? [Start free](https://app.shieldlabs.ai).

## Quick start

Save this as `QuickStart.java` in a project that depends on `shieldlabs-java`:

```java
import ai.shieldlabs.EvaluateOptions;
import ai.shieldlabs.Evaluation;
import ai.shieldlabs.Identification;
import ai.shieldlabs.IdentificationScoredEvent;
import ai.shieldlabs.Risk;
import ai.shieldlabs.ShieldLabsClient;
import ai.shieldlabs.WebhookEvent;
import ai.shieldlabs.Webhooks;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class QuickStart {
    // Reads SHIELDLABS_API_KEY: the Private API Key of your domain (sec_...).
    private final ShieldLabsClient shieldlabs = ShieldLabsClient.fromEnvironment();

    // One identification authorizes one action. This in-memory set keeps the example short;
    // in production, claim request IDs atomically in your database or cache.
    private final Set<String> usedRequestIds = ConcurrentHashMap.newKeySet();

    // 1. Call this with the requestId the browser sent together with the signup form.
    public boolean allowSignup(String requestId) {
        // Scoring is asynchronous: this waits (up to 10 seconds by default) until the verdict is stored.
        Optional<Identification> identification = shieldlabs.identifications().get(requestId);

        // 2. Missing, reused, stale, rate limited, no device signals, browser automation
        //    or the dangerous band: refuse, or route to review or a step-up check.
        Evaluation evaluation = Risk.evaluate(
                identification.orElse(null),
                EvaluateOptions.builder().replayCheck(id -> !usedRequestIds.add(id)).build());
        evaluation.getReason().ifPresent(reason -> System.out.println("refused: " + reason.getValue()));
        return evaluation.isOk();
    }

    // 3. Call this with the raw body and the X-Shield-Signature header of a webhook delivery.
    public void handleWebhook(byte[] rawBody, String signatureHeader) {
        WebhookEvent event = Webhooks.constructEvent(
                rawBody, signatureHeader, System.getenv("SHIELDLABS_WEBHOOK_SECRET")); // whsec_...
        if (event instanceof IdentificationScoredEvent) {
            Identification scored = ((IdentificationScoredEvent) event).getIdentification();
            System.out.println(scored.getRequestId() + " " + scored.getRiskScore());
        }
    }

    public static void main(String[] args) {
        String requestId = args[0]; // a request ID your page received from the browser SDK
        System.out.println(new QuickStart().allowSignup(requestId) ? "allowed" : "refused");
    }
}
```

Run it with `SHIELDLABS_API_KEY` set and a request ID as the argument. A runnable backend with both halves (a signup guard and a webhook receiver) lives in [`examples/httpserver`](examples/httpserver).

## Guide

### Waiting for the verdict

The browser gets a request ID as soon as the identification starts, but scoring is asynchronous: the History row appears about 1 to 3 seconds after the browser call and can be refined for up to about 10 seconds while follow-up checks finish (network checks such as the local IP arrive in later versions of the row). Start the identification when the user begins the action, for example when the signup form gets focus, so the verdict is usually stored by the time the form is submitted. `identifications().get(requestId)` polls the History API until the row appears and returns the first version it sees. `waitFor` is the **total time budget** of the call, 10 s by default:

- **Schedule.** The first poll runs right away, then after waits of 250 ms, 500 ms, 1 s, 1.5 s and then every 2 s. With another `pollInterval` p the waits are p, 2p, 4p, 6p, 8p and then 8p again, each capped at 2 s, or at p when p is longer: 1 s waits 1, 2, 2, 2 s, and 3 s polls every 3 s. A wait that would pass the deadline is cut short, so **the last poll runs at the deadline**.
- **One attempt per poll.** Each poll is a single HTTP attempt: the client's retries (`maxRetries`) do not apply inside the wait. Its timeout is the client `timeout`, shortened to the time left but never below 1 s, so the call can end up to 1 s after the budget.
- **Zero budget.** `waitFor(Duration.ZERO)` makes one poll and does not wait: a single HTTP attempt without retries, whose timeout is the client `timeout` but at most 1 s. If that poll fails (a 429, a 5xx response, a connection error or a timeout), its exception is thrown. `noWait()` instead makes a single lookup that is retried like any History request.
- **Transient errors keep polling.** A 429, a 5xx response, a connection error or a timeout does not end the wait: the next poll follows on the schedule. At the deadline, the exception of the last poll is thrown if it failed; if the last poll answered without a row, the result is `Optional.empty()`.
- **429.** The History API allows about 15 requests per second per domain for all your callers together, counted per one-second window. Inside the wait, a 429 is always followed by at least 1 s before the next poll: the SDK waits the longest of the next scheduled wait, 1 s and `Retry-After` (at most 10 s). A `Retry-After` of 0 or a date in the past counts as 0, so the 1 s minimum still applies. The wait is shortened only to keep the last poll at the deadline, and a `Retry-After` longer than the time left throws the `RateLimitException` at once. Calls outside the wait (`search`, `stream`, `iterate` and a `noWait()` lookup) follow `Retry-After` as sent (see [Errors and retries](#errors-and-retries)).
- **Errors that do not heal stop at once.** A 400, 401, 403 or 404 throws right away (`BadRequestException`, `AuthenticationException`, `NotFoundException`): a wrong key or a wrong base URL does not fix itself.
- **Empty means unverified.** `Optional.empty()` means no identification appeared in time. Treat it as **unverified**, never as clean.

`Optional.empty()` also covers an identification that was never stored: while a visitor's IP is over the per-IP limit of identifications, the browser still gets a request ID, but no row is written for it. To read the refined row later, make a single lookup (`noWait()`) again after about 10 seconds; that lookup is retried like any History request.

```java
GetIdentificationOptions options = GetIdentificationOptions.builder()
        .waitFor(Duration.ofSeconds(5))          // total budget; default 10 s
        .pollInterval(Duration.ofMillis(250))    // first wait; then x2, x4, x6, x8, each at most max(2 s, interval)
        .build();
Optional<Identification> identification = client.identifications().get(requestId, options);

// A single lookup without waiting, for example when the action happened long ago:
client.identifications().get(requestId, GetIdentificationOptions.builder().noWait().build());
```

`getAsync` follows the same rules. The request ID must be a UUID; anything else throws `ValidationException` before a request is sent.

### Searching history for account-abuse checks

`history().search(type, value, options)` reads one page (newest first) for one identifier; `history().stream(...)` and `history().iterate(...)` page lazily through all of them. Pick the identifier by the question:

| Question | Lookup |
|---|---|
| What was the verdict for this action? | `LookupType.REQUEST_ID` |
| What did this account do recently? | `LookupType.USER_HID` |
| Which accounts share this device, visitor or IP? (multi-accounting, account sharing) | `DEVICE_ID`, `VISITOR_ID`, `IP` |
| What happened in this visit or this browser? | `SESSION_ID`, `COOKIE_ID` |

```java
// User HID values that do not name one of your accounts: anonymous checks and placeholders.
// Identifications without a User HID (null) are skipped as well.
Set<String> notAnAccount = Set.of("anonymous", "fail", "-1", "unknown");

// How many accounts has this device been used with?
Identification current = identification.get();
if (!Risk.NIL_DEVICE_ID.equals(current.getDeviceId())) {   // nil = no usable device signals
    Set<String> accounts = client.history()
            .stream(LookupType.DEVICE_ID, current.getDeviceId(),
                    HistoryIterateOptions.builder().maxItems(500).build())
            .map(Identification::getUserHid)
            .filter(hid -> hid != null && !notAnAccount.contains(hid))
            .collect(Collectors.toSet());
    if (accounts.size() >= 3) {
        // send the signup to review
    }
}

// One page at a time:
HistoryPage page = client.history().search(LookupType.USER_HID, userHid,
        HistorySearchOptions.builder().limit(50).offset(0).build());
page.getIdentifications();   // List<Identification>
page.getTotal();  // rows matching the lookup
```

Every lookup is checked before it is sent, because the History API does not validate its input (an unknown type would return the latest rows of the whole domain, and a malformed UUID or IP returns a server error): UUID types need a UUID (sent lowercase), `IP` needs a dotted IPv4 address (IPv6 addresses are not searchable), `USER_HID` needs a non-empty string, `limit` is 1-100 and `offset` 0 or more. Use `LookupType.fromValue("device_id")` when the type comes from configuration. Rows are ordered by time without a tie-breaker, so the stream skips request IDs it has already returned; it stops at the reported total, at an empty page or after `maxItems`.

A User HID is sent exactly as given, as one URL path segment in the canonical form the History API matches: letters, digits, `- . _ ~` and `$ & + , : ; = @` stay as they are and everything else is percent-encoded, so values with `@`, `+`, `=` or spaces (an email address, a base64 string) match exactly. A value that contains `/`, and the values `.` and `..`, cannot be searched and throw `ValidationException`. User HIDs from `UserHid.fromUserId` are 64 hex characters and always work.

### Webhooks

Each delivery is a `POST` with `X-Shield-Signature: sha256=<hex HMAC-SHA256>` computed over the raw body with the endpoint's signing secret (the whole string, including `whsec_`). Verify the raw bytes before parsing anything: re-serialized JSON has different bytes.

```java
byte[] rawBody = ...;                                   // read the body as bytes, unparsed
String signature = request.getHeader(Webhooks.SIGNATURE_HEADER);
try {
    WebhookEvent event = Webhooks.constructEvent(rawBody, signature, secret);
    if (event instanceof IdentificationScoredEvent) {
        Identification identification = ((IdentificationScoredEvent) event).getIdentification();
        if (processed.add(identification.getRequestId())) {   // idempotent on data.request_id
            queue.submit(identification);                     // do the work after answering
        }
    }
    // WebhookPingEvent: the endpoint check. UnknownWebhookEvent: a newer event type; acknowledge it.
    respond(200);
} catch (SignatureVerificationException e) {
    respond(401);
} catch (WebhookParseException e) {
    respond(400);
}
```

- **Respond fast.** ShieldLabs waits one second for a 2xx answer. Acknowledge first, then do slow work on a queue.
- **Be idempotent on `data.request_id`.** Today each identification is delivered once per endpoint, with a 1-second timeout and no retries. A later server release adds retries that resend identical bytes, so store the request IDs you have processed.
- **Rotate secrets without downtime.** `verifySignature` and `constructEvent` accept several secrets and pass when any matches: `Webhooks.constructEvent(rawBody, signature, newSecret, oldSecret)`.
- **Use the History API for guaranteed reads.** A delivery can fail, and a History row can be refined after the webhook was sent (the webhook is not sent again), so read History when a decision must not depend on a delivery or needs the latest state.
- The **Test** button in the analytics dashboard sends a sample `identification.scored` event with 17 of the 19 detection flags and second-precision timestamps; missing flags read as `false`, so it parses like production traffic. **Verify** sends a `webhook.ping`.

`Webhooks.verifySignature(...)` returns a boolean instead of throwing; both methods also take the body as a `String` or the secrets as a `Collection<String>`.

### Risk decisions

- `Risk.band(score)` returns `TRUSTED` (0-29), `SUSPICIOUS` (30-59), `DANGEROUS` (60-100) or `RATE_LIMITED` for a score above 100. The value 999 is a rate-limit marker, never a score: when a visitor's IP goes over the per-IP limit of identifications, ShieldLabs records the block once as a separate identification with the marker 999 and its own request ID. It carries no verdict. The request IDs your page receives during the block get no identification at all, so `identifications().get()` returns empty for them: treat that as unverified. `Risk.isRateLimited(score)` checks for the marker.
- Branch on `getRiskScore()` and `getDetectionFlags()`. Risk signal names (`getSignals()`) are for display and logging, and never add up signal weights yourself: weights can be negative or informational.
- The device ID `00000000-0000-0000-0000-000000000000` (`Risk.NIL_DEVICE_ID`) means no usable device signals reached ShieldLabs.
- One identification should authorize one protected action: keep used request IDs and refuse reuse, and refuse identifications older than your freshness window.

`Risk.evaluate(identification, options)` bundles those rules. Checks run in order and the first failure wins: missing identification, replayed request ID (when you pass a replay check), older than `maxAge` (5 minutes by default), rate-limit marker, nil device ID, a blocking flag (`browser_automation` and `javascript_disabled` by default), a blocking band (`DANGEROUS` by default). The defaults are a starting point to tune:

```java
EvaluateOptions policy = EvaluateOptions.builder()
        .maxAge(Duration.ofMinutes(5))
        .blockBands(RiskBand.DANGEROUS)
        .blockFlags(DetectionFlag.BROWSER_AUTOMATION, DetectionFlag.JAVASCRIPT_DISABLED, DetectionFlag.TOR)
        .replayCheck(id -> !usedRequestIds.add(id))
        .build();
Evaluation evaluation = Risk.evaluate(identification.orElse(null), policy);
evaluation.isOk();       // true when every check passed
evaluation.getReason();  // Optional<Evaluation.Reason>, for example BLOCKED_FLAG
evaluation.getBand();    // Optional<RiskBand>
evaluation.getFlag();    // Optional<DetectionFlag> for BLOCKED_FLAG
```

### Linking identifications to your users

Pass a User HID to the browser SDK so that History lookups by `USER_HID` work. Create it on your server, never from a raw email address or account ID:

```java
// userHidSecret: a server-side secret from your secret store, used only for this purpose
String userHid = UserHid.fromUserId(account.getId(), userHidSecret);
// HMAC-SHA256(key = secret, message = user ID) as 64 lowercase hex characters
```

The same inputs always give the same value; keep the secret on the server and do not change it once in use.

### Management API: domain profile

```java
ManagementClient management = ManagementClient.builder()
        .secretKey(System.getenv("SHIELDLABS_SECRET_KEY"))
        .domain(System.getenv("SHIELDLABS_DOMAIN"))   // "https://www.example.com/" becomes "example.com"
        .build();
DomainProfile profile = management.getProfile();
profile.getRemainingIdentifications();  // can be negative when the account is over its included volume
profile.getPublicKeyMasked();           // "****************************a3f8"
```

The server matches the registered domain exactly, so the client trims and lowercases it and strips a scheme, a path, a trailing slash and a leading `www.`. Call the Management API sparingly and cache the profile (see the rate limits below).

### Rate limits

| API | Limit | What the SDK does |
|---|---|---|
| History API (`account.shieldlabs.ai`) | about 15 requests per second **per domain**, shared by all your callers; no ban | retries a 429 after `Retry-After` as sent (at most 10 s; 0 or a past date retries at once), or after at least one second when the response has no `Retry-After`; while `identifications().get` waits, a 429 is followed by at least one second before the next poll, even with `Retry-After: 0`, and polling goes on until its deadline, unless `Retry-After` (at most 10 s) asks for longer than the time left: then the `RateLimitException` is thrown at once |
| Management API (`api.shieldlabs.ai`) | about 15 requests per minute **per caller IP**; the request over the limit blocks that IP for 10 minutes | never retries a 429 (it would only keep the block); throws `RateLimitException` |

History reads and Management calls do not use your included identifications.

### Async calls

`searchAsync`, `getAsync` and `getProfileAsync` return a `CompletableFuture`. Waiting uses scheduled delays, not a blocked thread, and cancelling the future returned by `getAsync` stops further polls. A failure completes the future exceptionally with the SDK exception as the cause (`join()` wraps it in `CompletionException`); invalid arguments fail the future with `ValidationException` without sending a request.

```java
client.identifications().getAsync(requestId)
        .thenApply(found -> Risk.evaluate(found.orElse(null)))
        .thenAccept(evaluation -> System.out.println(evaluation));
```

### Configuration

| Builder option | Default | Notes |
|---|---|---|
| `apiKey(String)` | required | Private API Key `sec_...`; never logged. Surrounding whitespace (a trailing line break from a secrets file) is removed; a key of another shape only logs a warning |
| `baseUrl(URI or String)` | `https://account.shieldlabs.ai` | the origin; a trailing `/api` is removed so paths never become `/api/api/...`. Must use https, because every request carries the key; plain http is accepted for `localhost`, `127.0.0.1` and `[::1]` (local test servers) |
| `allowInsecureHttp(boolean)` | `false` | accepts a plain http base URL on another host, for example a test server in a container network; the key then travels unencrypted and the client logs a warning. Never enable it for production |
| `timeout(Duration)` | 10 s | per HTTP attempt; while `identifications().get` waits, each poll gets this timeout shortened to the time left (at least 1 s) |
| `maxRetries(int)` | 2 | retries after connection errors, timeouts, 429 (History only) and 5xx; not used by the polls of `identifications().get`, which are single attempts |
| `httpClient(HttpClient)` | a new `java.net.http.HttpClient` | bring your own for proxies, executors or TLS settings |

`ShieldLabsClient.fromEnvironment()` reads `SHIELDLABS_API_KEY` and `SHIELDLABS_API_BASE_URL`; `ManagementClient.fromEnvironment()` reads `SHIELDLABS_SECRET_KEY`, `SHIELDLABS_DOMAIN` and `SHIELDLABS_MANAGEMENT_BASE_URL`. `ManagementClient.builder()` has the same `baseUrl`, `allowInsecureHttp`, `timeout`, `maxRetries` and `httpClient` options. Every request carries `Accept: application/json` and `User-Agent: shieldlabs-java/1.0.0 (Java <version>; <os>)`. The SDK sends no telemetry and logs no keys or bodies.

Clients are immutable and safe for concurrent use: create one per domain and share it.

## Reference

| API | Description |
|---|---|
| `ShieldLabsClient.builder()...build()` | History API client (Private API Key) |
| `client.identifications().get(requestId[, GetIdentificationOptions])` | `Optional<Identification>`, waiting for the verdict |
| `client.identifications().getAsync(...)` | `CompletableFuture<Optional<Identification>>` |
| `client.history().search(LookupType, value[, HistorySearchOptions])` | `HistoryPage` with `getIdentifications()` and `getTotal()` |
| `client.history().searchAsync(...)` | `CompletableFuture<HistoryPage>` |
| `client.history().stream(LookupType, value[, HistoryIterateOptions])` | lazy `Stream<Identification>` over all pages, de-duplicated |
| `client.history().iterate(...)` | the same as an `Iterable<Identification>` |
| `ManagementClient.builder()...build().getProfile()` | `DomainProfile`; also `getProfileAsync()` |
| `Webhooks.verifySignature(payload, header, secrets...)` | `boolean` |
| `Webhooks.constructEvent(payload, header, secrets...)` | `IdentificationScoredEvent`, `WebhookPingEvent` or `UnknownWebhookEvent` |
| `Risk.band(score)` / `Risk.isRateLimited(score)` | `RiskBand` / `boolean` |
| `Risk.evaluate(identification[, EvaluateOptions])` | `Evaluation` with `isOk()`, `getReason()`, `getBand()`, `getFlag()` |
| `UserHid.fromUserId(userId, secret)` | HMAC-SHA256 hex User HID |
| `Identification.fromHistoryRow(map)` / `fromWebhookData(map)` | normalize JSON you already parsed |

`Identification` has the same shape whether it came from a History row or a webhook. Getters follow the webhook field names (`getRequestId()`, `getVisitorId()`, `getDeviceId()`, `getSessionId()`, `getCookieId()`, `getUserHid()`, `getDomain()`, `getPublicIp()`, `getLocalIp()`, `getConnectionType()`, `getOs()`, `getBrowser()`, `getDeviceType()`, `getTrafficSource()`, `getRiskScore()`, `getSignals()`, `getDetectionFlags()`, `getObservedAt()`, `getSource()`), and `raw()` returns the original JSON object. Serialized with Jackson, models produce the webhook JSON names (`request_id`, `risk_score`, ...). Country values are English country names such as `"Germany"`, or `""` when unknown. Known signal names are constants in `SignalName` and known connection types in `ConnectionType`; both sets are open, so compare against the constants and keep unknown values.

## Errors and retries

All exceptions are unchecked and extend `ShieldLabsException`:

| Exception | When | Retried |
|---|---|---|
| `BadRequestException` | HTTP 400 | no |
| `AuthenticationException` | HTTP 401 or 403: wrong key, wrong domain or disabled domain | no |
| `QuotaExceededException` | HTTP 402 | no |
| `NotFoundException` | HTTP 404: usually a wrong base URL | no |
| `RateLimitException` | HTTP 429; `getRetryAfter()` when the server sent `Retry-After` | History: yes. Management: no |
| `ServerException` | HTTP 5xx, including proxy error pages | yes |
| `ApiException` | any other error status; base class of the above with `getStatusCode()`, `getBody()`, `getParsedBody()`, `getErrorMessage()`, `getHeaders()` | no |
| `ApiConnectionException` | network failure | yes (not after an interrupt) |
| `ApiTimeoutException` | no response within the timeout | yes |
| `ValidationException` | invalid arguments, detected before any request | no request is sent |
| `SignatureVerificationException` | a webhook signature is missing, malformed or wrong | answer 401 |
| `WebhookParseException` | a verified webhook body is not a valid event | answer 400 |

Retries apply to GET requests only (every SDK call is a GET): exponential backoff from 0.5 s, doubling up to 8 s, with jitter. A `Retry-After` header is followed as sent, up to 10 s (0 or a date in the past retries at once); a History 429 without `Retry-After` waits at least 1 s, because that limit counts requests per one-second window. While `identifications().get` waits for a verdict, it does not retry inside a poll: a 429, a 5xx, a connection error or a timeout is followed by the next poll on its schedule, until the deadline, and after a 429 the next poll waits at least 1 s whatever `Retry-After` says (see [Waiting for the verdict](#waiting-for-the-verdict)). Error bodies vary (empty, `null`, a bare JSON string, a JSON object sent as `text/plain`, an HTML proxy page) and are parsed defensively.

## Compatibility

- Java 11, 17 and 21 are tested in CI; the library targets Java 11 bytecode and works on the module path as the automatic module `ai.shieldlabs`.
- One runtime dependency: `com.fasterxml.jackson.core:jackson-databind` 2.x.
- APIs: History API `/api/v1`, Management API `/v1`, webhook schema version `2026-06-01`. Unknown fields, event types, schema versions, connection types and signal names are accepted.
- Versioning follows [Semantic Versioning](https://semver.org). See [CHANGELOG.md](CHANGELOG.md).

## Development

The supported client consumes schema-generated wire views for History, profiles, webhooks and
lookup types. The generator retains raw values so existing tolerant normalization and unknown-field
handling remain unchanged. Renaming a consumed field or changing its declared type requires the
client adapter to be updated: contract mutation checks exercise this in CI.

The standalone client in `generated/` remains a reference implementation; its transport is not
used by the supported library. Retries, polling and webhook verification remain in this library.

```bash
./sync.sh      # download the current OpenAPI description into resources/
python3 -m pip install -r scripts/requirements.txt
python3 scripts/generate-wire.py          # supported client's wire views
python3 scripts/generate-wire.py --check  # fail on stale views
python3 scripts/check-wire-drift.py       # real compiler checks (requires Maven)
./generate.sh  # also rebuild the standalone reference client (requires Docker)
```


```bash
mvn verify                                   # compile with -Xlint:all -Werror, tests, coverage, javadoc
mvn install -DskipTests && mvn -f examples/httpserver/pom.xml verify
```

Without a local JDK, run the build in Docker:

```bash
docker run --rm -v "$PWD":/src -w /src maven:3.9-eclipse-temurin-17 mvn -B verify
```

The tests use the shared test fixtures in `src/test/resources/fixtures` (History rows, webhook bodies, signature vectors, error bodies) and a local `com.sun.net.httpserver` server, so they need no network access. See [CONTRIBUTING.md](CONTRIBUTING.md).

Documentation: [docs.shieldlabs.ai](https://docs.shieldlabs.ai). Support: [contact@shieldlabs.ai](mailto:contact@shieldlabs.ai).

## License

[MIT](LICENSE). Copyright (c) 2026 ShieldLabs Inc.
