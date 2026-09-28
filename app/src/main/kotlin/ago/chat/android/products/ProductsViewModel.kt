package ago.chat.android.products

import ago.chat.android.core.domain.modules.ModulesApi
import ago.chat.android.core.domain.modules.ModulesResult
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
 * `26-249`: the «Продукты» screen's own state machine — reads the enabled-module list through the
 * existing [ModulesApi] alone (the same read «База знаний» uses) and derives each product's held-ness
 * from its keys, the identical "loads on construction, retry just asks again" shape
 * [ago.chat.android.faq.ModulesFaqViewModel]'s own doc comment states. Created only when the operator
 * opens the Ещё → Администрирование → «Продукты» row (`MoreScreen`'s own `openRowId` gate), so the first
 * [ModulesApi.fetch] call happens only then.
 *
 * **The derivation is `ago-console`'s own `buildRows`, key for key.** The console holds `calendar` when
 * `enabledModules.includes("calendar")` and `faq` when it includes `"faq"`; chat is always held. This
 * maps the same two keys off the fetched [ago.chat.android.core.domain.modules.EnabledModule.moduleKey]
 * list. A failed read is a failed screen (never a silently product-less "everything is Пока нет"), the
 * identical [ModulesResult.Failed] → [ProductsUiState.Failed] pass-through the sibling view model makes.
 */
@HiltViewModel
public class ProductsViewModel
    @Inject
    constructor(
        private val modulesApi: ModulesApi,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : ViewModel() {
        private val mutableState = MutableStateFlow<ProductsUiState>(ProductsUiState.Loading)
        public val state: StateFlow<ProductsUiState> = mutableState.asStateFlow()

        init {
            refresh()
        }

        /** The initial load, the top bar's own «Обновить» action, and the retry a
         * [ProductsUiState.Failed] screen offers — one method for all three, the identical "asking again
         * is the whole of retry" shape [ago.chat.android.faq.ModulesFaqViewModel.refresh] states. */
        public fun refresh() {
            mutableState.update { ProductsUiState.Loading }
            viewModelScope.launch {
                val result = withContext(ioDispatcher) { modulesApi.fetch() }
                mutableState.update {
                    when (result) {
                        is ModulesResult.Loaded -> ProductsUiState.Loaded(catalogueFor(result.moduleKeys()))
                        is ModulesResult.Failed -> ProductsUiState.Failed(result.reason)
                    }
                }
            }
        }
    }

/** `ago-console`'s own `ProductsPage.buildRows`, ported: the fixed catalogue (chat · calendar · faq) in
 * that order, each flagged against the keys this workspace actually holds. Chat is unconditional; the
 * other two mirror the console's own `enabledModules.includes(...)` checks against the same wire keys. */
private fun catalogueFor(heldKeys: Set<String>): List<ProductHolding> =
    listOf(
        ProductHolding(Product.Chat, held = true),
        ProductHolding(Product.Calendar, held = CALENDAR_MODULE_KEY in heldKeys),
        ProductHolding(Product.Faq, held = FAQ_MODULE_KEY in heldKeys),
    )

private fun ModulesResult.Loaded.moduleKeys(): Set<String> = modules.mapTo(mutableSetOf()) { it.moduleKey }

/** `Ago.Chat.Domain.ModuleKey` values, read exactly once here the way the console reads them exactly once
 * in its own `buildRows` — the copy a reader sees never carries either raw string. */
private const val CALENDAR_MODULE_KEY = "calendar"
private const val FAQ_MODULE_KEY = "faq"
