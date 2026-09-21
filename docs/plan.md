# The plan

## What the app is for

`ago-console` is a tool an operator sits in for a whole shift, at a desk. Its own workspace screen
is designed around exactly that (`WorkspaceLayout.tsx`: "one screen an operator can work a shift
in"). The Android app is for the other half of the same person's day — the half where they are not
at the desk and the visitor is still waiting.

That framing is not a licence to ship a thin app. The author's own instruction is that the Android
client reaches the same functionality as `office.reserve-me.ru`, minus what only a platform owner
can reach. This plan honours that: **48 of the console's 54 routes are in scope**, and the six that
are not each carry a stated reason ([`scope-inventory.md`](scope-inventory.md)). What the framing
decides is *order*, not extent — which screens ship first, and which can wait without the app being
dishonest about what it is.

## Three dependencies that do not exist yet

These were found by reading the backend and the deployment, not assumed. Each is outside
`ago-android` and outside `26-00`'s own scope, and each blocks something a reader would reasonably
expect the app to do on day one. They are stated here first because they are the part of this plan
most likely to change what the author wants built.

### 1. There is no push infrastructure anywhere in the product

`Ago.Chat.*` contains no Firebase Cloud Messaging, no device-token table, no APNs, nothing. The
console's alerting (`18-05`, `workspace/alerts.ts` + `useAlerts.ts`) is the browser `Notification`
API, which requires the page to be open and the SignalR connection live.

Ported literally, that gives an Android app which notifies only while it is in the foreground with a
live connection — **a worse notifier than the desktop console**, and the opposite of the one thing a
native app is uniquely good at. Android will kill a background socket; this is not a tuning problem.

So "the operator learns a visitor is waiting while the phone is in their pocket" is a **backend
change** — a device-registration endpoint, an outbox consumer fanning out to FCM, and a per-operator
delivery decision that reuses `alerts.ts`'s own rules rather than inventing a second set. It is its
own backlog item, in `ago-chat`, and it is the single largest piece of work this stage implies that
is not Kotlin.

Until it exists, the app is honest about it: foreground-only alerts, and a Settings screen that says
so rather than offering a switch that cannot keep its promise.

### 2. Keycloak has exactly one public client, and the API validates a single audience

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

### 3. There is no OpenAPI description of the API, anywhere

No `AddOpenApi`, no `MapOpenApi`, no Swashbuckle, no NSwag — in `ago-chat`, `ago-platform` or
`ago-calendar`. Both existing clients hand-write their wire types from `Ago.Chat.Contracts`
(`ago-console/src/realtime/protocol/types.ts`: "it exists so the rest of the console never guesses
field names"; `ago-widget` does the same).

This is load-bearing for the architecture decision (`adr/0178`): "share a *generated* API client
between Android and iOS" is not an option that exists today — there is nothing to generate from, and
creating it means changing three .NET hosts. The Android client will hand-write its wire types the
same way both existing clients do, and for the same reason.

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
app someone would actually carry. It is also the set that most needs push (§1 above) to be worth
carrying.

**Phase 2 — the rest of the day-to-day.** Confirmed bookings, clients and the reveal flow, workers,
services, schedule, all-conversations, search, restricted visitors, the team-people screen, and the
two audit trails.

**Phase 3 — configuration and administration.** Channels, automation, documents, storage, products,
export, billing (read-only — see below), account deletion, the calendar setup screens. These are the
screens somebody sets up once and revisits rarely; they are last because being last costs almost
nothing, not because they are excluded.

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
