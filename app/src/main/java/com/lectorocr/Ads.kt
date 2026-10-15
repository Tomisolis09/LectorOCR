package com.lectorocr

import android.app.Activity
import android.content.Context
import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback

/**
 * Publicidad con AdMob.
 *  - Banner fijo abajo (adaptable al ancho de la pantalla).
 *  - Anuncio a pantalla completa: como máximo uno cada 5 minutos, y solo en momentos
 *    naturales (al terminar de extraer texto o al abrir un archivo), nunca mientras se lee.
 *
 * Los IDs vienen de BuildConfig: en debug siempre son los de prueba de Google.
 */
object Ads {

    private const val INTERVAL_MS = 5 * 60 * 1000L

    private var started = false
    private var loading = false
    private var interstitial: InterstitialAd? = null

    // El contador arranca al abrir la app: el primer anuncio completo sale a los 5 minutos.
    private var lastShown = SystemClock.elapsedRealtime()

    fun init(context: Context) {
        if (started) return
        started = true
        MobileAds.initialize(context.applicationContext) {}
        loadInterstitial(context.applicationContext)
    }

    private fun loadInterstitial(context: Context) {
        if (loading || interstitial != null) return
        loading = true
        InterstitialAd.load(
            context,
            BuildConfig.ADMOB_INTERSTITIAL_ID,
            AdRequest.Builder().build(),
            object : InterstitialAdLoadCallback() {
                override fun onAdLoaded(ad: InterstitialAd) {
                    interstitial = ad
                    loading = false
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    interstitial = null
                    loading = false
                }
            },
        )
    }

    /** Muestra el anuncio completo solo si ya pasaron 5 minutos desde el último. */
    fun maybeShow(activity: Activity) {
        val ad = interstitial
        if (ad == null) {
            loadInterstitial(activity.applicationContext)
            return
        }
        if (SystemClock.elapsedRealtime() - lastShown < INTERVAL_MS) return

        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdShowedFullScreenContent() {
                lastShown = SystemClock.elapsedRealtime()
            }

            override fun onAdDismissedFullScreenContent() {
                interstitial = null
                lastShown = SystemClock.elapsedRealtime()
                loadInterstitial(activity.applicationContext)
            }

            override fun onAdFailedToShowFullScreenContent(error: AdError) {
                interstitial = null
                loadInterstitial(activity.applicationContext)
            }
        }
        interstitial = null
        ad.show(activity)
    }
}

@Composable
fun BannerAd(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val widthDp = LocalConfiguration.current.screenWidthDp
    val adView = remember(widthDp) {
        AdView(context).apply {
            setAdSize(AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(context, widthDp))
            adUnitId = BuildConfig.ADMOB_BANNER_ID
            loadAd(AdRequest.Builder().build())
        }
    }
    DisposableEffect(adView) { onDispose { adView.destroy() } }
    AndroidView(factory = { adView }, modifier = modifier)
}
