package ago.chat.android.di

import ago.chat.android.BuildConfig
import ago.chat.android.core.domain.analytics.OwnAnalyticsApi
import ago.chat.android.core.domain.bookings.BookingsApi
import ago.chat.android.core.domain.conversations.ComposerDraftStore
import ago.chat.android.core.domain.conversations.ConversationListCache
import ago.chat.android.core.domain.conversations.ConversationsApi
import ago.chat.android.core.domain.identity.ActiveSiteSelection
import ago.chat.android.core.domain.identity.IdentityApi
import ago.chat.android.core.domain.identity.PostSignInRouter
import ago.chat.android.core.domain.permissions.OperatorPermissionsApi
import ago.chat.android.core.domain.team.OperatorTeamApi
import ago.chat.android.core.network.analytics.KtorOwnAnalyticsApi
import ago.chat.android.core.network.auth.AccessTokenProvider
import ago.chat.android.core.network.bookings.KtorBookingsApi
import ago.chat.android.core.network.conversations.KtorConversationsApi
import ago.chat.android.core.network.createAgoHttpClient
import ago.chat.android.core.network.identity.KtorIdentityApi
import ago.chat.android.core.network.permissions.KtorOperatorPermissionsApi
import ago.chat.android.core.network.realtime.OperatorHubConnection
import ago.chat.android.core.network.realtime.OperatorHubEvents
import ago.chat.android.core.network.team.KtorOperatorTeamApi
import ago.chat.android.data.AgoChatDatabase
import ago.chat.android.data.conversations.ConversationRowDao
import ago.chat.android.data.conversations.RoomConversationListCache
import ago.chat.android.data.thread.ComposerDraftDao
import ago.chat.android.data.thread.RoomComposerDraftStore
import ago.chat.android.session.AgoActiveSite
import ago.chat.android.session.AgoAuthSession
import ago.chat.android.session.DataStoreThemePreferences
import ago.chat.android.session.OidcConfig
import ago.chat.android.signin.SignInSession
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

    @Provides
    public fun provideConversationsApi(
        client: HttpClient,
        config: OidcConfig,
    ): ConversationsApi = KtorConversationsApi(client, config.apiBaseUrl)

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
}
