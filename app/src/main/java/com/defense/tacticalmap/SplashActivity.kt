package com.defense.tacticalmap

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.animation.AlphaAnimation
import android.view.animation.AnimationSet
import android.view.animation.ScaleAnimation
import androidx.appcompat.app.AppCompatActivity

/**
 * SplashActivity — Veer Rakshak boot screen.
 *
 * Shows the compass logo with a fade+scale animation, then
 * transitions into MainActivity after a short delay.
 * Suppressing lint for the intentional 2-second delay.
 */
@SuppressLint("CustomSplashScreen")
class SplashActivity : AppCompatActivity() {

    private val SPLASH_DURATION_MS = 2200L
    private val splashHandler = Handler(Looper.getMainLooper())
    private var hasLaunchedMain = false
    private val launchMainRunnable = Runnable {
        if (hasLaunchedMain || isFinishing || isDestroyed) return@Runnable
        hasLaunchedMain = true
        startActivity(
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
        )
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // If the launcher re-delivers MAIN/LAUNCHER into an existing task, don't spawn
        // another splash->main chain on top of the already running app.
        if (!isTaskRoot &&
            intent?.hasCategory(Intent.CATEGORY_LAUNCHER) == true &&
            Intent.ACTION_MAIN == intent?.action
        ) {
            finish()
            return
        }

        setContentView(R.layout.activity_splash)

        // Hide system UI for immersive splash
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        )

        // Animate the logo: fade in + subtle scale up
        val logoView  = findViewById<View>(R.id.splashLogo)
        val titleView = findViewById<View>(R.id.splashTitle)
        val subView   = findViewById<View>(R.id.splashSubtitle)
        val loadView  = findViewById<View>(R.id.splashLoading)

        // Logo: fade in + scale from 0.85 → 1.0
        val logoAnim = AnimationSet(true).apply {
            addAnimation(AlphaAnimation(0f, 1f).also { it.duration = 900 })
            addAnimation(ScaleAnimation(
                0.85f, 1f, 0.85f, 1f,
                ScaleAnimation.RELATIVE_TO_SELF, 0.5f,
                ScaleAnimation.RELATIVE_TO_SELF, 0.5f
            ).also { it.duration = 900 })
            fillAfter = true
        }

        // Title + subtitle: fade in with slight delay
        val textFade = AlphaAnimation(0f, 1f).apply {
            startOffset = 400
            duration = 800
            fillAfter = true
        }

        // Loading text: fade in last
        val loadFade = AlphaAnimation(0f, 0.5f).apply {
            startOffset = 900
            duration = 600
            fillAfter = true
        }

        logoView.startAnimation(logoAnim)
        titleView.startAnimation(textFade)
        subView.startAnimation(textFade)
        loadView.startAnimation(loadFade)

        // Navigate to MainActivity after splash duration
        splashHandler.postDelayed(launchMainRunnable, SPLASH_DURATION_MS)
    }

    override fun onDestroy() {
        splashHandler.removeCallbacks(launchMainRunnable)
        super.onDestroy()
    }
}
