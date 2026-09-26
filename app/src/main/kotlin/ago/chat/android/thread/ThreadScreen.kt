package ago.chat.android.thread

import ago.chat.android.R
import ago.chat.android.core.domain.conversations.ConversationStateLabel
import ago.chat.android.core.domain.conversations.conversationStateLabel
import ago.chat.android.core.domain.net.NetworkFailure
import ago.chat.android.core.domain.visitorDisplayPrefixParts
import ago.chat.android.core.network.realtime.MessageDto
import ago.chat.android.thread.contactpanel.ContactDetailPanel
import ago.chat.android.thread.contactpanel.ContactPanelViewModel
import ago.chat.android.ui.components.networkFailureText
import ago.chat.android.ui.components.rememberTickingNow
import ago.chat.android.ui.components.shortElapsedText
import ago.chat.android.ui.icons.AgoIcons
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.mapNotNull
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * `26-15`: the thread screen - history, send, receive. Two decisions this file's own doc comment
 * states rather than leaving to be inferred from the code, per `docs/navigation.md`'s own framing that
 * both are worth stating in the open:
 *
 * ## The visitor chip: absent, not inert
 *
 * `navigation.md` draws the app bar's visitor identity as a tappable chip opening the (not-yet-built)
 * visitor context sheet, and asks this item to pick "present-but-inert" or "absent-until-then". This
 * screen picks **absent**: [TopAppBar]'s title below is [ThreadTitleBlock] rendered as plain,
 * non-interactive text — no `clickable`, no ripple, nothing that looks like it should respond to a
 * tap. The ticket's own reasoning is why: "a chip that does nothing when tapped is worse than no
 * chip" is a stronger, more specific claim than "a control that appears later is a layout change
 * nobody expects" — the latter is true of *any* control this app will ever add, and would argue
 * against ever shipping a screen incrementally at all. An inert chip actively teaches an operator that
 * tapping it does nothing, which is a worse thing to teach than simply not having drawn a tap target;
 * the identical hide-rather-than-disable posture `AttachmentUploadGrantToggle` states for itself. When
 * the visitor context sheet lands, the chip is a small, additive change to this same title slot - not
 * a redesign of it.
 *
 * ## The attach control
 *
 * Drawn — a real, visible paperclip — exactly when [ThreadRoute]'s own `hasAttachmentUploadGrant`
 * parameter is `true`, and omitted entirely otherwise (`navigation.md` §"The attach control": hidden,
 * never disabled, when the grant is absent — a disabled one would advertise a capability nobody could
 * use). What it does when shown is a deliberate stub: the picker, the presigned upload and the
 * `WorkManager` job are this item's own Out of scope ("Attachments themselves... a later item"), so
 * there is genuinely nothing yet to wire its tap to. This is a narrower promise than the visitor chip
 * makes — this item's whole job here is *which conversations show the control at all*, decided
 * correctly from the row's own [ago.chat.android.core.domain.conversations.ConversationSummary.hasAttachmentUploadGrant],
 * not what happens after a tap.
 *
 * `26-68`: `hasAttachmentUploadGrant` is `Boolean?`, not `Boolean` — a restored thread with no matching
 * queue row yet cannot honestly answer this either way, and the old `?: false`
 * (`ago.chat.android.shell.ConversationsTabHost`) hid a control an operator might actually be entitled
 * to with no sign anything was unknown. `null` and `false` both hide the paperclip — this item's own
 * scope stops at "never invent an answer", not "tell the operator apart 'no' from 'not sure yet'" —
 * but the type now forces every caller to say which one it means instead of a silent default doing it
 * for them. The restored-thread case resolves itself the moment
 * [ago.chat.android.conversations.ConversationListViewModel]'s own `refresh()` (already called
 * unconditionally from `init`, no new network call added for this) lands a queue that contains the
 * row — recomposition then carries the real value down with nothing further to wire.
 */
@Composable
public fun ThreadRoute(
    conversationId: String,
    visitorId: String?,
    emojiCreature: String?,
    emojiFood: String?,
    visitorName: String?,
    createdAt: String?,
    conversationState: String?,
    hasAttachmentUploadGrant: Boolean?,
    onBack: () -> Unit,
    /** `26-68`: `true` only when the row lookup ([ago.chat.android.shell.ConversationsTabHost]) has a
     * *confirmed-fresh* queue answer with no match at all — the permanent case its own Found section
     * names ("closed, or reassigned... never matches, ever"), not the merely-not-fetched-yet one. Feeds
     * [ThreadTitleBlock]'s own fallback text so that case is named on screen rather than left as a
     * title that stays blank forever with no explanation. */
    identityUnavailable: Boolean = false,
    // `26-147`: `conversation:read`, computed once from the operator's permission set by
    // `AppShellScreen`'s own `conversationsTab` default and threaded down through
    // `ConversationsTabHost` - the identical "the caller who holds the permission set computes the
    // Boolean" split every other gate in this app draws. Gates the contact-panel affordance
    // hide-not-disable (design Q7): `false` (the default every direct-construction test still gets) means
    // the affordance is never drawn, and the panel VM below is never opened.
    canReadContactDetail: Boolean = false,
    // `26-169`: `conversation:send`, computed once from the operator's permission set by `AppShellScreen`'s
    // own `conversationsTab` default and threaded down through `ConversationsTabHost` alongside
    // `canReadContactDetail`. Gates the КОНТАКТНЫЕ ДАННЫЕ section's row `⋮` (edit + set-assessment)
    // hide-not-disable (design Q7, `docs/design/26-156-*.md`); it never gates the section's existence or
    // its «Показать» reveal (both ride `conversation:read`, the panel's own gate). `false` (the default
    // every direct-construction test still gets) hides every row's `⋮`.
    canSendConversation: Boolean = false,
    // `26-149`: `conversation:tag`, computed once from the operator's permission set by `AppShellScreen`'s
    // own `conversationsTab` default and threaded down through `ConversationsTabHost` alongside
    // `canReadContactDetail`. Gates the tags section's write affordances hide-not-disable (design Q7); it
    // never gates the section's existence (reading tags rides `conversation:read`, the panel's own gate).
    // `false` (the default every direct-construction test still gets) hides the add/remove controls.
    canTagConversation: Boolean = false,
    // `26-150`: `conversation:note_write`, computed once from the operator's permission set by
    // `AppShellScreen`'s own `conversationsTab` default and threaded down through `ConversationsTabHost`
    // alongside `canReadContactDetail`/`canTagConversation`. Gates only the notes sub-screen's own
    // composer hide-not-disable (design Q7); it never gates the «Заметки команды» row or its count/list
    // (reading notes rides `conversation:read`, the panel's own gate, the identical split
    // `canTagConversation` above draws for the tags section). `false` (the default every direct-construction
    // test still gets) hides the composer.
    canWriteNote: Boolean = false,
    // `26-152`: `conversation:attachment_upload_grant`, computed once from the operator's permission set
    // by `AppShellScreen`'s own `conversationsTab` default and threaded down through `ConversationsTabHost`
    // alongside `canReadContactDetail`/`canTagConversation`/`canWriteNote`. Gates the contact-panel
    // attachment-upload section's whole existence (hide-not-disable, design Q7) - unlike the three
    // Booleans above, there is no separate read gate for this one to fall back to
    // ([ago.chat.android.thread.contactpanel.sections.AttachmentUploadSection]'s own doc comment). `false`
    // (the default every direct-construction test still gets) hides the section entirely.
    canGrantAttachmentUpload: Boolean = false,
    // `26-153`: `conversation:close`, computed once from the operator's permission set by
    // `AppShellScreen`'s own `conversationsTab` default and threaded down through `ConversationsTabHost`
    // alongside `canReadContactDetail`/`canTagConversation`/`canWriteNote`/`canGrantAttachmentUpload`.
    // Gates the contact-panel's «Закрыть диалог» button hide-not-disable (design Q7); independent of
    // `canRestrictVisitor` below - an operator may hold either, both, or neither. `false` (the default
    // every direct-construction test still gets) hides the button.
    canCloseConversation: Boolean = false,
    // `26-153`: `conversation:block`, computed the same way and threaded down alongside
    // `canCloseConversation`. Gates the contact-panel's reversible «Ограничить»/«Снять ограничение»
    // button hide-not-disable (design Q7). `false` (the default every direct-construction test still
    // gets) hides the button.
    canRestrictVisitor: Boolean = false,
    viewModel: ThreadViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // `26-147`: the panel VM is deliberately NOT a `hiltViewModel()` default parameter of this route.
    // [ThreadViewModel] above can be one only because [ago.chat.android.shell.ConversationsTabHost]
    // always passes it explicitly from an overridable provider, so that default expression is never
    // evaluated - which is exactly why the shell/back-contract instrumented tests (which compose this
    // real route over a fake `ThreadViewModel`, under a plain non-Hilt `ComponentActivity`) do not
    // crash on it. A second `hiltViewModel()` default here had no such override and fired the moment a
    // thread opened in those tests, crashing them. So the panel VM is obtained lazily, inside the
    // `if (showContactPanel)` block below, and only ever when the operator actually opens the sheet -
    // a code path no shell/back-contract test reaches (none taps the affordance), so none is dragged
    // through Hilt. `ContactDetailPanel` itself stays stateless (state + callbacks passed down), which
    // is what lets `ContactDetailPanelTest` drive it with a plain [ContactPanelUiState] and no Hilt.
    var showContactPanel by rememberSaveable { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current

    // The one "leave this thread" action, reached two ways - the app bar's own back arrow (passed to
    // `ThreadScreen` below) and the system back gesture/button (`BackHandler` below) - both routed
    // through the identical wrapper so neither one can skip releasing the hub subscription or
    // flushing the draft. Registering this `BackHandler` here, rather than one level up in
    // `SignedInHost` guarding on "is a thread open", is what lets that caller stay unaware of this
    // screen's own cleanup - it only ever has to know "the operator asked to leave", not how leaving
    // is implemented.
    val leaveThread: () -> Unit = {
        viewModel.close()
        onBack()
    }
    BackHandler(onBack = leaveThread)

    LaunchedEffect(conversationId) {
        viewModel.open(conversationId)
    }

    // `ThreadViewModel`'s own doc comment on why `ON_STOP` is what actually has to fire the draft
    // flush, rather than a `DisposableEffect`'s own `onDispose`: a process death can happen with no
    // "leaving the thread" involved at all, and `ON_STOP` is the lifecycle event Android guarantees
    // before that can happen. `ON_STOP` also fires on a plain device rotation - harmless here, since
    // flushing an unchanged draft is a no-op write - which is exactly why `close()` below is *not*
    // wired to this same observer: rotation must not release the hub subscription or reset this
    // screen's own state, only leaving the thread for real (`onBack`) should.
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_STOP) viewModel.flushDraft()
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    ThreadScreen(
        state = state,
        visitorId = visitorId,
        emojiCreature = emojiCreature,
        emojiFood = emojiFood,
        visitorName = visitorName,
        createdAt = createdAt,
        conversationState = conversationState,
        hasAttachmentUploadGrant = hasAttachmentUploadGrant,
        identityUnavailable = identityUnavailable,
        // `26-147`: hide-not-disable (design Q7) - the affordance is `null`, so `ThreadTitleBlock`
        // renders as plain, non-interactive text exactly as before, whenever the operator lacks
        // `conversation:read`. Present only when they hold it; opening the sheet is all this does, and the
        // panel VM + its one visitor-summary read for H4/H5 are created lazily inside the sheet block
        // below (see the top-of-route comment on why the VM must not be created before then).
        onOpenContactPanel = if (canReadContactDetail) ({ showContactPanel = true }) else null,
        onBack = leaveThread,
        onLoadOlder = viewModel::loadOlder,
        onRetryJoin = viewModel::retryJoin,
        onDraftChanged = viewModel::onDraftChanged,
        onSend = viewModel::sendClicked,
        onRetrySend = viewModel::retrySend,
        onDismissSendRefusal = viewModel::dismissSendRefusal,
        // `26-80`: the one signal `MessageList` reports upward - see `ThreadViewModel.markReadUpTo`'s
        // own doc comment for why this has to come from the list's actual scroll state rather than
        // simply `state.messages`' own newest entry.
        onNewestVisibleSequenceChanged = viewModel::markReadUpTo,
    )

    if (showContactPanel) {
        // The one `hiltViewModel()` for the panel - reached only here, when the operator has actually
        // opened the sheet. Scoped to the same `ViewModelStoreOwner` (the back-stack entry / activity)
        // whether it is created now or on a later open, so it survives the sheet closing and reopening.
        // `LaunchedEffect(conversationId)` fires the visitor-summary read for H4/H5 when the sheet opens
        // (and re-reads if this route is somehow reused for another conversation) - [ContactPanelViewModel.open]'s
        // own same-id guard makes a repeat a no-op.
        val contactPanelViewModel: ContactPanelViewModel = hiltViewModel()
        val contactPanelState by contactPanelViewModel.state.collectAsStateWithLifecycle()
        // `26-153`: keyed on `visitorId` too, not only `conversationId` - `ContactPanelViewModel.open`'s
        // own doc comment on why the restriction section needs a fresh call when a restored thread's
        // `visitorId` resolves from `null` to a real value with no `conversationId` change of its own to
        // key a repeat call on.
        LaunchedEffect(conversationId, visitorId) { contactPanelViewModel.open(conversationId, visitorId) }
        // `26-153`: the one-shot signal that «Закрыть диалог» succeeded - dismisses this sheet and then
        // reuses the identical `leaveThread` path the back arrow/gesture already takes (hub cleanup +
        // `onBack`), so a closed conversation returns the operator to the queue exactly the way any other
        // "leave this thread" does. Collected only while the sheet is up, the same scope every other
        // panel-specific effect here already has.
        LaunchedEffect(contactPanelViewModel) {
            contactPanelViewModel.conversationClosed.collect {
                showContactPanel = false
                leaveThread()
            }
        }
        ContactDetailPanel(
            state = contactPanelState,
            emojiCreature = emojiCreature,
            emojiFood = emojiFood,
            visitorName = visitorName,
            visitorId = visitorId,
            conversationState = conversationState,
            onRetrySummary = contactPanelViewModel::retry,
            onRevealContactDetail = contactPanelViewModel::revealContactDetail,
            onRetryContactDetails = contactPanelViewModel::retryContactDetails,
            // `26-169`: the КОНТАКТНЫЕ ДАННЫЕ section's edit + set-assessment callbacks + its
            // `conversation:send` gate, wired the same way the tags/notes callbacks below are - the VM stays
            // permission-agnostic, the gate lives here in the UI layer where the permission set is known.
            canSendConversation = canSendConversation,
            onStartEditContactDetail = contactPanelViewModel::startEditContactDetail,
            onEditContactDetailDraftChanged = contactPanelViewModel::onEditContactDetailDraftChanged,
            onSaveEditContactDetail = contactPanelViewModel::saveEditContactDetail,
            onCancelEditContactDetail = contactPanelViewModel::cancelEditContactDetail,
            onSetContactDetailAssessment = contactPanelViewModel::setContactDetailAssessment,
            // `26-149`: the tags section's write callbacks + its `conversation:tag` gate, wired the same way
            // the contact-details callbacks above are - the VM stays permission-agnostic (it always exposes
            // apply/remove), the gate lives here in the UI layer where the permission set is known.
            canTag = canTagConversation,
            onAddTag = contactPanelViewModel::applyTag,
            onRemoveTag = contactPanelViewModel::removeTag,
            onRetryTags = contactPanelViewModel::retryTags,
            // `26-150`: the notes section's own callbacks + its `conversation:note_write` gate, wired the
            // same way the tags callbacks above are - the VM stays permission-agnostic, the gate lives
            // here in the UI layer where the permission set is known.
            canWriteNote = canWriteNote,
            onNoteDraftChanged = contactPanelViewModel::onNoteDraftChanged,
            onAddNote = contactPanelViewModel::addNote,
            onRetryNotes = contactPanelViewModel::retryNotes,
            // `26-151`: the «Прошлые диалоги» section's own callbacks, wired the same way the sections
            // above are - the VM stays permission-agnostic (there is nothing to gate: Q6 made this section
            // strictly read-only), so no extra Boolean is threaded down for it, unlike `canTag`/`canWriteNote`.
            onRetryPastDialogs = contactPanelViewModel::retryPastDialogs,
            onLoadMorePastDialogs = contactPanelViewModel::loadMorePastDialogs,
            onOpenPastDialog = contactPanelViewModel::openPastDialog,
            onClosePastDialogHistory = contactPanelViewModel::closePastDialogHistory,
            onRetryPastDialogHistory = contactPanelViewModel::retryPastDialogHistory,
            onLoadOlderPastDialogHistory = contactPanelViewModel::loadOlderPastDialogHistory,
            // `26-152`: the attachment-upload section's own callbacks + its `conversation:attachment_upload_grant`
            // gate, wired the same way the sections above are - the VM stays permission-agnostic, the gate
            // lives here in the UI layer where the permission set is known.
            canGrantAttachmentUpload = canGrantAttachmentUpload,
            onToggleAttachmentUpload = contactPanelViewModel::toggleAttachmentUpload,
            onRetryAttachmentUpload = contactPanelViewModel::retryAttachmentUpload,
            // `26-153`: the panel's own final section - «Закрыть диалог» + reversible
            // «Ограничить»/«Снять ограничение» - its two callbacks + their independent gates, wired the
            // same way the sections above are. The success path for close is the `conversationClosed`
            // collector above, not a callback here - the VM reports the write landed, `ThreadRoute` owns
            // what leaving the thread means.
            canClose = canCloseConversation,
            onClose = contactPanelViewModel::closeConversation,
            canRestrict = canRestrictVisitor,
            onToggleRestriction = contactPanelViewModel::toggleRestriction,
            onRetryRestriction = contactPanelViewModel::retryRestriction,
            onDismiss = { showContactPanel = false },
        )
    }
}

/** The stateless half - [ThreadRoute] wires the [ThreadViewModel] above it, the same "route wires,
 * screen renders" split every other screen in this app already follows. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ThreadScreen(
    state: ThreadUiState,
    visitorId: String?,
    emojiCreature: String?,
    emojiFood: String?,
    visitorName: String?,
    createdAt: String?,
    conversationState: String?,
    hasAttachmentUploadGrant: Boolean?,
    onBack: () -> Unit,
    onLoadOlder: () -> Unit,
    onRetryJoin: () -> Unit,
    onDraftChanged: (String) -> Unit,
    onSend: () -> Unit,
    onRetrySend: () -> Unit,
    onDismissSendRefusal: () -> Unit,
    onNewestVisibleSequenceChanged: (Long) -> Unit,
    identityUnavailable: Boolean = false,
    // `26-147`: the contact-panel open affordance, or `null` when the operator lacks `conversation:read`
    // (hide-not-disable, design Q7). When present, `ThreadTitleBlock`'s own visitor identity block
    // becomes the tap target - the "small, additive change to this same title slot" this file's own
    // top-of-file doc comment predicted the visitor context sheet would land as, now that the sheet
    // exists. When `null`, the block stays the plain, non-interactive text it has always been.
    onOpenContactPanel: (() -> Unit)? = null,
) {
    // `26-40`: the app-bar subtitle's own age half - the mockup's short elapsed form, ticking on the
    // identical shared clock `ConversationListScreen`'s own row ages already read
    // (`ui.components.ElapsedText`). `createdAt` is `null` exactly when `ConversationsTabHost` opened
    // this screen with no matching queue row in hand - the one case this whole subtitle renders nothing
    // for, rather than a guessed age or a stray leading separator.
    val now = rememberTickingNow()
    val subtitle =
        createdAt?.let { started ->
            val elapsed = shortElapsedText(started, now)
            val stateWord = conversationState?.let { threadStateWord(conversationStateLabel(it)) }
            if (stateWord != null) "$stateWord · $elapsed" else elapsed
        }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Scaffold(
            topBar = {
                // `26-32`: no `Column` any more. This was an app bar with the retired
                // `HubConnectionDebugRow` under it on a line of its own — the identical leftover the
                // conversation list carried, and worse here, where every line taken from the app bar
                // is a line taken from the conversation itself.
                //
                // `26-88`: this app bar carries no `actions` block at all any more. It briefly held a
                // bare `HubConnectionDot` here (the pre-`26-77` dot every top-level screen also drew,
                // before `AccountAvatarAction` replaced it there) — a leftover this drill-down screen
                // was never in scope to receive its own account menu for, so the honest fix was
                // removing the stray dot outright rather than migrating it to a control this screen
                // has no use for. The hub's connection state is still visible one screen back, on the
                // conversation list's own `AccountAvatarAction`.
                TopAppBar(
                    navigationIcon = {
                        // `26-23`: the mockup's `i-back`, a real vector - this used to be a
                        // literal `Text("←")`, which is also what `AppShellScreen`'s retired
                        // `BottomDestination.emoji()` cited as its own precedent. Both are gone.
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = AgoIcons.Back,
                                contentDescription = stringResource(R.string.action_back),
                            )
                        }
                    },
                    title = {
                        // `26-147`: a tap target when [onOpenContactPanel] is non-null (the operator holds
                        // `conversation:read`), plain non-interactive text otherwise - see this file's own
                        // top-of-file doc comment on why "absent" was the right first shape and why the
                        // chip is additive now that the sheet exists.
                        ThreadTitleBlock(
                            emojiCreature = emojiCreature,
                            emojiFood = emojiFood,
                            visitorName = visitorName,
                            visitorId = visitorId,
                            identityUnavailable = identityUnavailable,
                            subtitle = subtitle,
                            onOpenContactPanel = onOpenContactPanel,
                        )
                    },
                )
            },
            bottomBar = {
                Composer(
                    draft = state.draft,
                    sending = state.sending,
                    hasAttachmentUploadGrant = hasAttachmentUploadGrant == true,
                    onDraftChanged = onDraftChanged,
                    onSend = onSend,
                )
            },
        ) { padding ->
            Column(modifier = Modifier.fillMaxSize().padding(padding)) {
                if (state.pendingRetry) {
                    DismissibleBanner(
                        message = stringResource(R.string.thread_send_pending_retry),
                        actionLabel = stringResource(R.string.action_retry),
                        onAction = onRetrySend,
                    )
                }
                state.sendRefusedMessage?.let { refusal ->
                    DismissibleBanner(
                        message = refusal,
                        actionLabel = stringResource(R.string.action_dismiss),
                        onAction = onDismissSendRefusal,
                    )
                }

                when {
                    state.joining -> LoadingBody()
                    // A join failure never leaves any message on screen (nothing was ever loaded) -
                    // the one signal this screen uses to tell "the initial join failed" apart from "a
                    // later 'load older' page failed", since both share the same `historyError` field.
                    state.messages.isEmpty() && state.historyError != null ->
                        JoinErrorBody(error = state.historyError, onRetry = onRetryJoin)

                    else ->
                        MessageList(
                            // `.weight(1f)` is resolved here, inside the enclosing `Column`'s own
                            // `ColumnScope`, and carried into `MessageList` as an ordinary `Modifier` -
                            // the standard way a scope-specific modifier crosses a composable boundary
                            // without that composable needing the scope itself as a receiver.
                            modifier = Modifier.weight(1f),
                            messages = state.messages,
                            canLoadOlder = state.canLoadOlder,
                            loadingOlder = state.loadingOlder,
                            historyError = state.historyError,
                            onLoadOlder = onLoadOlder,
                            onNewestVisibleSequenceChanged = onNewestVisibleSequenceChanged,
                        )
                }
            }
        }
    }
}

/**
 * `26-40`: the mockup's `.appbar .ttl.sm` / `.sub` pair — `docs/backlog/26-40-*.md`'s own Found: the
 * title line is the visitor's name alone (no eight-character code, no emoji glyphs), and a quiet
 * second line under it carries the conversation's state and its age when both are known.
 *
 * Reads [visitorDisplayPrefixParts] directly rather than delegating to
 * `ui.components.VisitorDisplayPrefix` (which this call site was the only caller of, and which drew the
 * id unconditionally with no opt-out) — the same choice `ConversationListScreen`'s own
 * `ConversationRowIdentityLine` already makes for the row's identity line: the rule about *which parts
 * of a visitor identity exist at all* stays stated exactly once, in [visitorDisplayPrefixParts]
 * (`:core:domain`), and only the *layout* differs per caller. The emoji pair itself is deliberately not
 * drawn here either, even as a small glyph beside the name: the mockup's own title is plain text
 * (`Лиса · Апельсин` is [VisitorDisplayPrefixParts.displayName]'s own fallback *wording*, not the emoji
 * glyphs plus that wording), and this app bar has no avatar slot for a pair to sit beside the way the
 * row's leading `VisitorAvatar` does.
 *
 * `26-68`: [visitorId] is nullable now, joining the other three fields this composable already treats
 * as honestly-absent — a restored thread with no matching queue row yet no longer has the
 * conversation's own id substituted into that slot (`ago.chat.android.shell.ConversationsTabHost`'s own
 * fix), so `parts.visitorId` can genuinely be `null` here. That changes nothing about *this* function's
 * own rendering, since `parts.visitorId` was never read here in the first place (only
 * [VisitorDisplayPrefixParts.displayName] is) — the fabricated value was dead data as far as this title
 * is concerned, never actually the eight-character string an operator saw. [identityUnavailable] is the
 * one genuinely new case this title has to render: `parts.displayName` is `null` in exactly that case
 * too (nothing is known about a row that was never found), so the fallback text below is what keeps a
 * *permanently* unmatched thread from sitting with a blank title forever with no explanation
 * (`docs/backlog/26-68-*.md`'s own Scope item 4) — never shown for the merely-not-fetched-yet case,
 * which still renders a blank title exactly as before, correctly, while it waits for the queue to
 * catch up.
 */
@Composable
private fun ThreadTitleBlock(
    emojiCreature: String?,
    emojiFood: String?,
    visitorName: String?,
    visitorId: String?,
    identityUnavailable: Boolean,
    subtitle: String?,
    onOpenContactPanel: (() -> Unit)? = null,
) {
    val parts = visitorDisplayPrefixParts(emojiCreature, emojiFood, visitorName, visitorId)
    val titleText = parts.displayName ?: stringResource(R.string.thread_identity_unavailable).takeIf { identityUnavailable }
    // `26-147`: the tap target only exists when [onOpenContactPanel] does - a `clickable` added
    // conditionally rather than a disabled one, the same hide-not-disable posture the composer's own
    // attach control already takes. The `Role.Button` + `contentDescription` name the action for a screen
    // reader; the `testTag` is `ContactDetailPanelTest`'s own hook, independent of the (Russian) title
    // text below.
    val affordanceLabel = stringResource(R.string.thread_open_contact_panel)
    val titleModifier =
        if (onOpenContactPanel != null) {
            Modifier
                .testTag(THREAD_CONTACT_PANEL_AFFORDANCE_TEST_TAG)
                .clickable(onClick = onOpenContactPanel)
                .semantics {
                    role = Role.Button
                    contentDescription = affordanceLabel
                }
        } else {
            Modifier
        }
    Column(modifier = titleModifier) {
        titleText?.let { name ->
            Text(
                text = name,
                // `.appbar .ttl.sm{font-size:17px; font-weight:700; letter-spacing:-.01em}` -
                // `titleLarge` is this app's own 17sp token-backed role (`Type.kt`); only the weight is
                // lifted, the same "nearest token, weight adjusted" move `ConversationRowIdentityLine`
                // already makes for its own bold name line.
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        subtitle?.let { text ->
            Text(
                text = text,
                // `.appbar .sub{font-size:11.5px; font-weight:500; color:var(--ink-soft)}` -
                // `onSurfaceVariant` is `--ink-soft` (`Theme.kt`'s own confirmed mapping); `labelSmall`
                // is the nearest token size (12sp/Medium).
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * `26-40`: [ConversationStateLabel]'s own prose half — the classification is [conversationStateLabel]'s
 * job (`:core:domain`, pure and testable); which Russian word each arm reads is `:app`'s, the identical
 * split `ConversationListScreen`'s own elapsed-time rendering already draws between [ElapsedLabel] and
 * its `stringResource` calls. `null` for [ConversationStateLabel.Unknown] - an empty or unrecognised
 * wire spelling renders no state word at all, never a guessed one.
 */
@Composable
private fun threadStateWord(label: ConversationStateLabel): String? =
    when (label) {
        ConversationStateLabel.Pending -> stringResource(R.string.conversation_state_pending)
        ConversationStateLabel.Waiting -> stringResource(R.string.conversation_state_waiting)
        ConversationStateLabel.Assigned -> stringResource(R.string.conversation_state_assigned)
        ConversationStateLabel.Closed -> stringResource(R.string.conversation_state_closed)
        ConversationStateLabel.Unknown -> null
    }

@Composable
private fun LoadingBody() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun JoinErrorBody(
    error: NetworkFailure,
    onRetry: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = stringResource(R.string.thread_join_failed_title), style = MaterialTheme.typography.titleMedium)
            Text(
                text = networkFailureText(error),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
            Button(onClick = onRetry, modifier = Modifier.padding(top = 16.dp)) {
                Text(text = stringResource(R.string.action_retry))
            }
        }
    }
}

@Composable
private fun DismissibleBanner(
    message: String,
    actionLabel: String,
    onAction: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onAction) { Text(text = actionLabel) }
    }
}

/**
 * `docs/backlog/26-15-*.md`: "the message list ordered by `sequence`, keyset paging upward". `messages`
 * arrives already sorted ascending (oldest first) by [ThreadViewModel.mergeAndRender]; this list is
 * rendered with `reverseLayout = true` so the newest message anchors the bottom of the viewport (the
 * ordinary chat convention) and scrolling *up* moves toward older ones - the "Load older messages"
 * button, an explicit tap rather than a silent infinite-scroll trigger (matching `ago-console`'s own
 * `Thread.tsx`), sits at the far end of the reversed list, which renders at the visual top.
 *
 * `26-80`: this is also the one place that can honestly answer "what has the operator actually seen" -
 * [onNewestVisibleSequenceChanged] reports the [MessageDto.sequence] of whichever currently-visible
 * item sits closest to the bottom (the smallest index in [listState]'s own reversed layout, so the
 * newest of whatever is genuinely on screen, not [messages]' own newest entry). One persistent
 * `LaunchedEffect` keyed on [listState] - not on [messages], which would restart it on every merge and
 * throw away `distinctUntilChanged`'s memory of the last value reported - covers both triggers this
 * needs: a new message changing [newestFirst] (via [rememberUpdatedState], so the collector always
 * reads the current list) and the operator scrolling to reveal a different item, with no list change
 * at all. [ThreadViewModel.markReadUpTo]'s own doc comment covers why the console's simpler
 * "just use the newest loaded message" cannot be ported as-is - it depends on `Thread` always
 * re-scrolling to a new arrival, which this list does not do.
 *
 * `26-151`: `internal` rather than `private` — the contact-detail panel's «Прошлые диалоги» read-only
 * history view ([ago.chat.android.thread.contactpanel.sections.PastDialogsSection]) reuses this exact
 * composable to render one past conversation's messages, per design Q6 and the S-I scope ("reuse the
 * message renderer, no composer/actions"). This function already has no composer and no swipe/tag/close
 * actions of its own — those live in [ThreadScreen]'s `Scaffold` (the `Composer` in `bottomBar`) and
 * elsewhere entirely — so it was already the read-only list the past-dialogs view needs; only its
 * visibility needed to widen, not its shape. [onNewestVisibleSequenceChanged] is a no-op for a past
 * conversation (marking read applies only to the live, currently-assigned one -
 * [ago.chat.android.thread.ThreadViewModel.markReadUpTo]'s own contract), and [canLoadOlder]/[onLoadOlder]
 * page a past conversation's own history exactly the way they page the live one's.
 */
@Composable
internal fun MessageList(
    messages: List<MessageDto>,
    canLoadOlder: Boolean,
    loadingOlder: Boolean,
    historyError: NetworkFailure?,
    onLoadOlder: () -> Unit,
    onNewestVisibleSequenceChanged: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val newestFirst = messages.asReversed()
    val listState = rememberLazyListState()
    val currentNewestFirst by rememberUpdatedState(newestFirst)
    val currentOnNewestVisibleSequenceChanged by rememberUpdatedState(onNewestVisibleSequenceChanged)

    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.minOfOrNull { it.index } }
            .mapNotNull { minIndex -> minIndex?.let { currentNewestFirst.getOrNull(it)?.sequence } }
            .distinctUntilChanged()
            .collect { sequence -> currentOnNewestVisibleSequenceChanged(sequence) }
    }

    LazyColumn(
        state = listState,
        reverseLayout = true,
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(vertical = 8.dp),
    ) {
        items(newestFirst, key = { it.id }) { message -> MessageBubble(message) }

        if (canLoadOlder) {
            item(key = "load-older") {
                Box(modifier = Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
                    if (loadingOlder) {
                        CircularProgressIndicator(modifier = Modifier.padding(8.dp))
                    } else {
                        TextButton(onClick = onLoadOlder) {
                            Text(text = stringResource(R.string.thread_load_older_action))
                        }
                    }
                }
            }
        }

        // A "load older" failure, not a join failure - the caller above only reaches this composable
        // once the join itself succeeded, so any `historyError` here is this list's own to show.
        historyError?.let { error ->
            item(key = "history-error") {
                DismissibleBanner(
                    message = networkFailureText(error),
                    actionLabel = stringResource(R.string.action_retry),
                    onAction = onLoadOlder,
                )
            }
        }
    }
}

/**
 * `26-23`: the mockup's `.bub`, which this used to miss in three separate ways rather than one.
 *
 * **Shape.** `.bub{border-radius:16px}` with `.bub.in{border-bottom-left-radius:5px}` /
 * `.bub.out{border-bottom-right-radius:5px}` — a tail on the corner nearest its author, the ordinary
 * chat convention. The old symmetric `RoundedCornerShape(14.dp)` gave both directions the same
 * outline, so the only thing distinguishing them was which side of the screen they sat on.
 *
 * **Fill.** `.bub.out{background:var(--brand); color:#fff}` — *solid* brand with white text, which is
 * `primary`/`onPrimary`. The old `primaryContainer` is the brand *tint*, a pale lavender-blue; against
 * the visitor's own `surfaceVariant` the two read as near-identical washes rather than as "mine" and
 * "theirs". `.bub.in{background:var(--sunken); color:var(--ink)}` maps to `surfaceVariant` (which
 * `Theme.kt` binds to `--ago-surface-sunken`, confirmed rather than assumed) — but with `onSurface`
 * text, *not* the `onSurfaceVariant` that `Surface` would otherwise infer from the container, because
 * the mockup asks for `--ink` here and `Theme.kt` maps `--ink-soft`, not `--ink`, to
 * `onSurfaceVariant`.
 *
 * **The timestamp.** `.bub .t{opacity:.72}` — an alpha over *whatever the bubble's own text colour is*,
 * which is why it now reads [LocalContentColor] rather than naming `onSurfaceVariant` outright. The
 * old fixed colour was a real defect the moment the outgoing bubble became solid brand: a dark grey
 * timestamp on a saturated brand fill is close to unreadable.
 *
 * `.bub{max-width:76%}` is expressed as a weighted pair — a gutter that takes the remaining 24% and a
 * bubble that may take *up to* the other 76% (`fill = false`) — because Compose has no percentage
 * `max-width` modifier, and `fillMaxWidth(0.76f)` would make every bubble exactly that wide rather
 * than at most.
 *
 * **The accessible name (`26-65`).** Everything above is visual — side, fill, shape — and none of it
 * reaches a screen reader; before this, the `Column` below contributed two unlabelled `Text` nodes
 * (body, then a bare `HH:mm`) with no signal at all of who sent either one. The fix is the identical
 * shape `26-64` already applies to a conversation-list row: `Modifier.semantics(mergeDescendants =
 * true) { contentDescription = … }` on the bubble [Surface] itself, with an explicit
 * [messageBubbleContentDescription] that *overrides* whatever the merge would otherwise concatenate
 * from the children, rather than adding to it — the same override `ConversationRow`'s own doc comment
 * relies on, and the reason a visitor's or system bubble reading this description aloud never also
 * speaks the delivery tick glyph appended to the operator's own `.t` line: that glyph lives only in the
 * child `Text`'s own text, which this explicit override replaces rather than reads. `isOperator` above
 * stays a two-way visual split on purpose (`26-23`'s shape is out of scope here); the *spoken* author is
 * a genuine three-way [MessageAuthorKind], because a system notice folded into "the visitor" would be
 * announced as if the customer wrote it (`docs/backlog/26-65-*.md`'s own Scope item 2, and `26-42`'s own
 * Out of scope, which already flagged the third kind as real).
 *
 * **The delivery tick.** `26-42`: one tick once the server has an operator's message, two once
 * [MessageDto.deliveredAt] is set — this mockup screen's own caption states plainly what the second one
 * means and what it does not: "Порядок — это назначаемый сервером `sequence`, никогда не часы, а
 * вторая галочка — это `Message.DeliveredAt`, собственное подтверждение виджета, **а не квитанция об
 * отправке**." Appended inside the same `Text` as the clock time — the mockup's own `.t` line reads
 * `09:39 ✓✓` as one string, not two elements — and so drawn in the identical [LocalContentColor] alpha
 * this timestamp already reads, for the identical reason that alpha exists at all. A literal glyph, not
 * a vector: `26-23`'s own lesson was about a literal character standing in for a *tap target* ("←"/"📎"
 * as a button's whole content) — a non-interactive tick appended to a string already being built by
 * hand for this exact line is the case that item's own report left open, and the mockup's caption above
 * draws the identical glyphs. A visitor's or system message never carries a tick — [deliveryTick] is
 * only ever consulted when [isOperator] is true.
 */
@Composable
private fun MessageBubble(message: MessageDto) {
    val isOperator = message.authorKind == "Operator"
    val description = messageBubbleContentDescription(message)
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = if (isOperator) Arrangement.End else Arrangement.Start,
    ) {
        if (isOperator) {
            Spacer(modifier = Modifier.weight(BUBBLE_GUTTER_WEIGHT))
        }
        Surface(
            modifier =
                Modifier
                    .weight(BUBBLE_MAX_WIDTH_WEIGHT, fill = false)
                    .testTag(messageBubbleContentTestTag(message.id))
                    .semantics(mergeDescendants = true) { contentDescription = description },
            color = if (isOperator) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
            contentColor = if (isOperator) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
            shape = bubbleShape(isOperator = isOperator),
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                Text(text = message.body, style = MaterialTheme.typography.bodyMedium)
                clockTimeOrNull(message.createdAt)?.let { time ->
                    Text(
                        text = if (isOperator) "$time ${deliveryTick(message.deliveredAt)}" else time,
                        style = MaterialTheme.typography.labelSmall,
                        color = LocalContentColor.current.copy(alpha = BUBBLE_TIMESTAMP_ALPHA),
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }
        if (!isOperator) {
            Spacer(modifier = Modifier.weight(BUBBLE_GUTTER_WEIGHT))
        }
    }
}

/**
 * `26-65`: [MessageDto.authorKind]'s three wire values (`"Operator"` / `"Visitor"` / `"System"`,
 * `MessageDto.kt`'s own doc comment), named for the *spoken* description below — a genuine three-way
 * split, unlike [MessageBubble]'s own `isOperator`, which deliberately keeps folding the visitor and
 * the system together for the *visual* treatment (`26-23`'s shape is out of scope here). An unset or
 * unrecognised value (`""`, the DTO's own default) falls to [Visitor] — the identical fallback
 * `isOperator`'s `== "Operator"` comparison already gives visually, kept rather than invented anew.
 */
private enum class MessageAuthorKind { Operator, Visitor, System }

private fun messageAuthorKind(authorKind: String): MessageAuthorKind =
    when (authorKind) {
        "Operator" -> MessageAuthorKind.Operator
        "System" -> MessageAuthorKind.System
        else -> MessageAuthorKind.Visitor
    }

/**
 * `26-65`: the bubble's one spoken sentence — author, then body, then time, the order
 * `docs/backlog/26-65-*.md`'s own Scope item 1 states — assembled with [listOfNotNull] and
 * [joinToString], the identical shape `ConversationListScreen`'s own `conversationRowContentDescription`
 * already uses for a row's spoken form. The author words are `ago-console`'s own `Thread.tsx`
 * `authorLabel` mapping, named the same way on this second surface rather than invented afresh
 * (`R.string.message_bubble_author_operator`/`_visitor`/`_system` — "Оператор"/"Посетитель"/"Система").
 * A blank [MessageDto.body] (an attachment-only message, `MessageDto.kt`'s own `attachmentId`) is
 * dropped from the sentence rather than read as an empty clause, the same "an absent part supplies no
 * trace" rule the row's own description already follows.
 *
 * The time never reads as bare `HH:mm` digits with nothing marking what they are — `message_bubble_time`
 * ("в %1$s") frames it explicitly as a time-of-day, the same move the row's own elapsed clauses make for
 * a number that would otherwise be ambiguous out of visual context. [clockTimeOrNull]'s own null case
 * (an unparseable `createdAt`) drops the time clause entirely rather than speaking a made-up one — no
 * change to that function's own "never invented, rendered honestly" posture.
 */
@Composable
private fun messageBubbleContentDescription(message: MessageDto): String {
    val authorClause =
        when (messageAuthorKind(message.authorKind)) {
            MessageAuthorKind.Operator -> stringResource(R.string.message_bubble_author_operator)
            MessageAuthorKind.Visitor -> stringResource(R.string.message_bubble_author_visitor)
            MessageAuthorKind.System -> stringResource(R.string.message_bubble_author_system)
        }
    val timeClause = clockTimeOrNull(message.createdAt)?.let { time -> stringResource(R.string.message_bubble_time, time) }
    return listOfNotNull(authorClause, message.body.takeIf { it.isNotBlank() }, timeClause).joinToString(separator = ". ")
}

/** `26-65`: this bubble's own hook for `MessageBubbleSemanticsTest` — a `testTag` keyed by
 * [MessageDto.id] rather than a query built on the (Russian, wording-sensitive) `contentDescription`
 * itself, the identical reasoning `CONVERSATION_ROW_CONTENT_TEST_TAG`'s own doc comment gives. */
internal fun messageBubbleContentTestTag(messageId: String): String = "messageBubbleContent:$messageId"

/** `26-147`: `ContactDetailPanelTest`'s own hook onto the app-bar tap target that opens the contact
 * panel - keyed here rather than queried by the (Russian) title text, the identical reasoning
 * [messageBubbleContentTestTag] gives. The node carrying it exists only when the operator holds
 * `conversation:read` (hide-not-disable), which is exactly what the gating test asserts. */
internal const val THREAD_CONTACT_PANEL_AFFORDANCE_TEST_TAG: String = "threadContactPanelAffordance"

/** `26-42`: one tick ("✓") — the server has the message — until [deliveredAt] is set, then two
 * ("✓✓") — the visitor's own widget acknowledged it. Never a third state: there is no read receipt in
 * this product (`docs/backlog/26-42-*.md`'s own Out of scope), so [deliveredAt] absent-or-present is
 * the whole of it. */
private fun deliveryTick(deliveredAt: String?): String = if (deliveredAt != null) TICK_DELIVERED else TICK_SENT

private const val TICK_SENT = "✓"
private const val TICK_DELIVERED = "✓✓"

/** `.bub{border-radius:16px}` with the one tail corner at 5px — bottom-start for the visitor's
 * bubbles, bottom-end for the operator's. */
private fun bubbleShape(isOperator: Boolean): RoundedCornerShape =
    RoundedCornerShape(
        topStart = BUBBLE_CORNER,
        topEnd = BUBBLE_CORNER,
        bottomEnd = if (isOperator) BUBBLE_TAIL_CORNER else BUBBLE_CORNER,
        bottomStart = if (isOperator) BUBBLE_CORNER else BUBBLE_TAIL_CORNER,
    )

private val BUBBLE_CORNER = 16.dp
private val BUBBLE_TAIL_CORNER = 5.dp
private const val BUBBLE_MAX_WIDTH_WEIGHT = 0.76f
private const val BUBBLE_GUTTER_WEIGHT = 1f - BUBBLE_MAX_WIDTH_WEIGHT
private const val BUBBLE_TIMESTAMP_ALPHA = 0.72f

/** `null` for anything that fails to parse - the same "never invented, rendered honestly" posture
 * `ago.chat.android.core.domain.conversations.elapsedSince` already takes for a malformed `createdAt`,
 * rather than throwing out of a composable or showing a made-up time. Rendered in the device's own
 * zone - the operator reading this screen, not the visitor's. */
private fun clockTimeOrNull(createdAt: String): String? =
    runCatching {
        OffsetDateTime.parse(createdAt).atZoneSameInstant(ZoneId.systemDefault()).format(CLOCK_FORMAT)
    }.getOrNull()

private val CLOCK_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

@Composable
private fun Composer(
    draft: String,
    sending: Boolean,
    hasAttachmentUploadGrant: Boolean,
    onDraftChanged: (String) -> Unit,
    onSend: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Column {
            // `26-41`: `.composer{border-top:1px solid var(--line)}` - `--line` is `outlineVariant`
            // (`Theme.kt`, confirmed rather than assumed). Without this the composer and the message
            // list above it shared an edge with nothing drawn on it at all.
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = ComposerHorizontalPadding, vertical = ComposerVerticalPadding),
                verticalAlignment = Alignment.CenterVertically,
                // `.composer{gap:9px}` - one gap, applied evenly on both sides of the field regardless
                // of whether the paperclip is drawn at all, rather than a lone `Spacer` on one side of
                // it (the shape this replaced: no gap before the field, an 8dp `Spacer` only after it).
                horizontalArrangement = Arrangement.spacedBy(ComposerGap),
            ) {
                if (hasAttachmentUploadGrant) {
                    // A real, visible control - not disabled - whose tap does nothing yet. See this file's
                    // own top-of-file doc comment on why that stub is the honest shape for this item.
                    // `26-23` swapped its literal `"📎"` for the mockup's own `i-clip` vector; what the
                    // control *does* is untouched, and still deliberately nothing.
                    IconButton(onClick = { }) {
                        Icon(
                            imageVector = AgoIcons.Clip,
                            contentDescription = stringResource(R.string.thread_composer_attach),
                        )
                    }
                }
                ComposerField(
                    draft = draft,
                    onDraftChanged = onDraftChanged,
                    modifier = Modifier.weight(1f),
                )
                // `26-23`: the mockup's `.iconbtn.tinted` - a circular brand-filled button carrying the
                // `i-send` paper plane, not a text-labelled `Button`. `FilledIconButton`'s own defaults
                // already *are* that description (`primary` container, `onPrimary` content, circular), so
                // nothing about the shape is restated here. `thread_composer_send` survives as the
                // control's accessible name rather than being deleted with the visible label: a send
                // button that a screen reader announces as "button" and nothing else is worse than the
                // text one it replaces.
                FilledIconButton(onClick = onSend, enabled = draft.isNotBlank() && !sending) {
                    Icon(
                        imageVector = AgoIcons.Send,
                        contentDescription = stringResource(R.string.thread_composer_send),
                    )
                }
            }
        }
    }
}

/**
 * `26-41`: the mockup's `.field` — a 40dp, fully-rounded, sunken pill (`docs/backlog/26-41-*.md`'s own
 * Found), never Material 3's default filled `TextField`: that control is 56dp tall with 4dp
 * top-corners-only and carries the filled variant's own underline indicator in every state, none of
 * which the mockup draws. Built on [BasicTextField] rather than [TextField] with an overridden shape and
 * colours — `TextField`'s own minimum height and internal label/indicator layout are built around
 * Material 3's filled-field spec, and fighting that spec down to a literal 40dp pill with no indicator
 * anywhere is more code, and less certain to actually have no indicator in every state, than drawing the
 * handful of things a pill needs (fill, shape, padding, a placeholder) directly on the same
 * `BasicTextField` primitive `TextField` itself is built on. `heightIn(min = ...)` rather than a fixed
 * `height` is what keeps `maxLines = 5` growth working: the pill is exactly 40dp tall at rest and taller
 * once the draft wraps, the identical growth the old `TextField` already had.
 */
@Composable
private fun ComposerField(
    draft: String,
    onDraftChanged: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // `.field{font-size:13.5px}` - no existing type-scale token sits at 13.5sp (`Type.kt`'s own scale is
    // 12/13/15/17/20/22), so this is the mockup's own literal, named here with its CSS rule rather than
    // silently rounded to a nearby token - the identical "traceable, not invented" treatment this file's
    // own `BUBBLE_*` constants already get below.
    val textStyle = TextStyle(fontSize = ComposerFieldFontSize, color = MaterialTheme.colorScheme.onSurface)
    Surface(
        modifier = modifier.heightIn(min = ComposerFieldHeight),
        shape = CircleShape,
        // `.field{background:var(--sunken)}` - `surfaceVariant` is `--sunken` (`Theme.kt`'s own
        // confirmed mapping, the identical role the visitor's own message bubble already reads).
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        // `.field{padding:0 14px}` - horizontal padding only; the mockup centres its content with
        // `align-items:center`, which [Alignment.CenterStart] below gives for free without an invented
        // vertical padding of its own.
        Box(modifier = Modifier.padding(horizontal = ComposerFieldHorizontalPadding), contentAlignment = Alignment.CenterStart) {
            BasicTextField(
                value = draft,
                onValueChange = onDraftChanged,
                modifier = Modifier.fillMaxWidth(),
                textStyle = textStyle,
                maxLines = COMPOSER_MAX_LINES,
                cursorBrush = SolidColor(MaterialTheme.colorScheme.onSurface),
                // The placeholder as `BasicTextField`'s own `decorationBox`, not a sibling `Text` beside
                // it: a sibling draws to the same pixels but leaves two unrelated semantics nodes behind,
                // which is exactly what broke `BackContractDialogsTabTest.clause6` on real CI - `onNodeWithText`
                // found the plain placeholder node instead of the one carrying `RequestFocus`/`SetText`.
                // `decorationBox` renders inside the field's own node, so the placeholder and the editable
                // text share the one semantics identity a screen reader and a Compose test both expect.
                decorationBox = { innerTextField ->
                    if (draft.isEmpty()) {
                        Text(
                            text = stringResource(R.string.thread_composer_placeholder),
                            style = textStyle,
                            // `.field{color:var(--ink-faint)}` for the mockup's own placeholder text -
                            // this app's `ColorScheme` has no role wired to `--ink-faint` (`Theme.kt`'s
                            // own DERIVED/CARRIED OVER accounting), so `onSurfaceVariant` (`--ink-soft`,
                            // one step darker) is the nearest already-public role rather than a new one
                            // added for this single call site.
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    innerTextField()
                },
            )
        }
    }
}

// `26-41`: the mockup's own composer metrics, named once here with the CSS rule each one comes from -
// nothing below is a chosen number.
//
// `.composer{padding:9px 12px}`
private val ComposerHorizontalPadding = 12.dp
private val ComposerVerticalPadding = 9.dp

// `.composer{gap:9px}`
private val ComposerGap = 9.dp

// `.field{height:40px}`
private val ComposerFieldHeight = 40.dp

// `.field{padding:0 14px}`
private val ComposerFieldHorizontalPadding = 14.dp

// `.field{font-size:13.5px}`
private val ComposerFieldFontSize = 13.5.sp

private const val COMPOSER_MAX_LINES = 5
