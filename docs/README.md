# `ago-android` — documentation

This repository will hold **AGO Chat's native Android operator client**. As of this commit it holds
**no application code**, deliberately: `ago-root`'s `docs/backlog/26-00-*.md` is a planning-only
item, and its own sequencing is plan → screens and flows → mockups → the author's approval → *then*
Kotlin. Nothing here authorises a `settings.gradle.kts`.

| Question | File |
|---|---|
| Why this app exists, what ships in what order, and what it depends on that does not exist yet | [`plan.md`](plan.md) |
| Which of `ago-console`'s 54 routes port, which are redesigned, which are excluded, and why | [`scope-inventory.md`](scope-inventory.md) |
| Which screen leads to which, by what action | [`navigation.md`](navigation.md) |
| How the app itself is built — modules, layers, transport, identity, offline | [`architecture.md`](architecture.md) |
| Why Android is a standalone native codebase and no `ago-mobile-common` exists | `ago-root`'s `docs/adr/0178-*.md` |
| What the screens actually look like | the published mockups: <https://claude.ai/code/artifact/8b4fb3a8-ddc0-4b2f-81ce-81d13c30a9d3> |

The mockups are ten Material Design screens — sign-in, the owner-only terminal state, the
conversation list, a thread, the visitor context sheet, the pending-bookings queue, confirmed
bookings, the Ещё list, attachment storage under contextual multi-select, and the tablet two-pane
layout — drawn against the product's own palette and type from `ago-console/src/design/tokens.css`.
They are a review surface, not a specification: where a mockup and this documentation disagree, the
documentation is what was decided.

Everything in this repository is public. The same rule `ago-root/CLAUDE.md` states applies here
verbatim: no secret, no token, no node address, nobody's data — including in a mockup's placeholder
text.

## What this app is not

It is not a second product. It is a second **client** for AGO Chat's existing public API, on the
same footing as `ago-console` and `ago-widget`: it holds no domain logic that the server does not
already own, it invents no permission of its own, and it adds nothing to `Ago.Chat.*`. Where a
capability is gated, it is gated by the identical permission the server already checks — never by a
mobile-only check written here (`ago-root`'s `docs/architecture/authorization.md`).
