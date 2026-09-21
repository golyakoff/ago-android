# Screen-by-screen port inventory

Read from `ago-console`'s own `src/App.tsx`, `src/shell/consoleNav.ts` and each page's own doc
comment on 2026-09-21 — **54 routes**, not a description of what the console does.

Each row is marked one of three ways:

- **as-is** — the same screen, the same controls, the same gate, re-expressed in Material components.
  "As-is" never means "a web view"; it means nothing about the *information architecture* changes.
- **redesign** — the screen ports, but its layout does not survive a 400dp-wide viewport and the row
  says exactly what changes and why.
- **excluded** — not built for Android, with a reason.

The gate column is the permission the *console* already checks, and the Android app checks the same
one for the same stated purpose: deciding what to draw. The server's own `IPermissionChecker` check
is the actual refusal in every case (`ago-root`'s `docs/architecture/authorization.md`).

---

## 1. Pre-session and account bootstrap (6 routes)

| Route | Gate | Disposition | Notes |
|---|---|---|---|
| `/callback` | none | **mechanism, not a screen** | An OIDC redirect has no UI on Android — AppAuth returns the authorization result from a Custom Tab. What *does* port is the logic behind it: `CallbackPage`'s three-way precedence (existing operator → platform owner → fresh registrant, `adr/0063`) becomes the app's own post-authentication router. See §2 for why dropping its owner arm would be a defect. |
| `/signup` | none | **redesign** | The console needs this as a *route* only because `RequireAuth` redirects an anonymous visitor straight to Keycloak, leaving no unauthenticated page to hang a "Sign up" link on (`SignupPage.tsx`'s own reasoning). An app has such a surface by construction — its launch screen — so this becomes a button there, not a destination. |
| `/policies/:documentKey` | none | **as-is** | A read-only render of an immutable published version. Reached before any account exists, so it must work signed-out; `?version=` still resolves to that exact text. |
| `/invite/:code` | none | **redesign** | Its whole purpose is "a stranger opens a link they were sent". On Android that is an **App Link**: the same URL opens the app when installed and the console's own page when not. Three facts, one card — which shop, from whom, that it expires — and nothing more, because the server deliberately returns nothing more. |
| `/onboarding` | session only | **as-is** | Registering a new site. A short form; nothing about it is wide. |
| `/redeem-invite` | session only | **as-is** | One code field and a submit. Pre-filled when arrived at via the App Link above. |

## 2. Platform owner (5 routes) — all excluded

The author's escape hatch was "port it as-is if carving it out turns out cheaper or safer than
excluding it". **It does not apply here, and the reason is structural rather than a matter of
taste.** These five routes are already outside the operator layout in the console: they mount their
own string provider (`OwnerStringsProvider`, deliberately English regardless of any tenant's
locale), call their own API module (`ownerApi.ts`), model their own three-state access answer, and
share not one component with the operator shell. There is nothing tangled to carve. Excluding them
is *not building a self-contained feature*; porting them is strictly more work, not less.

| Route | Disposition | Reason (per the item's own requirement that each owner screen states one) |
|---|---|---|
| `/owner` | **excluded** | The cross-tenant site list. It is the entry point to the four below and has no standalone value once they are gone. |
| `/owner/sites/:siteId` | **excluded** | The only owner screen carrying **cross-tenant writes** — module grant and revoke (`adr/0098`), including `23-13`'s force-and-reason override (`adr/0118`). The grant additionally requires the **deployment-wide provisioning secret** in the request body (`adr/0095`). Typing a deployment-wide secret into a phone keyboard is the one thing this plan will not propose, whatever the convenience. |
| `/owner/pricing` | **excluded** | A read of the platform's own price list. Consulted when a price is being set or argued about — desk work, performed rarely, with no time pressure that a phone would relieve. |
| `/owner/suspensions` | **excluded** | Suspending a tenant is an enforcement action against somebody's business (`adr/0166`). Its cost of being wrong is high and its urgency is low: exactly the inverse of what earns a place on a phone. |
| `/owner/tenant-isolation` | **excluded** | Five headline figures read from two APIs, consulted while auditing. A dashboard, read at a desk, with no action attached. |

**One piece of owner logic ports anyway, and must.** `CallbackPage` asks the owner question as part
of *sign-in routing*, not as owner-screen code: it probes `GET /api/v1/owner/sites?limit=1` and
routes an accepted caller to `/owner`, because `GET /api/v1/operators/me` answers `403` for a
platform owner and for a fresh registrant alike (`adr/0063`). An Android app that drops the probe
reproduces `12-04`'s exact defect — it would show an owner the **site-registration form**, whose
button commits a `Site`, its roles and an `Operator` row with no un-register path in the product.

So the app runs the identical probe, and an owner-only identity (no operator seat) lands on a
**terminal screen** saying the platform-owner console is web-only, with a link. That screen has no
console equivalent; it is listed in §11.

## 3. Диалоги — conversations (5 routes)

| Route | Gate | Disposition | Notes |
|---|---|---|---|
| `/` + `/conversations/:conversationId` | none / assignment | **redesign — the biggest one** | The console's workspace is a three-region grid: conversation list, open thread, visitor context panel. On a phone this is **two screens and a sheet**: the list, the thread, and the visitor context as an expandable **Material bottom sheet** over the thread. The sheet, not a third route, because the panel is reference material consulted *while* answering — routing to it would lose the composer draft, which is the exact loss `11-06` restructured the console to prevent. On a tablet or unfolded foldable, `ListDetailPaneScaffold` restores the console's own two-pane shape and the sheet becomes the third pane. The routing contract is unchanged: a conversation is still a real, linkable, restorable destination. |
| `/conversations/all` | `site:configure` | **redesign** | A five-column table (visitor, state, assigned operator, started, unread) becomes a card list with a filter chip row. One thing must be *visibly* true rather than a dead tap: an admin here gets read-only summary data and still cannot open another operator's thread (`authorization.md`, `5-08`) — so a row is not tappable-into-a-thread, and says why. |
| `/conversations/search` | `site:configure` | **redesign** | The search field moves into the top app bar; the keyset `Load more` becomes infinite scroll. The jump-to-hit behaviour ports unchanged, bounded loop and all (`ConversationPage`'s `locateSequence`, 40 hops). |
| `/conversations/restricted` | `site:configure` | **as-is** | A list of restricted visitors with a lift action, each kind drawn with its own tone. |

## 4. Записи — calendar (10 routes)

| Route | Gate | Disposition | Notes |
|---|---|---|---|
| `/calendar/waiting` | `calendar:configure` **or** any of `booking:confirm`/`reject`/`cancel` | **redesign (cards)** | The pending queue. **The app's second reason to exist**, after the thread: everything auto-confirms unless somebody vetoes it before the deadline, so acting away from a desk has real value. One queue spanning every calendar, no "mine" — unchanged. Table rows become cards with the two actions as buttons. |
| `/calendar/bookings` | `calendar:configure` or `customer:read` | **redesign** | A week × master grid is the one console layout that is fundamentally wide. On Android it becomes **day-first**: a horizontal date strip, then that day's bookings grouped by master. The question the screen answers ("what is on for Thursday") is unchanged; only the axis order is. |
| `/calendar/clients` | `customer:read` | **redesign** | Contact table → card list. The masked-phone **Reveal** control ports unchanged and stays audited (§5). The customer-merge dialog becomes a full-screen dialog with both candidates' booking histories stacked and scrollable, and the "this cannot be taken back" copy kept verbatim — `adr/0161` exists so that fact is *felt*, and shrinking the viewport must not shrink the warning. |
| `/calendar/masters` | `calendar:configure` | **as-is** | No search, no paging, no filter — "ten workers is a lot for this product". Inline confirm-then-commit for delete, not a modal, matching the console. |
| `/calendar/masters/:workerId/slots` | `calendar:configure` | **redesign** | The 14-day slot preview becomes a day-grouped list. Times stay digits-only and locale-invariant, in the business's own zone, exactly as the console renders them. |
| `/calendar/masters/:workerId/recut` | `calendar:configure` | **excluded** | The only exclusion in this document that is about *risk* rather than fit. A three-step destructive flow whose middle step is a per-booking cancel-or-keep decision over an arbitrary number of rows, with no undo — the one place where a small viewport materially raises the chance of a wrong irreversible answer. The app links to the console instead of reproducing it. Reopen if the author disagrees; this is a judgement, not a constraint. |
| `/calendar/services` | `calendar:configure` | **as-is** | List + add/edit, price in whole roubles with the optional "от" flag, optional description. |
| `/calendar/schedule` | `calendar:configure` | **as-is** | Two manual edits — a day off, and a short day. Business-local days, and the server still refuses a day that has a booking on it. |
| `/calendar/setup` | `calendar:configure` | **redesign** | Three short forms (calendars, working hours, embed origins) in one screen become three collapsible sections, each re-reading after every write — no optimistic update, which is the source screen's own rule. The allowed-origins field is webmaster work and sits last. |
| `/calendar/customer-merges` | `calendar:configure` | **as-is** | Audit list with keyset `Load more`. Gate deliberately wider than the merge action itself — unchanged. |

## 5. Аналитика — analytics (6 routes)

| Route | Gate | Disposition | Notes |
|---|---|---|---|
| `/analytics/me` | none (any real operator) | **as-is** | Ungated on purpose — a grant here would be something a tenant could withhold from its own staff. It is also the one analytics screen that genuinely belongs on a phone, and it is in Phase 1 for that reason. |
| `/analytics/site` | `site:configure` | **redesign** | A date-range form plus several tables of three numbers. On Android: a date-range chip row, then stacked stat cards, then one horizontally-scrollable table per breakdown. |
| `/analytics/conversion` | `site:configure` | **redesign** | Same shape as above. |
| `/analytics/tags` | `site:configure` | **redesign** | Same shape as above. |
| `/analytics/booking-flow` | `site:configure` | **redesign** | Same shape as above. |
| `/calendar/phone-reveals` | `calendar:configure` | **as-is** | The reveal audit trail — one row per reveal, never a per-operator count, which is a deliberate restraint rather than an omission. Lives under Analytics in the nav while keeping its `/calendar/` address, as in the console. |

## 6. Команда — team (2 routes)

| Route | Gate | Disposition | Notes |
|---|---|---|---|
| `/team/people` | `site:manage_operators` | **redesign** | A table with four row actions (seat toggle, role change, remove, invite) becomes a card list with a per-row overflow menu. The invite dialog becomes a bottom sheet whose primary action is the **Android share sheet** on the invite URL — strictly better than the console's copy button, since sending the link is the actual goal. Role change and removal keep their confirmations and surface the server's own refusal text verbatim. |
| `/team/chat` | none (every operator) | **as-is in substance, native in form** | Already a messaging surface; the easiest screen in the console to make feel native. Unread badge, message removal for an admin, delta fetch over the hub — all unchanged. |

## 7. Каналы — channels (6 routes)

| Route | Gate (console / server) | Disposition | Notes |
|---|---|---|---|
| `/channels/install` | `site:configure` | **redesign** | The four-state installation headline stays — it is a real health signal, not decoration. The snippet itself gets a **share** action rather than copy-to-clipboard: a phone cannot paste into somebody's website, but it can send the snippet to whoever can. |
| `/channels/widget` | `site:configure` | **redesign** | An appearance form with a colour picker and a live preview. Side-by-side does not fit; the preview becomes its own pinned region above a scrolling form, and the colour picker becomes a Material colour input rather than a desktop swatch grid. |
| `/channels/max` | `site:configure` / `channel:manage` | **as-is** | Connect, status, disconnect. Pasting a bot token is arguably *easier* here than on desktop — the token arrives in a message on the same device. |
| `/channels/telegram` | `site:configure` / `channel:manage` | **as-is** | As above. |
| `/channels/vk` | `site:configure` / `channel:manage` | **as-is** | As above. |
| `/channels/email` | `site:configure` | **as-is** | A settings form, not a connect flow — this channel has no per-tenant credential. The tenant logo upload uses the Android photo picker; validation stays server-side (`adr/0177`). |

## 8. Автоматизация — automation (5 routes)

| Route | Gate | Disposition | Notes |
|---|---|---|---|
| `/automation/canned` | `site:configure` | **redesign** | "One blank row kept at the bottom to type into" is a desktop idiom that fails on a soft keyboard. Becomes a list + FAB, with each response edited on its own screen. Order is preserved because the operator arranged it. |
| `/automation/ai-suggestions` | `site:configure` | **as-is** | A switch plus explanatory copy, and the on-screen note that it is the *same* switch `/account/ai` writes — that note must survive the port, because discovering the sharing by surprise is exactly what it exists to prevent. |
| `/automation/auto-reply` | `site:configure` | **redesign** | Keyword rules where **order is behaviour** (first rule wins). The trailing-blank-row editor becomes a reorderable list with drag handles, and the ordering rule stays stated on screen. |
| `/automation/faq` | `site:configure` | **as-is** | Module registration and knowledge-base management. |
| `/automation/tags` | `site:configure` | **redesign** | Same list + FAB shape as canned responses. Vocabulary only — applying a tag happens in the conversation sheet, as in the console. |

## 9. Администрирование — administration (8 routes)

| Route | Gate | Disposition | Notes |
|---|---|---|---|
| `/account/products` | `site:configure` | **as-is** | What the tenant could buy, and what is enabled. |
| `/account/billing` | `site:configure` | **redesign — reads, never takes money** | Tier, seats, administrator slots and invoice state all render. **Checkout opens the console's own checkout in a Custom Tab.** The reason is Google Play's payments policy, not a technical one; `plan.md` states it in full. This is the one row in this document where the redesign is driven by a distribution rule rather than a viewport. |
| `/account/device-storage` | `site:configure` | **as-is** | Static disclosure rows; the screen fetches nothing, by design. |
| `/account/documents` | `site:configure` | **redesign** | Reading a published version, and the "who accepted what, when" table, port directly. Publishing a new version means a long legal text — kept, since pasting from a phone's clipboard is a real path, but it opens as its own full-screen editor rather than a form inline beside the current version. The acceptance table still never shows the client IP or user agent the record also holds. |
| `/account/ai` | `site:configure` | **as-is** | Three separate controls producing three separately-timestamped records — **never collapsed into one button on a smaller screen**. That separation is the whole point of the screen (`25-04`), and a phone is exactly where the temptation to merge them would arise. |
| `/account/storage` | `site:configure` | **redesign** | Quota bar first, unchanged. The sortable/filterable table with bulk select becomes a list with **long-press multi-select** and a contextual top app bar — the standard Material pattern, and a closer match to the console's own intent than a checkbox column. Filters (including "never downloaded" and "duplicate content") move into a bottom sheet. The pre-confirm total stays. |
| `/account/export` | `site:export` | **as-is, plus one Android affordance** | Request, poll, history. The finished archive is handed to the system download manager rather than held in the app, so it lands somewhere the operator can actually forward it from. |
| `/account/delete` | `site:erase` | **as-is in substance, relocated** | Irreversible destruction of the whole tenant. The flow and its confirmation are unchanged. What changes is reachability: it lives at the bottom of Settings and is never drawn in the primary navigation. That is a deliberate friction, and it is the honest alternative to excluding a capability the operator legitimately holds. |

## 10. Personal (1 route)

| Route | Gate | Disposition | Notes |
|---|---|---|---|
| `/appearance` | none | **redesign** | Folded into an app-level **Settings** screen rather than standing alone, alongside concerns the console has no equivalent for (§11). The three-state system/light/dark choice ports directly onto Material 3 theming; on Android 12+ it sits beside dynamic colour. |

## 11. Screens with no console equivalent

Four, and each exists because of something about Android rather than something about AGO Chat.

| Screen | Why it exists |
|---|---|
| **Sign-in / launch** | The console has none — `RequireAuth` redirects straight to Keycloak. An app needs a surface before authentication: a sign-in button, the "create an account" path `/signup` currently is, and a visible indication of which deployment it is talking to. |
| **Platform-owner terminal screen** | §2. An owner-only identity must land somewhere honest rather than on the site-registration form. |
| **Notification settings** | Android notification channels are an OS-level concept with no web counterpart. Its ancestor is the console's `AlertSettings`, but the mapping is not one-to-one, and until push exists (`plan.md` §1) this screen's job is partly to say what the app cannot yet promise. |
| **Active-site switcher** | One identity can hold seats at several sites (`adr/0068`). The console solves this with a module-level `X-Ago-Active-Site` header and `GET /api/v1/me/tenancies`; the app needs the same header on every REST call and the hub's query-string equivalent, and the switch itself belongs in the navigation drawer header rather than buried in a settings list. |

## Counts

| | |
|---|---|
| Console routes read | **54** |
| In scope for Android | **48** |
| — of which port as-is | 22 |
| — of which port with a mobile redesign | 25 |
| — of which port as a mechanism rather than a screen | 1 (`/callback`) |
| Excluded | **6** (5 platform-owner, 1 the worker re-cut) |
| New screens with no console equivalent | 4 |

The two workspace routes (`/` and `/conversations/:conversationId`) are counted separately here and
described as one row above, because they are one screen in the console and two destinations in the
app.
