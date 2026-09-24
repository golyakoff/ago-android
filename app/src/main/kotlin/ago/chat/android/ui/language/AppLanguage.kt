package ago.chat.android.ui.language

/**
 * `26-92`: the app's own three interface-language states — [ago.chat.android.ui.theme.ThemeMode]'s
 * tri-state shape, ported: [System] means "follow whatever the device is set to", [Russian]/[English]
 * are a manual override, and there is no fourth value because `26-91` shipped resources for exactly
 * these two languages (`docs/backlog/26-92-*.md`'s own Out of scope: this item wires a mechanism for
 * two languages, not an N-language framework). [localeTag] is `null` for [System] — there is no locale
 * to name when the choice is "don't override anything" — and a real BCP 47 tag
 * ([java.util.Locale.forLanguageTag]) for the other two.
 *
 * **Not the widget's own per-site language.** This enum, and everything that reads or writes it
 * ([AppLanguagePreferences], [ago.chat.android.session.DataStoreAppLanguagePreferences],
 * [applyAppLanguage]), governs only this *app's* own interface — button labels, screen titles, system
 * messages, the `values`/`values-en` resource sets `26-91` produced. The language a visitor sees inside
 * the chat widget on a shop's own site is a completely separate setting, configured per site in
 * `ago-console`, stored server-side, and never read or written from anywhere in `ago-android` at all —
 * a future reader who finds "language" in this codebase and assumes it is that setting would be
 * looking at the wrong repository as well as the wrong file.
 */
public enum class AppLanguage(
    public val localeTag: String?,
) {
    System(localeTag = null),
    Russian(localeTag = "ru"),
    English(localeTag = "en"),
}
