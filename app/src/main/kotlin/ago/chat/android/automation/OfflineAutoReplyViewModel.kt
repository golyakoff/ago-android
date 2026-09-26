package ago.chat.android.automation

import ago.chat.android.core.domain.autoreply.AutoReplyRule
import ago.chat.android.core.domain.autoreply.OfflineAutoReply
import ago.chat.android.core.domain.autoreply.OfflineAutoReplyApi
import ago.chat.android.core.domain.autoreply.OfflineAutoReplyResult
import ago.chat.android.core.domain.autoreply.OfflineAutoReplyWriteResult
import ago.chat.android.di.IoDispatcher
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * `26-192`/`C5` (`docs/design/tenant-channels-android.md` §4.3): Автоматизация → «Автоответ вне
 * смены» — reads the settings once, holds an editable draft (enabled/fallback/ordered rules), and
 * [save]s the whole thing back as one [OfflineAutoReplyApi.update] call, the identical full-DTO shape
 * [ago.chat.android.channels.WidgetConfigViewModel] already establishes for its own site-scoped settings
 * form — unlike [ago.chat.android.channels.BrandingViewModel]'s own *two* independent writes, there is
 * exactly one write surface here because [OfflineAutoReply.rules]' own order is the behaviour and a
 * partial update could never safely express "this row moved, that one did not".
 *
 * **[save] is a no-op while a save is already in flight**, read off the *current* state rather than a
 * separate guard flag — the identical discipline
 * [ago.chat.android.channels.ChannelConnectViewModel]'s own doc comment states for its own
 * connect/disconnect pair.
 *
 * **The client-side courtesy check runs inside [save], not in the screen.** Unlike the branding logo's
 * own courtesy check (`ago.chat.android.channels.validateLogoCourtesy`), which runs in `BrandingRoute`
 * because the bytes it inspects come from a picker outside any view model, every value
 * [validateOfflineAutoReplyDraft] needs — `enabled`, `fallbackReply`, `rules` — already lives in this
 * class's own [state], so there is no reason to hand it back out to the composable only to hand it back
 * in again.
 */
@HiltViewModel
internal class OfflineAutoReplyViewModel
    @Inject
    constructor(
        private val api: OfflineAutoReplyApi,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : ViewModel() {
        private val mutableState = MutableStateFlow<OfflineAutoReplyUiState>(OfflineAutoReplyUiState.Loading)
        val state: StateFlow<OfflineAutoReplyUiState> = mutableState.asStateFlow()

        /** This screen's own local row-identity counter (`AutoReplyRuleDraft.id`'s own doc comment
         * states why it exists) - monotonically increasing, so a value handed out is never reused even
         * across a reload, an add, and a save's own re-seed all happening in the same session. */
        private var nextDraftId = 0L

        init {
            refresh()
        }

        /** The initial load, and the [OfflineAutoReplyUiState.Failed] retry. */
        fun refresh() {
            mutableState.update { OfflineAutoReplyUiState.Loading }
            viewModelScope.launch {
                when (val result = withContext(ioDispatcher) { api.fetch() }) {
                    is OfflineAutoReplyResult.Loaded -> mutableState.update { loadedStateFrom(result.settings) }
                    is OfflineAutoReplyResult.Failed -> mutableState.update { OfflineAutoReplyUiState.Failed(result.reason) }
                }
            }
        }

        fun setEnabled(enabled: Boolean) {
            updateLoaded { it.copy(enabled = enabled, saveError = null) }
        }

        fun setFallbackReply(value: String) {
            updateLoaded { it.copy(fallbackReply = value, saveError = null) }
        }

        /** Appends one blank rule at the end of the list - the "Add" button's own effect
         * (`docs/design/tenant-channels-android.md` §4.3), never the console's own trailing-auto-blank-row
         * idiom (`scope-inventory.md` §8: that idiom fails on a soft keyboard). */
        fun addRule() {
            updateLoaded { loaded ->
                loaded.copy(
                    rules = loaded.rules + AutoReplyRuleDraft(id = nextDraftId++, keyword = "", reply = ""),
                    saveError = null,
                )
            }
        }

        fun updateRuleKeyword(
            id: Long,
            keyword: String,
        ) {
            updateLoaded { loaded ->
                loaded.copy(rules = loaded.rules.map { if (it.id == id) it.copy(keyword = keyword) else it }, saveError = null)
            }
        }

        fun updateRuleReply(
            id: Long,
            reply: String,
        ) {
            updateLoaded { loaded ->
                loaded.copy(rules = loaded.rules.map { if (it.id == id) it.copy(reply = reply) else it }, saveError = null)
            }
        }

        fun removeRule(id: Long) {
            updateLoaded { loaded -> loaded.copy(rules = loaded.rules.filterNot { it.id == id }, saveError = null) }
        }

        /** Moves the rule at [fromIndex] to [toIndex] - `ReorderableColumn`'s own `onSettle(fromIndex,
         * toIndex)` callback (`OfflineAutoReplyScreen.kt`), applied once per drop, and the keyboard/
         * TalkBack "move up"/"move down" actions' own effect too - both paths land here, since both mean
         * the identical thing to the draft: **order is behaviour**, so this is the one place that order
         * ever changes. An out-of-range or no-op index pair is ignored rather than crashing - the reorder
         * library's own settle callback and a boundary-disabled move button should never produce one, but
         * this stays defensive rather than trusting that. */
        fun moveRule(
            fromIndex: Int,
            toIndex: Int,
        ) {
            updateLoaded { loaded ->
                if (fromIndex == toIndex || fromIndex !in loaded.rules.indices || toIndex !in loaded.rules.indices) {
                    return@updateLoaded loaded
                }
                val reordered = loaded.rules.toMutableList()
                reordered.add(toIndex, reordered.removeAt(fromIndex))
                loaded.copy(rules = reordered, saveError = null)
            }
        }

        /**
         * Runs [validateOfflineAutoReplyDraft] first; a problem stops here as
         * [OfflineAutoReplyActionError.Invalid], no network call made. Otherwise builds the request from
         * [meaningfulAutoReplyRules] (blank rows dropped, order preserved, `fallbackReply` trimmed,
         * rule keywords trimmed - the identical trimming `toRequestRules` applies) and calls
         * [OfflineAutoReplyApi.update].
         */
        fun save() {
            val loaded = mutableState.value as? OfflineAutoReplyUiState.Loaded ?: return
            if (loaded.saving) return

            val problem = validateOfflineAutoReplyDraft(loaded.enabled, loaded.fallbackReply, loaded.rules)
            if (problem != null) {
                mutableState.update { current ->
                    (current as? OfflineAutoReplyUiState.Loaded)?.copy(
                        saveError = OfflineAutoReplyActionError.Invalid(problem),
                    ) ?: current
                }
                return
            }

            mutableState.update { current ->
                (current as? OfflineAutoReplyUiState.Loaded)?.copy(saving = true, saveError = null) ?: current
            }

            val settings =
                OfflineAutoReply(
                    enabled = loaded.enabled,
                    fallbackReply = loaded.fallbackReply.trim(),
                    rules =
                        meaningfulAutoReplyRules(loaded.rules).map {
                            AutoReplyRule(keyword = it.keyword.trim(), reply = it.reply)
                        },
                )

            viewModelScope.launch {
                when (val result = withContext(ioDispatcher) { api.update(settings) }) {
                    is OfflineAutoReplyWriteResult.Saved ->
                        mutableState.update { current ->
                            val previousTick = (current as? OfflineAutoReplyUiState.Loaded)?.savedTick ?: 0
                            loadedStateFrom(result.settings).copy(savedTick = previousTick + 1)
                        }

                    is OfflineAutoReplyWriteResult.Refused ->
                        mutableState.update { current ->
                            (current as? OfflineAutoReplyUiState.Loaded)?.copy(
                                saving = false,
                                saveError = OfflineAutoReplyActionError.ServerRefusal(result.detail),
                            ) ?: current
                        }

                    is OfflineAutoReplyWriteResult.Failed ->
                        mutableState.update { current ->
                            (current as? OfflineAutoReplyUiState.Loaded)?.copy(
                                saving = false,
                                saveError = OfflineAutoReplyActionError.Unavailable(result.reason),
                            ) ?: current
                        }
                }
            }
        }

        /** Every edit above shares this one shape: a no-op unless [state] is currently
         * [OfflineAutoReplyUiState.Loaded] (nothing to edit while [OfflineAutoReplyUiState.Loading]/
         * [OfflineAutoReplyUiState.Failed]), applied through [MutableStateFlow.update] rather than a
         * read-then-write pair. */
        private inline fun updateLoaded(transform: (OfflineAutoReplyUiState.Loaded) -> OfflineAutoReplyUiState.Loaded) {
            mutableState.update { current -> (current as? OfflineAutoReplyUiState.Loaded)?.let(transform) ?: current }
        }

        /** [OfflineAutoReply.rules] arrive server-ordered; each becomes one [AutoReplyRuleDraft] with a
         * freshly minted local [AutoReplyRuleDraft.id] - a fetch's own rows and a save's own echoed rows
         * are never assumed to keep the same ids an earlier draft used, since the server is the one
         * source of truth this always reloads from ([OfflineAutoReplyWriteResult.Saved]'s own doc
         * comment: re-seed from the echo, never the request). */
        private fun loadedStateFrom(settings: OfflineAutoReply): OfflineAutoReplyUiState.Loaded =
            OfflineAutoReplyUiState.Loaded(
                enabled = settings.enabled,
                fallbackReply = settings.fallbackReply,
                rules = settings.rules.map { AutoReplyRuleDraft(id = nextDraftId++, keyword = it.keyword, reply = it.reply) },
            )
    }
