package co.kaush.msusf.movies

import android.arch.lifecycle.AndroidViewModel
import android.arch.lifecycle.ViewModel
import android.arch.lifecycle.ViewModelProvider
import co.kaush.msusf.MSApp
import co.kaush.msusf.movies.MSMovieEvent.AddToHistoryEvent
import co.kaush.msusf.movies.MSMovieEvent.RestoreFromHistoryEvent
import co.kaush.msusf.movies.MSMovieEvent.ScreenLoadEvent
import co.kaush.msusf.movies.MSMovieEvent.SearchMovieEvent
import co.kaush.msusf.movies.MSMovieResult.ScreenLoadResult
import co.kaush.msusf.movies.MSMovieResult.SearchHistoryResult
import co.kaush.msusf.movies.MSMovieResult.SearchMovieResult
import io.reactivex.Observable
import io.reactivex.ObservableTransformer
import io.reactivex.schedulers.Schedulers
import timber.log.Timber

/**
 * For this example, a simple ViewModel would have sufficed,
 * but in most real world examples we would use an AndroidViewModel
 *
 * Our Unit tests should still be able to run given this
 */
class MSMainVm(
    app: MSApp,
    private val movieRepo: MSMovieRepository
) : AndroidViewModel(app) {

    private var viewState: MSMovieViewState = MSMovieViewState()

    fun render(vararg es: Observable<out MSMovieEvent>): Observable<MSMovieViewState> {

        // gather events
        val events: Observable<out MSMovieEvent> =
            Observable.mergeArray(*es)
                .doOnNext { Timber.d("----- event ${it.javaClass.simpleName}") }

        // events -> results (use cases)
        val results: Observable<Lce<out MSMovieResult>> =
            eventsToResults(events)
                .doOnNext { Timber.d("----- result $it") }

        // results -> view state (reducer)
        return resultsToViewState(results)
            .doOnNext { Timber.d("----- viewState $it") }
    }

    // -----------------------------------------------------------------------------------
    // Internal helpers

    private fun eventsToResults(
        events: Observable<out MSMovieEvent>
    ): Observable<Lce<out MSMovieResult>> {
        return events.publish { o ->
            Observable.merge(
                o.ofType(ScreenLoadEvent::class.java).compose(onScreenLoad()),
                o.ofType(SearchMovieEvent::class.java).compose(onSearchMovie()),
                o.ofType(AddToHistoryEvent::class.java).compose(onAddToHistory()),
                o.ofType(RestoreFromHistoryEvent::class.java).compose(onRestoreFromHistory())
            )
        }
    }

    private fun resultsToViewState(
        results: Observable<Lce<out MSMovieResult>>
    ): Observable<MSMovieViewState> {
        return results.scan(viewState) { state, result ->
            when (result) {

                is Lce.Content -> {
                    when (result.packet) {
                        is ScreenLoadResult -> state.copy(searchBoxText = "")

                        is SearchMovieResult -> {
                            val movie: MSMovie = result.packet.movie

                            state.copy(
                                searchedMovieTitle = movie.title,
                                searchedMovieRating = movie.ratingSummary,
                                searchedMoviePoster = movie.posterUrl,
                                searchedMovieReference = movie
                            )
                        }

                        is SearchHistoryResult -> {
                            (result.packet.movieHistory)
                                ?.let {
                                    val adapterList: MutableList<MSMovie> =
                                        mutableListOf(*state.adapterList.toTypedArray())
                                    adapterList.add(it)
                                    state.copy(adapterList = adapterList)
                                } ?: state.copy()
                        }
                    }
                }

                is Lce.Loading -> {
                    state.copy(
                        searchBoxText = null,
                        searchedMovieTitle = "Searching Movie...",
                        searchedMovieRating = "",
                        searchedMoviePoster = "",
                        searchedMovieReference = null
                    )
                }

                is Lce.Error -> {
                    when (result.packet) {
                        is SearchMovieResult -> {
                            val movie: MSMovie = result.packet.movie
                            state.copy(searchedMovieTitle = movie.errorMessage!!)
                        }
                        else -> throw RuntimeException("Unexpected result LCE state")
                    }
                }
            }
        }
            .distinctUntilChanged()
            .doOnNext { viewState = it }
    }

    // -----------------------------------------------------------------------------------
    // use cases

    private fun onScreenLoad(): ObservableTransformer<ScreenLoadEvent, Lce<ScreenLoadResult>> {
        return ObservableTransformer { upstream ->
            upstream.map { Lce.Content(ScreenLoadResult) }
        }
    }

    private fun onSearchMovie(): ObservableTransformer<SearchMovieEvent, Lce<SearchMovieResult>> {
        return ObservableTransformer { upstream ->
            upstream.switchMap { searchMovieEvent ->
                movieRepo.searchMovie(searchMovieEvent.searchedMovieTitle)
                    .subscribeOn(Schedulers.io())
                    .map {
                        if (it.errorMessage?.isNullOrBlank() == false) {
                            Lce.Error(SearchMovieResult(it))
                        } else {
                            Lce.Content(SearchMovieResult(it))
                        }
                    }
                    .startWith(Lce.Loading())
            }
        }
    }

    private fun onAddToHistory(): ObservableTransformer<AddToHistoryEvent, Lce<SearchHistoryResult>> {
        return ObservableTransformer { upstream ->
            upstream.map {
                val movieResult: MSMovie = viewState.searchedMovieReference!!

                if (!viewState.adapterList.contains(movieResult)) {
                    Lce.Content(SearchHistoryResult(movieResult))
                } else {
                    Lce.Content(SearchHistoryResult(null))
                }
            }
        }
    }

    private fun onRestoreFromHistory(): ObservableTransformer<RestoreFromHistoryEvent,
        Lce<SearchMovieResult>> {
        return ObservableTransformer { upstream ->
            upstream.map { Lce.Content(SearchMovieResult(it.movieFromHistory)) }
        }
    }
}

// -----------------------------------------------------------------------------------
// LCE

sealed class Lce<T> {
    class Loading<T> : Lce<T>()
    data class Content<T>(val packet: T) : Lce<T>()
    data class Error<T>(val packet: T) : Lce<T>()
}

// -----------------------------------------------------------------------------------

class MSMainVmFactory(
    private val app: MSApp,
    private val movieRepo: MSMovieRepository
) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel?> create(modelClass: Class<T>): T {
        return MSMainVm(app, movieRepo) as T
    }
}
