package ago.chat.android.bookings

import ago.chat.android.R
import ago.chat.android.core.domain.bookings.BookingsQueueFailure
import ago.chat.android.core.domain.bookings.ConfiguredService
import ago.chat.android.core.domain.bookings.ConfirmationCountdown
import ago.chat.android.core.domain.bookings.PendingBooking
import ago.chat.android.core.domain.bookings.confirmationCountdown
import ago.chat.android.core.domain.calendarsetup.ConfiguredCalendar
import ago.chat.android.core.domain.workers.Worker
import ago.chat.android.core.network.realtime.OperatorHubConnectionState
import ago.chat.android.schedule.WorkingHoursBody
import ago.chat.android.schedule.WorkingHoursUiState
import ago.chat.android.schedule.WorkingHoursViewModel
import ago.chat.android.shell.rememberPendingConversationOpener
import ago.chat.android.ui.components.AccountAvatarAction
import ago.chat.android.ui.components.IdentifierText
import ago.chat.android.ui.components.rememberTickingNow
import ago.chat.android.ui.icons.AgoIcons
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.Duration
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * `26-48`: Записи, for real — replaces `ago.chat.android.shell.BookingsPlaceholderScreen` at
 * `AppShellScreen.kt`'s own `NavHost`. Obtains its own [BookingsViewModel] via [hiltViewModel] — the
 * identical wiring [ago.chat.android.conversations.ConversationListRoute] already establishes for
 * Диалоги.
 */
@Composable
public fun BookingsRoute(
    showConfirmedSegment: Boolean,
    showClientsSegment: Boolean,
    showSetupSegment: Boolean,
    showMastersSegment: Boolean,
    showServicesSegment: Boolean,
    showHoursSegment: Boolean,
    hubConnectionState: OperatorHubConnectionState,
    onOpenSettings: () -> Unit,
    onSignOut: () -> Unit,
    operatorDisplayName: String? = null,
    operatorEmail: String? = null,
    viewModel: BookingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var selectedTab by rememberSaveable { mutableStateOf(BookingsTab.Pending) }

    // `26-51`: [ConfirmedBookingsViewModel] is obtained by `hiltViewModel()` only inside this branch, so
    // an operator lacking `customer:read` never constructs it and never triggers its `init`-time read
    // (that class's own doc comment). Calling a `@Composable` conditionally like this is safe here
    // specifically because `hiltViewModel()` is keyed by the requesting class against the surrounding
    // `NavBackStackEntry`'s own `ViewModelStore`, not by the call's position in the composition, so
    // toggling this branch never recreates the view model underneath it.
    val confirmedState: ConfirmedBookingsUiState?
    val onSelectDay: (String) -> Unit
    val onRetryConfirmed: () -> Unit
    val onRevealConfirmed: (String) -> Unit
    if (showConfirmedSegment) {
        val confirmedViewModel: ConfirmedBookingsViewModel = hiltViewModel()
        val collectedConfirmedState by confirmedViewModel.state.collectAsStateWithLifecycle()
        confirmedState = collectedConfirmedState
        onSelectDay = confirmedViewModel::onDaySelected
        onRetryConfirmed = confirmedViewModel::refresh
        onRevealConfirmed = confirmedViewModel::reveal
    } else {
        confirmedState = null
        onSelectDay = {}
        onRetryConfirmed = {}
        onRevealConfirmed = {}
    }
    // `26-117`: the booking-detail sheet's own «Перейти к диалогу» / chat icon
    // (`docs/backlog/26-117-*.md`'s own "Dialog link") reuses the identical cross-tab thread navigation
    // `26-18`'s own push-tap flow already established, rather than a new navigation mechanism grown just
    // for this screen - [ago.chat.android.shell.PendingConversationOpener]'s own doc comment states why
    // it is a `StateFlow`-backed singleton reachable from any composable (`rememberPendingConversationOpener`
    // reads it straight off the application's own Hilt graph), not a callback threaded down from
    // `AppShellScreen`: `AppShellContent`'s own collector already switches the bottom bar to Диалоги the
    // moment a pending id appears, and `ConversationsTabHost`'s own collector already opens the thread -
    // calling `.open(conversationId)` here is indistinguishable, on the receiving end, from a notification
    // tap. `originConversationId` is `null` on every row today (`ConfirmedBooking`'s own doc comment), so
    // this call is unreachable in practice until `docs/design/26-112-*.md`'s own GAP-C1 ships - the
    // moment it does, this wiring needs no further Android change.
    val pendingConversationOpener = rememberPendingConversationOpener()
    val onOpenDialog: (String) -> Unit = { conversationId -> pendingConversationOpener.open(conversationId) }

    // `26-52`: the identical Hilt-avoidance-when-ungated shape [confirmedState] above already
    // establishes, applied to [ContactsViewModel] for the same reason - an operator lacking both
    // `calendar:configure` and `customer:read` never triggers this read either.
    val contactsState: ContactsUiState?
    val onRetryContacts: () -> Unit
    val onRevealContact: (String) -> Unit
    if (showClientsSegment) {
        val contactsViewModel: ContactsViewModel = hiltViewModel()
        val collectedContactsState by contactsViewModel.state.collectAsStateWithLifecycle()
        contactsState = collectedContactsState
        onRetryContacts = contactsViewModel::refresh
        onRevealContact = contactsViewModel::reveal
    } else {
        contactsState = null
        onRetryContacts = {}
        onRevealContact = {}
    }

    // `26-96`: the identical Hilt-avoidance-when-ungated shape the two branches above establish,
    // applied to [ServicesViewModel] - an operator lacking `calendar:configure` never constructs it and
    // never triggers its `init`-time read of the tenant configuration.
    val servicesState: ServicesUiState?
    val onRetryServices: () -> Unit
    val onEditService: (ConfiguredService) -> Unit
    val onCancelServiceEdit: () -> Unit
    val onServiceDraftChanged: (ServiceDraft) -> Unit
    val onSubmitService: (ServiceDraft) -> Unit
    if (showServicesSegment) {
        val servicesViewModel: ServicesViewModel = hiltViewModel()
        val collectedServicesState by servicesViewModel.state.collectAsStateWithLifecycle()
        servicesState = collectedServicesState
        onRetryServices = servicesViewModel::refresh
        onEditService = servicesViewModel::edit
        onCancelServiceEdit = servicesViewModel::cancelEdit
        onServiceDraftChanged = servicesViewModel::onDraftChanged
        onSubmitService = servicesViewModel::submit
    } else {
        servicesState = null
        onRetryServices = {}
        onEditService = {}
        onCancelServiceEdit = {}
        onServiceDraftChanged = {}
        onSubmitService = {}
    }

    // `26-142`: the identical Hilt-avoidance-when-ungated shape the branches above establish, applied to
    // [CalendarSetupViewModel] - an operator lacking `calendar:configure` never constructs it and never
    // triggers its `init`-time read of the tenant configuration.
    val calendarSetupState: CalendarSetupUiState?
    val onRetryCalendarSetup: () -> Unit
    val onOriginsTextChanged: (String) -> Unit
    val onSaveOrigins: () -> Unit
    val onAddCalendar: () -> Unit
    val onEditCalendar: (ConfiguredCalendar) -> Unit
    val onCalendarFormChanged: (CalendarForm) -> Unit
    val onCancelCalendarEdit: () -> Unit
    val onSubmitCalendar: (CalendarForm) -> Unit
    if (showSetupSegment) {
        val calendarSetupViewModel: CalendarSetupViewModel = hiltViewModel()
        val collectedCalendarSetupState by calendarSetupViewModel.state.collectAsStateWithLifecycle()
        calendarSetupState = collectedCalendarSetupState
        onRetryCalendarSetup = calendarSetupViewModel::refresh
        onOriginsTextChanged = calendarSetupViewModel::onOriginsTextChanged
        onSaveOrigins = calendarSetupViewModel::saveOrigins
        onAddCalendar = calendarSetupViewModel::startAddCalendar
        onEditCalendar = calendarSetupViewModel::editCalendar
        onCalendarFormChanged = calendarSetupViewModel::onCalendarFormChanged
        onCancelCalendarEdit = calendarSetupViewModel::cancelCalendarEdit
        onSubmitCalendar = calendarSetupViewModel::submitCalendar
    } else {
        calendarSetupState = null
        onRetryCalendarSetup = {}
        onOriginsTextChanged = {}
        onSaveOrigins = {}
        onAddCalendar = {}
        onEditCalendar = {}
        onCalendarFormChanged = {}
        onCancelCalendarEdit = {}
        onSubmitCalendar = {}
    }

    // `26-140`: the identical Hilt-avoidance-when-ungated shape the branches above establish, applied to
    // [MastersViewModel] - an operator lacking `calendar:configure` never constructs it and never triggers
    // its `init`-time read of the worker dictionary.
    val mastersState: MastersUiState?
    val onRetryMasters: () -> Unit
    val onAddMaster: () -> Unit
    val onEditMaster: (Worker) -> Unit
    val onToggleMasterActive: (Worker) -> Unit
    val onDeleteMaster: (String) -> Unit
    val onCancelMasterEdit: () -> Unit
    val onMasterFormChanged: (WorkerForm) -> Unit
    val onSubmitMaster: (WorkerForm) -> Unit
    if (showMastersSegment) {
        val mastersViewModel: MastersViewModel = hiltViewModel()
        val collectedMastersState by mastersViewModel.state.collectAsStateWithLifecycle()
        mastersState = collectedMastersState
        onRetryMasters = mastersViewModel::refresh
        onAddMaster = mastersViewModel::startAdd
        onEditMaster = mastersViewModel::edit
        onToggleMasterActive = mastersViewModel::toggleActive
        onDeleteMaster = mastersViewModel::delete
        onCancelMasterEdit = mastersViewModel::cancelEdit
        onMasterFormChanged = mastersViewModel::onFormChanged
        onSubmitMaster = mastersViewModel::submit
    } else {
        mastersState = null
        onRetryMasters = {}
        onAddMaster = {}
        onEditMaster = {}
        onToggleMasterActive = {}
        onDeleteMaster = {}
        onCancelMasterEdit = {}
        onMasterFormChanged = {}
        onSubmitMaster = {}
    }

    // `26-97`: the identical Hilt-avoidance-when-ungated shape the branches above establish - an
    // operator without `calendar:configure` never constructs [WorkingHoursViewModel] and so never
    // triggers its `init`-time read of a configuration document they may not be entitled to.
    val workingHoursState: WorkingHoursUiState?
    val onRetryWorkingHours: () -> Unit
    val onSaveWorkingHours: (String, Int, String, String) -> Unit
    val onDeleteWorkingHours: (String) -> Unit
    if (showHoursSegment) {
        val workingHoursViewModel: WorkingHoursViewModel = hiltViewModel()
        val collectedWorkingHoursState by workingHoursViewModel.state.collectAsStateWithLifecycle()
        workingHoursState = collectedWorkingHoursState
        onRetryWorkingHours = workingHoursViewModel::refresh
        onSaveWorkingHours = workingHoursViewModel::save
        onDeleteWorkingHours = workingHoursViewModel::delete
    } else {
        workingHoursState = null
        onRetryWorkingHours = {}
        onSaveWorkingHours = { _, _, _, _ -> }
        onDeleteWorkingHours = {}
    }

    BookingsScreen(
        state = state,
        showConfirmedSegment = showConfirmedSegment,
        showClientsSegment = showClientsSegment,
        showSetupSegment = showSetupSegment,
        showMastersSegment = showMastersSegment,
        showServicesSegment = showServicesSegment,
        showHoursSegment = showHoursSegment,
        selectedTab = selectedTab,
        onTabSelected = { selectedTab = it },
        onRetry = viewModel::refresh,
        onReject = viewModel::reject,
        onCancel = viewModel::cancel,
        onMarkNoShow = viewModel::markNoShow,
        confirmedState = confirmedState,
        onSelectDay = onSelectDay,
        onRetryConfirmed = onRetryConfirmed,
        onRevealConfirmed = onRevealConfirmed,
        onOpenDialog = onOpenDialog,
        contactsState = contactsState,
        onRetryContacts = onRetryContacts,
        onRevealContact = onRevealContact,
        servicesState = servicesState,
        onRetryServices = onRetryServices,
        onEditService = onEditService,
        onCancelServiceEdit = onCancelServiceEdit,
        onServiceDraftChanged = onServiceDraftChanged,
        onSubmitService = onSubmitService,
        calendarSetupState = calendarSetupState,
        onRetryCalendarSetup = onRetryCalendarSetup,
        onOriginsTextChanged = onOriginsTextChanged,
        onSaveOrigins = onSaveOrigins,
        onAddCalendar = onAddCalendar,
        onEditCalendar = onEditCalendar,
        onCalendarFormChanged = onCalendarFormChanged,
        onCancelCalendarEdit = onCancelCalendarEdit,
        onSubmitCalendar = onSubmitCalendar,
        mastersState = mastersState,
        onRetryMasters = onRetryMasters,
        onAddMaster = onAddMaster,
        onEditMaster = onEditMaster,
        onToggleMasterActive = onToggleMasterActive,
        onDeleteMaster = onDeleteMaster,
        onCancelMasterEdit = onCancelMasterEdit,
        onMasterFormChanged = onMasterFormChanged,
        onSubmitMaster = onSubmitMaster,
        workingHoursState = workingHoursState,
        onRetryWorkingHours = onRetryWorkingHours,
        onSaveWorkingHours = onSaveWorkingHours,
        onDeleteWorkingHours = onDeleteWorkingHours,
        hubConnectionState = hubConnectionState,
        operatorDisplayName = operatorDisplayName,
        operatorEmail = operatorEmail,
        onOpenSettings = onOpenSettings,
        onSignOut = onSignOut,
    )
}

/**
 * The stateless half — see [BookingsRoute]'s own doc comment for why [BookingsRoute] exists at all
 * ([AppShellScreen]'s own `bookingsTab` slot needs a Hilt-free substitute for the back-button-contract
 * tests, the identical reason that file's own `conversationsTab`/`settingsScreen` slots exist).
 *
 * **The segmented control is built from [showConfirmedSegment]/[showClientsSegment], never drawn with a
 * fixed shape.** `26-48` shipped this with exactly one hard-coded [SegmentedButton]; `26-51` replaced
 * that with [visibleBookingsSegments] for Утверждены — the same "compute the list, don't draw a fixed
 * shape" correction `visibleBottomDestinations` already models one level up; `26-52` lands the mockup's
 * third segment, Клиенты, as a third entry in that same function, computed from its own independent
 * gate rather than a third hand-written [SegmentedButton] here.
 *
 * `26-103`: [visibleBookingsSegments] never returns more than three entries any more — five wrapped on a
 * real device once `26-96`/`26-97` each added one. [showServicesSegment]/[showHoursSegment] still arrive
 * here unchanged, but now feed [visibleBookingsConfigMenuEntries] instead, drawn by [BookingsConfigMenu]
 * rather than as a fourth/fifth [SegmentedButton]. `26-109` moved that menu out of the segment row (it
 * was crowding the segments again) and into the `TopAppBar` `actions`, left of the avatar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BookingsScreen(
    state: BookingsUiState,
    showConfirmedSegment: Boolean,
    showClientsSegment: Boolean,
    showSetupSegment: Boolean,
    showMastersSegment: Boolean,
    showServicesSegment: Boolean,
    showHoursSegment: Boolean,
    selectedTab: BookingsTab,
    onTabSelected: (BookingsTab) -> Unit,
    onRetry: () -> Unit,
    onReject: (String) -> Unit,
    onCancel: (String) -> Unit,
    onMarkNoShow: (String) -> Unit,
    confirmedState: ConfirmedBookingsUiState?,
    onSelectDay: (String) -> Unit,
    onRetryConfirmed: () -> Unit,
    onRevealConfirmed: (String) -> Unit,
    onOpenDialog: (String) -> Unit,
    contactsState: ContactsUiState?,
    onRetryContacts: () -> Unit,
    onRevealContact: (String) -> Unit,
    servicesState: ServicesUiState?,
    onRetryServices: () -> Unit,
    onEditService: (ConfiguredService) -> Unit,
    onCancelServiceEdit: () -> Unit,
    onServiceDraftChanged: (ServiceDraft) -> Unit,
    onSubmitService: (ServiceDraft) -> Unit,
    calendarSetupState: CalendarSetupUiState?,
    onRetryCalendarSetup: () -> Unit,
    onOriginsTextChanged: (String) -> Unit,
    onSaveOrigins: () -> Unit,
    onAddCalendar: () -> Unit,
    onEditCalendar: (ConfiguredCalendar) -> Unit,
    onCalendarFormChanged: (CalendarForm) -> Unit,
    onCancelCalendarEdit: () -> Unit,
    onSubmitCalendar: (CalendarForm) -> Unit,
    mastersState: MastersUiState?,
    onRetryMasters: () -> Unit,
    onAddMaster: () -> Unit,
    onEditMaster: (Worker) -> Unit,
    onToggleMasterActive: (Worker) -> Unit,
    onDeleteMaster: (String) -> Unit,
    onCancelMasterEdit: () -> Unit,
    onMasterFormChanged: (WorkerForm) -> Unit,
    onSubmitMaster: (WorkerForm) -> Unit,
    workingHoursState: WorkingHoursUiState?,
    onRetryWorkingHours: () -> Unit,
    onSaveWorkingHours: (String, Int, String, String) -> Unit,
    onDeleteWorkingHours: (String) -> Unit,
    hubConnectionState: OperatorHubConnectionState = OperatorHubConnectionState.Disconnected,
    operatorDisplayName: String? = null,
    operatorEmail: String? = null,
    onOpenSettings: () -> Unit = {},
    onSignOut: () -> Unit = {},
) {
    // `ago-console`'s own `useNow` hook, restated - the one clock read this screen makes, so every
    // deadline countdown on it re-renders together rather than each row reading `OffsetDateTime.now()`
    // on its own recomposition schedule (`ConversationListScreen`'s own identical reasoning for
    // `rememberTickingNow`).
    val now = rememberTickingNow()
    val segments = visibleBookingsSegments(showConfirmedSegment, showClientsSegment)
    val configMenuEntries =
        visibleBookingsConfigMenuEntries(showSetupSegment, showMastersSegment, showServicesSegment, showHoursSegment)

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(text = stringResource(R.string.nav_bookings)) },
                    actions = {
                        // `26-109`: the overflow moved out of the segment row and into the top bar, left
                        // of the persistent avatar action (Material convention: overflow before identity) -
                        // sharing the row was crowding the segmented control, wrapping «Утверждены» to two
                        // lines. `BookingsConfigMenu` still hides itself when [configMenuEntries] is empty,
                        // so nothing changes when there is nothing to show.
                        BookingsConfigMenu(
                            entries = configMenuEntries,
                            labelFor = { tab -> bookingsTabLabel(tab = tab, pendingState = state) },
                            onSelect = onTabSelected,
                        )
                        // `26-77`: Записи had neither a presence dot nor a menu before this item - the
                        // avatar is this screen's first `actions` content of any kind.
                        AccountAvatarAction(
                            displayName = operatorDisplayName,
                            email = operatorEmail,
                            hubConnectionState = hubConnectionState,
                            onOpenSettings = onOpenSettings,
                            onSignOut = onSignOut,
                            modifier = Modifier.padding(end = 4.dp),
                        )
                    },
                )
            },
        ) { padding ->
            Column(modifier = Modifier.fillMaxSize().padding(padding)) {
                // `26-109`: the segmented row now holds only the segments - `BookingsConfigMenu` moved to
                // the `TopAppBar` `actions` above, so the row no longer shares its width with the overflow.
                SingleChoiceSegmentedButtonRow(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    segments.forEachIndexed { index, tab ->
                        SegmentedButton(
                            selected = selectedTab == tab,
                            onClick = { onTabSelected(tab) },
                            shape = SegmentedButtonDefaults.itemShape(index, segments.size),
                            label = { Text(text = bookingsTabLabel(tab = tab, pendingState = state)) },
                            icon = {},
                        )
                    }
                }

                when (selectedTab) {
                    // `26-49`: the caption is drawn once, above the list, for every arm of [state] rather
                    // than only when [state] is [BookingsUiState.Loaded] - it explains the absence of a
                    // «Подтвердить» control (`docs/backlog/26-49-*.md`'s own Scope item 4, quoting the
                    // mockup's own caption), a fact true regardless of whether the queue is currently
                    // loading, empty, loaded or refusing to load.
                    BookingsTab.Pending ->
                        Column(modifier = Modifier.fillMaxSize()) {
                            Text(
                                text = stringResource(R.string.bookings_auto_confirms_caption),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                            )
                            // `26-49`: the one banner for a veto write that lost a race or failed outright
                            // - drawn above the list, never in place of it, so a refusal never hides the
                            // rows the operator was just looking at (`applyPendingResult`'s own doc
                            // comment: the fresh rows and this error land in the same atomic state update).
                            if (state is BookingsUiState.Loaded) {
                                state.actionError?.let { error ->
                                    ActionErrorBanner(error = error, modifier = Modifier.fillMaxWidth())
                                }
                            }
                            Box(modifier = Modifier.weight(1f)) {
                                when (state) {
                                    BookingsUiState.Loading -> LoadingBody()
                                    is BookingsUiState.Loaded ->
                                        if (state.bookings.isEmpty()) {
                                            EmptyBody(stringResource(R.string.bookings_queue_empty))
                                        } else {
                                            PendingBookingsList(
                                                bookings = state.bookings,
                                                now = now,
                                                busyBookingIds = state.busyBookingIds,
                                                onReject = onReject,
                                                onCancel = onCancel,
                                                onMarkNoShow = onMarkNoShow,
                                            )
                                        }

                                    BookingsUiState.NotConfigured -> EmptyBody(stringResource(R.string.bookings_not_configured))
                                    is BookingsUiState.Failed -> RefusalBody(reason = state.reason, onRetry = onRetry)
                                }
                            }
                        }

                    // `confirmedState` is non-null exactly when `showConfirmedSegment` is true - the only
                    // condition under which this tab even appears in `segments` for `onTabSelected` to
                    // have been able to select it in the first place.
                    BookingsTab.Confirmed ->
                        confirmedState?.let {
                            ConfirmedBookingsBody(
                                state = it,
                                onSelectDay = onSelectDay,
                                onRetry = onRetryConfirmed,
                                onReveal = onRevealConfirmed,
                                onOpenDialog = onOpenDialog,
                            )
                        }

                    // `26-52`: the identical "non-null exactly when selectable" invariant
                    // `BookingsTab.Confirmed`'s own comment above states, for `showClientsSegment`.
                    BookingsTab.Clients ->
                        contactsState?.let {
                            ContactsBody(state = it, onRetry = onRetryContacts, onReveal = onRevealContact)
                        }

                    // `26-142`: the identical "non-null exactly when selectable" invariant the branches
                    // around it state, for `showSetupSegment`. Kept stateless here rather than calling
                    // `hiltViewModel()` inline, for this file's own Route/Screen-split reason.
                    BookingsTab.Calendars ->
                        calendarSetupState?.let {
                            CalendarSetupBody(
                                state = it,
                                onRetry = onRetryCalendarSetup,
                                onOriginsTextChanged = onOriginsTextChanged,
                                onSaveOrigins = onSaveOrigins,
                                onAddCalendar = onAddCalendar,
                                onEditCalendar = onEditCalendar,
                                onCalendarFormChanged = onCalendarFormChanged,
                                onCancelCalendarEdit = onCancelCalendarEdit,
                                onSubmitCalendar = onSubmitCalendar,
                            )
                        }

                    // `26-140`: the identical "non-null exactly when selectable" invariant the branches
                    // around it state, for `showMastersSegment`. Kept stateless here rather than calling
                    // `hiltViewModel()` inline, for this file's own Route/Screen-split reason.
                    BookingsTab.Masters ->
                        mastersState?.let {
                            MastersBody(
                                state = it,
                                onRetry = onRetryMasters,
                                onAdd = onAddMaster,
                                onEdit = onEditMaster,
                                onToggleActive = onToggleMasterActive,
                                onDelete = onDeleteMaster,
                                onCancelEdit = onCancelMasterEdit,
                                onFormChanged = onMasterFormChanged,
                                onSubmit = onSubmitMaster,
                            )
                        }

                    // `26-96`: the identical "non-null exactly when selectable" invariant, for
                    // `showServicesSegment`.
                    BookingsTab.Services ->
                        servicesState?.let {
                            ServicesBody(
                                state = it,
                                onRetry = onRetryServices,
                                onEdit = onEditService,
                                onCancelEdit = onCancelServiceEdit,
                                onDraftChanged = onServiceDraftChanged,
                                onSubmit = onSubmitService,
                            )
                        }

                    // `26-97`: the identical "non-null exactly when selectable" invariant the two
                    // branches above state, for `showHoursSegment`. Kept stateless here rather than
                    // calling `hiltViewModel()` inline, so this composable stays the Hilt-free half
                    // the back-contract tests can drive - this file's own doc comment's whole reason
                    // for the Route/Screen split.
                    BookingsTab.Hours ->
                        workingHoursState?.let {
                            WorkingHoursBody(
                                state = it,
                                onRetry = onRetryWorkingHours,
                                onSave = onSaveWorkingHours,
                                onDelete = onDeleteWorkingHours,
                            )
                        }
                }
            }
        }
    }
}

/**
 * `26-103`: the Записи segmented control's own `⋮` — [entries] is
 * [visibleBookingsConfigMenuEntries]'s own result, already filtered by `calendar:configure`, so this
 * composable's whole gate is its first line: nothing to open, nothing to tap. The identical "hide, don't
 * disable" shape [ago.chat.android.analytics.AnalyticsReportsOverflowMenu] already draws for Аналитика's
 * own `⋮` — a plain `remember` for `expanded` (a menu left open across process death is not state worth
 * restoring), and the menu closed before [onSelect] runs rather than after: [onSelect] here sets
 * [BookingsScreen]'s own `selectedTab`, and a `setExpanded` sequenced after it would be redundant at
 * best, since selecting a tab does not navigate away from this composition the way opening a report
 * does.
 *
 * [labelFor] is a slot rather than a direct call to [bookingsTabLabel] so this file's one existing
 * labelling function is reused verbatim for both the segmented row and this menu — a fourth Услуги/Часы
 * label worded differently between the two surfaces is exactly the kind of drift a shared function
 * exists to rule out.
 */
@Composable
private fun BookingsConfigMenu(
    entries: List<BookingsTab>,
    labelFor: @Composable (BookingsTab) -> AnnotatedString,
    onSelect: (BookingsTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (entries.isEmpty()) return

    var expanded by remember { mutableStateOf(false) }

    IconButton(onClick = { expanded = true }, modifier = modifier) {
        Icon(
            imageVector = AgoIcons.MoreVertical,
            contentDescription = stringResource(R.string.bookings_config_menu_action),
        )
    }

    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        entries.forEach { entry ->
            DropdownMenuItem(
                text = { Text(text = labelFor(entry)) },
                onClick = {
                    expanded = false
                    onSelect(entry)
                },
            )
        }
    }
}

@Composable
private fun bookingsTabLabel(
    tab: BookingsTab,
    pendingState: BookingsUiState,
): AnnotatedString =
    when (tab) {
        BookingsTab.Pending -> pendingSegmentLabel(countFor(pendingState))
        BookingsTab.Confirmed -> buildAnnotatedString { append(stringResource(R.string.bookings_tab_confirmed)) }
        BookingsTab.Clients -> buildAnnotatedString { append(stringResource(R.string.bookings_tab_clients)) }
        BookingsTab.Calendars -> buildAnnotatedString { append(stringResource(R.string.calendar_setup_tab)) }
        BookingsTab.Masters -> buildAnnotatedString { append(stringResource(R.string.masters_tab)) }
        BookingsTab.Services -> buildAnnotatedString { append(stringResource(R.string.bookings_tab_services)) }
        BookingsTab.Hours -> buildAnnotatedString { append(stringResource(R.string.working_hours_tab)) }
    }

/** `null` before [BookingsUiState.Loaded] is known, exactly the "no digit for a count not yet known"
 * rule [ago.chat.android.conversations.segmentedCountFor] already states for Диалоги's own two tabs. */
private fun countFor(state: BookingsUiState): Int? = (state as? BookingsUiState.Loaded)?.bookings?.size

@Composable
private fun pendingSegmentLabel(count: Int?): AnnotatedString =
    buildAnnotatedString {
        append(stringResource(R.string.bookings_tab_pending))
        if (count != null) {
            append(" ")
            withStyle(
                MaterialTheme.typography.labelMedium
                    .copy(fontWeight = FontWeight.Bold)
                    .toSpanStyle(),
            ) {
                append(count.toString())
            }
        }
    }

/** `internal`, not `private`: [ConfirmedBookingsScreen.kt][ConfirmedBookingsBody] reuses this and the
 * three composables below it for the identical loading/empty/refusal states, rather than a second copy
 * of each. */
@Composable
internal fun LoadingBody() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
internal fun EmptyBody(text: String) {
    Box(modifier = Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/** `docs/backlog/26-48-*.md`'s own Done-when: "renders as a refusal with a retry, and never as a raw
 * exception class name" — [failureMessage] is the one place this screen turns
 * [ago.chat.android.core.domain.bookings.BookingsQueueFailure] into the Russian sentence, never the
 * adapter that produced it (`KtorBookingsApi`'s own doc comment: classification lives there, wording
 * lives here).
 *
 * [unexpectedMessageRes] is a parameter, not hard-coded, because `26-51`'s own Утверждены segment reads
 * a different endpoint than the pending queue and needs its own wording for
 * [BookingsQueueFailure.Unexpected] (`bookings_load_failed_unexpected`'s own Russian text names "очередь
 * записей" specifically) — [BookingsQueueFailure.Transport]'s own wording is shared as-is, since it is
 * already worded generically about reaching AGO Calendar at all, not about either read's own shape.
 */
@Composable
internal fun RefusalBody(
    reason: BookingsQueueFailure,
    onRetry: () -> Unit,
    unexpectedMessageRes: Int = R.string.bookings_load_failed_unexpected,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = failureMessage(reason, unexpectedMessageRes),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Button(onClick = onRetry, modifier = Modifier.padding(top = 16.dp)) {
            Text(text = stringResource(R.string.action_retry))
        }
    }
}

@Composable
private fun failureMessage(
    reason: BookingsQueueFailure,
    unexpectedMessageRes: Int,
): String =
    when (reason) {
        BookingsQueueFailure.Transport -> stringResource(R.string.bookings_load_failed_transport)
        BookingsQueueFailure.Unexpected -> stringResource(unexpectedMessageRes)
    }

/** `26-49`: the one place a failed veto write is shown - `error.detail`/`networkFailureText`'s own
 * `ClaimErrorUi` precedent in [ago.chat.android.conversations.ConversationListScreen], restated for
 * this port's own [BookingsQueueFailure] classification via the existing [failureMessage] helper below,
 * rather than a second copy of that when-block. `internal`, not `private`: `26-53`'s own
 * [ContactsScreen.kt][ContactsBody] reuses this for a failed phone-reveal - the identical
 * [BookingActionErrorUi] the pending queue's own veto writes already use, since a reveal reduces to the
 * same "server refusal, or something else" question. */
@Composable
internal fun ActionErrorBanner(
    error: BookingActionErrorUi,
    modifier: Modifier = Modifier,
) {
    Text(
        text =
            when (error) {
                is BookingActionErrorUi.ServerRefusal -> error.detail
                is BookingActionErrorUi.Unavailable -> failureMessage(error.reason, R.string.bookings_load_failed_unexpected)
                // `26-96`: the one arm whose sentence this app owns - see [BookingActionErrorUi.InvalidDuration].
                BookingActionErrorUi.InvalidDuration -> stringResource(R.string.services_duration_invalid)
            },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
        modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun PendingBookingsList(
    bookings: List<PendingBooking>,
    now: OffsetDateTime,
    busyBookingIds: Set<String>,
    onReject: (String) -> Unit,
    onCancel: (String) -> Unit,
    onMarkNoShow: (String) -> Unit,
) {
    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 8.dp)) {
        items(bookings, key = { it.bookingId }) { booking ->
            PendingBookingCard(
                booking = booking,
                now = now,
                busy = booking.bookingId in busyBookingIds,
                onReject = { onReject(booking.bookingId) },
                onCancel = { onCancel(booking.bookingId) },
                onMarkNoShow = { onMarkNoShow(booking.bookingId) },
            )
            HorizontalDivider()
        }
    }
}

/**
 * The mockup's own card — service and duration, when and with whom, the deadline, and the calendar's
 * short id (`docs/backlog/26-48-*.md`'s own Scope item 4). Every one of `serviceId`/`workerId`/
 * `calendarId` is an id, rendered through [IdentifierText] — never a name, because
 * `PendingBookingResponse` does not carry one yet (`PendingBooking`'s own doc comment).
 *
 * `26-49`: the three veto actions - `docs/backlog/26-49-*.md`'s own Scope item 4 draws no
 * «Подтвердить» beside them, on purpose (this file's own [BookingsScreen]-level caption says why).
 * [busy] disables all three at once for *this* card only - never the whole list
 * (`CalendarQueuePage.tsx:258-266`'s own `disabled={busyId === row.bookingId}`, read per-row).
 */
@Composable
private fun PendingBookingCard(
    booking: PendingBooking,
    now: OffsetDateTime,
    busy: Boolean,
    onReject: () -> Unit,
    onCancel: () -> Unit,
    onMarkNoShow: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        // "Service and duration" (`docs/backlog/26-48-*.md`'s own Scope item 4) - one row.
        BookingDetailRow(label = stringResource(R.string.bookings_card_service_label)) {
            IdentifierText(id = booking.serviceId, style = MaterialTheme.typography.bodyMedium)
            durationMinutesOrNull(booking.startsAt, booking.endsAt)?.let { minutes ->
                Text(
                    text = stringResource(R.string.bookings_duration_minutes, minutes.coerceIn(0, Int.MAX_VALUE.toLong()).toInt()),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
        // "When and with whom" - two rows rather than one shared line, so each has its own label; the
        // mockup groups them visually, but nothing in this item's Scope requires one Compose `Row`
        // over two, and two labelled rows read at least as clearly on a phone-width card.
        clockTimeOrNull(booking.startsAt)?.let { time ->
            BookingDetailRow(label = stringResource(R.string.bookings_card_when_label), modifier = Modifier.padding(top = 4.dp)) {
                Text(text = time, style = MaterialTheme.typography.bodyMedium)
            }
        }
        BookingDetailRow(label = stringResource(R.string.bookings_card_worker_label), modifier = Modifier.padding(top = 4.dp)) {
            IdentifierText(id = booking.workerId, style = MaterialTheme.typography.bodyMedium)
        }
        // The deadline - a full sentence on its own, so it carries no separate label.
        Text(
            text = confirmationCountdownText(booking.confirmationDeadline, now),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 8.dp),
        )
        BookingDetailRow(
            label = stringResource(R.string.bookings_card_calendar_label),
            modifier = Modifier.padding(top = 4.dp),
        ) {
            IdentifierText(id = booking.calendarId, style = MaterialTheme.typography.bodySmall)
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            TextButton(onClick = onReject, enabled = !busy) {
                Text(text = stringResource(R.string.bookings_action_reject))
            }
            TextButton(onClick = onCancel, enabled = !busy) {
                Text(text = stringResource(R.string.bookings_action_cancel))
            }
            TextButton(onClick = onMarkNoShow, enabled = !busy) {
                Text(text = stringResource(R.string.bookings_action_no_show))
            }
        }
    }
}

/** `SettingsScreen`'s own `AboutLine` shape, restated: a label, then whatever the row actually needs to
 * show beside it - here a slot rather than a single value, since some rows carry two pieces (a time and
 * a worker id; a service id and its duration). `internal`, not `private`: `26-117`'s own booking-detail
 * sheet (`ConfirmedBookingsScreen.kt`'s own `ConfirmedBookingDetailBody`) reuses this for its own
 * Услуга/Мастер/Телефон/Подтверждён по SMS/Источник rows rather than a second, near-identical
 * label-then-content row shape. */
@Composable
internal fun BookingDetailRow(
    label: String,
    modifier: Modifier = Modifier,
    labelStyle: TextStyle = MaterialTheme.typography.labelMedium,
    content: @Composable () -> Unit,
) {
    // Label leads the row, the value trails it at the row's own end - the weighted spacer between the two
    // is what pins the content to the right edge (`26-135`'s own "values right-aligned to the sheet edge",
    // the identical mockup layout). `labelStyle` is parametrised, default unchanged, so the confirmed-
    // booking detail sheet can lift its own labels to `bodyMedium` without this pending-card call site's
    // own `labelMedium` moving with it (`26-135`: "parametrise the label style, default unchanged").
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            style = labelStyle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 8.dp),
        )
        Spacer(modifier = Modifier.weight(1f))
        content()
    }
}

@Composable
private fun confirmationCountdownText(
    confirmationDeadline: String,
    now: OffsetDateTime,
): String =
    when (val countdown = confirmationCountdown(confirmationDeadline, now)) {
        is ConfirmationCountdown.HoursRemaining ->
            stringResource(R.string.bookings_confirms_in_hours, countdown.value.coerceIn(0, Int.MAX_VALUE.toLong()).toInt())

        ConfirmationCountdown.Unknown -> stringResource(R.string.bookings_deadline_unknown)
    }

/** `null` for anything that fails to parse - the same "never invented, rendered honestly" posture
 * `ThreadScreen`'s own `clockTimeOrNull` already takes for a malformed timestamp. Rendered in the
 * device's own zone - the operator reading this screen, not the calendar's own business time zone. */
private fun clockTimeOrNull(startsAt: String): String? =
    runCatching {
        OffsetDateTime.parse(startsAt).atZoneSameInstant(ZoneId.systemDefault()).format(CLOCK_FORMAT)
    }.getOrNull()

private fun durationMinutesOrNull(
    startsAt: String,
    endsAt: String,
): Long? {
    val start = runCatching { OffsetDateTime.parse(startsAt) }.getOrNull() ?: return null
    val end = runCatching { OffsetDateTime.parse(endsAt) }.getOrNull() ?: return null
    return Duration.between(start, end).toMinutes().takeIf { it >= 0 }
}

private val CLOCK_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
