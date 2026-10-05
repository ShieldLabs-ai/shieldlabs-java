# Changelog

All notable changes to this project are documented in this file. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres to
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- `sync.sh` downloads the OpenAPI description and `generate.sh` rebuilds the generated clients.
- Connect the supported client to OpenAPI-generated wire views, preserving tolerant normalization.
- Check generated freshness, breaking schema mutations and installed-jar consumption in CI.

## [1.0.0] - 2026-09-30

### Added

- `ShieldLabsClient` for the History API (`https://account.shieldlabs.ai/api/v1/history/{type}/{value}`)
  with a Private API Key (surrounding whitespace is removed), configurable base URL (a trailing `/api`
  is removed), per-attempt timeout, retries with exponential backoff and jitter (`Retry-After` is
  followed as sent, up to 10 s; a 429 without it waits at least one second) and an injectable
  `java.net.http.HttpClient`. Base URLs must use https; plain http is accepted for `localhost`,
  `127.0.0.1` and `[::1]`, and for other hosts only with `allowInsecureHttp(true)`.
- `identifications().get(requestId)` and `getAsync(...)`: read the verdict for a request ID, waiting
  for it within a total time budget (`waitFor`, 10 s by default, where zero makes one poll without
  waiting or retries; `noWait()` makes a single lookup with the client's retries).
  Polls run right away, then after 250 ms, 500 ms, 1 s, 1.5 s and then every 2 s (for a
  `pollInterval` p: p, 2p, 4p, 6p, 8p and then 8p again, each capped at 2 s, or at p when p is
  longer, so 3 s polls every 3 s), and the last poll runs at the deadline. Each poll is one HTTP
  attempt with the timeout min(client timeout, max(time left, 1 s)). A 429, a 5xx response, a
  connection error or a timeout keeps the wait going, and the exception of the last poll is thrown at
  the deadline (an empty last poll returns empty). After a 429 the next poll waits at least 1 s: the
  longest of the scheduled wait, 1 s and `Retry-After` (at most 10 s; a `Retry-After` of 0 or a past
  date still waits 1 s); a `Retry-After` longer than the time left is thrown at once. A 400, 401, 403
  or 404 stops the wait at once. Cancelling the future returned by `getAsync` stops further polls.
- `history().search(...)`, `searchAsync(...)`, `stream(...)` and `iterate(...)`: lookups by IP, User
  HID, visitor ID, request ID, device ID, session ID or cookie ID, with client-side validation of the
  type, UUIDs (sent lowercase), IPv4 addresses, `limit` and `offset`, and lazy paging that skips
  repeated request IDs. A User HID is sent in the canonical path form the History API matches
  (`$ & + , : ; = @` unescaped); values that contain `/`, and the values `.` and `..`, are refused.
- `ManagementClient` with `getProfile()` and `getProfileAsync()`, domain normalization, and no retry
  on 429 (the Management API blocks an IP for 10 minutes after about 15 requests per minute).
- `Webhooks.verifySignature(...)` and `Webhooks.constructEvent(...)` for `X-Shield-Signature`, accepting
  one or several secrets for rotation, and typed events: `IdentificationScoredEvent`,
  `WebhookPingEvent`, `UnknownWebhookEvent`.
- `Identification`, one model for History rows and webhook data, with the 19 `DetectionFlags`, risk
  signals, traffic source, public and local IP, and the original JSON in `raw()`; `HistoryPage`
  (`getIdentifications()`, `getTotal()`) and `DomainProfile`. Serialized with Jackson, the models use
  the webhook JSON names.
- `Risk.band(score)`, `Risk.isRateLimited(score)` and `Risk.evaluate(identification, options)`, a
  reusable guard for protected actions (missing, replayed, stale, rate-limit marker, no device
  signals, blocking flags, blocking bands).
- `UserHid.fromUserId(userId, secret)` to create a User HID on the server.
- Exception hierarchy under `ShieldLabsException`, including `QuotaExceededException` for HTTP 402.
- Example backend on `com.sun.net.httpserver` in `examples/httpserver`.

[1.0.0]: https://github.com/ShieldLabs-ai/shieldlabs-java/releases/tag/v1.0.0
