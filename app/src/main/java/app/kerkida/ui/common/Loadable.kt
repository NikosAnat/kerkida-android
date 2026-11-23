package app.kerkida.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import app.kerkida.KerkidaApp
import app.kerkida.data.AppContainer
import app.kerkida.data.Sourced
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant

sealed interface Loadable<out T> {
    data object Loading : Loadable<Nothing>
    data class Failed(val error: Throwable) : Loadable<Nothing>
    data class Ready<T>(
        val value: T,
        val fetchedAt: Instant,
        val isStale: Boolean,
        val isRefreshing: Boolean = false,
    ) : Loadable<T>
}

/** One piece of screen data: loads it, keeps it during refreshes and records failures. */
class Resource<T>(
    private val scope: CoroutineScope,
    private val loader: suspend (refresh: Boolean) -> Sourced<T>,
) {
    private val _state = MutableStateFlow<Loadable<T>>(Loadable.Loading)
    val state: StateFlow<Loadable<T>> = _state.asStateFlow()
    private var job: Job? = null

    /** [silent] updates in the background without showing a refresh spinner. */
    fun load(refresh: Boolean = false, silent: Boolean = false) {
        job?.cancel()
        val previous = (_state.value as? Loadable.Ready<T>)?.copy(isRefreshing = false)
        _state.value = previous?.copy(isRefreshing = !silent) ?: Loadable.Loading
        job = scope.launch {
            try {
                // JSON parsing stays off the main thread so scrolling remains smooth.
                val result = withContext(Dispatchers.Default) { loader(refresh) }
                _state.value = Loadable.Ready(result.data, result.fetchedAt, result.isStale)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _state.value = previous?.copy(isStale = true, isRefreshing = false) ?: Loadable.Failed(error)
            }
        }
    }
}

@Composable
inline fun <reified VM : ViewModel> kerkidaViewModel(
    key: String? = null,
    crossinline create: (AppContainer) -> VM,
): VM {
    val app = LocalContext.current.applicationContext as KerkidaApp
    return viewModel(key = key, initializer = { create(app.container) })
}
