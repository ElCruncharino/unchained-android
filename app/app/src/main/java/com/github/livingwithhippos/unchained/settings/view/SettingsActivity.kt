package com.github.livingwithhippos.unchained.settings.view

import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.setupActionBarWithNavController
import com.github.livingwithhippos.unchained.R
import dagger.hilt.android.AndroidEntryPoint

/**
 * A simple [AppCompatActivity] subclass. Used to navigate from any fragment to the settings screen
 * since the multiple backstack navigation makes it kind of complicated.
 */
@AndroidEntryPoint
class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_settings)

        setSupportActionBar(findViewById(R.id.emptyAppBar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val navController =
            (supportFragmentManager.findFragmentById(R.id.fragment_container) as NavHostFragment)
                .navController
        val appBarConfiguration = AppBarConfiguration(navController.graph)
        setupActionBarWithNavController(navController, appBarConfiguration)
    }

    override fun onSupportNavigateUp(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Use OnBackPressedDispatcher for Android 13+
            onBackPressedDispatcher.onBackPressed()
        } else {
            // Use the deprecated super.onBackPressed() for older versions
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
        return true
    }
}
