# The plan

## What the app is for

`ago-console` is a tool an operator sits in for a whole shift, at a desk. Its own workspace screen
is designed around exactly that (`WorkspaceLayout.tsx`: "one screen an operator can work a shift
in"). The Android app is for the other half of the same person's day — the half where they are not
at the desk and the visitor is still waiting.

That framing is not a licence to ship a thin app. The author's own instruction is that the Android
client reaches the same functionality as `office.reserve-me.ru`, minus what only a platform owner
can reach. This plan honours that: **49 of the console's 54 routes are in scope**, and the five that
are not are the five platform-owner screens, each with a stated reason
([`scope-inventory.md`](scope-inventory.md)). What the framing decides is *order*, not extent —
which screens ship first, and which can wait without the app being dishonest about what it is.

**Phone first, tablet last, and that is a priority rather than a hedge.** The author's own words:
*"не убивайся… телефон — основной инструмент… планшет — скорее дополнительно прикольная фича"*. The
wide layout is one breakpoint over the same code, and it earns its place only because the phone
screens were built as two-screens-and-a-sheet rather than as a shrunk grid — which is a claim about
the phone design, not an investment in the tablet one. No mockup iteration spends effort polishing
the tablet frame, and no phase in this plan is gated on it.

## Push is the reason this app exists, and it does not exist yet

This section used to be called "three dependencies that do not exist yet", and it listed push first
among three caveats. That framing was wrong, and the author corrected it in exactly the terms that
matter: push is not a caveat on the app, it is what the app is *for*, and rewriting whatever part of
`Ago.Chat.*` it takes to get there is an acceptable price.

So it is stated on its own, before anything else in this plan, and the two genuine infrastructure
chores that follow are the ones that stayed caveats.

### The priority

`Ago.Chat.*` contains no Firebase Cloud Messaging, no device-token table, no APNs, nothing. The
console's alerting (`18-05`, `workspace/alerts.ts` + `useAlerts.ts`) is the browser `Notification`
API, which requires the page to be open and the SignalR connection live.

Ported literally, that gives an Android app which notifies only while it is in the foreground with a
live connection — **a worse notifier than the desktop console**, and the opposite of the one thing a
native app is uniquely good at. Android will kill a background socket; this is not a tuning problem,
and no amount of Kotlin fixes it.

Everything else in this document describes an app that is *more convenient* than the console.
Without push, none of it makes the app *necessary*: an operator whose phone stays silent will keep
the console open on a desktop, and the phone becomes a second place to do the same work rather than
the place the work reaches them. The whole value of this stage is downstream of one backend
capability.

**That capability was not designed here, and deliberately so — it now is, and is being built.**
"The operator learns a visitor is waiting while the phone is in their pocket" was a backend change —
a device-registration endpoint, an outbox consumer fanning out to FCM, and a per-operator delivery
decision that reuses `alerts.ts`'s own rules rather than inventing a second set. It is designed in
[`docs/architecture/push-notifications.md`](../../ago-root/docs/architecture/push-notifications.md)
and [`adr/0179`](../../ago-root/docs/adr/0179-operator-push-is-a-worker-fan-out-to-a-device-row-and-the-loudness-decision-stays-on-the-client.md)
(`26-01`), and split into three real `ago-chat` items the author has put into work: `26-03` (device
registration, in progress), `26-04` (the FCM adapter — blocked on the author's own data-residency
decision and a live reachability measurement, not on design), and `26-05` (the fan-out consumers).
Nothing in this document constrains their shape beyond what the client side needs: a device token
registered per signed-in operator per device (keyed by `operator + installation`, never by the token
itself, so rotation needs no sweep job), a payload carrying enough to route a tap straight to a
thread or a booking (`navigation.md`'s deep-link table), and revocation on sign-out.

**The app is designed for the world where push exists.** The Notification settings screen shows the
real thing — a channel per kind of event, each with its own switch, quiet hours, and the note that
Away is per operator rather than per device — with every control disabled behind one banner naming
the item that has to land first. That is a deliberate choice over the earlier draft's honest-limits
copy: a screen that only explains what the app cannot do is a screen nobody designs the good version
of later. The promise is drawn, and the reason it is not yet live is stated once, at the top, where
it cannot be mistaken for a shipped feature.

Until the backend lands, runtime behaviour is unchanged and honest: foreground-only alerts, and the
settings screen says so.

## Two infrastructure chores that stayed caveats

Unlike push, neither of these changes what the app is worth. Both were found by reading the backend
and the deployment rather than assumed, and both are cheap next to the section above.

### Keycloak has exactly one public client, and the API validates a single audience

`ago-deploy/k8s/base/keycloak-realm-import.json` declares one public client, `ago-console`, with web
redirect URIs only. `Ago.Chat.Api`'s `CompositionRoot.cs` sets `ValidateAudience = true` with
`ValidAudience = keycloakAudience` — a single string, defaulting to `"ago-console"`.

A native app cannot use a web redirect URI, and a second Keycloak client would by default emit its
own `aud`, which the API would reject. The route that needs **no `ago-chat` change** is a new public
client `ago-android` in the realm import, with a native redirect URI and an audience mapper whose
`included.client.audience` is the same value the API already validates. That is an `ago-deploy`
change, which this stage may make additively.

The alternative — widening the API's audience validation to a list — is a change to `ago-chat` that
`26-00`'s own Out-of-scope forbids, and is the worse shape anyway: the app should present a
credential the API already knows how to accept, not ask the API to accept more kinds.

### There is no OpenAPI description of the API, and there will not be one

No `AddOpenApi`, no `MapOpenApi`, no Swashbuckle, no NSwag — in `ago-chat` or `ago-calendar` (the
only two hosts that serve a public contract at all; `ago-platform` ships no host of its own). Both
existing clients hand-write their wire types from `Ago.Chat.Contracts`
(`ago-console/src/realtime/protocol/types.ts`: "it exists so the rest of the console never guesses
field names"; `ago-widget` does the same).

This was load-bearing for the architecture decision (`adr/0178`) as an open question and is now
closed as a real decision (`26-02`, not planned): the decisive finding is that OpenAPI would cover
236 of 236 REST operations and 0 of 19 SignalR hub methods and 0 of 8 push events, while the
conversation itself — join, history, send, receive, presence, team chat — is 100% hub, and this
widget's own web sibling touches exactly six REST endpoints. A generated client would help the
administrative surface and not the screen an operator actually works from. The Android client hand-
writes its wire types the same way both existing clients do, and for the same reason, permanently.

## Order of work

Each phase is a set of backlog items in `ago-root`, numbered when this plan is approved. A phase is
not a release train — it is the order in which a reviewer would want to see the app become real.

**Phase 0 — the shell that proves the hard parts.** The three things most likely to be wrong are not
screens: OIDC against Keycloak from a native client, the SignalR operator hub over a connection
Android will suspend, and the `X-Ago-Active-Site` tenancy header on every call. Phase 0 is sign-in →
conversation list → open a thread → send a message → watch it arrive on the console. One vertical
slice, end to end, over the real transport. Nothing else ships until this does.

**Phase 1 — a shift's worth of work.** Conversations (list, thread, visitor sheet, close, claim,
tag, note), the calendar pending queue with confirm/reject, team chat, and My numbers. This is the
app someone would actually carry. It is also the set that most needs push (above) to be worth
carrying.

**Phase 2 — the rest of the day-to-day.** Confirmed bookings, clients and the reveal flow, workers,
services, schedule, all-conversations, search, restricted visitors, the team-people screen, and the
two audit trails.

**Phase 3 — configuration and administration.** Channels, automation, documents, storage, products,
export, billing (read-only — see below), account deletion, the calendar setup screens. These are the
screens somebody sets up once and revisits rarely; they are last because being last costs almost
nothing, not because they are excluded.

## A product decision this app forces, and the console will want too

### The visitor avatar is a badge, not a bare pair, and Domain does not change (`25-207`)

A visitor's identity in this product is an emoji pair — one creature, one food — permanent from the
moment it is assigned (`Ago.Chat.Domain.VisitorEmojiDictionary`, `workspace/visitorEmoji.ts`, `25-56`).
An earlier draft of this section proposed widening that dictionary to five categories; **that
proposal was walked back the same day, through a live design conversation, and is not this item's
scope**. The final decision, settled in `25-207`:

- **The pool and the category roles do not change at all.** `Creatures` stays the large, centered
  icon; `Foods` stays the badge. No fifth category, no cross-category pairing, no `Ago.Chat.Domain`
  change, no migration.
- **The visual fix is a badge composition, not a pair side by side**: the creature large and centred
  (`28px`), the food emoji as a small badge overlapping the bottom-right edge (`16px`) with no
  background circle of its own — settled live, several variants compared in a dedicated Artifact
  before landing on this one.
- **A nameless visitor's label reads as words, not a bare pair and a hash**: `Лиса · Апельсин` rather
  than the glyphs themselves, with the short code kept as a small, faint trailing detail rather than
  dropped. The name comes from a client-side i18n table keyed by the emoji glyph itself (copied
  byte-for-byte from `VisitorEmojiDictionary.cs`, to avoid a Unicode variation-selector mismatch) —
  **not a translated field added to `Ago.Chat.Domain`**, which stays free of any language-specific
  content.

This mockup's own avatars were updated to match: every avatar uses the badge composition, every
invalid round-2 placeholder pair (drawn from a five-category set that was never real) was corrected
to an actual `Creatures`/`Foods` member, and every nameless-visitor label shows the localized pair
name instead of a bare pair and hex code.

## One commercial risk that is not technical

`/account/billing` contains a real checkout. Google Play's payments policy normally requires digital
goods sold inside an Android app to go through Play Billing, which takes a cut and does not fit a
per-seat B2B subscription with an invoice. Whether AGO Chat qualifies for the business-software
exemption is a policy judgement, not an engineering one, and getting it wrong is a store removal
rather than a bug.

The plan's answer, chosen to be safe rather than clever: **the Android billing screen reads status
and never takes money.** Tier, seats, administrator slots, current invoice state — all rendered; the
checkout itself opens the console's own checkout in a Custom Tab. That is a deliberate redesign, it
is recorded as one in the inventory, and it can be revisited if the author gets a definitive answer
from Google.

## What "the same functionality" is measured against

`ago-console`'s own current `main`, read route by route on 2026-09-21: 54 routes, of which 18 are
gated on `site:configure` (the `isAdmin` proxy `consoleNav.ts` names), 5 are platform-owner-only, 6
are reachable with no session or no operator seat, and the rest carry a narrower permission of their
own. The inventory lists every one of them.

Nothing in this plan changes who may do what. Every gate the app applies client-side is the same
gate the console applies client-side, for the same stated reason the console gives for it: hiding a
control is UX, and the server's own `IPermissionChecker` check is what actually refuses.
