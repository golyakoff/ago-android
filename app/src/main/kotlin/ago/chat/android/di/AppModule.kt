package ago.chat.android.di

import ago.chat.android.BuildConfig
import ago.chat.android.core.domain.analytics.BookingFunnelReportApi
import ago.chat.android.core.domain.analytics.ConversionReportApi
import ago.chat.android.core.domain.analytics.OwnAnalyticsApi
import ago.chat.android.core.domain.analytics.SiteAnalyticsApi
import ago.chat.android.core.domain.analytics.TagBreakdownReportApi
import ago.chat.android.core.domain.bookings.BookingsApi
import ago.chat.android.core.domain.contactdetails.ContactDetailsApi
import ago.chat.android.core.domain.conversations.ComposerDraftStore
import ago.chat.android.core.domain.conversations.ConversationListCache
import ago.chat.android.core.domain.conversations.ConversationsApi
import ago.chat.android.core.domain.devices.DeviceRegistrationApi
import ago.chat.android.core.domain.devices.InstallationIdProvider
import ago.chat.android.core.domain.devices.PushProvider
import ago.chat.android.core.domain.identity.ActiveSiteSelection
import ago.chat.android.core.domain.identity.IdentityApi
import ago.chat.android.core.domain.identity.PostSignInRouter
import ago.chat.android.core.domain.notes.ConversationNotesApi
import ago.chat.android.core.domain.permissions.OperatorPermissionsApi
import ago.chat.android.core.domain.schedule.WorkingHoursApi
import ago.chat.android.core.domain.tags.ConversationTagsApi
import ago.chat.android.core.domain.team.OperatorTeamApi
import ago.chat.android.core.network.analytics.KtorBookingFunnelReportApi
import ago.chat.android.core.network.analytics.KtorConversionReportApi
import ago.chat.android.core.network.analytics.KtorOwnAnalyticsApi
import ago.chat.android.core.network.analytics.KtorSiteAnalyticsApi
import ago.chat.android.core.network.analytics.KtorTagBreakdownReportApi
import ago.chat.android.core.network.auth.AccessTokenProvider
import ago.chat.android.core.network.bookings.KtorBookingsApi
import ago.chat.android.core.network.contactdetails.KtorContactDetailsApi
import ago.chat.android.core.network.conversations.KtorConversationsApi
import ago.chat.android.core.network.createAgoHttpClient
import ago.chat.android.core.network.devices.KtorDeviceRegistrationApi
import ago.chat.android.core.network.identity.KtorIdentityApi
import ago.chat.android.core.network.notes.KtorConversationNotesApi
import ago.chat.android.core.network.permissions.KtorOperatorPermissionsApi
import ago.chat.android.core.network.realtime.HubConnectionControl
import ago.chat.android.core.network.realtime.OperatorHubConnection
import ago.chat.android.core.network.realtime.OperatorHubEvents
import ago.chat.android.core.network.schedule.KtorWorkingHoursApi
import ago.chat.android.core.network.tags.KtorConversationTagsApi
import ago.chat.android.core.network.team.KtorOperatorTeamApi
import ago.chat.android.data.AgoChatDatabase
import ago.chat.android.data.conversations.ConversationRowDao
import ago.chat.android.data.conversations.ConversationsUnreadTotal
import ago.chat.android.data.conversations.RoomConversationListCache
import ago.chat.android.data.thread.ComposerDraftDao
import ago.chat.android.data.thread.RoomComposerDraftStore
import ago.chat.android.devices.AndroidBatteryOptimizationChecker
import ago.chat.android.devices.AndroidNotificationChannelStateReader
import ago.chat.android.devices.AndroidNotificationPermissionChecker
import ago.chat.android.devices.AppForegroundTracker
import ago.chat.android.devices.AutostartAdvisor
import ago.chat.android.devices.BatteryAwarenessPromptPreferences
import ago.chat.android.devices.BatteryOptimizationChecker
import ago.chat.android.devices.ConversationRefreshSignal
import ago.chat.android.devices.DataStoreBatteryAwarenessPromptPreferences
import ago.chat.android.devices.DataStoreInstallationId
import ago.chat.android.devices.DataStorePushMessageDedupeStore
import ago.chat.android.devices.DataStoreQuietHoursPreferences
import ago.chat.android.devices.DefaultConversationRefreshSignal
import ago.chat.android.devices.DefaultOpenConversationTracker
import ago.chat.android.devices.DeviceRegistrar
import ago.chat.android.devices.DeviceRegistrationCoordinator
import ago.chat.android.devices.DeviceRegistrationScheduler
import ago.chat.android.devices.DeviceRevocation
import ago.chat.android.devices.FcmPushGateway
import ago.chat.android.devices.LocalClock
import ago.chat.android.devices.ManufacturerAutostartAdvisor
import ago.chat.android.devices.NotificationChannelStateReader
import ago.chat.android.devices.NotificationPermissionChecker
import ago.chat.android.devices.OpenConversationTracker
import ago.chat.android.devices.ProcessLifecycleForegroundTracker
import ago.chat.android.devices.PushMessageDedupeStore
import ago.chat.android.devices.PushNotificationPresenter
import ago.chat.android.devices.PushRegistrationGateway
import ago.chat.android.devices.QuietHoursPreferences
import ago.chat.android.devices.RuStorePushGateway
import ago.chat.android.devices.SystemLocalClock
import ago.chat.android.devices.SystemPushNotificationPresenter
import ago.chat.android.devices.TransportSelector
import ago.chat.android.devices.WorkManagerDeviceRegistrationScheduler
import ago.chat.android.presence.AndroidBatteryOptimizationGate
import ago.chat.android.presence.AndroidForegroundServiceLauncher
import ago.chat.android.presence.BatteryOptimizationGate
import ago.chat.android.presence.DefaultOperatorPresenceController
import ago.chat.android.presence.DefaultOperatorPresenceGate
import ago.chat.android.presence.ForegroundServiceLauncher
import ago.chat.android.presence.OperatorPresenceController
import ago.chat.android.presence.OperatorPresenceGate
import ago.chat.android.session.AgoActiveSite
import ago.chat.android.session.AgoAuthSession
import ago.chat.android.session.DataStoreAppLanguagePreferences
import ago.chat.android.session.DataStoreThemePreferences
import ago.chat.android.session.OidcConfig
import ago.chat.android.session.OperatorIdentityProvider
import ago.chat.android.session.appLanguageDataStore
import ago.chat.android.shell.DefaultPendingConversationOpener
import ago.chat.android.shell.PendingConversationOpener
import ago.chat.android.signin.SignInSession
import ago.chat.android.ui.language.AppLanguagePreferences
import ago.chat.android.ui.theme.ThemePreferences
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import java.io.File
import javax.inject.Qualifier
import javax.inject.Singleton

/**
 * Which dispatcher the disk and network work runs on. A qualifier rather than a bare
 * `CoroutineDispatcher` binding, because a second one (`@DefaultDispatcher`, for CPU work) is the
 * ordinary next addition and an unqualified binding would have to be renamed the day it arrives.
 * Injecting it at all — rather than calling `Dispatchers.IO` inside the classes that need it — is
 * what makes those classes testable without a real thread pool.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
public annotation class IoDispatcher

/**
 * `26-06`: which `DataStore<Preferences>` binding is the installation-id file, since
 * [provideThemeDataStore] already claims the unqualified `DataStore<Preferences>` type for `26-17`'s
 * own preference - the identical "two providers of the same type need a qualifier" reason
 * [IoDispatcher] states above, applied to a second `DataStore` file rather than a second dispatcher.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
public annotation class DeviceDataStore

/**
 * `26-92`: a third `DataStore<Preferences>` file, the identical "two providers of the same type need a
 * qualifier" reason [DeviceDataStore] above already states — see [DataStoreAppLanguagePreferences]'s own
 * doc comment for why this preference gets its own file rather than joining [provideThemeDataStore]'s.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
public annotation class LanguageDataStore

/**
 * `26-12`: the whole object graph, in the one module allowed to hold it.
 *
 * Read top to bottom it is also the shape of this item: a config, an encrypted session, a client
 * that attaches the session's token and the chosen tenancy to every request, an adapter over three
 * endpoints, and a router that reads their answers. Nothing here is a factory for a factory — every
 * `@Provides` below exists because some constructor takes an interface that `:core:*` declares and
 * `:app` implements, which is the ports-and-adapters seam `docs/architecture.md` draws between the
 * modules.
 *
 * **Why `@Provides` and not `@Inject` constructors in `:core:*`.** Putting `@Inject` on
 * `KtorIdentityApi` or `PostSignInRouter` would make both core modules depend on Dagger's
 * annotations, which would be a Hilt dependency inside the module whose defining property is that it
 * has no Android or framework dependency at all (`adr/0178`). The cost is this file; the benefit is
 * that `:core:domain` stays a plain Kotlin module a KMP `commonMain` could absorb unchanged.
 */
@Module
@InstallIn(SingletonComponent::class)
public object AppModule {
    @Provides
    @IoDispatcher
    public fun provideIoDispatcher(): CoroutineDispatcher = Dispatchers.IO

    @Provides
    @Singleton
    public fun provideOidcConfig(): OidcConfig =
        OidcConfig(
            issuer = BuildConfig.AGO_KEYCLOAK_ISSUER,
            clientId = BuildConfig.AGO_OIDC_CLIENT_ID,
            redirectUri = BuildConfig.AGO_OIDC_REDIRECT_URI,
            postLogoutRedirectUri = BuildConfig.AGO_OIDC_POST_LOGOUT_REDIRECT_URI,
            apiBaseUrl = BuildConfig.AGO_API_BASE_URL,
            consoleUrl = BuildConfig.AGO_CONSOLE_URL,
            calendarApiBaseUrl = BuildConfig.AGO_CALENDAR_API_BASE_URL,
        )

    @Provides
    @Singleton
    public fun provideActiveSiteSelection(activeSite: AgoActiveSite): ActiveSiteSelection = activeSite

    @Provides
    @Singleton
    public fun provideAccessTokenProvider(session: AgoAuthSession): AccessTokenProvider = session

    @Provides
    @Singleton
    public fun provideSignInSession(session: AgoAuthSession): SignInSession = session

    /**
     * `26-77`: [ago.chat.android.shell.AppShellViewModel]'s own port for the account menu's header —
     * see [OperatorIdentityProvider]'s own doc comment for why this is a third view onto the same
     * `AgoAuthSession` singleton rather than a new call to anything.
     */
    @Provides
    @Singleton
    public fun provideOperatorIdentityProvider(session: AgoAuthSession): OperatorIdentityProvider = session

    /**
     * One client for the whole app. Both request-shaping plugins are installed by
     * `installAgoRestDefaults`, so there is no second way to build an authenticated client and
     * therefore no way for a later API module to be the one that forgot the tenancy header.
     */
    @Provides
    @Singleton
    public fun provideHttpClient(
        accessTokens: AccessTokenProvider,
        activeSite: ActiveSiteSelection,
    ): HttpClient = createAgoHttpClient(accessTokens, activeSite)

    @Provides
    @Singleton
    public fun provideIdentityApi(
        client: HttpClient,
        config: OidcConfig,
    ): IdentityApi = KtorIdentityApi(client, config.apiBaseUrl)

    @Provides
    public fun providePostSignInRouter(
        identity: IdentityApi,
        activeSite: ActiveSiteSelection,
    ): PostSignInRouter = PostSignInRouter(identity, activeSite)

    /**
     * `26-16`: [ago.chat.android.shell.AppShellViewModel]'s own port — a second adapter over the
     * identical `GET /api/v1/operators/me` endpoint [provideIdentityApi]'s [KtorIdentityApi] already
     * calls for its status alone. See [OperatorPermissionsApi]'s own doc comment for why this is a
     * deliberate second call rather than a widened [IdentityApi], following `ago-console`'s own
     * precedent for the identical endpoint.
     */
    @Provides
    public fun provideOperatorPermissionsApi(
        client: HttpClient,
        config: OidcConfig,
    ): OperatorPermissionsApi = KtorOperatorPermissionsApi(client, config.apiBaseUrl)

    /**
     * `26-13`: the app's one `/hubs/operator` connection. A `@Singleton` for the identical reason
     * [provideHttpClient] above is one — "one connection per signed-in session" is true because there
     * is exactly one instance in this graph, not by any screen's own discipline
     * (`OperatorHubConnection`'s own doc comment). Built from `config.apiBaseUrl` the same way
     * `provideIdentityApi` above derives its own base URL — one deployment, read once, here.
     */
    @Provides
    @Singleton
    public fun provideOperatorHubConnection(
        accessTokens: AccessTokenProvider,
        activeSite: ActiveSiteSelection,
        config: OidcConfig,
    ): OperatorHubConnection =
        OperatorHubConnection(
            hubUrl = "${config.apiBaseUrl}/hubs/operator",
            accessTokens = accessTokens,
            activeSite = activeSite,
        )

    /**
     * `26-14`: [ConversationListViewModel][ago.chat.android.conversations.ConversationListViewModel]
     * depends on the narrow [OperatorHubEvents] interface, not the concrete [OperatorHubConnection],
     * precisely so a test can substitute a fake — see that interface's own doc comment. Hilt needs this
     * one extra binding because a `@Provides` returning the concrete type does not by itself satisfy an
     * injection point asking for the interface; the underlying instance is still the identical
     * `@Singleton` `OperatorHubConnection` `provideOperatorHubConnection` above builds; this is a second
     * *view* onto that one graph node, not a second connection.
     */
    @Provides
    @Singleton
    public fun provideOperatorHubEvents(connection: OperatorHubConnection): OperatorHubEvents = connection

    /**
     * `26-85`: [ago.chat.android.realtime.OperatorHubConnectionLifecycle]'s own port — the identical
     * "second view onto the one `@Singleton` graph node" shape [provideOperatorHubEvents] above already
     * establishes, restated for the connect/disconnect half instead of the read half.
     */
    @Provides
    @Singleton
    public fun provideHubConnectionControl(connection: OperatorHubConnection): HubConnectionControl = connection

    // `26-85`: everything `OperatorPresenceService`'s own gating needs, each a small `@Singleton` seam
    // for the identical reason [providePushRegistrationGateway] below already is one - see each
    // interface's own doc comment for what a plain-JVM test substitutes it with.

    @Provides
    @Singleton
    public fun provideOperatorPresenceGate(gate: DefaultOperatorPresenceGate): OperatorPresenceGate = gate

    /** `26-85`: the one binding that *does* carry a `Context` - [AndroidForegroundServiceLauncher]'s own
     * doc comment states why it is a separate class from [ago.chat.android.presence.OperatorPresenceController]
     * rather than one more method on it, the identical split [provideDeviceRegistrationScheduler] below
     * already draws for [WorkManagerDeviceRegistrationScheduler]. */
    @Provides
    @Singleton
    public fun provideForegroundServiceLauncher(launcher: AndroidForegroundServiceLauncher): ForegroundServiceLauncher = launcher

    @Provides
    @Singleton
    public fun provideBatteryOptimizationGate(gate: AndroidBatteryOptimizationGate): BatteryOptimizationGate = gate

    @Provides
    @Singleton
    public fun provideOperatorPresenceController(controller: DefaultOperatorPresenceController): OperatorPresenceController = controller

    @Provides
    public fun provideConversationsApi(
        client: HttpClient,
        config: OidcConfig,
    ): ConversationsApi = KtorConversationsApi(client, config.apiBaseUrl)

    /**
     * `26-115`: the contact-detail panel's own list-and-reveal port — `config.apiBaseUrl`, the same
     * `Ago.Chat.Api` origin [provideConversationsApi] above already reads, since the contact-details
     * endpoints live on that same host, not the calendar's.
     */
    @Provides
    public fun provideContactDetailsApi(
        client: HttpClient,
        config: OidcConfig,
    ): ContactDetailsApi = KtorContactDetailsApi(client, config.apiBaseUrl)

    /**
     * `26-115`: the contact-detail panel's own tags port. Needs [ActiveSiteSelection] in addition to the
     * `apiBaseUrl` [provideContactDetailsApi] above already reads — the site vocabulary read is
     * `{siteId}`-scoped in the URL itself, the identical shape [provideOperatorTeamApi] below already
     * threads [ActiveSiteSelection] through for.
     */
    @Provides
    public fun provideConversationTagsApi(
        client: HttpClient,
        config: OidcConfig,
        activeSite: ActiveSiteSelection,
    ): ConversationTagsApi = KtorConversationTagsApi(client, config.apiBaseUrl, activeSite)

    /**
     * `26-115`: the contact-detail panel's own «Заметки команды» port — the same `apiBaseUrl`
     * [provideContactDetailsApi] above reads, since notes are one more endpoint on that same origin.
     */
    @Provides
    public fun provideConversationNotesApi(
        client: HttpClient,
        config: OidcConfig,
    ): ConversationNotesApi = KtorConversationNotesApi(client, config.apiBaseUrl)

    /**
     * `26-57`: [ago.chat.android.analytics.AnalyticsViewModel]'s own port — `config.apiBaseUrl`, the
     * identical chat API base URL [provideConversationsApi] above already reads, since
     * `GET /api/v1/conversations/analytics/me` is one more endpoint on that same origin — no new base
     * URL, unlike [provideBookingsApi] below.
     */
    @Provides
    public fun provideOwnAnalyticsApi(
        client: HttpClient,
        config: OidcConfig,
    ): OwnAnalyticsApi = KtorOwnAnalyticsApi(client, config.apiBaseUrl)

    /**
     * `26-70`: [ago.chat.android.analytics.SiteAnalyticsViewModel]'s own port — the same
     * `config.apiBaseUrl` [provideOwnAnalyticsApi] above reads, since
     * `GET /api/v1/conversations/analytics` is the site-wide sibling of the personal endpoint on that
     * same origin. A second `@Provides` rather than a second method behind one binding, because the two
     * are deliberately separate ports ([SiteAnalyticsApi]'s own doc comment): the personal report needs
     * only a real operator identity server-side, this one needs `site:configure`.
     */
    @Provides
    public fun provideSiteAnalyticsApi(
        client: HttpClient,
        config: OidcConfig,
    ): SiteAnalyticsApi = KtorSiteAnalyticsApi(client, config.apiBaseUrl)

    /**
     * `26-71`: [ago.chat.android.analytics.ConversionReportViewModel]'s own port — the same
     * `config.apiBaseUrl` [provideSiteAnalyticsApi] above reads, since
     * `GET /api/v1/conversations/conversion-report` is one more endpoint on that same origin. A third
     * `@Provides` rather than a third method behind one binding, for the identical reason
     * [provideSiteAnalyticsApi]'s own doc comment gives for keeping the site report separate from the
     * personal one ([ConversionReportApi]'s own doc comment).
     */
    @Provides
    public fun provideConversionReportApi(
        client: HttpClient,
        config: OidcConfig,
    ): ConversionReportApi = KtorConversionReportApi(client, config.apiBaseUrl)

    /**
     * `26-72`: [ago.chat.android.analytics.TagBreakdownReportViewModel]'s own port — the same
     * `config.apiBaseUrl` [provideConversionReportApi] above reads, since
     * `GET /api/v1/conversations/tag-breakdown-report` is one more endpoint on that same origin. A
     * fourth `@Provides` rather than a fourth method behind one binding, for the identical reason
     * [provideConversionReportApi]'s own doc comment gives for keeping each report's port separate
     * ([TagBreakdownReportApi]'s own doc comment).
     */
    @Provides
    public fun provideTagBreakdownReportApi(
        client: HttpClient,
        config: OidcConfig,
    ): TagBreakdownReportApi = KtorTagBreakdownReportApi(client, config.apiBaseUrl)

    /**
     * `26-73`: [ago.chat.android.analytics.BookingFunnelReportViewModel]'s own port — the same
     * `config.apiBaseUrl` [provideTagBreakdownReportApi] above reads, since
     * `GET /api/v1/conversations/module-flow-report` is served by `Ago.Chat`, not the calendar API
     * [provideBookingsApi] below reads (`docs/backlog/26-73-*.md`'s own Scope item 1). A fifth
     * `@Provides` rather than a fifth method behind one binding, for the identical reason
     * [provideTagBreakdownReportApi]'s own doc comment gives for keeping each report's port separate
     * ([BookingFunnelReportApi]'s own doc comment).
     */
    @Provides
    public fun provideBookingFunnelReportApi(
        client: HttpClient,
        config: OidcConfig,
    ): BookingFunnelReportApi = KtorBookingFunnelReportApi(client, config.apiBaseUrl)

    /**
     * `26-48`: [KtorBookingsApi] always constructs — even when [OidcConfig.calendarApiBaseUrl] is
     * `null` — because the "not configured" case is answered *inside* the adapter
     * ([KtorBookingsApi]'s own doc comment), the same shape `ago-console`'s `requireBaseUrl()` already
     * establishes: a nullable base URL is a fact the adapter's first line checks, not a reason for this
     * module to hand out a nullable [BookingsApi] Hilt would have to be taught to inject.
     */
    @Provides
    public fun provideBookingsApi(
        client: HttpClient,
        config: OidcConfig,
    ): BookingsApi = KtorBookingsApi(client, config.calendarApiBaseUrl)

    /**
     * `26-97`: the working-hours correction port. A second `@Provides` against the same calendar
     * origin rather than three more methods on [BookingsApi], for the reason [WorkingHoursApi]'s own
     * doc comment gives - those are reads of what is on the calendar, these are configuration writes
     * on a different noun behind a different server-side gate. Always constructs, including when
     * [OidcConfig.calendarApiBaseUrl] is `null`, the identical reason [provideBookingsApi] states.
     */
    @Provides
    public fun provideWorkingHoursApi(
        client: HttpClient,
        config: OidcConfig,
    ): WorkingHoursApi = KtorWorkingHoursApi(client, config.calendarApiBaseUrl)

    /**
     * `26-55`: [ago.chat.android.team.PeopleViewModel]'s own port — plain REST on the chat API this app
     * already talks to, no new base URL ([KtorOperatorTeamApi]'s own doc comment states why
     * [ActiveSiteSelection] is threaded through here rather than left implicit, the way
     * [provideConversationsApi] above can leave it).
     */
    @Provides
    public fun provideOperatorTeamApi(
        client: HttpClient,
        config: OidcConfig,
        activeSite: ActiveSiteSelection,
    ): OperatorTeamApi = KtorOperatorTeamApi(client, config.apiBaseUrl, activeSite)

    /**
     * `26-14`: Room's first database in this app — one `@Singleton` file for the process's whole life,
     * the identical "one instance in the graph is what makes it true" reasoning [provideHttpClient] and
     * [provideOperatorHubConnection] above already state for their own singletons.
     * [ago.chat.android.data.AgoChatDatabase] is `internal`, and Kotlin forbids a `public` function from
     * exposing a less-visible type in its own signature — the same rule stated in full beside
     * [provideConversationListCache] below — so this provider is `internal` too, which costs nothing
     * since Hilt's generated component lives in this same module's own compilation.
     *
     * `26-15`: `fallbackToDestructiveMigration()` — see [ago.chat.android.data.AgoChatDatabase]'s own
     * doc comment on `version = 2` for why a wipe-and-recreate is the honest choice here rather than a
     * hand-written `Migration`, and why that will not always be true.
     *
     * `25-214`: Room 2.8 deprecated the no-argument overload in favour of one that says out loud
     * whether tables Room does not manage are dropped too. `true` is Room's own recommended value
     * *and* behaviourally identical here: this file is created by Room, holds only the three
     * entities [ago.chat.android.data.AgoChatDatabase] declares, and has no hand-made table for the
     * `false` (legacy) behaviour to spare.
     */
    @Provides
    @Singleton
    internal fun provideAgoChatDatabase(
        @ApplicationContext context: Context,
    ): AgoChatDatabase =
        Room
            .databaseBuilder(context, AgoChatDatabase::class.java, "ago-chat.db")
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()

    @Provides
    internal fun provideConversationRowDao(database: AgoChatDatabase): ConversationRowDao = database.conversationRowDao()

    // `internal`, not `public`: the parameter type (`RoomConversationListCache`) is itself `internal`,
    // and Kotlin forbids a `public` signature from exposing a less-visible type. Hilt's generated
    // component code is part of this same Gradle module's own compilation, so `internal` here is no
    // narrower than what Hilt actually needs to see.
    @Provides
    internal fun provideConversationListCache(cache: RoomConversationListCache): ConversationListCache = cache

    // `26-46`: the identical `RoomConversationListCache` singleton, bound to its second, `:app`-level
    // port - see [ConversationsUnreadTotal]'s own doc comment for why this one is not declared in
    // `:core:domain` beside [ConversationListCache] above. `internal`, for the identical reason
    // [provideConversationListCache] above already is: the parameter type (`RoomConversationListCache`)
    // is itself `internal`, regardless of [ConversationsUnreadTotal] (the return type) being `public`.
    @Provides
    internal fun provideConversationsUnreadTotal(cache: RoomConversationListCache): ConversationsUnreadTotal = cache

    @Provides
    internal fun provideComposerDraftDao(database: AgoChatDatabase): ComposerDraftDao = database.composerDraftDao()

    // `26-15`: the identical `internal` reasoning [provideConversationListCache] above states, for the
    // composer's own port.
    @Provides
    internal fun provideComposerDraftStore(store: RoomComposerDraftStore): ComposerDraftStore = store

    /**
     * `26-17`: the Тема preference's own file — see [ThemePreferences]'s own doc comment for why
     * `androidx.datastore` rather than Room or plain `SharedPreferences`. `context.filesDir`, not
     * `context.dataDir` or a raw path string: it is the one location every other on-device store in
     * this app already resolves through a framework accessor rather than a hand-typed path
     * (`SessionStore`'s own `EncryptedSharedPreferences.create`, `provideAgoChatDatabase`'s own
     * `Room.databaseBuilder`), and it survives an app update but not an uninstall — the correct
     * lifetime for a UI preference, the same lifetime `SessionStore`'s own file has.
     */
    @Provides
    @Singleton
    public fun provideThemeDataStore(
        @ApplicationContext context: Context,
    ): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(
            produceFile = { File(context.filesDir, "theme.preferences_pb") },
        )

    @Provides
    @Singleton
    public fun provideThemePreferences(preferences: DataStoreThemePreferences): ThemePreferences = preferences

    /**
     * `26-92`: the Язык preference's own file — [DataStoreAppLanguagePreferences]'s own doc comment
     * states why it is not [provideThemeDataStore] above. Built through [appLanguageDataStore] rather
     * than a second `PreferenceDataStoreFactory.create` call typed out here, so this provider and
     * [ago.chat.android.MainActivity.attachBaseContext]'s own bootstrap read can never name two different
     * paths for what must be the same file.
     */
    @Provides
    @Singleton
    @LanguageDataStore
    public fun provideAppLanguageDataStore(
        @ApplicationContext context: Context,
    ): DataStore<Preferences> = appLanguageDataStore(context)

    @Provides
    @Singleton
    public fun provideAppLanguagePreferences(preferences: DataStoreAppLanguagePreferences): AppLanguagePreferences = preferences

    /**
     * `26-06`: [DataStoreInstallationId]'s own file - deliberately not [provideThemeDataStore] above
     * (see [DataStoreInstallationId]'s own doc comment for why it needs a file `SessionStore.clear()`
     * never touches) and deliberately not `SessionStore` itself (that store is encrypted for a
     * credential this id is not, and `clear()`-on-sign-out is precisely the behaviour this value must
     * not inherit).
     */
    @Provides
    @Singleton
    @DeviceDataStore
    public fun provideDeviceDataStore(
        @ApplicationContext context: Context,
    ): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(
            produceFile = { File(context.filesDir, "device.preferences_pb") },
        )

    @Provides
    @Singleton
    public fun provideInstallationIdProvider(installationId: DataStoreInstallationId): InstallationIdProvider = installationId

    /**
     * `26-06`/`26-100`: the device-registration half over the shared authenticated `HttpClient` every
     * other `:core:network` adapter uses - `KtorDeviceRegistrationApi`'s own doc comment states why
     * `"android"` alone is a literal inside it, `provider` no longer one since `adr/0181`.
     */
    @Provides
    public fun provideDeviceRegistrationApi(
        client: HttpClient,
        config: OidcConfig,
    ): DeviceRegistrationApi = KtorDeviceRegistrationApi(client, config.apiBaseUrl)

    /**
     * `26-100`/`adr/0181`: the selecting binding - [TransportSelector] decides once, at graph-
     * construction time, which concrete gateway this app's one `@Singleton` [PushRegistrationGateway]
     * resolves to for the rest of this process's life (that class's own doc comment: "decided once, at
     * the DI boundary, not re-asked by every caller"). Both concrete gateways are themselves
     * `@Singleton` and do nothing at construction time, so requesting both here and using only one costs
     * nothing measurable either way.
     */
    @Provides
    @Singleton
    public fun providePushRegistrationGateway(
        selector: TransportSelector,
        fcmGateway: FcmPushGateway,
        ruStoreGateway: RuStorePushGateway,
    ): PushRegistrationGateway =
        when (selector.selectedProvider()) {
            PushProvider.Fcm -> fcmGateway
            PushProvider.RuStore -> ruStoreGateway
        }

    /**
     * `26-06`: two bindings onto the identical `@Singleton` [DeviceRegistrationCoordinator] instance -
     * the same "a second binding is a second *view*, not a second graph node" shape
     * [provideOperatorHubEvents] above already establishes for [OperatorHubConnection]/`OperatorHubEvents`.
     * [AgoAuthSession] depends on [DeviceRevocation] (as a `dagger.Lazy`, to break the injection cycle
     * that interface's own doc comment on `AgoAuthSession`'s constructor explains); [SignInViewModel]
     * depends on [DeviceRegistrar]. Neither depends on the concrete coordinator directly, which is
     * what keeps both testable on a plain JVM with no Android runtime behind them.
     */
    @Provides
    @Singleton
    public fun provideDeviceRevocation(coordinator: DeviceRegistrationCoordinator): DeviceRevocation = coordinator

    @Provides
    @Singleton
    public fun provideDeviceRegistrar(coordinator: DeviceRegistrationCoordinator): DeviceRegistrar = coordinator

    /** `26-06`: the one binding that *does* carry a `Context` - [WorkManagerDeviceRegistrationScheduler]'s
     * own doc comment states why it is a separate class from [DeviceRegistrationCoordinator] rather than
     * one more method on it. */
    @Provides
    @Singleton
    public fun provideDeviceRegistrationScheduler(scheduler: WorkManagerDeviceRegistrationScheduler): DeviceRegistrationScheduler =
        scheduler

    // `26-18`: everything the receive path needs, each a small `@Singleton` seam for the identical
    // reason [providePushRegistrationGateway] above already is one - see each interface's own doc
    // comment for what a plain-JVM test substitutes it with.

    @Provides
    @Singleton
    public fun provideAppForegroundTracker(tracker: ProcessLifecycleForegroundTracker): AppForegroundTracker = tracker

    @Provides
    @Singleton
    public fun provideOpenConversationTracker(tracker: DefaultOpenConversationTracker): OpenConversationTracker = tracker

    @Provides
    @Singleton
    public fun provideConversationRefreshSignal(signal: DefaultConversationRefreshSignal): ConversationRefreshSignal = signal

    /** `26-18`: [DataStorePushMessageDedupeStore]'s own file - [provideDeviceDataStore] above, the
     * identical `device.preferences_pb` [DataStoreInstallationId] already writes to - see that class's
     * own doc comment for why a dedicated third file is not worth it for this value either. */
    @Provides
    @Singleton
    public fun providePushMessageDedupeStore(store: DataStorePushMessageDedupeStore): PushMessageDedupeStore = store

    @Provides
    @Singleton
    public fun providePushNotificationPresenter(presenter: SystemPushNotificationPresenter): PushNotificationPresenter = presenter

    @Provides
    @Singleton
    public fun provideNotificationPermissionChecker(checker: AndroidNotificationPermissionChecker): NotificationPermissionChecker = checker

    /** `26-128`: Settings → «Режим работы»'s own live read - see [BatteryOptimizationChecker]'s own doc
     * comment for why this is a second port rather than reusing `26-85`'s own `BatteryOptimizationGate`. */
    @Provides
    @Singleton
    public fun provideBatteryOptimizationChecker(checker: AndroidBatteryOptimizationChecker): BatteryOptimizationChecker = checker

    /** `26-128`: Settings → «Автозапуск»'s own manufacturer-based guess - see [AutostartAdvisor]'s own
     * doc comment for why this can never be a real system read. */
    @Provides
    @Singleton
    public fun provideAutostartAdvisor(advisor: ManufacturerAutostartAdvisor): AutostartAdvisor = advisor

    /** `26-128`: the first-launch battery/autostart sheet's own "don't show again" flag -
     * [provideDeviceDataStore] above, the identical `device.preferences_pb`
     * [DataStoreInstallationId]/[DataStoreQuietHoursPreferences] already write to - see
     * [DataStoreBatteryAwarenessPromptPreferences]'s own doc comment for why a dedicated file is not
     * worth it for this one flag either. */
    @Provides
    @Singleton
    public fun provideBatteryAwarenessPromptPreferences(
        preferences: DataStoreBatteryAwarenessPromptPreferences,
    ): BatteryAwarenessPromptPreferences = preferences

    // `26-19`: the notification-settings screen's own three ports - each a small `@Singleton` seam for
    // the identical reason every other framework call in this app sits behind one (rule 2).

    @Provides
    @Singleton
    public fun provideNotificationChannelStateReader(reader: AndroidNotificationChannelStateReader): NotificationChannelStateReader = reader

    @Provides
    @Singleton
    public fun provideLocalClock(clock: SystemLocalClock): LocalClock = clock

    /** `26-19`: [DataStoreQuietHoursPreferences]'s own file - [provideDeviceDataStore] above, the
     * identical `device.preferences_pb` [DataStoreInstallationId]/[DataStorePushMessageDedupeStore]
     * already write to - see that class's own doc comment for why a dedicated file is not worth it for
     * these three fields either. */
    @Provides
    @Singleton
    public fun provideQuietHoursPreferences(preferences: DataStoreQuietHoursPreferences): QuietHoursPreferences = preferences

    /** `26-18`: [MainActivity][ago.chat.android.MainActivity]'s own bridge onto the navigation graph -
     * see [PendingConversationOpener]'s own doc comment for why this is a `StateFlow`-backed singleton
     * rather than a one-shot event. */
    @Provides
    @Singleton
    public fun providePendingConversationOpener(opener: DefaultPendingConversationOpener): PendingConversationOpener = opener
}
