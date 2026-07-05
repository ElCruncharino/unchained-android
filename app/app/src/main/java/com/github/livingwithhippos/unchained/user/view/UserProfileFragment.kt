package com.github.livingwithhippos.unchained.user.view

import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.content.PermissionChecker
import androidx.core.content.edit
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import coil.load
import com.github.livingwithhippos.unchained.R
import com.github.livingwithhippos.unchained.base.UnchainedFragment
import com.github.livingwithhippos.unchained.data.model.TorBoxUser
import com.github.livingwithhippos.unchained.data.model.User
import com.github.livingwithhippos.unchained.data.model.toUser
import com.github.livingwithhippos.unchained.databinding.FragmentUserProfileBinding
import com.github.livingwithhippos.unchained.settings.view.SettingsActivity
import com.github.livingwithhippos.unchained.settings.view.SettingsFragment.Companion.KEY_REFERRAL_ASKED
import com.github.livingwithhippos.unchained.settings.view.SettingsFragment.Companion.KEY_REFERRAL_USE
import com.github.livingwithhippos.unchained.statemachine.authentication.FSMAuthenticationState
import com.github.livingwithhippos.unchained.user.viewmodel.TorBoxAccountStatus
import com.github.livingwithhippos.unchained.user.viewmodel.UserProfileEvent
import com.github.livingwithhippos.unchained.user.viewmodel.UserProfileViewModel
import com.github.livingwithhippos.unchained.utilities.ACCOUNT_LINK
import com.github.livingwithhippos.unchained.utilities.EventObserver
import com.github.livingwithhippos.unchained.utilities.REFERRAL_LINK
import com.github.livingwithhippos.unchained.utilities.TORBOX_ACCOUNT_LINK
import com.github.livingwithhippos.unchained.utilities.TORBOX_API_KEY_PATTERN
import com.github.livingwithhippos.unchained.utilities.TORBOX_SUBSCRIPTION_LINK
import com.github.livingwithhippos.unchained.utilities.extension.getClipboardText
import com.github.livingwithhippos.unchained.utilities.extension.getFileSizeString
import com.github.livingwithhippos.unchained.utilities.extension.hideKeyboard
import com.github.livingwithhippos.unchained.utilities.extension.openExternalWebPage
import com.github.livingwithhippos.unchained.utilities.extension.showToast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.launch

/**
 * A simple [UnchainedFragment] subclass. Shows the accounts of both supported debrid services,
 * each on its own card: the Real-Debrid one from the shared activity view model (whose fetch also
 * feeds the authentication state machine) and the TorBox one loaded independently
 */
@AndroidEntryPoint
class UserProfileFragment : UnchainedFragment() {

    @Inject lateinit var preferences: SharedPreferences

    private val viewModel: UserProfileViewModel by viewModels()

    private var _binding: FragmentUserProfileBinding? = null

    // This property is only valid between onCreateView and onDestroyView.
    private val binding
        get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        // Inflate the layout for this fragment
        _binding = FragmentUserProfileBinding.inflate(inflater, container, false)
        val view = binding.root

        // the main user fetch, also observed by the authentication state machine flows
        if (activityViewModel.getCachedUser() == null) {
            activityViewModel.fetchUser()
        }
        lifecycleScope.launch {
            if (activityViewModel.isTokenPrivate()) {
                binding.tvLoginDescription.text = getString(R.string.login_type_private)
            } else {
                binding.tvLoginDescription.text = getString(R.string.login_type_open)
            }
        }

        viewModel.fetchAccountsStatus()

        viewModel.realDebridActiveLiveData.observe(viewLifecycleOwner) { active ->
            if (_binding == null) return@observe
            if (active) {
                binding.groupRdConnected.visibility = View.VISIBLE
                binding.tvRdNotConnected.visibility = View.GONE
                binding.bRdConnect.visibility = View.GONE
                // when torbox backs the main login the fetched user is the mapped torbox one and
                // must not fill the real debrid card, so the cached user is only used here. A
                // token just checked by the inline connect flow beats the possibly stale cache
                (viewModel.connectedRealDebridUser ?: activityViewModel.getCachedUser())?.let {
                    populateRealDebridView(it)
                }
            } else {
                binding.groupRdConnected.visibility = View.GONE
                binding.tvRdNotConnected.visibility = View.VISIBLE
                binding.bRdConnect.visibility = View.VISIBLE
            }
        }

        viewModel.torBoxStatusLiveData.observe(viewLifecycleOwner) { status ->
            if (_binding == null) return@observe
            when (status) {
                is TorBoxAccountStatus.Connected -> {
                    binding.groupTbConnected.visibility = View.VISIBLE
                    binding.tvTbNotConnected.visibility = View.GONE
                    binding.bTbConnect.visibility = View.GONE
                    populateTorBoxView(status.user)
                }
                TorBoxAccountStatus.NotConnected -> {
                    binding.groupTbConnected.visibility = View.GONE
                    binding.tvTbNotConnected.text = getString(R.string.torbox_not_connected)
                    binding.tvTbNotConnected.visibility = View.VISIBLE
                    binding.bTbConnect.visibility = View.VISIBLE
                }
                TorBoxAccountStatus.Error -> {
                    binding.groupTbConnected.visibility = View.GONE
                    binding.tvTbNotConnected.text = getString(R.string.torbox_account_error)
                    binding.tvTbNotConnected.visibility = View.VISIBLE
                    // the key can be replaced right from the card when it stopped working
                    binding.bTbConnect.visibility = View.VISIBLE
                }
            }
        }

        viewModel.eventLiveData.observe(
            viewLifecycleOwner,
            EventObserver { event ->
                when (event) {
                    UserProfileEvent.RealDebridConnected -> {
                        // refresh the activity level user (and its cache) with the new token and
                        // flip the card to its connected state
                        activityViewModel.fetchUser()
                        viewModel.fetchAccountsStatus()
                    }
                    UserProfileEvent.RealDebridTokenError ->
                        context?.showToast(R.string.invalid_token)
                }
            },
        )

        binding.bRdConnect.setOnClickListener { showRealDebridConnectDialog() }

        binding.bTbConnect.setOnClickListener { showTorBoxConnectDialog() }

        activityViewModel.userLiveData.observe(viewLifecycleOwner) {
            if (_binding == null) return@observe
            if (viewModel.realDebridActiveLiveData.value == true)
                populateRealDebridView(it.peekContent())
            lifecycleScope.launch {
                if (activityViewModel.isTokenPrivate()) {
                    binding.tvLoginDescription.text = getString(R.string.login_type_private)
                } else {
                    binding.tvLoginDescription.text = getString(R.string.login_type_open)
                }
            }
        }

        binding.bAccount.setOnClickListener {
            // if we never asked, show a dialog
            if (!preferences.getBoolean(KEY_REFERRAL_ASKED, false)) {
                // set asked as true
                preferences.edit { putBoolean(KEY_REFERRAL_ASKED, true) }

                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(getString(R.string.referral))
                    .setMessage(getString(R.string.referral_proposal))
                    .setNegativeButton(getString(R.string.decline)) { _, _ ->
                        preferences.edit { putBoolean(KEY_REFERRAL_USE, false) }
                        context?.openExternalWebPage(ACCOUNT_LINK)
                    }
                    .setPositiveButton(getString(R.string.accept)) { _, _ ->
                        preferences.edit { putBoolean(KEY_REFERRAL_USE, true) }
                        context?.openExternalWebPage(REFERRAL_LINK)
                    }
                    .show()
            } else {
                if (preferences.getBoolean(KEY_REFERRAL_USE, false))
                    context?.openExternalWebPage(REFERRAL_LINK)
                else context?.openExternalWebPage(ACCOUNT_LINK)
            }
        }

        binding.bTbAccount.setOnClickListener {
            context?.openExternalWebPage(TORBOX_ACCOUNT_LINK)
        }

        binding.bTbSubscription.setOnClickListener {
            context?.openExternalWebPage(TORBOX_SUBSCRIPTION_LINK)
        }

        activityViewModel.fsmAuthenticationState.observe(viewLifecycleOwner) {
            if (it != null) {
                when (it.peekContent()) {
                    is FSMAuthenticationState.WaitingUserAction -> {
                        // an error occurred, check it and eventually go back to the start fragment
                        val action = UserProfileFragmentDirections.actionUserToStartFragment()
                        safeNavigate(action)
                    }

                    FSMAuthenticationState.StartNewLogin -> {
                        // the user reset the login, go to the auth fragment
                        val action =
                            UserProfileFragmentDirections.actionUserToAuthenticationFragment()
                        safeNavigate(action)
                    }

                    FSMAuthenticationState.AuthenticatedOpenToken,
                    FSMAuthenticationState.AuthenticatedPrivateToken,
                    FSMAuthenticationState.RefreshingOpenToken -> {
                        // managed by activity
                    }

                    FSMAuthenticationState.CheckCredentials -> {
                        // shouldn't matter
                    }

                    FSMAuthenticationState.Start,
                    FSMAuthenticationState.WaitingToken,
                    FSMAuthenticationState.WaitingUserConfirmation -> {
                        // shouldn't happen
                    }
                }
            }
        }

        binding.bSettings.setOnClickListener {
            val intent = Intent(requireContext(), SettingsActivity::class.java)
            startActivity(intent)
        }

        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(
                    requireContext(),
                    Manifest.permission.POST_NOTIFICATIONS,
                ) != PermissionChecker.PERMISSION_GRANTED
        ) {
            activityViewModel.requireNotificationPermissions()
        }

        return view
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun populateRealDebridView(user: User?) {
        if (_binding == null) return
        user?.let {
            binding.tvName.text = it.username
            binding.tvMail.text = it.email
            // todo: check https://coil-kt.github.io/coil/image_loaders/#caching
            binding.ivProfilePic.load(it.avatar) { crossfade(true) }
            if (it.premium > 0) {
                binding.tvPremium.text = getString(R.string.premium)
            } else {
                binding.tvPremium.text = getString(R.string.not_premium)
            }
            binding.tvPremiumDays.text =
                getString(R.string.premium_days_format, it.premium / 60 / 60 / 24)
            binding.tvPoints.text = getString(R.string.premium_points_format, it.points)
            binding.pointsBar.setProgressCompat(it.points, true)
        }
    }

    /**
     * Shows the dialog connecting a real debrid account from its card: a short explanation with a
     * tappable link to the api token page, the token input with paste support and a reminder that
     * the OAuth login is still available from the login screen. The token is refused right away
     * when it is blank, too short or shaped like a torbox api key (a uuid); otherwise it is
     * checked against the real debrid user endpoint and stored only when it works
     */
    private fun showRealDebridConnectDialog() {
        showConnectServiceDialog(
            title = getString(R.string.real_debrid),
            description = getString(R.string.connect_real_debrid_message),
            inputHint = getString(R.string.private_token),
            secondaryHint = getString(R.string.connect_real_debrid_oauth_hint),
        ) { token ->
            when {
                token.matches(TORBOX_API_KEY_PATTERN.toRegex()) -> {
                    context?.showToast(R.string.real_debrid_token_is_torbox)
                    false
                }
                // real debrid tokens are around 52 characters, same check as the login screen
                token.length < 40 -> {
                    context?.showToast(R.string.invalid_token)
                    false
                }
                else -> {
                    viewModel.connectRealDebrid(token)
                    true
                }
            }
        }
    }

    /**
     * Shows the dialog connecting a torbox account from its card: a short explanation with a
     * tappable link to the torbox settings page and the api key input with paste support. A key
     * with a valid uuid shape goes through the same save path as the login screen and the
     * settings field: the preference always, the datastore only when real debrid is not the main
     * login
     */
    private fun showTorBoxConnectDialog() {
        showConnectServiceDialog(
            title = getString(R.string.torbox),
            description = getString(R.string.connect_torbox_message),
            inputHint = getString(R.string.torbox_api_key),
            secondaryHint = null,
        ) { key ->
            if (key.matches(TORBOX_API_KEY_PATTERN.toRegex())) {
                activityViewModel.saveTorBoxApiKey(key)
                viewModel.fetchAccountsStatus()
                true
            } else {
                context?.showToast(R.string.invalid_token)
                false
            }
        }
    }

    /**
     * Builds the shared connect dialog. [onSave] receives the trimmed input and returns whether
     * the dialog can be closed, so invalid tokens keep it open for a correction
     */
    private fun showConnectServiceDialog(
        title: String,
        description: String,
        inputHint: String,
        secondaryHint: String?,
        onSave: (String) -> Boolean,
    ) {
        val view = layoutInflater.inflate(R.layout.dialog_connect_service, null)
        val tokenField = view.findViewById<TextInputEditText>(R.id.tiConnectToken)
        view.findViewById<TextView>(R.id.tvConnectTitle).text = title
        view.findViewById<TextView>(R.id.tvConnectDescription).text = description
        view.findViewById<TextInputLayout>(R.id.tfConnectToken).hint = inputHint
        if (secondaryHint != null) {
            val secondaryView = view.findViewById<TextView>(R.id.tvConnectSecondary)
            secondaryView.text = secondaryHint
            secondaryView.visibility = View.VISIBLE
        }
        view.findViewById<Button>(R.id.bPasteConnectToken).setOnClickListener {
            tokenField.setText(getClipboardText(), TextView.BufferType.EDITABLE)
            tokenField.hideKeyboard()
        }
        val dialog =
            MaterialAlertDialogBuilder(requireContext())
                .setView(view)
                .setNegativeButton(getString(R.string.close)) { d, _ -> d.cancel() }
                .setPositiveButton(getString(R.string.save), null)
                .show()
        // set after show() to control when the dialog is dismissed
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
            if (onSave(tokenField.text.toString().trim())) dialog.dismiss()
        }
    }

    private fun populateTorBoxView(user: TorBoxUser) {
        if (_binding == null) return
        binding.tvTbMail.text = user.email ?: getString(R.string.torbox)
        val planName =
            when (user.plan) {
                0,
                null -> getString(R.string.torbox_plan_free)
                1 -> getString(R.string.torbox_plan_essential)
                2 -> getString(R.string.torbox_plan_pro)
                3 -> getString(R.string.torbox_plan_standard)
                else -> user.plan.toString()
            }
        binding.tvTbPlan.text = getString(R.string.torbox_plan_format, planName)
        // reuse the user mapping to turn the expiration date into the premium seconds left
        binding.tvTbPremiumDays.text =
            getString(R.string.premium_days_format, user.toUser().premium / 60 / 60 / 24)
        binding.tvTbDownloaded.text =
            getString(
                R.string.torbox_total_downloaded_format,
                getFileSizeString(requireContext(), user.totalDownloaded ?: 0L),
            )
    }
}
