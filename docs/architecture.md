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
  Android. **Hilt** is the framework (the author's own decision, 2026-09-21) — wired in `26-12`,
  the first item with anything to inject, not in the scaffolding item that precedes it.

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
| Navigation | Navigation Compose, single `Activity` | Multiple activities, or a hand-rolled back stack. The back-button contract in `navigation.md` is the part that must be right, and Navigation Compose is what implements it without being written. The five destinations are ordered **Диалоги, Записи, Команда, Аналитика, Ещё** — the same order on the phone's bottom bar and the tablet's navigation rail, by how often a shift touches each, not by the console rail's own order. |
| HTTP | Ktor client (OkHttp engine) | Retrofit. Retrofit is excellent and Android-only; Ktor client is multiplatform, so choosing it now is the one place where keeping the KMP door open costs nothing at all. |
| JSON | `kotlinx.serialization` | Moshi/Gson. Same reasoning: multiplatform, and it is Ktor's own default. |
| Realtime | `com.microsoft.signalr:signalr`, Microsoft's official Java client | A hand-rolled SignalR implementation. The official client is JVM-only — see `adr/0178` for why that fact is the single hardest constraint on ever sharing this layer with iOS. |
| Identity | AppAuth for Android (Authorization Code + PKCE) | Embedding a WebView login, which is what OAuth's own current best practice exists to stop. |
| Local store | Room | SharedPreferences for anything structured, or a hand-rolled file cache. See "Offline" below for what is and is not cached. |
| Background work | WorkManager | A foreground service holding a socket open. Android will not let that work, and pretending otherwise is the whole point of `plan.md`'s own "Push is the reason this app exists". |

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

`26-12` made all of that concrete. AppAuth drives Authorization Code + PKCE in a Custom Tab against
the `ago-android` client `26-11` added to the realm, with the realm's endpoints **discovered** from
the issuer rather than typed out; `SessionStore` (`:app`, `session/`) is the one
`EncryptedSharedPreferences` file, holding AppAuth's serialised `AuthState` and the active site
together so that one `clear()` at sign-out leaves nothing behind. "Never leaves the device" needs two
manifest facts, not one: `android:allowBackup="false"` turns off cloud backup, and
`android:dataExtractionRules` (`res/xml/data_extraction_rules.xml`) turns off Android 12+'s separate
device-to-device transfer channel, which `allowBackup` does not reach. "Never appears in a log" is a
property of what is *absent*: no `Logging` plugin is installed on the Ktor client at any level in any
build type (`ago-console`'s `5-14` is the precedent — a transport logging its own negotiated URL at
Information level was printing a live operator JWT), and the classes that hold tokens contain no
logging statement at all.

The token-freshness rule is implemented as a Ktor client plugin (`BearerToken`, `:core:network`) that
asks an `AccessTokenProvider` **inside the send pipeline, per attempt** — not Ktor's own
`Auth`/`bearer` provider, which caches the token it loaded until a `401` and so is the very shape
`5-16` names. The same plugin forces one renewal and one retry on a `401`, setting the header on the
retried request rather than reusing the one the server just rejected.

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

`26-12` built it: `ActiveSiteHeader` (`:core:network`, `tenancy/`) attaches `X-Ago-Active-Site` to
every request the app's one `HttpClient` makes, reading `ActiveSiteSelection` — a port declared in
`:core:domain`, because "which shop am I working in" is a product fact rather than a detail of one
transport (`26-13`'s hub carries the same value as a query-string parameter off the same source), and
implemented once in `:app` over `SessionStore`. Read fresh per request, for the same reason the
bearer token is: the site picker writes it after the client is built, and `26-17`'s switcher changes
it again mid-session.

**One ordering the tenancy mechanism turns on, found by reading the server rather than assumed.**
`GET /api/v1/me/tenancies` must be answered *before* `GET /api/v1/operators/me` is sent, because
`ResolveOperatorIdentityHandler` resolves an identity with several eligible tenancies and no header
to nothing at all — so `operators/me` answers `403` for a multi-tenancy operator until the header
exists. `navigation.md`'s sign-in section has the full reasoning and the corrected diagram.

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

## Push

`26-06`/`adr/0180`: RuStore Push, not FCM — `adr/0180` names the reason (Russian data residency on a
stock Android phone, settled by changing provider rather than by the legal escalation `adr/0179` left
open). `docs/architecture/push-notifications.md` is the authoritative design; this section states what
this repository's own code does with it.

**Three call sites write the same row.** `PUT /api/v1/me/devices/{installationId}` runs at every
sign-in (`SignInViewModel.routeNow()`'s `Operator` arm), from `AgoPushMessagingService.onNewToken` (the
provider's own rotation callback), and from a periodic `WorkManager` job
(`DeviceRegistrationWorker`/`WorkManagerDeviceRegistrationScheduler`) — the third one exists because
`onNewToken` cannot fire for an app that was not running when a rotation happened.
`DeviceRegistrationCoordinator` is the one place that sequence — read the installation id, ask the SDK
for the current token, write it — is expressed, precisely so the three call sites cannot quietly drift
from one another (`26-59`'s own history is the reason that drift is worth naming as a risk at all).

**The periodic job runs every 24 hours, with a `NetworkType.CONNECTED` constraint.** Not the 15-minute
floor `PeriodicWorkRequest` itself allows: `onNewToken` already handles a rotation in real time while
the app is running, so this job exists solely as a backstop for the case it cannot cover. There is no
documented token lifetime to race, so nothing calls for a shorter interval; a daily heartbeat is
frequent enough that the server's own `last_seen_at` drifting more than a day stale is a genuine "is
this install still alive" signal, and infrequent enough to cost nothing worth measuring in battery for
a write this cheap. It is scheduled once, at sign-in (`ExistingPeriodicWorkPolicy.KEEP`, so a second
sign-in never resets its clock), and deliberately never explicitly cancelled on sign-out — see
`WorkManagerDeviceRegistrationScheduler`'s own doc comment for why a bounded, harmless retry against an
idle endpoint costs less than the coupling cancelling it would add to `AgoAuthSession`.

**`installation_id` lives in its own, unencrypted `DataStore` file — never `SessionStore`'s.** It has to
survive exactly the sign-out that empties `SessionStore` (`docs/architecture/push-notifications.md`'s
own reason `installation_id` exists: the server row's identity is `(operator_id, installation_id)`, not
`(operator_id, token)`), and it is not a credential, so it costs nothing `EncryptedSharedPreferences`
would be buying.

**The RuStore SDK is reached through one seam, `PushRegistrationGateway`**, declared in `:app` rather
than `:core:domain` — unlike `DeviceRegistrationApi` (a REST port, `:core:domain`, the identical
`ConversationsApi` shape), a push provider chosen specifically because it runs on Android has no
KMP-`commonMain` generalisation to protect (`adr/0178`), the same reasoning that already keeps
`SignInSession`'s AppAuth-shaped session in `:app`. `RuStorePushGateway`'s own doc comment states why it
bridges the SDK's own callback-shaped `Task` type with `suspendCancellableCoroutine` rather than the
SDK's own blocking `Task.await()`.

**`checkPushAvailability()`'s answer is recorded, never hidden.** `DeviceRegistrationCoordinator` logs
an `Unavailable` result at `WARN` (the reason only, never a token) and exposes the last answer as
`SignInViewModel.pushAvailability`, the identical `StateFlow` shape `hubConnectionState` already
establishes — so a future screen (`26-19`) has a real value to bind to with no further plumbing, without
this item building a screen that is not its job to build.

**A RuStore Console push project already exists** — "AGO Chat Production",
`1Q8iLXwwBZViuznG6eCTHgkzrTE9Bto6` (`25-216`) — supplied as a `BuildConfig` field
(`AGO_RUSTORE_PUSH_PROJECT_ID`) the identical way every other deployment value in `app/build.gradle.kts`
already is. One project for both build types, not one per build type: `25-215` unified debug and release
under a single signing key/fingerprint before this project existed, which is what makes one RuStore
Console project (bound to that one fingerprint) enough.

## The design system: tokens, dynamic colour, and hardcoded-colour enforcement

`26-10` transcribes `ago-console/src/design/tokens.css` — the console's own single source for colour,
type and shape — into a Material 3 `ColorScheme` + `Typography` + `Shapes` set
(`app/src/main/kotlin/ago/chat/android/ui/theme/`). This is deliberately not a second design system:
`adr/0030` is the console's own decision about its closed palette, and the Android reading transcribes
it rather than re-deciding it. Every value in `Color.kt` is either CARRIED OVER unchanged from a
`tokens.css` custom property, or DERIVED from one by a rule stated next to it (Material 3 has more
colour roles — the `surfaceContainer*` tonal-elevation family, `secondary`/`tertiary`, `inversePrimary`
— than `tokens.css` has tokens for) — the same CARRIED OVER / DERIVED discipline `tokens.css`'s own
header comment applies to itself. A handful of `tokens.css` tokens (`--ago-warning`,
`--ago-brand-hover`, `--ago-live`, `--ago-ink-faint`) have no honest Material 3 `ColorScheme` slot at
all — every slot is already assigned to a different token — and are kept as named constants for a call
site to read directly, rather than forced into a role that doesn't fit. `26-44`'s `SectionLabel`
(`ui/components/`) is `--ago-ink-faint`'s own call site, and its own doc comment states the reasoning in
full, including why it resolves dark/light from the active `ColorScheme` rather than
`isSystemInDarkTheme()`.

**Android 12+ dynamic colour is not used — a decision, not an oversight.** `dynamicLightColorScheme()`/
`dynamicDarkColorScheme()` (wallpaper-derived, Android 12+) are never called anywhere in this app;
`AgoChatTheme` always uses the fixed, token-derived colour schemes, on every OS version. Reasoning: the
console has no per-user, wallpaper-driven theming at all — its palette is a fixed brand identity, and
`adr/0030` treats that as a deliberate, closed decision, not a gap. A B2B operator tool where the same
person may work from the console on a desktop and this app on a phone benefits more from one
consistent brand identity across both surfaces than from matching whatever wallpaper happens to be on
an operator's phone that day — an operator recognising "this is AGO Chat" at a glance matters more here
than the personalisation dynamic colour is designed for on a consumer app. If a future item finds a
concrete reason dynamic colour should be offered as an opt-in (not a default), that is a new decision
to make explicitly, not a reason to treat this one as unconsidered.

**Enforcement of "no hardcoded colour at a call site" is a written convention, not a lint rule.**
Checked before deciding: Android Lint's own built-in `HardcodedColor`-shaped checks target colour
literals in XML resources, not `Color(0x...)`/`Color.Red`-shaped literals inside Compose Kotlin code,
and neither the Android Gradle Plugin nor the Compose compiler ships a built-in Compose-lint check for
this. Real third-party rule sets exist (`mrmans0n/compose-rules`, `slackhq/compose-lints`,
`ReactiveCircus/compose-lint-rules`) but each is a new Gradle plugin dependency and a second
suppression vocabulary — the identical reasoning `26-08` already gives for rejecting detekt in this
repository ("Android Lint plus `allWarningsAsErrors` already cover this project's actual needs; detekt
is a second suppression vocabulary for findings nobody has had yet"). Adding one for a single rule this
early, with no findings yet to justify it, would repeat the thing `26-08` already declined. The
convention instead: **a `Color`, a `TextStyle`, or a corner radius is read from `MaterialTheme`
(`.colorScheme`, `.typography`, `.shapes`) or from a named constant in `ui/theme/`, never written as a
literal at a screen's own call site.** If a real violation shows up in review, that is the moment to
revisit whether a lint dependency has become worth its cost — not before.

## Strings: resources in two languages, no literals

**`26-91` made this real, and it does not lapse.** From `26-91` on: no string literal in a
Composable or a ViewModel. Every user-facing string is a resource, added to both
`app/src/main/res/values/strings.xml` (Russian, this app's own base/default locale — `26-10`) **and**
`app/src/main/res/values-en/strings.xml` (English) **in the same change** — never one file now and the
other "later". A KDoc/line comment quoting Russian prose for documentation, and an `@Preview` function's
own placeholder sample data, are not user-facing text and are exempt (`26-91`'s own report names which
of the sweep's hits were judged which way, and why).

`26-91` shipped `values-en/strings.xml` with a real English translation for every key that existed in
`values/strings.xml` at the time — nothing reads it yet. `26-92` is what wires
`AppCompatDelegate.setApplicationLocales` and adds the Settings row that lets an operator actually pick
English; until then the app always renders Russian, on every device, regardless of this file's
existence. Do not read the presence of `values-en/` as a signal that language switching is live.

Two things a change touching strings needs to get right that a plain copy-paste won't:

- **Positional arguments may need reordering.** `%1$s`/`%2$s` may appear in a different order in the
  English translation than in the Russian original if that reads more naturally — Android resource
  files allow each locale's own string to declare its placeholders in whichever order it needs; a
  hardcoded Kotlin string interpolation could never do this.
- **Plural forms are not a copy-paste.** `russianPluralStringResource` (`ui/components/ElapsedText.kt`)
  hand-picks one of three resource ids (`_one`/`_few`/`_many`) by Russian's own mod-10/mod-100 grammar
  (CLDR "ru") rather than through Android's own `<plurals>` — deliberately, per that function's doc
  comment, since `<plurals>` picks its bucket from the *device's* locale rather than from the resource
  file actually supplying the string. English only has a one/other rule, so the `_few` and `_many`
  English strings are written identically (there is no third form to distinguish) — but the selection
  function itself remains Russian-specific: its "one" bucket also fires for 21, 31, 101… (anything
  ending in 1 except 11), where English wants the plural, not the singular. Reusing this function as-is
  once a locale switch exists would render "21 minute" — wrong. This is latent, not live, because
  nothing reads `values-en/` yet; **`26-92` (or a dedicated companion item) must adapt the selection
  itself — a real `<plurals>` per locale, or a locale-aware wrapper — before English plurals are ever
  actually shown.** Translating the surrounding text cannot fix a selection-function bug, and `26-91`
  deliberately left the function untouched rather than reach into runtime locale logic that item's own
  scope excluded.

Android Lint's own `MissingTranslation`/`ExtraTranslation` checks are on by default (no `lint {}` block
or `lint.xml` in this project overrides them) and now apply for the first time as of `26-91`, since a
second `values-*` locale directory existing at all is what makes them run. A `./gradlew lint` catching
either — a key present in one file and not the other — is this project's own real, mechanical proof
that the "both files, same change" rule above was actually followed, not merely asserted.

## How an identifier is rendered

Every id in this product is a GUID, and no screen ever prints one in full. The console's own
convention, applied at a dozen call sites and asserted by its tests, is **the first eight characters
in a monospace face** — `visitorId.slice(0, 8)`, `operatorId.slice(0, 8)`, `calendarId.slice(0, 8)`,
`conversationId.slice(0, 8)`, `siteId.slice(0, 8)`, `customerId.slice(0, 8)` — which is eight hex
characters, never a decimal counter and never a `#1234`-shaped reference. The app renders the same
eight characters, in the same monospace face, for the same reason: an operator reads them aloud to a
colleague or pastes them into a search, and two clients that truncate differently make that
impossible.

`26-10` makes this concrete rather than aspirational: the truncation rule itself is `shortId`
(`:core:domain`, `ago.chat.android.core.domain`) — a plain `String -> String` function with no Android
dependency, because it is a rule the product owns rather than a detail of any one screen. `IdentifierText`
(`:app`, `ui/components/`) is the one composable that calls it and renders the result in the product's
monospace face; no screen calls `shortId`/`.take(8)` itself. The same split — logic in `:core:domain`,
rendering in `:app` — applies to the composite below.

The one composite worth naming, because it is a single string with three optional parts:

```
{emojiCreature}{emojiFood} {visitorName?} {visitorId.slice(0, 8)}
```

— `visitorDisplayPrefix` in the console, and each part is genuinely absent rather than blank when
unknown (a visitor predating the emoji column renders as the short code alone). The app builds the
same string, and gives the emoji pair its own deliberately larger size the way `25-162` already does
on the web.

`26-10`: `visitorDisplayPrefixParts`/`visitorDisplayPrefixText` (`:core:domain`) hold the logic —
which parts are present, and the exact text-composition rule, matched against
`ago-console/src/workspace/visitorEmoji.ts`'s own `visitorEmojiPrefix`/`visitorDisplayPrefix` (each
present part supplies its own trailing space, none supplies a leading one, so a pair-less, name-less
visitor renders the short id alone with no gap). `26-40` retired the `:app` composable this paragraph
used to name here (`VisitorDisplayPrefix`, `ui/components/`) — see that item's own note below.

`26-30`: `VisitorDisplayPrefixParts` grew a fourth field, `displayName` — the visitor's own real name
when there is one, else the emoji pair's own localized fallback label ("Лиса · Апельсин", from
`VisitorEmojiNames.kt`, a byte-for-byte port of `ago-console/src/i18n/visitorEmojiNames.ts`'s table
over `Ago.Chat.Domain.VisitorEmojiDictionary`), else `null`. `visitorName` itself is unchanged — the
real name only, still what `visitorDisplayPrefixText` reads — so `displayName` is additive, not a
replacement. Both real call sites read `displayName`, which is what gives each one the identical
fallback with no derivation of its own.

`26-40`: the thread screen's app-bar title stopped being the one place that still rendered the short
code. Reading the approved mockup Artifact against real code found the app bar drawing a code the
mockup's own title never draws at all — `VisitorDisplayPrefix` (`:app`, `ui/components/`) drew the id
unconditionally, with no caller opt-out — while the mockup's own second line (channel · state · age)
was entirely absent. The fix retired that composable (its only caller was this app bar) in favour of
the thread screen's own `ThreadTitleBlock`, which reads `visitorDisplayPrefixParts` directly and draws
`displayName` alone — the identical "read the `:core:domain` function directly, keep the layout local"
choice `ConversationListScreen`'s own `ConversationRowIdentityLine` already made for the row's name
line. Underneath it, a subtitle renders the conversation's state
(`ConversationSummary.state`/`ConversationRowUi.state`, grown the same additive way `26-15` grew
`hasAttachmentUploadGrant`, classified by the pure `conversationStateLabel`/`ConversationStateLabel`
pair in `:core:domain`) and its age (`shortElapsedText`, moved out of `ConversationListScreen` into
`ui/components/ElapsedText.kt` once the app bar became a second caller) — never the channel, which no
field on the wire carries at all (incoming-channel expansion is `Ago.Chat`'s own Stage 14). The one
place an eight-character code is still drawn today is `IdentifierText`'s two other real call sites —
`SettingsScreen`'s site rows and the sign-in site picker — both ids an operator genuinely may have to
match, unlike a visitor's own conversation-list/app-bar identity.

`26-68`: `26-40`'s own retirement of the eight-character code above turned out to also (accidentally)
retire the *symptom* of a separate defect, without retiring the defect itself. `ConversationsTabHost`'s
row lookup (`(listState.mine + listState.waiting).firstOrNull { ... }`, `null` for a restored thread
before the queue has re-fetched, or one whose conversation has since left both halves for good) used to
fall back to `row?.visitorId ?: currentlyOpen` — substituting the *conversation's own id* into the
visitor-id slot. Once `26-40` stopped this app bar from ever drawing `visitorId` at all, that fabricated
value became dead data as far as the screen was concerned — never actually the wrong eight-character
string an operator saw, contrary to what this item's own Found section (written against a commit before
either change had landed) describes. The defect was real regardless: a value silently invented to fill
a slot that should have said "unknown", one call away from resurfacing the moment any future consumer —
the still-not-built visitor context sheet named above, most obviously — reads `visitorId` off the same
row. The fix: `VisitorDisplayPrefixParts.visitorId` (and the two `ThreadRoute`/`ThreadScreen` parameters
that feed it) are `String?` now, joining every other field in this composite that was already nullable;
`ConversationsTabHost` passes `row?.visitorId` with no fallback, exactly as it already did for
`emojiCreature`/`emojiFood`/`visitorName`. `hasAttachmentUploadGrant` got the identical treatment for
the identical reason — the old `?: false` hid a control an operator might actually hold, with no sign
anything was unknown — and is `Boolean?` throughout the same chain, `null` and `false` both hiding the
paperclip but only one of them meaning "confirmed no". Neither field needed a new network call to
resolve the ordinary transient case: `ConversationListViewModel.refresh()` already runs unconditionally
from that class's own `init`, so the real values land through the very next recomposition once the
queue answers. The one case a re-fetch can never resolve — a conversation gone from both halves for
good — is named on screen instead: `ConversationsTabHost` derives `identityUnavailable` from
`listState.isStale` (`false` only once a genuinely fresh queue answer confirmed the absence, never
merely "not fetched yet"), and `ThreadTitleBlock` renders `thread_identity_unavailable` in that one case
rather than sitting blank forever with nothing to explain why.

Two places where an id is what the wire carries and a name is what the screen needs — the pending
booking queue (`PendingBooking` has `workerId`/`serviceId`/`calendarId` and no names) and the
attachment upload grant (`attachmentUploadGrantedByOperatorId` with no join to a display name) —
are recorded as gaps in [`scope-inventory.md`](scope-inventory.md) rather than papered over with a
raw id in a sentence meant for a human.

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

### Where `:app`'s Compose UI tests run (`26-20`)

Two real options, priced against what this app actually has today (a handful of screens, no
screenshot suite, `docs/backlog/26-20-*.md`'s own scope naming `ago-console`'s fifty-four-route
`ux-gate` as the thing this is deliberately *not*):

- **An emulator on the GitHub-hosted runner** (`reactivecircus/android-emulator-runner`) — real
  `NavHost`, real system back dispatch, real `BackHandler`/`ModalBottomSheet` interaction, at the cost
  of an emulator boot (a fixed few minutes CI pays on every push) and this action's own
  well-documented history of flakiness on other projects.
- **Robolectric on the JVM** — no emulator boot, runs inside the same `test` task `:core:domain`
  already uses, but it is a *simulation* of the Android framework's own back-dispatch and window-focus
  machinery, not the real thing — exactly the layer the back-button contract lives in.

**Decision: the emulator.** The back-button contract (`26-16`) is a claim about how the *real*
`OnBackPressedDispatcher`, `NavHost` and `ModalBottomSheet` behave together, and those tests already
exist, already pass against a real device, and were independently re-verified against a real emulator
before this item started (`26-20`'s own brief) — Robolectric would mean re-writing a proven suite
against an approximation of the exact mechanism it is testing, to save a boot time this project can
still afford at its current size. Revisit this the day the suite's own wall-clock cost, not its
flakiness, becomes the argument against it.

**What this still cannot catch.** A GitHub-hosted emulator is a real Android framework, but it is not
a physical device: it proves nothing about a specific OEM's back-gesture customisation, a real
touchscreen's input timing, low-memory process death under real device pressure, or anything that
depends on real hardware sensors or a real cellular/Wi-Fi handoff. It also runs exactly one API level
(34, this app's own `targetSdk`) on exactly one screen profile — a real fragmentation bug on a
different OEM skin or a different screen density would pass this gate and still ship. Those gaps are
`26-22`'s own by-hand verification to close, not something a green CI run may be read as already
covering.

### Pinning the locale instrumented UI tests render against (`26-94`)

`26-91` gave the app a real `values-en/strings.xml` next to the default `values/` (Russian) set — and
the moment a real alternative existed, any English-*locale* device (including the CI emulator above,
which boots `en-US`) genuinely renders English, where it used to fall back to `values/` for lack of
anything else to resolve to. Fourteen instrumented test classes assert Russian text as a literal, so the
two only ever agreed by accident before `26-91`. Three mechanisms to force the CI *emulator's own*
locale were tried and each failed for a different, real reason (`.github/workflows/ci.yml`'s own comment
on the `instrumented-tests` job has the exact errors): a runtime `settings put system system_locales`
plus `LOCALE_CHANGED` broadcast is inert even under `adb root`, because nothing short of the framework's
own `LocaleManager`/`LocalePicker` reconfiguration call actually re-resolves resources; and a boot-time
`-prop persist.sys.locale=...` is rejected outright by the emulator image.

**The fix forces the *app's* own locale inside the test process instead of the device's.**
`ago.chat.android.testing.LocaleForcingTestRunner` (`app/src/androidTest/kotlin/.../testing/`), wired in
as `app/build.gradle.kts`'s `testInstrumentationRunner`, calls the platform `LocaleManager` (API 33+)
directly in its `onCreate` — before `super.onCreate()` lets `Instrumentation` create the target
`Application` or any `Activity` — so the very first `Configuration` the process ever resolves resources
against is already `ru`, for every test in every class, with no per-class code beyond removing
`@FlakyOnCi`. Since neither `values-ru/` nor `values-en-rRU/` exists, `ru` resolves straight to the
default `values/` set — the exact Russian text these tests already assert.

**Not `AppCompatDelegate.setApplicationLocales`**, this item's own first-suggested API, despite this
project already depending on `androidx.appcompat` transitively (`net.openid:appauth`'s own
`AppCompatActivity`-based redirect screens): decompiling `androidx.appcompat:appcompat:1.8.0` shows that
method only reaches `LocaleManager` by walking a static set of *already-created* `AppCompatDelegate`
instances for one with a usable `Context` — populated exclusively by `AppCompatActivity`,
`AppCompatDialog`, or an explicit `Activity`/`Dialog`-bound `AppCompatDelegate.create(...)` call, none of
which exists anywhere in this suite (every screen under test is a plain
`androidx.activity.ComponentActivity`, via `createAndroidComposeRule<ComponentActivity>()`). Calling it
before any such delegate exists — which is the process's exact state when a test run starts — silently
falls through to a branch that never reaches `LocaleManager` at all. Calling the platform API directly
sidesteps that dead end entirely and needed no new dependency, since `android.app.LocaleManager` is part
of the SDK itself; `LocaleForcingTestRunner`'s own doc comment has the full, decompiled account.

**Stated limit, not silently assumed:** this mechanism is exact and unconditional on API 33+, which is
what CI's own emulator runs (`api-level: 34`, matching this app's `targetSdk`) and what every
currently-supported real device runs. Below 33, the runner falls back to a plain `Locale`/`Configuration`
override that changes `Locale.getDefault()` immediately but cannot reach a freshly created `Activity`'s
own `Configuration` without an `AppCompatActivity` to hook — a real gap on that range, recorded rather
than hidden, and one this item's own real target (CI) never exercises.

The next instrumented test added to this suite needs nothing of its own for this: any class asserting
Russian resource text is already covered by `LocaleForcingTestRunner`, the same way every existing one
now is. This is scoped to the *test* process only and decides nothing about `26-92` (the in-app language
*toggle* a real operator can reach in Settings, unstarted) — but the same investigation surfaced a real
risk for whoever picks that item up: `MainActivity`
(`app/src/main/kotlin/ago/chat/android/MainActivity.kt`) is itself a plain `ComponentActivity`, exactly
like every screen this test suite drives, so `AppCompatDelegate.setApplicationLocales` is likely to hit
the identical dead end described above in production too — no `AppCompatDelegate` exists there either,
today. `26-94` does not fix or re-scope `26-92` — that is a separate promise this item has no mandate to
touch — but leaves this finding here rather than let `26-92` re-discover it the hard way.

## What this document deliberately does not decide

- **Whether iOS shares any of this Kotlin.** `adr/0178` states why that is not decidable yet and
  what would decide it.
- **Push delivery.** `plan.md`'s own "Push is the reason this app exists" - the app's first dependency,
  designed as its own backend item and not here.
- **Play Store distribution mechanics** — signing, tracks, release cadence. Real work, not
  architecture, and nothing about it constrains the shape above.
