package de.example.chatmonitor

import android.Manifest
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Environment
import android.util.Log
import android.webkit.URLUtil
import android.widget.Toast
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

// Portal root: the app itself redirects to the login page when needed.
// (Loading /login directly can show the login form even if you are logged in.)
const val START_URL = "https://schueler.schule-infoportal.de/"

// Hides the user dropdown (top right). Uses the CSSOM (element.style) instead of
// an injected <style> tag, because a Content-Security-Policy can block the latter.
// A MutationObserver re-applies it whenever the single-page app re-renders.
private const val HIDE_JS = """
(function() {
  var SEL = '.ui.icon.top.right.pointing.dropdown';
  function hide() {
    document.querySelectorAll(SEL).forEach(function(el) {
      el.style.setProperty('display', 'none', 'important');
    });
  }
  hide();
  if (window.__hideObserver) return;
  window.__hideObserver = new MutationObserver(hide);
  window.__hideObserver.observe(document, { childList: true, subtree: true });
})();
"""

class MainActivity : AppCompatActivity() {

    private lateinit var web: WebView

    // IDs of downloads started by this app (so we only open our own files).
    private val pendingDownloads = mutableSetOf<Long>()

    private val downloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
            if (!pendingDownloads.remove(id)) return

            val dm = getSystemService(DownloadManager::class.java)
            val uri = dm.getUriForDownloadedFile(id)

            if (uri == null) {
                Toast.makeText(this@MainActivity, R.string.download_failed, Toast.LENGTH_LONG).show()
                return
            }

            val mime = dm.getMimeTypeForDownloadedFile(id) ?: "*/*"
            val view = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, mime)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)

            try {
                startActivity(view)
            } catch (e: ActivityNotFoundException) {
                Toast.makeText(this@MainActivity, R.string.no_app_to_open, Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Log.w("ChatMonitor", "Could not open download", e)
            }
        }
    }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Notifier.createChannel(this)

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        web = WebView(this)
        setContentView(web)

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(web, true)
        }

        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true

        // Attachments: download with the login cookies, then open the file.
        web.setDownloadListener { url, userAgent, disposition, mime, _ ->
            startDownload(url, userAgent, disposition, mime)
        }
        ContextCompat.registerReceiver(
            this,
            downloadReceiver,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            ContextCompat.RECEIVER_EXPORTED,
        )

        web.webViewClient = object : WebViewClient() {
            // Also fires for in-page navigation (e.g. right after logging in).
            override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                CookieStore.save(this@MainActivity)
            }

            override fun onPageCommitVisible(view: WebView, url: String?) {
                view.evaluateJavascript(HIDE_JS, null)
            }

            override fun onPageFinished(view: WebView, url: String?) {
                view.evaluateJavascript(HIDE_JS, null)
                // Keep a copy of the cookies for the background worker.
                CookieStore.save(this@MainActivity)
            }
        }

        onBackPressedDispatcher.addCallback(this) {
            if (web.canGoBack()) web.goBack() else finish()
        }

        // Put back cookies that WebView may have lost since the last run.
        CookieStore.restore(this)

        if (savedInstanceState == null) web.loadUrl(START_URL) else web.restoreState(savedInstanceState)

        scheduleChecks()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
    }

    override fun onPause() {
        super.onPause()
        CookieStore.save(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(downloadReceiver)
    }

    private fun startDownload(url: String, userAgent: String?, disposition: String?, mime: String?) {
        Log.i("ChatMonitor", "download: $url ($mime)")

        if (!url.startsWith("http")) {
            Toast.makeText(this, R.string.download_failed, Toast.LENGTH_LONG).show()
            return
        }

        val fileName = URLUtil.guessFileName(url, disposition, mime)

        val request = DownloadManager.Request(Uri.parse(url))
            .addRequestHeader("Referer", START_URL)
            .setTitle(fileName)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)

        CookieManager.getInstance().getCookie(url)?.let { request.addRequestHeader("Cookie", it) }
        if (!userAgent.isNullOrEmpty()) request.addRequestHeader("User-Agent", userAgent)
        if (!mime.isNullOrEmpty()) request.setMimeType(mime)

        // Android 10+: public Downloads folder without extra permission.
        // Older versions: the app's own download folder.
        if (Build.VERSION.SDK_INT >= 29) {
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
        } else {
            request.setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, fileName)
        }

        val dm = getSystemService(DownloadManager::class.java)
        pendingDownloads.add(dm.enqueue(request))

        Toast.makeText(this, getString(R.string.download_started, fileName), Toast.LENGTH_SHORT).show()
    }

    override fun onStop() {
        super.onStop()
        CookieStore.save(this)
        // Check right after leaving the app (also useful for testing).
        WorkManager.getInstance(this).enqueue(
            OneTimeWorkRequestBuilder<ChatCheckWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
        )
    }

    private fun scheduleChecks() {
        // WorkManager's minimum interval for periodic work is 15 minutes.
        val request = PeriodicWorkRequestBuilder<ChatCheckWorker>(15, TimeUnit.MINUTES)
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            )
            .build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "chat-check",
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }
}
