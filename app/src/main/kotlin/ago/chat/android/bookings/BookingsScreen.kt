package ago.chat.android.bookings

import ago.chat.android.R
import ago.chat.android.core.domain.bookings.BookingsQueueFailure
import ago.chat.android.core.domain.bookings.ConfiguredService
import ago.chat.android.core.domain.calendarsetup.ConfiguredCalendar
import ago.chat.android.core.domain.readiness.BookingPrecondition
import ago.chat.android.core.domain.recut.RecutDecision
import ago.chat.android.core.domain.workers.Worker
import ago.chat.android.core.network.realtime.OperatorHubConnectionState
import ago.chat.android.schedule.WorkingHoursBody
import ago.chat.android.schedule.WorkingHoursUiState
import ago.chat.android.schedule.WorkingHoursViewModel
import ago.chat.android.shell.rememberPendingConversationOpener
import ago.chat.android.ui.components.AccountAvatarAction
import ago.chat.android.ui.components.rememberTickingNow
import ago.chat.android.ui.icons.AgoIcons
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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

/**
 * `26-171` (`26-155` part 3): which of the two Мастера drill-down pages `mastersDrillDownWorkerId`
 * refers to — `26-170` shipped this state as a bare `String?` because «Слоты» did not exist yet, so
 * there was only ever one kind of drill-down to be in. This item is the second kind, so the "which
 * screen" question now needs an answer of its own rather than being implied. A plain UI-layer enum, the
 * identical reason [BookingsTab] itself is one (that enum's own doc comment) — nothing outside this
 * file's own composables and view-model wiring needs to know these names exist.
 *
 * `26-172` (`26-155` part 4, the epic's own final part) adds [Recut] — the third and final drill-down,
 * reached from «График»'s own note+button (`WorkerScheduleFormFields`'s own `onOpenRecut`) and from the
 * Часы `RecutNotice` (`WorkingHoursScreen.kt`'s own `onOpenRecut`), never from a card action of its own
 * (Q2, `docs/design/26-155-*.md`: destructive, and only meaningful once a schedule exists).
 */
internal enum class MastersDrillDownKind {
    Schedule,
    Slots,
    Recut,
}

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
    showReadinessEntry: Boolean,
    showSetupSegment: Boolean,
    showMastersSegment: Boolean,
    showServicesSegment: Boolean,
    showHoursSegment: Boolean,
    hubConnectionState: OperatorHubConnectionState,
    onOpenSettings: () -> Unit,
    onSignOut: () -> Unit,
    operatorDisplayName: String? = null,
    operatorEmail: String? = null,
    // `26-157`: reported up so [ago.chat.android.shell.AppShellScreen] can hide the bottom navigation bar
    // while one of the four `⋮` configuration screens (Услуги/Часы/Мастера/Календари) is open — they render
    // as a modal page with a back-button app bar, not inside the normal shell. `true` exactly while
    // [activeConfigTab] is non-null.
    onConfigScreenChanged: (Boolean) -> Unit = {},
    viewModel: BookingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // `selectedTab` holds only the operational segment (Ожидают/Утверждены/Клиенты). The four
    // configuration screens reached from the `⋮` menu are tracked separately in [activeConfigTab] so that
    // closing one returns to whichever operational segment was showing (`26-157`).
    var selectedTab by rememberSaveable { mutableStateOf(BookingsTab.Pending) }
    // `26-157`: `null` = the operational Записи view; non-null = the modal config page for that screen. An
    // enum is saveable here the same way `selectedTab` above is. Reported up via [onConfigScreenChanged].
    var activeConfigTab by rememberSaveable { mutableStateOf<BookingsTab?>(null) }
    LaunchedEffect(activeConfigTab) { onConfigScreenChanged(activeConfigTab != null) }

    // `26-170` (`26-155` part 2): the Мастера segment's own second-level drill-down - `null` for the
    // Masters list, a worker id for «График» open over it. Held here, beside `activeConfigTab`, rather
    // than folded into a `MastersUiState` field: it is a property of *this composition's* navigation
    // (which second-level page is open), the identical "not the view model's business" reasoning
    // `confirmingDelete`/`editing` local `remember`s elsewhere in this package already state for a
    // transient dialog - the difference here is `rememberSaveable`, because unlike a dialog this page
    // survives a rotation (Q8, `docs/design/26-155-*.md`: back from it returns to Masters, not exits
    // the drill-down on every configuration change).
    var mastersDrillDownWorkerId by rememberSaveable { mutableStateOf<String?>(null) }

    // `26-171`: which of the two drill-down pages [mastersDrillDownWorkerId] refers to - see
    // [MastersDrillDownKind]'s own doc comment. Read only while [mastersDrillDownWorkerId] is non-null;
    // its value the rest of the time is never drawn from, so there is nothing to reset when a drill-down
    // closes (`onCloseConfig`/`onConfigSelected` below still clear [mastersDrillDownWorkerId] itself,
    // which is what actually hides both pages).
    var mastersDrillDownKind by rememberSaveable { mutableStateOf(MastersDrillDownKind.Schedule) }

    // `26-172` (`26-155` part 4): the «Пересчёт» drill-down's own «Пересчитать с» prefill - `null` when
    // reached from «График»'s own note+button (defaults to today, [WorkerRecutViewModel.open]'s own doc
    // comment), the server's own `recutFrom` when reached from the Часы `RecutNotice`. Read only while
    // [mastersDrillDownKind] is [MastersDrillDownKind.Recut]; cleared alongside [mastersDrillDownWorkerId]
    // everywhere that id is, so a later График-triggered open never inherits a stale Часы prefill.
    var mastersDrillDownRecutFrom by rememberSaveable { mutableStateOf<String?>(null) }

    // `26-164`: the identical Hilt-avoidance-when-ungated shape the branches below establish, applied to
    // [ReadinessViewModel] - an operator lacking `calendar:configure` never constructs it and never
    // triggers its `init`-time read of the booking-readiness chain.
    val readinessState: ReadinessUiState?
    val onRefreshReadiness: () -> Unit
    if (showReadinessEntry) {
        val readinessViewModel: ReadinessViewModel = hiltViewModel()
        val collectedReadinessState by readinessViewModel.state.collectAsStateWithLifecycle()
        readinessState = collectedReadinessState
        onRefreshReadiness = readinessViewModel::refresh
    } else {
        readinessState = null
        onRefreshReadiness = {}
    }
    // `26-164`: "re-read on every open of the screen" (`docs/design/26-154-*.md`'s own accepted Q4) -
    // [ReadinessViewModel] persists for as long as this whole Записи route does (it is obtained above,
    // gated on the permission alone, not on `activeConfigTab`), so a fresh read on every *reopen* of the
    // «Готовность» screen specifically needs an explicit trigger here, keyed on the one state that knows
    // when that happens.
    LaunchedEffect(activeConfigTab) {
        if (activeConfigTab == BookingsTab.Readiness) onRefreshReadiness()
    }

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
        onAddCalendar = calendarSetupViewModel::startAddCalendar
        onEditCalendar = calendarSetupViewModel::editCalendar
        onCalendarFormChanged = calendarSetupViewModel::onCalendarFormChanged
        onCancelCalendarEdit = calendarSetupViewModel::cancelCalendarEdit
        onSubmitCalendar = calendarSetupViewModel::submitCalendar
    } else {
        calendarSetupState = null
        onRetryCalendarSetup = {}
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

    // `26-170` (`26-155` part 2): the «График» drill-down's own view model - the identical
    // Hilt-avoidance-when-ungated shape [mastersState] above establishes, gated on `showMastersSegment`
    // (an operator lacking `calendar:configure` never even opens Мастера), `mastersDrillDownWorkerId`
    // (a Мастера operator who has not opened a drill-down yet never triggers this class's own
    // `init`-free but still network-holding [WorkerScheduleApi]) and, since `26-171`,
    // `mastersDrillDownKind` (an operator who opened «Слоты»/«Пересчёт» rather than «График» never
    // constructs this class either - [WorkerSlotsViewModel]/[WorkerRecutViewModel] are its own sibling
    // branches below). This is also what the `26-162` androidTest landmine requires: `hiltViewModel()`
    // for [WorkerScheduleViewModel] happens only in this branch of [BookingsRoute], never inside
    // [BookingsScreen] itself, which every shell/back-contract test drives under a plain
    // `ComponentActivity` with `bookingsTab` substituted for a marker `Text` - a route this branch is
    // never part of, since substituting `bookingsTab` skips [BookingsRoute] entirely.
    //
    // `26-172` removed [WorkerScheduleViewModel]'s own read-only re-cut preview hook (`previewRecut`/
    // `dismissRecutPreview`) - the note+button now navigates to [MastersDrillDownKind.Recut]'s own
    // sibling branch below instead of triggering a preview in place.
    val workerScheduleState: WorkerScheduleUiState?
    val onRetryWorkerSchedule: () -> Unit
    val onWorkerScheduleFormChanged: (WorkerScheduleForm) -> Unit
    val onSubmitWorkerSchedule: () -> Unit
    if (showMastersSegment && mastersDrillDownWorkerId != null && mastersDrillDownKind == MastersDrillDownKind.Schedule) {
        val workerScheduleViewModel: WorkerScheduleViewModel = hiltViewModel()
        val collectedWorkerScheduleState by workerScheduleViewModel.state.collectAsStateWithLifecycle()
        workerScheduleState = collectedWorkerScheduleState
        onRetryWorkerSchedule = workerScheduleViewModel::refresh
        onWorkerScheduleFormChanged = workerScheduleViewModel::onFormChanged
        onSubmitWorkerSchedule = workerScheduleViewModel::submit
        // `26-170`: the `hiltViewModel()`-scoped instance survives across which worker is open
        // ([WorkerScheduleViewModel]'s own doc comment) - this is what actually switches it, the
        // identical `LaunchedEffect(conversationId)` shape [ago.chat.android.thread.ThreadViewModel]'s
        // own callers already use for the same reason.
        val drillDownWorkerId = mastersDrillDownWorkerId
        LaunchedEffect(drillDownWorkerId) {
            if (drillDownWorkerId != null) workerScheduleViewModel.open(drillDownWorkerId)
        }
    } else {
        workerScheduleState = null
        onRetryWorkerSchedule = {}
        onWorkerScheduleFormChanged = {}
        onSubmitWorkerSchedule = {}
    }

    // `26-171` (`26-155` part 3): the «Слоты» drill-down's own view model - the identical
    // Hilt-avoidance-when-ungated shape [workerScheduleState] above establishes, gated the identical way
    // except on [MastersDrillDownKind.Slots] instead - an operator who opened «График» never constructs
    // this class either, and the identical `26-162` androidTest landmine applies for the identical
    // reason.
    val workerSlotsState: WorkerSlotsUiState?
    val onRetryWorkerSlots: () -> Unit
    val onRevealWorkerSlot: (String) -> Unit
    if (showMastersSegment && mastersDrillDownWorkerId != null && mastersDrillDownKind == MastersDrillDownKind.Slots) {
        val workerSlotsViewModel: WorkerSlotsViewModel = hiltViewModel()
        val collectedWorkerSlotsState by workerSlotsViewModel.state.collectAsStateWithLifecycle()
        workerSlotsState = collectedWorkerSlotsState
        onRetryWorkerSlots = workerSlotsViewModel::refresh
        onRevealWorkerSlot = workerSlotsViewModel::reveal
        // `26-170`'s own `drillDownWorkerId` switch, restated for this sibling view model.
        val drillDownWorkerId = mastersDrillDownWorkerId
        LaunchedEffect(drillDownWorkerId) {
            if (drillDownWorkerId != null) workerSlotsViewModel.open(drillDownWorkerId)
        }
    } else {
        workerSlotsState = null
        onRetryWorkerSlots = {}
        onRevealWorkerSlot = {}
    }

    // `26-172` (`26-155` part 4, the epic's own final part): the «Пересчёт» drill-down's own view model -
    // the identical Hilt-avoidance-when-ungated shape [workerSlotsState] above establishes, gated the
    // identical way except on [MastersDrillDownKind.Recut] instead - an operator who opened «График» or
    // «Слоты» never constructs this class either, and the identical `26-162` androidTest landmine
    // applies for the identical reason.
    val workerRecutState: WorkerRecutUiState?
    val onWorkerRecutFromChanged: (String) -> Unit
    val onPreviewWorkerRecut: () -> Unit
    val onDecideWorkerRecut: (String, RecutDecision) -> Unit
    val onRequestConfirmWorkerRecut: () -> Unit
    val onDismissConfirmWorkerRecut: () -> Unit
    val onConfirmWorkerRecut: () -> Unit
    val onRevealWorkerRecut: (String) -> Unit
    if (showMastersSegment && mastersDrillDownWorkerId != null && mastersDrillDownKind == MastersDrillDownKind.Recut) {
        val workerRecutViewModel: WorkerRecutViewModel = hiltViewModel()
        val collectedWorkerRecutState by workerRecutViewModel.state.collectAsStateWithLifecycle()
        workerRecutState = collectedWorkerRecutState
        onWorkerRecutFromChanged = workerRecutViewModel::onFromChanged
        onPreviewWorkerRecut = workerRecutViewModel::preview
        onDecideWorkerRecut = workerRecutViewModel::decide
        onRequestConfirmWorkerRecut = workerRecutViewModel::requestConfirm
        onDismissConfirmWorkerRecut = workerRecutViewModel::dismissConfirm
        onConfirmWorkerRecut = workerRecutViewModel::confirm
        onRevealWorkerRecut = workerRecutViewModel::reveal
        // `26-170`'s own `drillDownWorkerId` switch, restated for this sibling view model -
        // [WorkerRecutViewModel.open] always starts a fresh attempt (that class's own doc comment), so
        // this effect re-running whenever this branch is freshly (re-)entered - not only when the worker
        // id itself changes - is exactly the behaviour wanted, not a bug to guard against.
        val drillDownWorkerId = mastersDrillDownWorkerId
        LaunchedEffect(drillDownWorkerId) {
            if (drillDownWorkerId != null) workerRecutViewModel.open(drillDownWorkerId, mastersDrillDownRecutFrom)
        }
    } else {
        workerRecutState = null
        onWorkerRecutFromChanged = {}
        onPreviewWorkerRecut = {}
        onDecideWorkerRecut = { _, _ -> }
        onRequestConfirmWorkerRecut = {}
        onDismissConfirmWorkerRecut = {}
        onConfirmWorkerRecut = {}
        onRevealWorkerRecut = {}
    }

    // The drill-down page's own app-bar title (`«График»`/«Слоты»`/«Пересчёт» — {displayName}`) - resolved from the
    // roster [MastersViewModel] already holds rather than a second read, and `null` (a bare fallback
    // title) whenever the roster has not answered yet or no longer lists this worker.
    val mastersDrillDownWorkerName =
        (mastersState as? MastersUiState.Loaded)?.workers?.firstOrNull { it.workerId == mastersDrillDownWorkerId }?.displayName

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
        showReadinessEntry = showReadinessEntry,
        showSetupSegment = showSetupSegment,
        showMastersSegment = showMastersSegment,
        showServicesSegment = showServicesSegment,
        showHoursSegment = showHoursSegment,
        selectedTab = selectedTab,
        onSegmentSelected = { selectedTab = it },
        activeConfigTab = activeConfigTab,
        // `26-170`: switching to a different `⋮` screen (or closing the config page altogether) always
        // closes any open Мастера drill-down too - there is nothing to preserve across a genuinely
        // different screen, the identical "closing resets what was nested under it" rule this file's own
        // `onCloseConfig` already applies to `editing`/`confirmingDelete` further down the tree.
        onConfigSelected = { tab ->
            activeConfigTab = tab
            mastersDrillDownWorkerId = null
            mastersDrillDownRecutFrom = null
        },
        onCloseConfig = {
            activeConfigTab = null
            mastersDrillDownWorkerId = null
            mastersDrillDownRecutFrom = null
        },
        onRetry = viewModel::refresh,
        onReject = viewModel::reject,
        onCancel = viewModel::cancel,
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
        mastersDrillDownWorkerId = mastersDrillDownWorkerId,
        mastersDrillDownKind = mastersDrillDownKind,
        mastersDrillDownWorkerName = mastersDrillDownWorkerName,
        onOpenMastersSchedule = { worker ->
            mastersDrillDownWorkerId = worker.workerId
            mastersDrillDownKind = MastersDrillDownKind.Schedule
        },
        onOpenMastersSlots = { worker ->
            mastersDrillDownWorkerId = worker.workerId
            mastersDrillDownKind = MastersDrillDownKind.Slots
        },
        onCloseMastersDrillDown = {
            mastersDrillDownWorkerId = null
            mastersDrillDownRecutFrom = null
        },
        // `26-170`: the weekly-hours note's own in-hub swap (Q2's own sibling note, worded like
        // [BookingsScreen]'s own `onFixReadiness`) - leaves Мастера's roster underneath untouched and
        // opens Часы as a sibling `⋮` screen, never a third navigation level.
        onSwitchMastersDrillDownToHours = {
            mastersDrillDownWorkerId = null
            mastersDrillDownRecutFrom = null
            activeConfigTab = BookingsTab.Hours
        },
        // `26-172` (`26-155` part 4): the note+button inside «График» - stays on the same worker, only
        // the drill-down kind switches. `mastersDrillDownRecutFrom` stays `null` (defaults to today,
        // `WorkerRecutViewModel.open`'s own doc comment) - this is the "reached from schedule" entry, not
        // the Часы notice's own prefilled one below.
        onOpenRecutFromSchedule = {
            mastersDrillDownRecutFrom = null
            mastersDrillDownKind = MastersDrillDownKind.Recut
        },
        workerScheduleState = workerScheduleState,
        onRetryWorkerSchedule = onRetryWorkerSchedule,
        onWorkerScheduleFormChanged = onWorkerScheduleFormChanged,
        onSubmitWorkerSchedule = onSubmitWorkerSchedule,
        workerSlotsState = workerSlotsState,
        onRetryWorkerSlots = onRetryWorkerSlots,
        onRevealWorkerSlot = onRevealWorkerSlot,
        workerRecutState = workerRecutState,
        onWorkerRecutFromChanged = onWorkerRecutFromChanged,
        onPreviewWorkerRecut = onPreviewWorkerRecut,
        onDecideWorkerRecut = onDecideWorkerRecut,
        onRequestConfirmWorkerRecut = onRequestConfirmWorkerRecut,
        onDismissConfirmWorkerRecut = onDismissConfirmWorkerRecut,
        onConfirmWorkerRecut = onConfirmWorkerRecut,
        onRevealWorkerRecut = onRevealWorkerRecut,
        workingHoursState = workingHoursState,
        onRetryWorkingHours = onRetryWorkingHours,
        onSaveWorkingHours = onSaveWorkingHours,
        onDeleteWorkingHours = onDeleteWorkingHours,
        // `26-172`: the Часы `RecutNotice`'s own entry point - opens the Masters «Пересчёт» drill-down
        // for the rule's own worker, prefilled with the server's own `recutFrom`
        // (`WorkingHoursUiState.Loaded.noticeWorkerId`'s own doc comment states why the notice now
        // carries a `workerId` at all).
        onOpenMastersRecutFromHours = { workerId, from ->
            activeConfigTab = BookingsTab.Masters
            mastersDrillDownWorkerId = workerId
            mastersDrillDownRecutFrom = from
            mastersDrillDownKind = MastersDrillDownKind.Recut
        },
        readinessState = readinessState,
        onRetryReadiness = onRefreshReadiness,
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
    showReadinessEntry: Boolean,
    showSetupSegment: Boolean,
    showMastersSegment: Boolean,
    showServicesSegment: Boolean,
    showHoursSegment: Boolean,
    selectedTab: BookingsTab,
    onSegmentSelected: (BookingsTab) -> Unit,
    // `26-157`: `null` = the operational Записи view; non-null = the modal configuration page for that
    // screen (Услуги/Часы/Мастера/Календари), reached from the `⋮` menu. [onConfigSelected] opens one,
    // [onCloseConfig] returns to the operational view (the back button and system back).
    activeConfigTab: BookingsTab?,
    onConfigSelected: (BookingsTab) -> Unit,
    onCloseConfig: () -> Unit,
    onRetry: () -> Unit,
    // `26-163`: the veto pair is the whole action surface for a pending row - «Не пришёл» left with that
    // item, since `Event.MarkNoShow` accepts only a `Booked` row (`BookingsViewModel`'s own doc comment).
    onReject: (String) -> Unit,
    onCancel: (String) -> Unit,
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
    // `26-170` (`26-155` part 2): the Мастера segment's own second-level drill-down - `null`
    // for the Masters list. See [BookingsRoute]'s own doc comment on `mastersDrillDownWorkerId` for why
    // it lives one level up rather than inside [MastersUiState]. `26-171` adds [mastersDrillDownKind] -
    // see [MastersDrillDownKind]'s own doc comment for why a second «Слоты» page needs it.
    mastersDrillDownWorkerId: String?,
    mastersDrillDownKind: MastersDrillDownKind,
    mastersDrillDownWorkerName: String?,
    onOpenMastersSchedule: (Worker) -> Unit,
    onOpenMastersSlots: (Worker) -> Unit,
    onCloseMastersDrillDown: () -> Unit,
    onSwitchMastersDrillDownToHours: () -> Unit,
    // `26-172` (`26-155` part 4): «График»'s own note+button - opens [MastersDrillDownKind.Recut] for
    // the worker already open, never a fourth navigation level.
    onOpenRecutFromSchedule: () -> Unit,
    workerScheduleState: WorkerScheduleUiState?,
    onRetryWorkerSchedule: () -> Unit,
    onWorkerScheduleFormChanged: (WorkerScheduleForm) -> Unit,
    onSubmitWorkerSchedule: () -> Unit,
    workerSlotsState: WorkerSlotsUiState?,
    onRetryWorkerSlots: () -> Unit,
    onRevealWorkerSlot: (String) -> Unit,
    workerRecutState: WorkerRecutUiState?,
    onWorkerRecutFromChanged: (String) -> Unit,
    onPreviewWorkerRecut: () -> Unit,
    onDecideWorkerRecut: (String, RecutDecision) -> Unit,
    onRequestConfirmWorkerRecut: () -> Unit,
    onDismissConfirmWorkerRecut: () -> Unit,
    onConfirmWorkerRecut: () -> Unit,
    onRevealWorkerRecut: (String) -> Unit,
    workingHoursState: WorkingHoursUiState?,
    onRetryWorkingHours: () -> Unit,
    onSaveWorkingHours: (String, Int, String, String) -> Unit,
    onDeleteWorkingHours: (String) -> Unit,
    // `26-172`: the Часы `RecutNotice`'s own entry point - opens the Masters «Пересчёт» drill-down for
    // the notice's own worker, prefilled with the server's own `recutFrom`.
    onOpenMastersRecutFromHours: (workerId: String, from: String) -> Unit,
    readinessState: ReadinessUiState?,
    onRetryReadiness: () -> Unit,
    hubConnectionState: OperatorHubConnectionState = OperatorHubConnectionState.Disconnected,
    operatorDisplayName: String? = null,
    operatorEmail: String? = null,
    onOpenSettings: () -> Unit = {},
    onSignOut: () -> Unit = {},
) {
    // `26-157`: the four `⋮` configuration screens (Услуги/Часы/Мастера/Календари) render as a modal page
    // over the whole shell - a back-button app bar, no avatar, and (reported one level up through
    // [BookingsRoute]'s own `onConfigScreenChanged`) no bottom navigation tabs. `activeConfigTab` being
    // non-null is the whole switch; the operational Записи view (Ожидают/Утверждены/Клиенты) is drawn
    // otherwise, unchanged. System back closes the page before the shell's own back handling ever runs.
    if (activeConfigTab != null) {
        // `26-170`/`26-171`: a drill-down («График» or «Слоты») is a *third* level of modal page - over
        // Мастера, itself over Записи - so it is checked, and returns, before `BookingsConfigModalPage`
        // (the Мастера list's own page) ever renders. Structuring the two as mutually-exclusive early
        // returns, rather than one [BackHandler] each with an `enabled` flag, means exactly one
        // [BackHandler] is ever live at a time - there is nothing to stack, since only one of these two
        // composes on any given frame (Q8: back from either drill-down always lands on the Masters list
        // underneath it, never one level further). Which of the two pages renders is
        // [mastersDrillDownKind] - [MastersDrillDownKind]'s own doc comment.
        if (activeConfigTab == BookingsTab.Masters && mastersDrillDownWorkerId != null) {
            BackHandler(onBack = onCloseMastersDrillDown)
            when (mastersDrillDownKind) {
                MastersDrillDownKind.Schedule ->
                    WorkerScheduleDrillDownPage(
                        workerDisplayName = mastersDrillDownWorkerName,
                        state = workerScheduleState ?: WorkerScheduleUiState.Loading,
                        onBack = onCloseMastersDrillDown,
                        onRetry = onRetryWorkerSchedule,
                        onFormChanged = onWorkerScheduleFormChanged,
                        onSubmit = onSubmitWorkerSchedule,
                        onSwitchToHours = onSwitchMastersDrillDownToHours,
                        onOpenRecut = onOpenRecutFromSchedule,
                    )

                MastersDrillDownKind.Slots ->
                    WorkerSlotsDrillDownPage(
                        workerDisplayName = mastersDrillDownWorkerName,
                        state = workerSlotsState ?: WorkerSlotsUiState.Loading,
                        onBack = onCloseMastersDrillDown,
                        onRetry = onRetryWorkerSlots,
                        onReveal = onRevealWorkerSlot,
                    )

                // `26-172` (`26-155` part 4, the epic's own final part): «Пересчёт» - the full three-step
                // flow, replacing `26-170`'s own read-only preview hook.
                MastersDrillDownKind.Recut ->
                    WorkerRecutDrillDownPage(
                        workerDisplayName = mastersDrillDownWorkerName,
                        // Unreachable in practice: [workerRecutState] is non-null exactly when this
                        // branch itself renders (`BookingsRoute`'s own identical gating condition) - kept
                        // as a real fallback rather than `!!`, the identical defensive completeness the
                        // Schedule/Slots arms above already show with their own `?: ...Loading`.
                        state = workerRecutState ?: WorkerRecutUiState.Loaded(from = ""),
                        onBack = onCloseMastersDrillDown,
                        onFromChanged = onWorkerRecutFromChanged,
                        onPreview = onPreviewWorkerRecut,
                        onDecide = onDecideWorkerRecut,
                        onRequestConfirm = onRequestConfirmWorkerRecut,
                        onDismissConfirm = onDismissConfirmWorkerRecut,
                        onConfirm = onConfirmWorkerRecut,
                        onReveal = onRevealWorkerRecut,
                    )
            }
            return
        }
        BackHandler(onBack = onCloseConfig)
        BookingsConfigModalPage(
            configTab = activeConfigTab,
            pendingState = state,
            onBack = onCloseConfig,
            readinessState = readinessState,
            onRetryReadiness = onRetryReadiness,
            // `26-164`: «Исправить»/«Слоты» is an in-hub swap to another config screen, expressed here as
            // [onConfigSelected] itself - the identical navigation this menu's own entries already use to
            // open a screen. [BookingPrecondition.fixTargetTab]'s own `null` (only [BookingPrecondition.Unknown])
            // is a no-op: [ReadinessRow] never draws a button for it in the first place.
            onFixReadiness = { precondition -> precondition.fixTargetTab()?.let(onConfigSelected) },
            calendarSetupState = calendarSetupState,
            onRetryCalendarSetup = onRetryCalendarSetup,
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
            onOpenMastersSchedule = onOpenMastersSchedule,
            onOpenMastersSlots = onOpenMastersSlots,
            servicesState = servicesState,
            onRetryServices = onRetryServices,
            onEditService = onEditService,
            onCancelServiceEdit = onCancelServiceEdit,
            onServiceDraftChanged = onServiceDraftChanged,
            onSubmitService = onSubmitService,
            workingHoursState = workingHoursState,
            onRetryWorkingHours = onRetryWorkingHours,
            onSaveWorkingHours = onSaveWorkingHours,
            onDeleteWorkingHours = onDeleteWorkingHours,
            onOpenRecutFromHours = onOpenMastersRecutFromHours,
        )
        return
    }

    // `ago-console`'s own `useNow` hook, restated - the one clock read this screen makes, so every
    // deadline countdown on it re-renders together rather than each row reading `OffsetDateTime.now()`
    // on its own recomposition schedule (`ConversationListScreen`'s own identical reasoning for
    // `rememberTickingNow`).
    val now = rememberTickingNow()
    val segments = visibleBookingsSegments(showConfirmedSegment, showClientsSegment)
    val configMenuEntries =
        visibleBookingsConfigMenuEntries(
            showReadinessEntry,
            showSetupSegment,
            showMastersSegment,
            showServicesSegment,
            showHoursSegment,
        )

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
                        //
                        // `26-157`: selecting an entry now opens it as a modal config page (via
                        // [onConfigSelected]) rather than swapping a body underneath the same shell.
                        BookingsConfigMenu(
                            entries = configMenuEntries,
                            labelFor = { tab -> bookingsTabLabel(tab = tab, pendingState = state) },
                            onSelect = onConfigSelected,
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
                            onClick = { onSegmentSelected(tab) },
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
                                            )
                                        }

                                    BookingsUiState.NotConfigured -> EmptyBody(stringResource(R.string.bookings_not_configured))
                                    is BookingsUiState.Failed -> RefusalBody(reason = state.reason, onRetry = onRetry)
                                }
                            }
                        }

                    // `confirmedState` is non-null exactly when `showConfirmedSegment` is true - the only
                    // condition under which this tab even appears in `segments` for `onSegmentSelected` to
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

                    // `26-157`/`26-164`: the five configuration tabs are never a `selectedTab` - they are
                    // opened as a modal page tracked by `activeConfigTab` (handled above), never selected in
                    // the operational segmented view - so there is nothing for the operational `when` to draw.
                    BookingsTab.Readiness,
                    BookingsTab.Calendars,
                    BookingsTab.Masters,
                    BookingsTab.Services,
                    BookingsTab.Hours,
                    -> Unit
                }
            }
        }
    }
}

/**
 * `26-157`: the modal page a `⋮` configuration screen (Услуги/Часы/Мастера/Календари) renders as — a
 * back-button app bar with no [AccountAvatarAction], and no segmented row. It reuses the app's existing
 * detail-screen chrome verbatim (the identical [AgoIcons.Back] + [R.string.action_back] navigation icon
 * [ago.chat.android.thread.ThreadScreen] and [ago.chat.android.channels.InstallWidgetScreen] already
 * draw), rather than inventing new chrome. The bottom navigation tabs are hidden one level up
 * ([ago.chat.android.shell.AppShellScreen]), signalled by [BookingsRoute]'s own `onConfigScreenChanged`.
 *
 * The four config bodies are the same ones the operational view used to swap in place; each is still
 * rendered only when its state is non-null, the "non-null exactly when the entry was offered" invariant
 * `visibleBookingsConfigMenuEntries` guarantees (an operator without `calendar:configure` never sees the
 * `⋮` entry, so [configTab] is never one of these for them).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BookingsConfigModalPage(
    configTab: BookingsTab,
    pendingState: BookingsUiState,
    onBack: () -> Unit,
    readinessState: ReadinessUiState?,
    onRetryReadiness: () -> Unit,
    onFixReadiness: (BookingPrecondition) -> Unit,
    calendarSetupState: CalendarSetupUiState?,
    onRetryCalendarSetup: () -> Unit,
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
    onOpenMastersSchedule: (Worker) -> Unit,
    onOpenMastersSlots: (Worker) -> Unit,
    servicesState: ServicesUiState?,
    onRetryServices: () -> Unit,
    onEditService: (ConfiguredService) -> Unit,
    onCancelServiceEdit: () -> Unit,
    onServiceDraftChanged: (ServiceDraft) -> Unit,
    onSubmitService: (ServiceDraft) -> Unit,
    workingHoursState: WorkingHoursUiState?,
    onRetryWorkingHours: () -> Unit,
    onSaveWorkingHours: (String, Int, String, String) -> Unit,
    onDeleteWorkingHours: (String) -> Unit,
    // `26-172` (`26-155` part 4): Часы's own `RecutNotice` entry point.
    onOpenRecutFromHours: (workerId: String, from: String) -> Unit,
) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Scaffold(
            topBar = {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = AgoIcons.Back,
                                contentDescription = stringResource(R.string.action_back),
                            )
                        }
                    },
                    // `26-164`: Готовность is the one config tab whose page title is not the short menu
                    // label - the accepted design decision is a short `«Готовность»` `DropdownMenuItem`
                    // with the full question as the page title (`docs/design/26-154-*.md`'s own Q2), so
                    // this is the one place [bookingsTabLabel] is not reused for a page title as-is.
                    title = {
                        Text(
                            text =
                                if (configTab == BookingsTab.Readiness) {
                                    buildAnnotatedString { append(stringResource(R.string.readiness_page_title)) }
                                } else {
                                    bookingsTabLabel(tab = configTab, pendingState = pendingState)
                                },
                        )
                    },
                )
            },
        ) { padding ->
            Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                when (configTab) {
                    // `26-164`: Готовность.
                    BookingsTab.Readiness ->
                        readinessState?.let {
                            ReadinessBody(state = it, onRetry = onRetryReadiness, onFix = onFixReadiness)
                        }

                    // `26-142`: Календари - kept stateless here rather than calling `hiltViewModel()`
                    // inline, for this file's own Route/Screen-split reason.
                    BookingsTab.Calendars ->
                        calendarSetupState?.let {
                            CalendarSetupBody(
                                state = it,
                                onRetry = onRetryCalendarSetup,
                                onAddCalendar = onAddCalendar,
                                onEditCalendar = onEditCalendar,
                                onCalendarFormChanged = onCalendarFormChanged,
                                onCancelCalendarEdit = onCancelCalendarEdit,
                                onSubmitCalendar = onSubmitCalendar,
                            )
                        }

                    // `26-140`: Мастера.
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
                                onOpenSchedule = onOpenMastersSchedule,
                                onOpenSlots = onOpenMastersSlots,
                            )
                        }

                    // `26-96`: Услуги.
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

                    // `26-97`: Часы.
                    BookingsTab.Hours ->
                        workingHoursState?.let {
                            WorkingHoursBody(
                                state = it,
                                onRetry = onRetryWorkingHours,
                                onSave = onSaveWorkingHours,
                                onDelete = onDeleteWorkingHours,
                                onOpenRecut = onOpenRecutFromHours,
                            )
                        }

                    // The three operational segments are never opened as a modal config page.
                    BookingsTab.Pending, BookingsTab.Confirmed, BookingsTab.Clients -> Unit
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
        // `26-164`: the short menu label - see `BookingsConfigModalPage`'s own `title` for why the page
        // itself renders the full question instead of reusing this string.
        BookingsTab.Readiness -> buildAnnotatedString { append(stringResource(R.string.readiness_tab)) }
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
    // `26-159`: parametrised for the same reason [unexpectedMessageRes] already is - the chat
    // «Установка виджета» screen reuses this body against `Ago.Chat.Api`, so its transport wording must
    // not name "AGO Calendar" the way the default (worded for this file's own calendar reads) does.
    // Default unchanged, so every existing caller keeps the exact string it had.
    transportMessageRes: Int = R.string.bookings_load_failed_transport,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = failureMessage(reason, unexpectedMessageRes, transportMessageRes),
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
    transportMessageRes: Int = R.string.bookings_load_failed_transport,
): String =
    when (reason) {
        BookingsQueueFailure.Transport -> stringResource(transportMessageRes)
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

/** `SettingsScreen`'s own `AboutLine` shape, restated: a label, then whatever the row actually needs to
 * show beside it - a slot rather than a single value, since a row may carry two pieces (a masked phone
 * and its reveal control). `internal`, not `private`: `26-117`'s own booking-detail sheet
 * (`ConfirmedBookingsScreen.kt`'s own `ConfirmedBookingDetailBody`) and `26-163`'s pending sheet
 * (`PendingBookingsScreen.kt`) both draw their Услуга/Мастер/Телефон/... rows through this rather than a
 * second, near-identical label-then-content row shape. The `26-48` pending card that first drew it is
 * gone (`26-163` replaced it with the row-and-sheet the confirmed segment already had), which is why this
 * file's only remaining callers are elsewhere. */
@Composable
internal fun BookingDetailRow(
    label: String,
    modifier: Modifier = Modifier,
    labelStyle: TextStyle = MaterialTheme.typography.labelMedium,
    content: @Composable () -> Unit,
) {
    // Label leads the row, the value trails it at the row's own end - the weighted spacer between the two
    // is what pins the content to the right edge (`26-135`'s own "values right-aligned to the sheet edge",
    // the identical mockup layout). `labelStyle` is parametrised, default unchanged, so a detail sheet
    // can lift its labels to `bodyMedium` without moving any other call site's `labelMedium` with it
    // (`26-135`: "parametrise the label style, default unchanged").
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

/** The booking's own length in whole minutes, or `null` when either bound fails to parse - the identical
 * "never invented, rendered honestly" posture every formatter on these screens takes. `internal`, not
 * `private`: `26-163` made this the one copy for the confirmed row (`ConfirmedBookingsScreen.kt`), the
 * pending row and the pending sheet alike, replacing the per-file restatement two `private` callers
 * used to justify - three callers is where a shared function stops being premature. */
internal fun durationMinutesOrNull(
    startsAt: String,
    endsAt: String,
): Long? {
    val start = runCatching { OffsetDateTime.parse(startsAt) }.getOrNull() ?: return null
    val end = runCatching { OffsetDateTime.parse(endsAt) }.getOrNull() ?: return null
    return Duration.between(start, end).toMinutes().takeIf { it >= 0 }
}
