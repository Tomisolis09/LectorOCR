package com.lectorocr

import android.app.Activity
import android.content.Context
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
 *  - Banner fijo abajo (adaptable al ancho de la pantalla), siempre visible.
 *  - Anuncio a pantalla completa (imagen o video, según lo que entregue AdMob):
 *    uno cada 3 lecturas o escaneos, al terminar, nunca mientras se lee.
 *
 * Los IDs vienen de BuildConfig: en debug siempre son los de prueba de Google.
 */
object Ads {

    /** Cada cuántas lecturas/escaneos sale el anuncio completo. */
    private const val EVERY = 3

    private var started = false
    private var loading = false
    private var interstitial: InterstitialAd? = null
    private var readings = 0

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

    /** Llamar al terminar cada lectura o escaneo: cada 3 muestra el anuncio completo. */
    fun onReading(activity: Activity) {
        readings++
        if (readings < EVERY) return
        val ad = interstitial
        if (ad == null) {
            // Todavía no cargó: se intenta de nuevo en la próxima lectura
            loadInterstitial(activity.applicationContext)
            return
        }
        readings = 0

        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() {
                interstitial = null
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
