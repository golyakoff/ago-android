# How the Android app is built

The *why* of the top-level choice — native Kotlin, no shared mobile repository yet — is
`ago-root`'s `docs/adr/0178-*.md`. This document is the current-state description that decision
implies: what the modules are, what may depend on what, and how the four genuinely hard parts
(identity, tenancy, realtime, offline) work.

## Module layout

Three Gradle modules, and the boundary between them is the point.

```
ago-android/
  app/            Compose UI, navigation, Android framework, DI wiring
  core/network/   wire DTOs, HTTP client, SignalR client, token attachment
  core/domain/    entities, permission vocabulary, use cases, pure Kotlin
```

- `:core:domain` has **no Android dependency at all** — no `android.*`, no `androidx.*`, no
  `Context`, no `Parcelable`. It is a plain Kotlin JVM module.
- `:core:network` depends on `:core:domain`. It holds everything that knows what the wire looks
  like, and nothing that knows what a screen looks like.
- `:app` depends on both and is the only module where dependency injection is wired — the same
  "hosts reference everything and are the only place DI wiring lives" rule `ago-root`'s
  `CLAUDE.md` states for the backend, applied to the one module that is allowed to know about
  Android.

**The dependency direction is enforced by Gradle, not by discipline.** `:core:domain`'s build file
declares no Android plugin, so a framework import does not compile — the same "make it impossible
rather than merely forbidden" shape `adr/0012` gives the platform's package boundary. That is also
precisely what keeps the KMP option open at no ongoing cost: converting a pure-Kotlin JVM module to
a KMP `commonMain` source set is a build-file change and a directory move, not a rewrite. The day it
is not free is the day something in `:core:domain` reached for `Context`, and the build is what
prevents that day.

## Stack

| Concern | Choice | What it replaces, and why the alternative loses |
|---|---|---|
| UI | Jetpack Compose + Material 3 | Views/XML. Compose is Google's stated direction, and Material 3's own components (bottom sheets, list-detail scaffold, contextual app bar) are exactly the ones the redesigns in `scope-inventory.md` call for. |
| Navigation | Navigation Compose, single `Activity` | Multiple activities, or a hand-rolled back stack. The back-button contract in `navigation.md` is the part that must be right, and Navigation Compose is what implements it without being written. |
| HTTP | Ktor client (OkHttp engine) | Retrofit. Retrofit is excellent and Android-only; Ktor client is multiplatform, so choosing it now is the one place where keeping the KMP door open costs nothing at all. |
| JSON | `kotlinx.serialization` | Moshi/Gson. Same reasoning: multiplatform, and it is Ktor's own default. |
| Realtime | `com.microsoft.signalr:signalr`, Microsoft's official Java client | A hand-rolled SignalR implementation. The official client is JVM-only — see `adr/0178` for why that fact is the single hardest constraint on ever sharing this layer with iOS. |
| Identity | AppAuth for Android (Authorization Code + PKCE) | Embedding a WebView login, which is what OAuth's own current best practice exists to stop. |
| Local store | Room | SharedPreferences for anything structured, or a hand-rolled file cache. See "Offline" below for what is and is not cached. |
| Background work | WorkManager | A foreground service holding a socket open. Android will not let that work, and pretending otherwise is `plan.md` §1's whole point. |

Nothing above is a library that replaces something hand-rolled cheaply; each replaces a piece of
infrastructure this project has no reason to own.

## Identity

The app presents the **same kind of token the console does**: a Keycloak-issued OIDC access token,
Authorization Code + PKCE, validated by `Ago.Chat.Api` directly against Keycloak's JWKS
(`adr/0022`). Nothing about the server's identity model changes.

Two facts constrain how that is configured, both read from the deployment rather than assumed:

- The realm declares exactly one public client, `ago-console`, with web redirect URIs only.
- `Ago.Chat.Api` validates a **single** audience (`ValidAudience = keycloakAudience`, defaulting to
  `"ago-console"`).

So the app needs its own realm client, `ago-android`, public, with a native redirect URI, **and an
audience mapper emitting the value the API already validates**. That is an `ago-deploy` change and
nothing else. Widening the API's audience validation would be a change to `ago-chat`, which this
stage may not make, and is the worse shape regardless: a client should present a credential the
resource server already accepts.

The access token is short (5 minutes, by the realm's own setting) against a long SSO session. The
consequence that matters is a transport one and it is already written down in `ago-root`'s
`realtime.md`: **the hub's access-token factory must read the current token on every negotiate, not
close over one.** Both existing clients reached that bug independently. The Android client is
written knowing it.

Tokens are held in `EncryptedSharedPreferences`; the refresh token never leaves the device and never
appears in a log.

## Tenancy

One identity can hold operator seats at several sites (`adr/0068`). The console solves this with a
module-level `X-Ago-Active-Site` header attached to every authenticated REST call, plus a
query-string equivalent on the hub connection, resolved from `GET /api/v1/me/tenancies`.

The app does the identical thing, in one place: a Ktor client plugin in `:core:network` that adds
the header to every request, reading a single source of truth that the active-site switcher writes.
Not threaded through call signatures, and not duplicated per API module — the console's own remarks
explain why the header is a UX convenience rather than a security boundary (it can only ever
*narrow* what a request resolves to server-side, never widen it), and a native client inherits that
property unchanged. A stale read costs one `403` and a retry, never a cross-tenant leak.

## Realtime

One `/hubs/operator` connection per signed-in session, owned by a single connection holder in
`:core:network` and observed as a Kotlin `Flow` by whatever screen is on top. Screens do not own
connections.

Three properties carry over from the backend and are not the app's to decide:

- **Ordering is per conversation, never global** (`concurrency.md`, non-negotiable rule 6), and it
  comes from the server-assigned `sequence`, never from a timestamp. The app sorts by `sequence`.
- **Delivery is at-least-once**, so the client de-duplicates by `clientMessageId` on the way out and
  by message id on the way in — the same two mechanisms `ago-console`'s `protocol/dedup.ts` already
  implements.
- **Multiple connections per operator are already supported**: the Redis registry keys presence as a
  *set* of connection ids per operator (`presence:operator:{op_id}`). A phone and a desktop signed in
  at once is an existing shape, not a new one, and needs no backend change.

One behavioural consequence of that last point should be surfaced in the UI rather than discovered:
**`SetAwayAsync` is per operator, not per connection.** Setting Away on the phone sets Away for the
desktop console too. That is the server's existing semantics; the app's job is to say so where the
control lives, not to invent a per-device variant of it.

Reconnection uses the client's own backoff with full jitter, and a reconnect re-reads history from
the last known `sequence` rather than trusting what was in memory when the socket dropped.

## Offline

**The app caches for responsiveness, never for correctness.** That is the same rule the backend
lives under — "never cache what a write decision depends on" (non-negotiable rule 8) — read from the
client side.

Cached in Room: the conversation list, recent message history per open conversation, the tag and
canned-response vocabularies, and the operator's own permission set for drawing navigation. All of
it is rendered as *stale until proven fresh*, and a screen never blocks on the network to show what
it already has.

Never cached, and always read live: anything a write decision turns on. Seat and capacity checks,
booking availability, the pending-booking queue's own state at the moment a confirm is sent, and
every permission the server itself re-checks. A booking the app believes is pending may already be
confirmed; the server is the answer, and a refusal is rendered as a refusal rather than retried into
one.

There is **no offline write queue** in the planned scope. A message composed with no connection is
held as a draft and sent on reconnect with the operator watching, not silently replayed hours later
into a conversation someone else has since closed. If the author wants true offline send, it is its
own item with its own conflict rules, and it is not implied by anything here.

## Attachments

Bytes never pass through the API (`file-storage.md`): the client asks for a presigned URL and
uploads directly to object storage. That ports unchanged, with the upload running under WorkManager
so it survives the screen being left. Downloads go to the system download manager rather than into
app-private storage, so the operator can forward what they fetched.

## Testing

Matching `ago-root`'s `docs/conventions/testing.md` in shape rather than in tooling:

- **`:core:domain`** — plain JVM unit tests, no Android test runner, no Robolectric. If a rule needs
  a framework to test, it is in the wrong module.
- **`:core:network`** — Ktor's `MockEngine` for HTTP contract tests against recorded shapes, and a
  real `HubConnection` against a local `Ago.Chat.Api` for the transport tests that matter.
- **`:app`** — Compose UI tests for the flows `navigation.md` names, especially the back-button
  contract and the "a new assignment never navigates" rule, both of which are exactly the kind of
  behaviour that regresses silently.

A screen that compiles but has no test is not done — the same sentence `CLAUDE.md` already applies
to a vertical slice.

## What this document deliberately does not decide

- **Whether iOS shares any of this Kotlin.** `adr/0178` states why that is not decidable yet and
  what would decide it.
- **Push delivery.** `plan.md` §1. It is a backend change first and an Android change second.
- **Play Store distribution mechanics** — signing, tracks, release cadence. Real work, not
  architecture, and nothing about it constrains the shape above.
