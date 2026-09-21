package ago.chat.android.core.domain

/**
 * Every id in this product is a GUID, and no screen ever prints one in full: the convention,
 * applied at a dozen call sites in the console and asserted by its tests, is the first eight
 * characters in a monospace face (`ago-android/docs/architecture.md`, "How an identifier is
 * rendered" — `visitorId.slice(0, 8)`, `operatorId.slice(0, 8)`, and so on).
 *
 * It lives in `:core:domain` because it is a rule the product itself owns — not a detail of any
 * one screen — and every screen module that needs it calls this rather than reimplementing
 * `.take(8)` at its own call site, the same reasoning that keeps a shared vocabulary in one
 * place instead of duplicated per caller.
 */
public fun shortId(id: String): String = id.take(8)
