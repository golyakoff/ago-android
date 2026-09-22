package ago.chat.android.ui.theme

/**
 * `26-17`: the three states [AgoChatTheme]'s own doc comment already named as "a future settings
 * screen's own call to wire up" — the console's own `useTheme`/`ThemeToggle` system/light/dark choice,
 * ported. [System] means "follow `isSystemInDarkTheme()`", exactly what [AgoChatTheme] already defaults
 * to; [Light]/[Dark] are the manual override that value never had anywhere to come from until now.
 *
 * Deliberately not a `Boolean?` (`null` = system): a tri-state enum says what each state *means* at
 * every call site, where a nullable boolean would need a comment wherever it is read to say which
 * `null` stands for — the same "name the state, don't encode it" reasoning [OperatorHubConnectionState]
 * already applies to a much larger state machine.
 */
public enum class ThemeMode {
    System,
    Light,
    Dark,
}
