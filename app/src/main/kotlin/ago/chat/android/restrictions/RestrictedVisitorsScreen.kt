package ago.chat.android.restrictions

import ago.chat.android.R
import ago.chat.android.core.domain.VisitorEmojiPair
import ago.chat.android.core.domain.net.NetworkFailure
import ago.chat.android.core.domain.restrictions.RestrictionKind
import ago.chat.android.core.domain.restrictions.VisitorRestriction
import ago.chat.android.core.domain.shortId
import ago.chat.android.ui.components.networkFailureText
import ago.chat.android.ui.components.rememberTickingNow
import ago.chat.android.ui.components.visitorEmojiPairName
import ago.chat.android.ui.icons.AgoIcons
import ago.chat.android.ui.theme.agoStatusColors
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * `26-227`: «Ограниченные посетители» — a `site:configure` operator's compliance/oversight screen, not
 * a live queue (`docs/design/tenant-modules-restrictions-android.md` §1: "no auto-refresh poll"; a
 * manual «Обновить» in the top bar and after a lift is all this screen offers). Obtains its own
 * [RestrictedVisitorsViewModel] via [hiltViewModel] — the identical wiring
 * [ago.chat.android.analytics.PhoneRevealsReportRoute] already establishes for its closest sibling
 * screen; because [ago.chat.android.shell.ConversationsTabHost] composes this only while the overflow's
 * own menu item is open, that view model — and its first [ago.chat.android.core.domain.restrictions.VisitorRestrictionApi.list]
 * call — come into existence only when an operator actually asks for it.
 */
@Composable
public fun RestrictedVisitorsRoute(
    onBack: () -> Unit,
    canLift: Boolean,
    viewModel: RestrictedVisitorsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    RestrictedVisitorsScreen(
        state = state,
        canLift = canLift,
        onBack = onBack,
        onRefresh = viewModel::refresh,
        onLoadMore = viewModel::loadMore,
        onLift = viewModel::liftRestriction,
    )
}

/**
 * The stateless half — every future UI test targets this function directly, the same "route wires,
 * screen renders" split every other screen in this app already follows. A drill-in reached from the
 * Диалоги overflow, so it draws its own back arrow rather than a bottom-nav tab's plain title (the
 * identical shape [ago.chat.android.analytics.PhoneRevealsReportScreen] already takes for its own
 * drill-in).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RestrictedVisitorsScreen(
    state: RestrictedVisitorsUiState,
    canLift: Boolean,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    onLift: (restrictionId: String, visitorId: String) -> Unit,
) {
    // `ago.chat.android.conversations.ConversationListScreen`'s own established idiom for "what does
    // 'now' mean to this screen" - see `RestrictedVisitorsUiState`'s own doc comment for why this is a
    // render-time read rather than a value baked into the state.
    val now = rememberTickingNow()

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(text = stringResource(R.string.restricted_visitors_title)) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(imageVector = AgoIcons.Back, contentDescription = stringResource(R.string.action_back))
                        }
                    },
                    actions = {
                        // A compliance screen, not a live queue - no auto-refresh poll, only this manual
                        // action and the reload that already follows a successful lift
                        // (`RestrictedVisitorsViewModel.liftRestriction`'s own doc comment).
                        TextButton(onClick = onRefresh) {
                            Text(text = stringResource(R.string.restricted_visitors_refresh_action))
                        }
                    },
                )
            },
        ) { padding ->
            Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                when (state) {
                    RestrictedVisitorsUiState.Loading -> LoadingBody()

                    is RestrictedVisitorsUiState.Failed -> RefusalBody(reason = state.reason, onRetry = onRefresh)

                    is RestrictedVisitorsUiState.Loaded ->
                        if (state.rows.isEmpty()) {
                            EmptyBody(stringResource(R.string.restricted_visitors_empty))
                        } else {
                            RestrictionsList(
                                state = state,
                                now = now,
                                canLift = canLift,
                                onLoadMore = onLoadMore,
                                onLift = onLift,
                            )
                        }
                }
            }
        }
    }
}

@Composable
private fun LoadingBody() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun EmptyBody(text: String) {
    Box(modifier = Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text = text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The one real load this screen depends on failed, and there is nothing else to show in its place —
 * the identical title/detail/Retry shape [ago.chat.android.shell.AppShellScreen]'s own
 * `PermissionsLoadFailedScreen` and [ago.chat.android.conversations.ConversationListScreen]'s own
 * `QueueLoadFailedBody` already draw for the identical situation on their own screens. A third,
 * independent copy rather than a shared composable, the same restraint those two files' own doc
 * comments state for each other - a fourth caller would be the moment to actually extract one. */
@Composable
private fun RefusalBody(
    reason: NetworkFailure,
    onRetry: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = networkFailureText(reason),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onRetry, modifier = Modifier.padding(top = 16.dp)) {
                Text(text = stringResource(R.string.action_retry))
            }
        }
    }
}

@Composable
private fun RestrictionsList(
    state: RestrictedVisitorsUiState.Loaded,
    now: OffsetDateTime,
    canLift: Boolean,
    onLoadMore: () -> Unit,
    onLift: (restrictionId: String, visitorId: String) -> Unit,
) {
    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 8.dp)) {
        items(state.rows, key = { it.id }) { restriction ->
            RestrictionRow(
                restriction = restriction,
                status = restrictionStatus(restriction, now),
                canLift = canLift,
                lifting = state.liftingId == restriction.id,
                onLift = { onLift(restriction.id, restriction.visitorId) },
            )
            HorizontalDivider()
        }

        state.liftError?.let { error ->
            item(key = "lift-error") {
                Text(
                    text =
                        when (error) {
                            is LiftRestrictionError.Refused -> error.detail
                            is LiftRestrictionError.Failed -> stringResource(R.string.restricted_visitors_lift_failed)
                        },
                    style = MaterialTheme.typography.bodySmall,
                    color = agoStatusColors().dangerText,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }

        // `docs/design/tenant-modules-restrictions-android.md` §1.4: the control disappears once the
        // cursor is exhausted - never disabled, the identical hide-rather-than-grey rule
        // [ago.chat.android.analytics.PhoneRevealsReportScreen]'s own doc comment states.
        if (state.nextBeforeId != null) {
            item(key = "load-more") {
                Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                    if (state.loadingMore) {
                        CircularProgressIndicator()
                    } else {
                        TextButton(onClick = onLoadMore) {
                            Text(text = stringResource(R.string.restricted_visitors_load_more))
                        }
                    }
                }
            }
        }
    }
}

/**
 * One restriction row — visitor short-code + kind badge, a status pill, when/by whom/until, and — only
 * on an active row, and only when the operator holds `conversation:mark_spam`/`conversation:block`
 * ([canLift], gated by the caller — hide, not disable) — a ghost «Снять» button behind its own confirm
 * dialog (`docs/design/tenant-modules-restrictions-android.md` §1.4).
 */
@Composable
private fun RestrictionRow(
    restriction: VisitorRestriction,
    status: RestrictionStatus,
    canLift: Boolean,
    lifting: Boolean,
    onLift: () -> Unit,
) {
    val visitorLabel = restrictedVisitorLabel(restriction)
    val zone = ZoneId.systemDefault()

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text = visitorLabel, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
            KindBadge(kind = restriction.kind)
            StatusPill(status = status)
        }
        Text(
            text = restrictedAtLabel(restriction, zone),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
        Text(
            text = stringResource(R.string.restricted_visitors_restricted_by, shortId(restriction.restrictedBy)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp),
        )
        Text(
            text = expiresAtLabel(restriction, zone),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp),
        )

        if (canLift && status == RestrictionStatus.Active) {
            LiftControl(
                visitorLabel = visitorLabel,
                lifting = lifting,
                onLift = onLift,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun LiftControl(
    visitorLabel: String,
    lifting: Boolean,
    onLift: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirming by rememberSaveable { mutableStateOf(false) }

    TextButton(onClick = { confirming = true }, enabled = !lifting, modifier = modifier) {
        Text(
            text =
                stringResource(
                    if (lifting) R.string.restricted_visitors_lifting_action else R.string.restricted_visitors_lift_action,
                ),
        )
    }

    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text(text = stringResource(R.string.restricted_visitors_lift_confirm_title)) },
            text = { Text(text = stringResource(R.string.restricted_visitors_lift_confirm_body, visitorLabel)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirming = false
                        onLift()
                    },
                ) {
                    Text(text = stringResource(R.string.restricted_visitors_lift_confirm_action))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirming = false }) {
                    Text(text = stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

/** `docs/design/tenant-modules-restrictions-android.md` §1.4: `Spam` = accent/brand tint, `Block` =
 * danger tint - the identical [Surface] + rounded-corner shape
 * [ago.chat.android.conversations.ConversationListScreen]'s own `StatusPill` already draws, restated
 * here since that one is `private` to its own file. */
@Composable
private fun KindBadge(kind: RestrictionKind) {
    val (container, content) =
        when (kind) {
            RestrictionKind.Spam -> MaterialTheme.colorScheme.primary to MaterialTheme.colorScheme.onPrimary
            RestrictionKind.Block -> MaterialTheme.colorScheme.error to MaterialTheme.colorScheme.onError
        }
    val labelRes =
        when (kind) {
            RestrictionKind.Spam -> R.string.restricted_visitors_kind_spam
            RestrictionKind.Block -> R.string.restricted_visitors_kind_block
        }
    Surface(color = container, contentColor = content, shape = RoundedCornerShape(5.dp)) {
        Text(
            text = stringResource(labelRes),
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun StatusPill(status: RestrictionStatus) {
    val label =
        when (status) {
            RestrictionStatus.Active -> stringResource(R.string.restricted_visitors_status_active)
            RestrictionStatus.Expired -> stringResource(R.string.restricted_visitors_status_expired)
            RestrictionStatus.Lifted -> stringResource(R.string.restricted_visitors_status_lifted)
        }
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        shape = RoundedCornerShape(5.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
        )
    }
}

/**
 * `reference_visitor_emoji_pair_is_stored`: the visitor's own **stored** emoji pair, read straight off
 * the row this screen already fetched — never hash-derived from [VisitorRestriction.visitorId]. Renders
 * «Сова · Клубника (a0f3c952)» when the pair is present (the identical
 * [ago.chat.android.ui.components.visitorEmojiPairName] table every other screen in this app already
 * reads), or the bare short id alone when a visitor row predates the pair — never a guessed name for one
 * this screen was never given.
 */
@Composable
private fun restrictedVisitorLabel(restriction: VisitorRestriction): String {
    val creature = restriction.emojiCreature
    val food = restriction.emojiFood
    val shortVisitorId = shortId(restriction.visitorId)
    return if (creature != null && food != null) {
        val pairName = visitorEmojiPairName(VisitorEmojiPair(creature, food))
        stringResource(R.string.restricted_visitors_visitor_label, pairName, shortVisitorId)
    } else {
        shortVisitorId
    }
}

/** `d MMMM, HH:mm` -> "14 марта, 09:00" (ru) / "14 March, 09:00" (en) - an absolute date-and-time in the
 * device's own zone, never a relative "5 minutes ago" (CLAUDE.md rule 11), the identical pattern
 * [ago.chat.android.thread.contactpanel.sections.PastDialogsSection]'s own `pastDialogDate` uses. */
@Composable
private fun restrictedAtLabel(
    restriction: VisitorRestriction,
    zone: ZoneId,
): String {
    val locale = LocalConfiguration.current.locales[0]
    return DateTimeFormatter.ofPattern("d MMMM, HH:mm", locale).format(restriction.restrictedAt.atZone(zone))
}

@Composable
private fun expiresAtLabel(
    restriction: VisitorRestriction,
    zone: ZoneId,
): String {
    val expiresAt = restriction.expiresAt ?: return stringResource(R.string.restricted_visitors_expires_never)
    val locale = LocalConfiguration.current.locales[0]
    val formatted = DateTimeFormatter.ofPattern("d MMMM, HH:mm", locale).format(expiresAt.atZone(zone))
    return stringResource(R.string.restricted_visitors_expires_at, formatted)
}

/**
 * `docs/design/tenant-modules-restrictions-android.md` §1.6: display-only, computed the way the console
 * computes it — never an ordering decision (that stays the server's own keyset `id`). `now` is
 * [RestrictedVisitorsScreen]'s own [rememberTickingNow] read, passed down rather than read again here,
 * the identical "one clock read, every row renders from it" shape
 * [ago.chat.android.conversations.ConversationListScreen] already establishes for its own elapsed-time
 * labels.
 */
private fun restrictionStatus(
    restriction: VisitorRestriction,
    now: OffsetDateTime,
): RestrictionStatus {
    val expiresAt = restriction.expiresAt
    return when {
        restriction.liftedAt != null -> RestrictionStatus.Lifted
        expiresAt != null && !expiresAt.isAfter(now.toInstant()) -> RestrictionStatus.Expired
        else -> RestrictionStatus.Active
    }
}

/** `26-227`: a rendering-only classification — see [restrictionStatus]'s own doc comment for why this is
 * not a domain concept and lives in `:app` rather than beside [VisitorRestriction]. */
private enum class RestrictionStatus {
    Active,
    Expired,
    Lifted,
}
