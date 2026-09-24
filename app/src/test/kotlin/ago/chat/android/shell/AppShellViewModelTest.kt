package ago.chat.android.shell

import ago.chat.android.core.domain.net.NetworkFailure
import ago.chat.android.core.domain.permissions.OperatorPermissions
import ago.chat.android.core.domain.permissions.OperatorPermissionsApi
import ago.chat.android.core.domain.permissions.PermissionsFetch
import ago.chat.android.data.conversations.ConversationsUnreadTotal
import ago.chat.android.presence.OperatorPresenceController
import ago.chat.android.session.OperatorIdentity
import ago.chat.android.session.OperatorIdentityProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * `26-16`: the "not loaded yet" vs "loaded and holds nothing" distinction this item's own Done-when
 * names, proven at the one class that actually reads the session's permission set. A plain JVM test,
 * the same `StandardTestDispatcher` + `Dispatchers.setMain` shape `ConversationListViewModelTest`/
 * `SignInViewModelTest` already establish.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppShellViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `before the fetch answers, permissions is Unknown, never an invented empty grant set`() =
        runTest(dispatcher) {
            val viewModel = viewModelWith(FakeOperatorPermissionsApi(hang = true))

            dispatcher.scheduler.runCurrent()

            assertEquals(OperatorPermissions.Unknown, viewModel.permissions.value)
            assertNull(viewModel.loadError.value)
        }

    @Test
    fun `a successful fetch with an empty grant set is Known, not Unknown - the whole point of the distinction`() =
        runTest(dispatcher) {
            val viewModel = viewModelWith(FakeOperatorPermissionsApi(result = PermissionsFetch.Loaded(emptySet())))

            advanceUntilIdle()

            assertEquals(OperatorPermissions.Known(emptySet()), viewModel.permissions.value)
        }

    @Test
    fun `a successful fetch carries exactly the granted permissions, verbatim`() =
        runTest(dispatcher) {
            val granted = setOf("calendar:configure", "customer:read")
            val viewModel = viewModelWith(FakeOperatorPermissionsApi(result = PermissionsFetch.Loaded(granted)))

            advanceUntilIdle()

            assertEquals(OperatorPermissions.Known(granted), viewModel.permissions.value)
        }

    @Test
    fun `a failed fetch stays Unknown and surfaces a retry, never a guessed grant set`() =
        runTest(dispatcher) {
            val viewModel =
                viewModelWith(FakeOperatorPermissionsApi(result = PermissionsFetch.Failed(NetworkFailure.ServerError(503))))

            advanceUntilIdle()

            assertEquals(OperatorPermissions.Unknown, viewModel.permissions.value)
            assertEquals(NetworkFailure.ServerError(503), viewModel.loadError.value)
        }

    @Test
    fun `retry re-asks and can recover from a failure into a real answer`() =
        runTest(dispatcher) {
            val api = FakeOperatorPermissionsApi(result = PermissionsFetch.Failed(NetworkFailure.NoConnection))
            val viewModel = viewModelWith(api)
            advanceUntilIdle()
            assertEquals(OperatorPermissions.Unknown, viewModel.permissions.value)

            api.result = PermissionsFetch.Loaded(setOf("site:configure"))
            viewModel.retry()
            advanceUntilIdle()

            assertEquals(OperatorPermissions.Known(setOf("site:configure")), viewModel.permissions.value)
            assertNull(viewModel.loadError.value)
        }

    /** `26-85`: `OperatorPresenceController` decides whether `OperatorPresenceService` runs from
     * exactly the permission set this view model already fetched - never a second, independent read of
     * the same endpoint. */
    @Test
    fun `a successful fetch hands the exact granted set to OperatorPresenceController`() =
        runTest(dispatcher) {
            val granted = setOf("conversation:send")
            val presenceController = FakeOperatorPresenceController()
            viewModelWith(FakeOperatorPermissionsApi(result = PermissionsFetch.Loaded(granted)), presenceController = presenceController)

            advanceUntilIdle()

            assertEquals(listOf(OperatorPermissions.Known(granted)), presenceController.permissionsLoadedCalls)
        }

    /** A failed fetch never resolves `permissions` past `Unknown` (the test above this one already
     * proves that) - and it must not tell `OperatorPresenceController` anything either, since there is
     * no real answer yet to gate a foreground service on. */
    @Test
    fun `a failed fetch never calls OperatorPresenceController at all`() =
        runTest(dispatcher) {
            val presenceController = FakeOperatorPresenceController()
            viewModelWith(
                FakeOperatorPermissionsApi(result = PermissionsFetch.Failed(NetworkFailure.NoConnection)),
                presenceController = presenceController,
            )

            advanceUntilIdle()

            assertEquals(emptyList<OperatorPermissions>(), presenceController.permissionsLoadedCalls)
        }

    @Test
    fun `identity starts null and resolves to whatever the identity provider answers`() =
        runTest(dispatcher) {
            val viewModel =
                viewModelWith(
                    FakeOperatorPermissionsApi(hang = true),
                    identityProvider =
                        FakeOperatorIdentityProvider(
                            OperatorIdentity(displayName = "Андрей Голяков", email = "a@example.com"),
                        ),
                )

            assertNull(viewModel.identity.value)

            advanceUntilIdle()

            assertEquals(OperatorIdentity(displayName = "Андрей Голяков", email = "a@example.com"), viewModel.identity.value)
        }

    /** `26-77`: [ago.chat.android.session.OperatorIdentityProvider.currentIdentity] can genuinely
     * answer `null` (no ID token claims at all) - proven distinctly from "not read yet" above, since
     * both render as `null` here but must never be conflated by a future reader of this class. */
    @Test
    fun `identity stays null when the provider itself has nothing to say`() =
        runTest(dispatcher) {
            val viewModel = viewModelWith(FakeOperatorPermissionsApi(hang = true), identityProvider = FakeOperatorIdentityProvider(null))

            advanceUntilIdle()

            assertNull(viewModel.identity.value)
        }

    // -------------------------------------------------------------------- `26-46`: unread total badge

    @Test
    fun `unreadConversationsTotal starts at whatever the source already holds`() =
        runTest(dispatcher) {
            val viewModel =
                viewModelWith(
                    FakeOperatorPermissionsApi(hang = true),
                    unreadTotal = FakeConversationsUnreadTotal(initial = null),
                )

            assertNull(viewModel.unreadConversationsTotal.value)
        }

    @Test
    fun `unreadConversationsTotal relays a live update from the source, without a restart`() =
        runTest(dispatcher) {
            val unreadTotal = FakeConversationsUnreadTotal(initial = null)
            val viewModel = viewModelWith(FakeOperatorPermissionsApi(hang = true), unreadTotal = unreadTotal)
            advanceUntilIdle()
            assertNull(viewModel.unreadConversationsTotal.value)

            unreadTotal.emit(3)
            advanceUntilIdle()

            assertEquals(3, viewModel.unreadConversationsTotal.value)
        }

    @Test
    fun `unreadConversationsTotal can go back to zero - a real answer, never re-rendered as unknown`() =
        runTest(dispatcher) {
            val unreadTotal = FakeConversationsUnreadTotal(initial = 2)
            val viewModel = viewModelWith(FakeOperatorPermissionsApi(hang = true), unreadTotal = unreadTotal)
            advanceUntilIdle()
            assertEquals(2, viewModel.unreadConversationsTotal.value)

            unreadTotal.emit(0)
            advanceUntilIdle()

            assertEquals(0, viewModel.unreadConversationsTotal.value)
        }

    private fun viewModelWith(
        api: OperatorPermissionsApi,
        identityProvider: OperatorIdentityProvider = FakeOperatorIdentityProvider(null),
        presenceController: OperatorPresenceController = FakeOperatorPresenceController(),
        unreadTotal: ConversationsUnreadTotal = FakeConversationsUnreadTotal(),
    ): AppShellViewModel =
        AppShellViewModel(
            api = api,
            identityProvider = identityProvider,
            presenceController = presenceController,
            unreadTotal = unreadTotal,
            ioDispatcher = dispatcher,
        )

    private class FakeOperatorPermissionsApi(
        var result: PermissionsFetch = PermissionsFetch.Loaded(emptySet()),
        private val hang: Boolean = false,
    ) : OperatorPermissionsApi {
        override suspend fun fetchMyPermissions(): PermissionsFetch {
            if (hang) kotlinx.coroutines.awaitCancellation()
            return result
        }
    }

    private class FakeOperatorIdentityProvider(
        private val identity: OperatorIdentity?,
    ) : OperatorIdentityProvider {
        override suspend fun currentIdentity(): OperatorIdentity? = identity
    }

    /** `26-85`: records every [OperatorPermissions] this view model hands to
     * [ago.chat.android.presence.OperatorPresenceController] - the one thing `AppShellViewModelTest`
     * needs to prove about the new wiring, with no `Service`, no `Context`, and no real gating logic
     * (`DefaultOperatorPresenceControllerTest`'s own job) in play at all. */
    private class FakeOperatorPresenceController : OperatorPresenceController {
        val permissionsLoadedCalls = mutableListOf<OperatorPermissions>()
        override val requestBatteryOptimizationExemptionEvents: Flow<Unit> = emptyFlow()

        override fun onPermissionsLoaded(permissions: OperatorPermissions) {
            permissionsLoadedCalls += permissions
        }

        override fun onSignedOut() {
            error("not exercised here - AppShellViewModel never calls this")
        }
    }

    /** `26-46`: a plain [MutableStateFlow] rather than a fake Room database - this class's own job is
     * proving [AppShellViewModel.unreadConversationsTotal] relays whatever [ConversationsUnreadTotal]
     * says, live, into a `StateFlow` a Compose caller can collect; `RoomConversationListCacheTest`
     * (androidTest) is what proves the real `SUM`/`InvalidationTracker` mechanics behind it. */
    private class FakeConversationsUnreadTotal(
        initial: Int? = null,
    ) : ConversationsUnreadTotal {
        private val total = MutableStateFlow(initial)

        fun emit(value: Int?) {
            total.value = value
        }

        override fun observeTotal(): Flow<Int?> = total
    }
}
