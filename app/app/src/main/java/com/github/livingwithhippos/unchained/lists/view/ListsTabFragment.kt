package com.github.livingwithhippos.unchained.lists.view

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.setFragmentResultListener
import androidx.fragment.app.viewModels
import androidx.lifecycle.Observer
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.paging.PagingData
import com.github.livingwithhippos.unchained.R
import com.github.livingwithhippos.unchained.base.UnchainedFragment
import com.github.livingwithhippos.unchained.data.model.APIError
import com.github.livingwithhippos.unchained.data.model.ApiConversionError
import com.github.livingwithhippos.unchained.data.model.AuthenticationState
import com.github.livingwithhippos.unchained.data.model.DownloadItem
import com.github.livingwithhippos.unchained.data.model.EmptyBodyError
import com.github.livingwithhippos.unchained.data.model.NetworkError
import com.github.livingwithhippos.unchained.data.model.TorrentItem
import com.github.livingwithhippos.unchained.databinding.FragmentTabListsBinding
import com.github.livingwithhippos.unchained.lists.viewmodel.DownloadListViewModel
import com.github.livingwithhippos.unchained.utilities.EventObserver
import com.github.livingwithhippos.unchained.utilities.extension.getApiErrorMessage
import com.github.livingwithhippos.unchained.utilities.extension.showToast
import com.github.livingwithhippos.unchained.utilities.extension.verticalScrollToPosition
import com.google.android.material.tabs.TabLayout
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * A simple [UnchainedFragment] subclass.
 * It is capable of showing a list of both [DownloadItem] and [TorrentItem] switched with a tab layout.
 */
@AndroidEntryPoint
class ListsTabFragment : UnchainedFragment(), DownloadListListener, TorrentListListener {

    enum class ListState {
        UPDATE_TORRENT, UPDATE_DOWNLOAD, READY
    }

    //todo: rename viewModel to ListTabViewModel
    private val viewModel: DownloadListViewModel by viewModels()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val listBinding = FragmentTabListsBinding.inflate(inflater, container, false)

        val downloadAdapter = DownloadListPagingAdapter(this)
        val torrentAdapter = TorrentListPagingAdapter(this)

        listBinding.rvDownloadList.adapter = downloadAdapter
        listBinding.rvTorrentList.adapter = torrentAdapter

        listBinding.srLayout.setOnRefreshListener {
            when (listBinding.tabs.selectedTabPosition) {
                TAB_DOWNLOADS -> {
                    downloadAdapter.refresh()
                }
                TAB_TORRENTS -> {
                    torrentAdapter.refresh()
                }
            }
        }

        // observers created to be easily added and removed. Pass the retrieved list to the adapter and removes the loading icon from the swipe layout
        val downloadObserver = Observer<PagingData<DownloadItem>> {
            lifecycleScope.launch {
                downloadAdapter.submitData(it)
                if (listBinding.srLayout.isRefreshing) {
                    listBinding.srLayout.isRefreshing = false
                    // this delay is needed to activate the scrolling, otherwise it won't work. Even 150L was not enough.
                    delay(200)
                    listBinding.rvDownloadList.layoutManager?.verticalScrollToPosition(
                        requireContext()
                    )
                    //todo: add ripple animation on item at position 0 if possible, see [runRippleAnimation]
                }

            }
        }

        val torrentObserver = Observer<PagingData<TorrentItem>> {
            lifecycleScope.launch {
                torrentAdapter.submitData(it)
                if (listBinding.srLayout.isRefreshing) {
                    listBinding.srLayout.isRefreshing = false
                    listBinding.rvTorrentList.layoutManager?.verticalScrollToPosition(requireContext())
                }
            }
        }

        // checks the authentication state. Needed to avoid automatic API calls before the authentication process is finished
        activityViewModel.authenticationState.observe(viewLifecycleOwner, {
            when (it.peekContent()) {
                AuthenticationState.AUTHENTICATED, AuthenticationState.AUTHENTICATED_NO_PREMIUM -> {
                    // register observers if not already registered
                    if (!viewModel.downloadsLiveData.hasActiveObservers())
                        viewModel.downloadsLiveData.observe(viewLifecycleOwner, downloadObserver)
                    if (!viewModel.torrentsLiveData.hasActiveObservers())
                        viewModel.torrentsLiveData.observe(viewLifecycleOwner, torrentObserver)
                }
                else -> {
                    // remove observers if present
                    viewModel.downloadsLiveData.removeObserver(downloadObserver)
                    viewModel.torrentsLiveData.removeObserver(torrentObserver)
                }
            }
        })

        listBinding.tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {

            override fun onTabSelected(tab: TabLayout.Tab?) {
                tab?.let {
                    listBinding.selectedTab = it.position

                    when (it.position) {
                        TAB_DOWNLOADS -> {
                            viewModel.setSelectedTab(TAB_DOWNLOADS)
                            if (!viewModel.downloadsLiveData.hasActiveObservers())
                                viewModel.downloadsLiveData.observe(
                                    viewLifecycleOwner,
                                    downloadObserver
                                )
                        }
                        TAB_TORRENTS -> {
                            viewModel.setSelectedTab(TAB_TORRENTS)
                            if (!viewModel.torrentsLiveData.hasActiveObservers())
                                viewModel.torrentsLiveData.observe(
                                    viewLifecycleOwner,
                                    torrentObserver
                                )
                        }
                    }
                }

            }

            override fun onTabReselected(tab: TabLayout.Tab?) {
                // either do nothing or refresh
            }

            override fun onTabUnselected(tab: TabLayout.Tab?) {
                // remove observer
                when (tab?.position) {
                    TAB_DOWNLOADS -> {
                        viewModel.downloadsLiveData.removeObserver(downloadObserver)
                    }
                    TAB_TORRENTS -> {
                        viewModel.torrentsLiveData.removeObserver(torrentObserver)
                    }
                }
            }
        })

        viewModel.downloadItemLiveData.observe(viewLifecycleOwner, {
            it.getContentIfNotHandled()?.let { links ->
                if (!links.isNullOrEmpty()) {
                    // switch to download tab
                    listBinding.tabs.getTabAt(TAB_DOWNLOADS)?.select()
                    // simulate list refresh
                    listBinding.srLayout.isRefreshing = true
                    // refresh items, when returned they'll stop the animation
                    downloadAdapter.refresh()
                }
            }
        })


        viewModel.deletedTorrentLiveData.observe(viewLifecycleOwner, {
            it.getContentIfNotHandled()?.let { _ ->
                context?.showToast(R.string.torrent_deleted)
                torrentAdapter.refresh()
            }
        })

        activityViewModel.listStateLiveData.observe(viewLifecycleOwner, {
            when (it.getContentIfNotHandled()) {
                ListState.UPDATE_DOWNLOAD -> {
                    downloadAdapter.refresh()
                }
                ListState.UPDATE_TORRENT -> {
                    torrentAdapter.refresh()
                }
                ListState.READY -> {
                }
                else -> {
                }
            }
        })


        setFragmentResultListener("downloadActionKey") { _, bundle ->
            bundle.getString("deletedDownloadKey")?.let {
                viewModel.deleteDownload(it)
            }
            bundle.getParcelable<DownloadItem>("openedDownloadItem")?.let {
                onClick(it)
            }
        }

        setFragmentResultListener("torrentActionKey") { _, bundle ->
            bundle.getString("deletedTorrentKey")?.let {
                viewModel.deleteTorrent(it)
            }
            bundle.getString("openedTorrentItem")?.let {
                val authState = activityViewModel.authenticationState.value?.peekContent()
                if (authState == AuthenticationState.AUTHENTICATED) {
                    val action = ListsTabFragmentDirections.actionListsTabToTorrentDetails(it)
                    findNavController().navigate(action)
                } else
                    context?.showToast(R.string.premium_needed)
            }
            bundle.getParcelable<TorrentItem>("downloadedTorrentItem")?.let {
                onClick(it)
            }
        }

        viewModel.deletedDownloadLiveData.observe(viewLifecycleOwner, {
            it.getContentIfNotHandled().let {
                context?.showToast(R.string.download_removed)
                downloadAdapter.refresh()
            }
        })

        viewModel.errorsLiveData.observe(viewLifecycleOwner, EventObserver {
            for (error in it) {
                when (error) {
                    is APIError -> {
                        context?.let { c ->
                            c.showToast(c.getApiErrorMessage(error.errorCode))
                        }
                    }
                    is EmptyBodyError -> {
                    }
                    is NetworkError -> {
                        context?.let { c ->
                            c.showToast(R.string.network_error)
                        }
                    }
                    is ApiConversionError -> {
                        context?.let { c ->
                            c.showToast(R.string.parsing_error)
                        }
                    }
                }
            }
        })

        listBinding.tabs.getTabAt(viewModel.getSelectedTab())?.select()

        return listBinding.root
    }

    override fun onClick(item: DownloadItem) {
        val authState = activityViewModel.authenticationState.value?.peekContent()
        if (authState == AuthenticationState.AUTHENTICATED) {
            val action = ListsTabFragmentDirections.actionListsTabToDownloadDetails(item)
            findNavController().navigate(action)
        } else
            context?.showToast(R.string.premium_needed)
    }

    override fun onLongClick(item: DownloadItem) {
        val dialog = DownloadContextualDialogFragment(item)
        dialog.show(parentFragmentManager, "DownloadContextualDialogFragment")
    }

    override fun onClick(item: TorrentItem) {
        val authState = activityViewModel.authenticationState.value?.peekContent()
        if (authState == AuthenticationState.AUTHENTICATED) {
            when (item.status) {
                "downloaded" -> {
                    // if the item has many links to download, show a toast
                    if (item.links.size > 2)
                        context?.showToast(R.string.downloading_torrent)
                    viewModel.downloadTorrent(item)
                }
                // open the torrent details fragment
                else -> {
                    val action = ListsTabFragmentDirections.actionListsTabToTorrentDetails(item.id)
                    findNavController().navigate(action)
                }
            }
        } else
            context?.showToast(R.string.premium_needed)
    }

    override fun onLongClick(item: TorrentItem) {
        val dialog = TorrentContextualDialogFragment(item)
        dialog.show(parentFragmentManager, "TorrentContextualDialogFragment")
    }

    companion object {
        const val TAB_DOWNLOADS = 0
        const val TAB_TORRENTS = 1
    }

}