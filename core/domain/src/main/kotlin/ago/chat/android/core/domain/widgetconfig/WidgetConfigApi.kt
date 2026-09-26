package ago.chat.android.core.domain.widgetconfig

import ago.chat.android.core.domain.net.NetworkFailure

/**
 * `26-193` (`docs/design/tenant-widget-android.md` §4.1): the port behind the «Виджет на сайте» area —
 * the widget's own on-site appearance/behaviour/consent settings, `GET`/`PUT
 * /api/v1/sites/{siteId}/widget-config`, gated `site:configure` on both verbs *inside the handler*, the
 * same permission the row itself is gated on — unlike the token channels, there is no rail-vs-server gap
 * to design around here. Declared here, implemented in `:core:network` (`KtorWidgetConfigApi`) — the
 * identical split [ago.chat.android.core.domain.installation.InstallationApi] already establishes: a
 * view model holding an `HttpClient` directly could not be tested without one, and every HTTP-shaped
 * decision (which status means what) belongs on the far side of this interface, in the adapter.
 *
 * **[update] always carries the whole [WidgetConfig], never a subset.** The server's own update request
 * binds a missing `bool` to `false`, and [WidgetConfig.requireContactConsent] is a live consent gate the
 * server refuses to leave unspecified on any write for exactly that reason — so [WidgetConfig] itself
 * carries all 16 fields with none optional-away, and this port has no method that accepts a slice of the
 * config. The "start from the committed whole, overwrite only one screen's fields" discipline that keeps
 * every [update] call complete lives one layer up, in the app's shared `WidgetConfigViewModel` — this
 * port only guarantees that *if* a caller hands it a complete [WidgetConfig], nothing about the call can
 * drop a field.
 */
public interface WidgetConfigApi {
    /** `GET /api/v1/sites/{siteId}/widget-config`. */
    public suspend fun fetch(): WidgetConfigResult

    /** `PUT /api/v1/sites/{siteId}/widget-config`, body = the complete [config]. */
    public suspend fun update(config: WidgetConfig): WidgetConfigWriteResult
}

/** `Position`, `BottomRight`|`BottomLeft` on the wire — the launcher's corner. */
public enum class WidgetPosition {
    BottomRight,
    BottomLeft,
}

/** `Locale`, `En`|`Ru` on the wire — the widget's own UI language, independent of both the operator app's
 * own language and the visitor's browser locale. Its label at the call site is the language's endonym
 * (`English`/`Русский`), **never translated** — the same choice `ago-console`'s own `LOCALE_LABELS`
 * makes, because translating "English" defeats the point of naming it for a reader who may not read the
 * app's own language. */
public enum class WidgetLocale {
    En,
    Ru,
}

/**
 * `autoOpenDelaySeconds` on the wire — a plain `int`, not a member name the way the other four enums
 * here cross. [seconds] is the wire value in both directions:
 * [ago.chat.android.core.network.widgetconfig.KtorWidgetConfigApi] writes it verbatim on a save and reads
 * an unrecognised value back as [Seconds30] (the server's own default) rather than failing to decode —
 * the same "old client, new server value, keep working" tolerance every wire enum in this app already
 * extends to an unrecognised *string* member.
 */
public enum class WidgetAutoOpenDelay(
    public val seconds: Int,
) {
    Seconds15(15),
    Seconds30(30),
    Seconds45(45),
    Seconds60(60),
    Seconds90(90),
    Seconds120(120),
    ;

    public companion object {
        /** An unrecognised [seconds] value — never observed from a well-behaved server, but a future
         * server-side value this client predates — falls back to [Seconds30], the server's own default,
         * rather than throwing through a read this screen would otherwise render as a crash. */
        public fun fromSeconds(seconds: Int): WidgetAutoOpenDelay = entries.firstOrNull { it.seconds == seconds } ?: Seconds30
    }
}

/** `ChannelSwitcherPlacement`, `AboveComposer`|`BelowLauncher` on the wire — where a visitor's connected
 * channels appear in the widget. */
public enum class ChannelSwitcherPlacement {
    AboveComposer,
    BelowLauncher,
}

/** `ChannelSwitcherIconSize`, `Large`|`Medium`|`Small` on the wire — the switcher's icon size when
 * [ChannelSwitcherPlacement.BelowLauncher]. Always present and always sent, even while
 * [ChannelSwitcherPlacement.AboveComposer] makes it invisible to a visitor, so a hidden field's current
 * value still round-trips unchanged through an editor that never showed it. */
public enum class ChannelSwitcherIconSize {
    Large,
    Medium,
    Small,
}

/**
 * The whole `WidgetConfigDto`/`UpdateWidgetConfigRequest` shape (`docs/design/tenant-widget-android.md`
 * §1.1) — all 16 fields, none optional-away, so a value of this type can always round-trip through
 * [WidgetConfigApi.update] without the adapter having to invent a value for a field this app's own UI
 * never touched. The three group editors this feature carries (Внешний вид built in `26-193`, Поведение
 * и приветствие/Согласие и запись in `W2`/`W3`) each edit only their own slice and build a save as
 * `committed.copy(<slice>)` — never a freshly-constructed [WidgetConfig] — so no field this app has
 * already loaded can go missing from a save.
 */
public data class WidgetConfig(
    val primaryColorHex: String?,
    val position: WidgetPosition,
    val locale: WidgetLocale,
    val panelTitle: String?,
    val attractAttention: Boolean,
    val autoOpenEnabled: Boolean,
    val autoOpenDelay: WidgetAutoOpenDelay,
    val autoOpenGreetingText: String?,
    val channelSwitcherPlacement: ChannelSwitcherPlacement,
    val channelSwitcherIconSize: ChannelSwitcherIconSize,
    val noticeText: String?,
    val noticeUrl: String?,
    val requireContactConsent: Boolean,
    val contactCaptureConfirmationText: String?,
    val acceptUnverifiedPhone: Boolean,
    val allowAttachmentUploadsByDefault: Boolean,
)

/** What reading the widget config came back with — the identical two-arm shape
 * [ago.chat.android.core.domain.tags.TagVocabularyResult] already establishes for a site-scoped read:
 * every cause of "the read failed" renders as the same one banner, so there is no reason to split this
 * further than the value it carries. */
public sealed interface WidgetConfigResult {
    public data class Loaded(
        val config: WidgetConfig,
    ) : WidgetConfigResult

    public data class Failed(
        val reason: NetworkFailure,
    ) : WidgetConfigResult
}

/** What saving the widget config came back with — the identical three-arm shape
 * [ago.chat.android.core.domain.contactdetails.ContactDetailWriteResult] already establishes for a write
 * that can be genuinely refused (a colour/notice-URL/greeting-text validation failure, here). */
public sealed interface WidgetConfigWriteResult {
    /** A `2xx` carrying the server's own echo of the saved config. The view model re-seeds its committed
     * config from *this*, never from the [WidgetConfig] it sent — a server-side normalisation is never
     * silently missed that way. */
    public data class Saved(
        val config: WidgetConfig,
    ) : WidgetConfigWriteResult

    /** A non-2xx whose body carried a genuine RFC 7807 `detail`, shown to the operator verbatim; nothing
     * was written, and the draft on screen is kept. */
    public data class Refused(
        val detail: String,
    ) : WidgetConfigWriteResult

    /** Everything that is not a genuine server refusal — a dropped connection, or a non-2xx whose body
     * carried no `detail` to show. */
    public data class Failed(
        val reason: NetworkFailure,
    ) : WidgetConfigWriteResult
}
