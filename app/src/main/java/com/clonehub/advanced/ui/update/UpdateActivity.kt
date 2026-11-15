package com.clonehub.advanced.ui.update

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.clonehub.advanced.R
import com.clonehub.advanced.core.update.UpdateChecker
import com.clonehub.advanced.core.update.UpdateInstaller
import com.clonehub.advanced.core.update.UpdatePath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The update screen: what the channel offers, what this device can install, and why.
 *
 * Every line of it is a measured value rather than a promise:
 *  - the installed version and versionCode, read from the package manager;
 *  - the offered version and versionCode, with the comparison spelled out;
 *  - the signing key this build carries, because Android refuses an update signed with another key;
 *  - whether this device accepts an installer intent, taken from [UpdateInstaller.recordedPath] —
 *    `UNKNOWN` on a fresh install, and the screen says so instead of implying it will work;
 *  - the digest the file was checked against, or the fact that the server published none.
 */
class UpdateActivity : AppCompatActivity() {

    private companion object {
        const val TAG = "UpdateActivity"
    }

    private lateinit var tvInstalled: TextView
    private lateinit var tvOffered: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvNotes: TextView
    private lateinit var tvPath: TextView
    private lateinit var btnCheck: Button
    private lateinit var btnInstall: Button
    private lateinit var btnOpenFile: Button
    private lateinit var btnPermission: Button
    private lateinit var progress: ProgressBar
    private lateinit var ivIcon: ImageView

    private var latest: com.clonehub.advanced.core.update.UpdateInfo? = null

    /** The verified APK this screen downloaded, if any — the only file it will ever install. */
    private var verifiedFile: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_update)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.update_title)
        bindViews()
        showInstalledState()
        check(force = false)
    }

    private fun bindViews() {
        tvInstalled = findViewById(R.id.tvUpdateInstalled)
        tvOffered = findViewById(R.id.tvUpdateOffered)
        tvStatus = findViewById(R.id.tvUpdateStatus)
        tvNotes = findViewById(R.id.tvUpdateNotes)
        tvPath = findViewById(R.id.tvUpdatePath)
        btnCheck = findViewById(R.id.btnUpdateCheck)
        btnInstall = findViewById(R.id.btnUpdateInstall)
        btnOpenFile = findViewById(R.id.btnUpdateOpenFile)
        btnPermission = findViewById(R.id.btnUpdatePermission)
        progress = findViewById(R.id.progressUpdate)
        ivIcon = findViewById(R.id.ivUpdateIcon)

        // The panel shows the app's own icon, because an update prompt that does not look like the
        // app it updates is the oldest trick in the book.
        UpdateChecker.iconFile(this)?.let { file ->
            runCatching {
                val uri = FileProvider.getUriForFile(this, UpdateInstaller.authority(this), file)
                ivIcon.setImageURI(uri)
            }
        }

        btnCheck.setOnClickListener { check(force = true) }
        btnInstall.setOnClickListener { install() }
        btnOpenFile.setOnClickListener { openVerifiedFile() }
        btnPermission.setOnClickListener {
            runCatching { startActivity(UpdateChecker.installPermissionSettings(this)) }
                .onFailure { toast(getString(R.string.update_permission_unavailable)) }
        }
    }

    private fun showInstalledState() {
        val name = UpdateChecker.installedVersionName(this)
        val code = UpdateChecker.installedVersionCode(this)
        val key = UpdateChecker.signingKeyKind(this)
        tvInstalled.text = getString(R.string.update_installed_line, name, code, key)
        btnInstall.isEnabled = false
        btnOpenFile.visibility = View.GONE
        val canInstall = UpdateChecker.canRequestInstalls(this)
        btnPermission.visibility = if (canInstall) View.GONE else View.VISIBLE
        tvPath.text = UpdateInstaller.describePath(this)
        Log.i(TAG, "UPDATE_SCREEN installed=$name($code) signing=$key canInstall=$canInstall path=${UpdateInstaller.recordedPath(this)}")
    }

    private fun check(force: Boolean) {
        setBusy(true, getString(R.string.update_checking))
        lifecycleScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                com.clonehub.advanced.core.update.UpdateScheduler.check(this@UpdateActivity, force)
            }
            setBusy(false, null)
            val info = outcome.info
            when {
                info == null -> {
                    val reason = outcome.error ?: outcome.skippedBecause ?: "unknown"
                    tvOffered.text = getString(R.string.update_check_failed, reason)
                    Log.i(TAG, "UPDATE_SCREEN check_failed=$reason")
                }
                else -> showOffer(info)
            }
        }
    }

    private fun showOffer(info: com.clonehub.advanced.core.update.UpdateInfo) {
        latest = info
        tvOffered.text = getString(
            R.string.update_offered_line,
            info.version,
            info.versionCode ?: 0,
            info.source
        )
        val blocked = info.blockedReason
        if (blocked != null) {
            // Not an error the user caused: the channel did not state enough to compare builds.
            tvStatus.text = getString(R.string.update_cannot_tell, blocked)
            tvStatus.visibility = View.VISIBLE
            btnInstall.isEnabled = false
        } else {
            tvStatus.text = getString(R.string.update_available, info.version)
            tvStatus.visibility = View.VISIBLE
            btnInstall.isEnabled = true
        }
        tvNotes.text = info.notes ?: getString(R.string.update_no_notes)
        tvNotes.visibility = View.VISIBLE
        Log.i(
            TAG,
            "UPDATE_SCREEN offered=${info.version}(${info.versionCode}) newer=${info.isNewer} " +
                "blocked=${blocked ?: "<none>"} size=${info.sizeBytes ?: -1} sha256=${info.sha256 ?: "<none>"}"
        )
    }

    private fun install() {
        val info = latest ?: return
        if (info.blockedReason != null) return
        setBusy(true, getString(R.string.update_downloading))
        lifecycleScope.launch {
            val download = withContext(Dispatchers.IO) {
                UpdateChecker.downloadApk(this@UpdateActivity, info) { written, total ->
                    runOnUiThread {
                        progress.isIndeterminate = total <= 0
                        if (total > 0) progress.progress = ((written * 100) / total).toInt()
                    }
                }
            }
            setBusy(false, null)
            download.onSuccess { file ->
                verifiedFile = file.file
                val digestLine = if (file.digestVerified) {
                    getString(R.string.update_verified, file.sha256.take(16))
                } else {
                    getString(R.string.update_unverified, file.sha256.take(16))
                }
                tvPath.text = "$digestLine\n${UpdateInstaller.describePath(this@UpdateActivity)}"
                val started = UpdateInstaller.tryInstall(this@UpdateActivity, file.file)
                if (started) {
                    // The verdict is read from logcat after the attempt, in onResume, because the
                    // platform answers asynchronously and a wrong guess would be worse than a delay.
                    tvStatus.text = getString(R.string.update_install_started)
                    tvStatus.visibility = View.VISIBLE
                } else {
                    btnOpenFile.visibility = View.VISIBLE
                    tvStatus.text = getString(R.string.update_install_blocked)
                    tvStatus.visibility = View.VISIBLE
                }
            }.onFailure { error ->
                tvStatus.text = getString(R.string.update_download_failed, error.message ?: "unknown")
                tvStatus.visibility = View.VISIBLE
                Log.w(TAG, "UPDATE_SCREEN download_failed", error)
            }
        }
    }

    private fun openVerifiedFile() {
        val file = verifiedFile ?: return
        // Opening the file is the path every Android level accepts: the package installer reads a
        // real file, not an intent URL. `ACTION_VIEW` with the app's own FileProvider is the same
        // intent the user would get from a file manager, without asking them to find the file.
        runCatching {
            val uri = FileProvider.getUriForFile(this, UpdateInstaller.authority(this), file)
            startActivity(UpdateChecker.installerIntent(uri))
        }.onSuccess {
            Log.i(TAG, "UPDATE_SCREEN open_file file=${file.name} bytes=${file.length()}")
        }.onFailure { error ->
            // Nothing can open it: name the path so the user can still reach the file themselves.
            tvPath.text = getString(R.string.update_file_location, file.absolutePath)
            Log.w(TAG, "UPDATE_SCREEN open_file_failed path=${file.absolutePath}", error)
        }
    }

    override fun onResume() {
        super.onResume()
        // Back from the installer (or from the settings screen): record what the platform decided.
        lifecycleScope.launch {
            val logcat = withContext(Dispatchers.IO) {
                runCatching {
                    Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-t", "400"))
                        .inputStream.bufferedReader().readText()
                }.getOrDefault("")
            }
            if (logcat.isNotEmpty()) {
                val verdict = UpdateInstaller.recordVerdictFromLog(this@UpdateActivity, logcat)
                if (verdict != UpdatePath.UNKNOWN) {
                    tvPath.text = UpdateInstaller.describePath(this@UpdateActivity)
                    if (verdict == UpdatePath.INTENT_ACCEPTED) {
                        btnOpenFile.visibility = View.GONE
                    }
                }
            }
            val canInstall = UpdateChecker.canRequestInstalls(this@UpdateActivity)
            btnPermission.visibility = if (canInstall) View.GONE else View.VISIBLE
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun setBusy(busy: Boolean, message: String?) {
        progress.visibility = if (busy) View.VISIBLE else View.GONE
        progress.isIndeterminate = true
        btnCheck.isEnabled = !busy
        btnInstall.isEnabled = !busy && latest?.blockedReason == null
        if (message != null) {
            tvStatus.text = message
            tvStatus.visibility = View.VISIBLE
        }
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()

    companion object {
        fun intent(activity: Activity): Intent = Intent(activity, UpdateActivity::class.java)
    }
}
