package ago.chat.android.channels

import ago.chat.android.core.domain.installation.InstallationApi
import ago.chat.android.core.domain.installation.SiteInstallation
import ago.chat.android.core.domain.installation.SiteInstallationResult
import ago.chat.android.di.IoDispatcher
import ago.chat.android.session.OidcConfig
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
 * `26-159`: the «Установка виджета» / Channels screen — the chat-widget embed snippet and its allowed
 * origins, read over [InstallationApi]. Constructed only for an operator holding `site:configure`
 * ([ago.chat.android.shell.MoreScreen] draws the row that opens this only under that gate), the identical
 * "does not even ask the server for it" hide-not-disable discipline the Записи config view models follow.
 *
 * **[OidcConfig] is injected only to compose the embed snippet.** The `<script>` tag is built here, once,
 * from the site's `publicKey` and the widget host ([OidcConfig.apiBaseUrl]) — the identical shape
 * `ago-console`'s own `InstallSnippetPage` and this app's own `CalendarSetupViewModel` build it,
 * `${apiBaseUrl}/widget/widget.js` with the key as `data-site`. Composed in the view model rather than
 * the composable so [InstallWidgetScreen] stays a pure renderer of a ready string, with no config
 * dependency of its own.
 *
 * **Read-only, no save.** Unlike `CalendarSetupViewModel`, there is no allowed-origins write here:
 * `Ago.Chat.Api` exposes no `site:configure`-gated write for a chat site's origins (only the
 * platform-owner `PUT /api/v1/owner/sites/{siteId}/allowed-origins`), and `InstallSnippetPage` renders
 * them read-only for the same reason.
 */
@HiltViewModel
internal class InstallWidgetViewModel
    @Inject
    constructor(
        private val api: InstallationApi,
        config: OidcConfig,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : ViewModel() {
        private val widgetHost: String = config.apiBaseUrl.trimEnd('/')

        private val mutableState = MutableStateFlow<InstallWidgetUiState>(InstallWidgetUiState.Loading)
        val state: StateFlow<InstallWidgetUiState> = mutableState.asStateFlow()

        init {
            refresh()
        }

        /** The initial load, and the retry a [InstallWidgetUiState.Failed] screen offers. */
        fun refresh() {
            mutableState.update { InstallWidgetUiState.Loading }
            viewModelScope.launch {
                val result = withContext(ioDispatcher) { api.fetchInstallation() }
                mutableState.update {
                    when (result) {
                        is SiteInstallationResult.Loaded ->
                            InstallWidgetUiState.Loaded(
                                embedSnippet = embedSnippet(result.installation),
                                allowedOrigins = result.installation.allowedOrigins,
                            )

                        is SiteInstallationResult.Failed -> InstallWidgetUiState.Failed(result.reason)
                    }
                }
            }
        }

        private fun embedSnippet(installation: SiteInstallation): String =
            "<script src=\"$widgetHost/widget/widget.js\" data-site=\"${installation.publicKey}\" async></script>"
    }
