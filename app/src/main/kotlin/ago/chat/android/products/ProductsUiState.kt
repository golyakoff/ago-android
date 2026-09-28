package ago.chat.android.products

import ago.chat.android.core.domain.net.NetworkFailure

/**
 * `26-249` (`ago-console`'s own `ProductsPage`, ported): Ещё → Администрирование → «Продукты» — every
 * product this platform offers, marked with whether this workspace already holds it. Read-only by the
 * platform's own design: `adr/0151` and `decisions.md` §6 make enabling a product owner-only (a runbook,
 * not a self-service write), so this screen has no enable/provision control at all — only the console's
 * own «Уже есть»/«Пока нет» status and the "contact AGO" note for a product the tenant lacks.
 *
 * **No fetch of its own kind.** The console reads its held-module keys straight off the same
 * `usePermissions().enabledModules` it already resolves once per session, rather than a second
 * purpose-built read. This app has no equivalent single already-resolved source, so it reuses the read
 * that already exists — [ago.chat.android.core.domain.modules.ModulesApi.fetch], the identical
 * `GET /api/v1/sites/{siteId}/modules` the «База знаний» screen reads — and derives product held-ness
 * from its module keys, exactly as the console's own `buildRows` derives it from `enabledModules`. No
 * new port: the derivation is a UI concern over an existing read, so it lives in [ProductsViewModel],
 * not behind a second network abstraction.
 *
 * The identical three-arm Loading/Loaded/Failed vocabulary every other single-read screen in this app
 * already uses ([ago.chat.android.faq.ModulesFaqUiState] is the closest sibling — a
 * `site:configure`-gated read that renders a list or a plain "ask us" note, never a mutation of its own).
 */
public sealed interface ProductsUiState {
    public data object Loading : ProductsUiState

    /** Every product this platform offers, in the console's own catalogue order (chat · calendar · faq),
     * each flagged [ProductHolding.held]. Never empty — the catalogue is fixed and the base product
     * ([Product.Chat]) is always held, the identical always-present posture the console's own `chat` row
     * states ("reaching this screen at all means an operator seat exists"). */
    public data class Loaded(
        val products: List<ProductHolding>,
    ) : ProductsUiState

    public data class Failed(
        val reason: NetworkFailure,
    ) : ProductsUiState
}

/** One product and whether this workspace holds it — the app-side shape of the console's own `ProductRow`
 * reduced to what a read-only view needs (no `action` link, since `26-249`'s brief is a status view and
 * `decisions.md` §6 forbids a control that looks like it provisions). */
public data class ProductHolding(
    val product: Product,
    val held: Boolean,
)

/** The platform's product catalogue, `ago-console`'s own `buildRows` order. A closed enum rather than the
 * raw module keys the wire carries: the copy a reader sees ("Чат", "Календарь", …) never shows a module
 * key, the identical rule the console's own `ProductsPage` doc comment states ("`buildRows` is the one
 * place either raw string is read"). [ProductsViewModel] maps the wire's `"calendar"`/`"faq"` keys onto
 * [Calendar]/[Faq]; [ago.chat.android.products.ProductsScreen] maps each entry onto its own name and
 * description resources. */
public enum class Product {
    /** The base product — always held (`vision.md`: the conversation substrate "is present in every
     * combination that has been described, without exception"). */
    Chat,

    /** Held when the wire's enabled-module list carries the `"calendar"` key. */
    Calendar,

    /** Held when the wire's enabled-module list carries the `"faq"` key. */
    Faq,
}
