package org.schabi.newpipe.subspipe

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.disposables.Disposable
import io.reactivex.rxjava3.schedulers.Schedulers
import org.schabi.newpipe.BaseFragment
import org.schabi.newpipe.R
import org.schabi.newpipe.database.subscription.SubscriptionEntity
import org.schabi.newpipe.databinding.FragmentSubspipeAddSubscriptionsBinding
import org.schabi.newpipe.databinding.SubspipeChannelResultItemBinding
import org.schabi.newpipe.error.ErrorUtil
import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.channel.ChannelInfo
import org.schabi.newpipe.extractor.channel.ChannelInfoItem
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.local.subscription.ImportConfirmationDialog
import org.schabi.newpipe.local.subscription.SubscriptionManager
import org.schabi.newpipe.local.subscription.workers.SubscriptionImportInput
import org.schabi.newpipe.streams.io.NoFileManagerSafeGuard
import org.schabi.newpipe.streams.io.StoredFileHelper
import org.schabi.newpipe.util.ExtractorHelper
import org.schabi.newpipe.util.Localization
import org.schabi.newpipe.util.NavigationHelper
import org.schabi.newpipe.util.external_communication.ShareUtils
import org.schabi.newpipe.util.image.CoilHelper
import org.schabi.newpipe.util.image.ImageStrategy

/**
 * SubsPipe: one place to add subscriptions.
 *
 * - Search YouTube for channels only (by name, @handle or channel link) and subscribe with one
 *   tap, without bringing back general video search.
 * - Import a YouTube Takeout subscriptions.csv, or restore a SubsPipe / NewPipe .json backup.
 */
class AddSubscriptionsFragment : BaseFragment() {
    private var _binding: FragmentSubspipeAddSubscriptionsBinding? = null
    private val binding get() = _binding!!

    private lateinit var subscriptionManager: SubscriptionManager
    private val disposables = CompositeDisposable()
    private var searchDisposable: Disposable? = null

    /** Subscriptions already in the database, keyed by [urlKey]. */
    private var subscribed: Map<String, SubscriptionEntity> = emptyMap()

    /** Channels with a subscribe / unsubscribe still being written, keyed by [urlKey]. */
    private val pending = mutableSetOf<String>()

    private val results = mutableListOf<ChannelRow>()
    private val adapter = ResultsAdapter()

    private var lastQuery: String? = null
    private val debouncedSearch = Runnable { runSearch(binding.channelSearchInput.text.toString()) }

    private val importCsvLauncher =
        registerForActivityResult(StartActivityForResult(), ::onCsvPicked)
    private val importBackupLauncher =
        registerForActivityResult(StartActivityForResult(), ::onBackupPicked)

    // /////////////////////////////////////////////////////////////////////////
    // Lifecycle
    // /////////////////////////////////////////////////////////////////////////

    override fun onAttach(context: Context) {
        super.onAttach(context)
        subscriptionManager = SubscriptionManager(context)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSubspipeAddSubscriptionsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun initViews(rootView: View, savedInstanceState: Bundle?) {
        super.initViews(rootView, savedInstanceState)
        binding.channelResults.layoutManager = LinearLayoutManager(requireContext())
        binding.channelResults.adapter = adapter
        updateSubscriptionCount()
        showIdle()
    }

    override fun initListeners() {
        super.initListeners()

        binding.channelSearchInput.setOnEditorActionListener { _, actionId, event ->
            val isEnter = event?.keyCode == KeyEvent.KEYCODE_ENTER &&
                event.action == KeyEvent.ACTION_DOWN
            if (actionId == EditorInfo.IME_ACTION_SEARCH || isEnter) {
                binding.channelSearchInput.removeCallbacks(debouncedSearch)
                runSearch(binding.channelSearchInput.text.toString())
                hideKeyboard()
                true
            } else {
                false
            }
        }

        binding.channelSearchInput.doAfterTextChanged { text ->
            val query = text?.toString()?.trim().orEmpty()
            binding.channelSearchClear.visibility = if (query.isEmpty()) View.GONE else View.VISIBLE
            binding.channelSearchInput.removeCallbacks(debouncedSearch)
            when {
                query.isEmpty() -> {
                    searchDisposable?.dispose()
                    lastQuery = null
                    showIdle()
                }
                // search as you type once there's enough to go on; links wait for the search key
                query.length >= 3 && !looksLikeChannelLink(query) ->
                    binding.channelSearchInput.postDelayed(debouncedSearch, SEARCH_DELAY_MS)
            }
        }

        binding.channelSearchClear.setOnClickListener {
            binding.channelSearchInput.setText("")
        }

        binding.importCsvRow.setOnClickListener {
            NoFileManagerSafeGuard.launchSafe(
                importCsvLauncher,
                // Takeout CSVs come through with all sorts of mime types, so accept anything
                StoredFileHelper.getPicker(requireContext(), "*/*"),
                TAG,
                requireContext()
            )
        }

        binding.importBackupRow.setOnClickListener {
            NoFileManagerSafeGuard.launchSafe(
                importBackupLauncher,
                StoredFileHelper.getPicker(requireContext(), "*/*"),
                TAG,
                requireContext()
            )
        }

        binding.openTakeout.setOnClickListener {
            ShareUtils.openUrlInBrowser(requireContext(), TAKEOUT_URL)
        }

        disposables.add(
            subscriptionManager.subscriptions()
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                    { list ->
                        subscribed = list.associateBy { urlKey(it.url) }
                        if (_binding != null) {
                            updateSubscriptionCount()
                            adapter.notifyDataSetChanged()
                        }
                    },
                    { ErrorUtil.showUiErrorSnackbar(this, "Loading subscriptions", it) }
                )
        )
    }

    override fun onResume() {
        super.onResume()
        setTitle(getString(R.string.subspipe_add_subscriptions))
    }

    override fun onDestroyView() {
        binding.channelSearchInput.removeCallbacks(debouncedSearch)
        searchDisposable?.dispose()
        disposables.clear()
        _binding = null
        super.onDestroyView()
    }

    // /////////////////////////////////////////////////////////////////////////
    // Channel search
    // /////////////////////////////////////////////////////////////////////////

    private fun runSearch(raw: String) {
        val query = raw.trim()
        if (query.isEmpty()) {
            showIdle()
            return
        }
        if (query == lastQuery && results.isNotEmpty()) {
            return
        }
        lastQuery = query
        searchDisposable?.dispose()
        showLoading()

        val request: Single<List<ChannelRow>> = if (looksLikeChannelLink(query)) {
            // a pasted link or @handle: open that exact channel, fall back to a name search
            ExtractorHelper.getChannelInfo(SERVICE_ID, toChannelUrl(query), false)
                .map { listOf(ChannelRow.from(it)) }
                .onErrorResumeNext { searchChannels(query.removePrefix("@")) }
        } else {
            searchChannels(query)
        }

        searchDisposable = request
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe(
                { rows -> if (_binding != null) showResults(rows) },
                {
                    lastQuery = null
                    if (_binding != null) showStatus(getString(R.string.subspipe_search_failed))
                }
            )
    }

    private fun searchChannels(query: String): Single<List<ChannelRow>> = ExtractorHelper.searchFor(
        SERVICE_ID,
        query,
        listOf(YoutubeSearchQueryHandlerFactory.CHANNELS),
        ""
    ).map { info ->
        info.relatedItems.filterIsInstance<ChannelInfoItem>().map { ChannelRow.from(it) }
    }

    private fun toggleSubscription(row: ChannelRow) {
        val key = urlKey(row.url)
        if (!pending.add(key)) {
            return
        }
        val existing = subscribed[key]
        val write = if (existing != null) {
            Completable.fromAction { subscriptionManager.deleteSubscription(existing) }
        } else {
            Completable.fromAction { subscriptionManager.insertSubscription(row.toEntity()) }
        }
        adapter.notifyDataSetChanged()

        disposables.add(
            write.subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .doFinally {
                    pending.remove(key)
                    if (_binding != null) adapter.notifyDataSetChanged()
                }
                .subscribe(
                    {
                        val message = if (existing != null) {
                            R.string.subspipe_unsubscribed_toast
                        } else {
                            R.string.subspipe_subscribed_toast
                        }
                        context?.let {
                            Toast.makeText(it, it.getString(message, row.name), Toast.LENGTH_SHORT)
                                .show()
                        }
                    },
                    { ErrorUtil.showUiErrorSnackbar(this, "Changing subscription", it) }
                )
        )
    }

    // /////////////////////////////////////////////////////////////////////////
    // Import
    // /////////////////////////////////////////////////////////////////////////

    private fun onCsvPicked(result: ActivityResult) {
        val uri = result.data?.dataString
        if (result.resultCode == Activity.RESULT_OK && uri != null) {
            // the YouTube importer reads Takeout's subscriptions.csv (and the older zip/json)
            ImportConfirmationDialog.show(
                this,
                SubscriptionImportInput.InputStreamMode(SERVICE_ID, uri)
            )
        }
    }

    private fun onBackupPicked(result: ActivityResult) {
        val uri = result.data?.dataString
        if (result.resultCode == Activity.RESULT_OK && uri != null) {
            ImportConfirmationDialog.show(this, SubscriptionImportInput.PreviousExportMode(uri))
        }
    }

    // /////////////////////////////////////////////////////////////////////////
    // UI states
    // /////////////////////////////////////////////////////////////////////////

    private fun showIdle() {
        results.clear()
        adapter.notifyDataSetChanged()
        binding.channelSearchProgress.visibility = View.INVISIBLE
        binding.channelSearchStatus.visibility = View.GONE
        binding.channelResults.visibility = View.GONE
        binding.importPanel.visibility = View.VISIBLE
    }

    private fun showLoading() {
        binding.channelSearchProgress.visibility = View.VISIBLE
        binding.importPanel.visibility = View.GONE
        if (results.isEmpty()) {
            binding.channelSearchStatus.visibility = View.GONE
        }
    }

    private fun showResults(rows: List<ChannelRow>) {
        results.clear()
        results.addAll(rows)
        adapter.notifyDataSetChanged()
        binding.channelSearchProgress.visibility = View.INVISIBLE
        binding.importPanel.visibility = View.GONE
        if (rows.isEmpty()) {
            showStatus(getString(R.string.subspipe_no_channels_found))
        } else {
            binding.channelSearchStatus.visibility = View.GONE
            binding.channelResults.visibility = View.VISIBLE
            binding.channelResults.scrollToPosition(0)
        }
    }

    private fun showStatus(message: String) {
        results.clear()
        adapter.notifyDataSetChanged()
        binding.channelSearchProgress.visibility = View.INVISIBLE
        binding.importPanel.visibility = View.GONE
        binding.channelResults.visibility = View.GONE
        binding.channelSearchStatus.text = message
        binding.channelSearchStatus.visibility = View.VISIBLE
    }

    private fun updateSubscriptionCount() {
        binding.subscriptionCount.text =
            getString(R.string.subspipe_subscription_count, subscribed.size)
    }

    private fun hideKeyboard() {
        val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE)
            as? InputMethodManager
        imm?.hideSoftInputFromWindow(binding.channelSearchInput.windowToken, 0)
    }

    // /////////////////////////////////////////////////////////////////////////
    // Results list
    // /////////////////////////////////////////////////////////////////////////

    private inner class ResultsAdapter : RecyclerView.Adapter<ResultHolder>() {
        override fun getItemCount() = results.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ResultHolder(
            SubspipeChannelResultItemBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )
        )

        override fun onBindViewHolder(holder: ResultHolder, position: Int) {
            holder.bind(results[position])
        }
    }

    private inner class ResultHolder(
        private val item: SubspipeChannelResultItemBinding
    ) : RecyclerView.ViewHolder(item.root) {
        fun bind(row: ChannelRow) {
            val context = item.root.context
            item.channelName.text = row.name
            CoilHelper.loadAvatar(item.channelAvatar, row.avatars)

            val details = mutableListOf<String>()
            if (row.subscriberCount >= 0) {
                details.add(Localization.shortSubscriberCount(context, row.subscriberCount))
            }
            row.description?.trim()?.takeIf { it.isNotEmpty() }?.let { details.add(it) }
            item.channelDetails.text = details.joinToString(" · ")
            item.channelDetails.visibility = if (details.isEmpty()) View.GONE else View.VISIBLE

            val key = urlKey(row.url)
            val isSubscribed = subscribed.containsKey(key)
            item.channelSubscribe.isEnabled = !pending.contains(key)
            if (isSubscribed) {
                item.channelSubscribe.setText(R.string.subspipe_subscribed)
                item.channelSubscribe.setBackgroundResource(R.drawable.subspipe_pill_button_tonal)
                item.channelSubscribe.setTextColor(
                    resolveColor(context, android.R.attr.textColorPrimary)
                )
            } else {
                item.channelSubscribe.setText(R.string.subspipe_subscribe)
                item.channelSubscribe.setBackgroundResource(R.drawable.subspipe_pill_button_filled)
                item.channelSubscribe.setTextColor(android.graphics.Color.WHITE)
            }
            item.channelSubscribe.setOnClickListener { toggleSubscription(row) }

            item.root.setOnClickListener {
                NavigationHelper.openChannelFragment(fm, row.serviceId, row.url, row.name)
            }
        }
    }

    /** A channel shown in the results, from either a search result or a looked-up link. */
    private data class ChannelRow(
        val serviceId: Int,
        val url: String,
        val name: String,
        val avatars: List<Image>,
        val subscriberCount: Long,
        val description: String?
    ) {
        fun toEntity() = SubscriptionEntity(
            serviceId = serviceId,
            url = url,
            name = name,
            avatarUrl = ImageStrategy.imageListToDbUrl(avatars),
            subscriberCount = subscriberCount.takeIf { it >= 0 },
            description = description
        )

        companion object {
            fun from(item: ChannelInfoItem) = ChannelRow(
                serviceId = item.serviceId,
                url = item.url,
                name = item.name.orEmpty(),
                avatars = item.thumbnails.orEmpty(),
                subscriberCount = item.subscriberCount,
                description = item.description
            )

            fun from(info: ChannelInfo): ChannelRow {
                // store the stable /channel/UC… address rather than an @handle link
                val id = info.id.orEmpty()
                val url = if (id.startsWith("UC")) "$CHANNEL_URL_PREFIX$id" else info.url
                return ChannelRow(
                    serviceId = info.serviceId,
                    url = url,
                    name = info.name.orEmpty(),
                    avatars = info.avatars.orEmpty(),
                    subscriberCount = info.subscriberCount,
                    description = info.description
                )
            }
        }
    }

    companion object {
        private val SERVICE_ID = ServiceList.YouTube.serviceId
        private const val SEARCH_DELAY_MS = 700L
        private const val CHANNEL_URL_PREFIX = "https://www.youtube.com/channel/"
        private const val TAKEOUT_URL = "https://takeout.google.com/settings/takeout/custom/youtube"

        /** Compare channel links regardless of http/https, www./m. and trailing slashes. */
        private fun urlKey(url: String?): String = url.orEmpty().trim()
            .substringAfter("://")
            .removePrefix("www.")
            .removePrefix("m.")
            .trimEnd('/')
            .lowercase()

        private fun looksLikeChannelLink(query: String): Boolean {
            val q = query.trim().lowercase()
            return (q.startsWith("@") && !q.contains(' ')) ||
                q.contains("youtube.com/") ||
                q.contains("youtu.be/")
        }

        private fun toChannelUrl(query: String): String {
            val q = query.trim()
            return when {
                q.startsWith("@") -> "https://www.youtube.com/$q"
                q.startsWith("http://") || q.startsWith("https://") -> q
                else -> "https://$q"
            }
        }

        private fun resolveColor(context: Context, attr: Int): Int {
            val typed = context.obtainStyledAttributes(intArrayOf(attr))
            return try {
                typed.getColor(0, android.graphics.Color.BLACK)
            } finally {
                typed.recycle()
            }
        }

        @JvmStatic
        fun newInstance() = AddSubscriptionsFragment()
    }
}
