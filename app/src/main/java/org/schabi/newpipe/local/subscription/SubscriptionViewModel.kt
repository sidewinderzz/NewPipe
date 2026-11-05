package org.schabi.newpipe.local.subscription

import android.app.Application
import android.content.Context
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.preference.PreferenceManager
import com.xwray.groupie.Group
import io.reactivex.rxjava3.core.Flowable
import io.reactivex.rxjava3.processors.BehaviorProcessor
import io.reactivex.rxjava3.schedulers.Schedulers
import java.util.concurrent.TimeUnit
import org.schabi.newpipe.local.feed.FeedDatabaseManager
import org.schabi.newpipe.local.subscription.item.ChannelItem
import org.schabi.newpipe.local.subscription.item.FeedGroupCardGridItem
import org.schabi.newpipe.local.subscription.item.FeedGroupCardItem
import org.schabi.newpipe.util.DEFAULT_THROTTLE_TIMEOUT

class SubscriptionViewModel(application: Application) : AndroidViewModel(application) {
    private var feedDatabaseManager: FeedDatabaseManager = FeedDatabaseManager(application)
    private var subscriptionManager = SubscriptionManager(application)

    // true -> list view, false -> grid view
    private val listViewMode = BehaviorProcessor.createDefault(
        !shouldUseGridForSubscription(application)
    )
    private val listViewModeFlowable = listViewMode.distinctUntilChanged()

    private val mutableStateLiveData = MutableLiveData<SubscriptionState>()
    private val mutableFeedGroupsLiveData = MutableLiveData<Pair<List<Group>, Boolean>>()
    val stateLiveData: LiveData<SubscriptionState> = mutableStateLiveData
    val feedGroupsLiveData: LiveData<Pair<List<Group>, Boolean>> = mutableFeedGroupsLiveData

    private var feedGroupItemsDisposable = Flowable
        .combineLatest(
            feedDatabaseManager.groups(),
            listViewModeFlowable,
            ::Pair
        )
        .throttleLatest(DEFAULT_THROTTLE_TIMEOUT, TimeUnit.MILLISECONDS)
        .map { (feedGroups, listViewMode) ->
            Pair(
                feedGroups.map(if (listViewMode) ::FeedGroupCardItem else ::FeedGroupCardGridItem),
                listViewMode
            )
        }
        .subscribeOn(Schedulers.io())
        .subscribe(
            { mutableFeedGroupsLiveData.postValue(it) },
            { mutableStateLiveData.postValue(SubscriptionState.ErrorState(it)) }
        )

    // SubsPipe: sort A-Z (default) or newest first. Newest first uses the order channels
    // were added to SubsPipe, since YouTube's Takeout export doesn't include subscribe dates.
    private val prefs = PreferenceManager.getDefaultSharedPreferences(application)
    private val sortNewestFirst = BehaviorProcessor.createDefault(
        prefs.getBoolean(PREF_SORT_NEWEST_FIRST, false)
    )

    private var stateItemsDisposable = Flowable
        .combineLatest(
            subscriptionManager.subscriptions(),
            sortNewestFirst.distinctUntilChanged(),
            ::Pair
        )
        .throttleLatest(DEFAULT_THROTTLE_TIMEOUT, TimeUnit.MILLISECONDS)
        .map { (subs, newestFirst) -> if (newestFirst) subs.sortedByDescending { it.uid } else subs }
        .map { it.map { entity -> ChannelItem(entity.toChannelInfoItem(), entity.uid, ChannelItem.ItemVersion.MINI) } }
        .subscribeOn(Schedulers.io())
        .subscribe(
            { mutableStateLiveData.postValue(SubscriptionState.LoadedState(it)) },
            { mutableStateLiveData.postValue(SubscriptionState.ErrorState(it)) }
        )

    override fun onCleared() {
        super.onCleared()
        stateItemsDisposable.dispose()
        feedGroupItemsDisposable.dispose()
    }

    fun setListViewMode(newListViewMode: Boolean) {
        listViewMode.onNext(newListViewMode)
    }

    fun isSortNewestFirst(): Boolean = sortNewestFirst.value ?: false

    fun setSortNewestFirst(newestFirst: Boolean) {
        prefs.edit { putBoolean(PREF_SORT_NEWEST_FIRST, newestFirst) }
        sortNewestFirst.onNext(newestFirst)
    }

    fun getListViewMode(): Boolean {
        return listViewMode.value ?: true
    }

    sealed class SubscriptionState {
        data class LoadedState(val subscriptions: List<Group>) : SubscriptionState()
        data class ErrorState(val error: Throwable? = null) : SubscriptionState()
    }

    companion object {
        private const val PREF_SORT_NEWEST_FIRST = "subspipe_subscriptions_sort_newest_first"

        /**
         * Returns whether to use GridLayout mode for Subscription Fragment.
         *
         * ### Current mapping:
         *
         *  | ItemViewMode | ItemVersion | Span count |
         *  |---|---|---|
         *  | AUTO | MINI | 1 |
         *  | LIST | MINI | 1 |
         *  | CARD | GRID | > 1 (ThemeHelper defined) |
         *  | GRID | GRID | > 1 (ThemeHelper defined) |
         *
         *  @see [SubscriptionViewModel.shouldUseGridForSubscription] to modify Layout Manager
         */
        @Suppress("UNUSED_PARAMETER")
        fun shouldUseGridForSubscription(context: Context): Boolean {
            // SubsPipe: channels are always a compact list (small avatar + name), and
            // channel groups are always a row of chips, whatever the list view mode is.
            return false
        }
    }
}
