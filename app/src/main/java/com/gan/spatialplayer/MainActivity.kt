package com.gan.spatialplayer

import android.Manifest
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MimeTypes
import androidx.recyclerview.widget.LinearLayoutManager
import com.gan.spatialplayer.databinding.ActivityMainBinding
import com.gan.spatialplayer.media.DeviceCapabilities
import com.gan.spatialplayer.ui.Haptics
import com.gan.spatialplayer.media.FfmpegCodecs
import com.gan.spatialplayer.media.FileEntry
import com.gan.spatialplayer.media.MediaRepository
import com.gan.spatialplayer.media.PowerampReader
import com.gan.spatialplayer.ui.ChipStrip
import com.gan.spatialplayer.ui.FileListAdapter
import com.gan.spatialplayer.ui.ThumbnailLoader
import kotlinx.coroutines.launch

/**
 * The launch surface: a list of playable files and nothing else.
 *
 * There is intentionally no media library here. It shows what MediaStore already indexed, plus
 * anything in a folder the user granted, plus Poweramp's library on request. Opening a file hands
 * off to [PlayerActivity], which owns all the decode and audio policy.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var repository: MediaRepository
    private lateinit var poweramp: PowerampReader
    private lateinit var adapter: FileListAdapter

    /** Whether the library list is expanded; persisted so the choice sticks. */
    private var libraryExpanded = false
    private val prefs by lazy { getSharedPreferences("spatial_player", MODE_PRIVATE) }

    private val entries = ArrayList<FileEntry>()
    private var scopedFolderUri: Uri? = null

    private val requestMediaPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) {
                // A refusal is not fatal: the picker still works and so does Poweramp.
                showToast(getString(R.string.empty_hint))
            }
            refresh()
        }

    /**
     * Audio access is needed to open Poweramp's files directly.
     *
     * Poweramp's own content URIs cannot be streamed (its provider rejects `openInputStream`), so
     * playback resolves the real file path instead - which requires this permission.
     */
    private val requestAudioPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) loadPowerampLibrary()
            else showToast(getString(R.string.poweramp_needs_audio_permission))
        }

    private val pickFile =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@registerForActivityResult
            persistAccess(uri)
            lifecycleScope.launch {
                val entry = repository.describe(uri)
                if (entry == null) {
                    showToast(getString(R.string.error_open))
                } else {
                    openPlayer(entry)
                }
            }
        }

    private val pickFolder =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri == null) return@registerForActivityResult
            persistAccess(uri)
            scopedFolderUri = uri
            getPreferences(MODE_PRIVATE).edit()
                .putString(PREF_FOLDER_URI, uri.toString())
                .apply()
            refresh()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        repository = MediaRepository(this)
        poweramp = PowerampReader(this)

        applyWindowInsets()
        setUpList()
        setUpActions()
        restoreFolderGrant()
        playIntro()

        // An explicit VIEW intent means the user chose a file elsewhere; honour that first.
        if (!handleViewIntent(intent)) {
            refresh()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleViewIntent(intent)
    }

    private fun handleViewIntent(intent: Intent?): Boolean {
        val uri = intent?.data ?: return false
        if (intent.action != Intent.ACTION_VIEW) return false
        lifecycleScope.launch {
            val entry = repository.describe(uri)
            if (entry != null) openPlayer(entry)
        }
        return true
    }

    // ------------------------------------------------------------------ setup

    private fun applyWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.root.setPadding(0, bars.top, 0, 0)
            val params = binding.actionBar.layoutParams as FrameLayout.LayoutParams
            params.bottomMargin = bars.bottom + dp(22)
            binding.actionBar.layoutParams = params
            insets
        }
    }

    private fun setUpList() {
        adapter = FileListAdapter(
            onClick = { entry -> openPlayer(entry) },
            thumbnails = ThumbnailLoader(this),
        )
        setUpLibraryDisclosure()
        binding.fileList.layoutManager = LinearLayoutManager(this)
        binding.fileList.adapter = adapter
        binding.fileList.itemAnimator = null
    }

    private fun setUpActions() {
        binding.buttonOpenFile.setOnClickListener {
            pickFile.launch(MediaRepository.PICKER_MIME_TYPES)
        }

        // Long-press opens a folder instead of a single file.
        binding.buttonOpenFile.setOnLongClickListener {
            pickFolder.launch(null)
            true
        }

        binding.buttonRefresh.setOnClickListener { refresh() }
        binding.buttonSpatialStatus.setOnClickListener { showSpatialSummary() }
        binding.buttonPoweramp.setOnClickListener { loadPowerampLibrary() }
    }

    private fun restoreFolderGrant() {
        val stored = getPreferences(MODE_PRIVATE).getString(PREF_FOLDER_URI, null)
        scopedFolderUri = stored?.let { runCatching { Uri.parse(it) }.getOrNull() }
    }

    private fun playIntro() {
        binding.root.alpha = 0f
        binding.root.animate().alpha(1f).setDuration(320L).start()
    }

    // ------------------------------------------------------------------ data

    private fun refresh() {
        setScanning(true)
        lifecycleScope.launch {
            val collected = ArrayList<FileEntry>()

            val granted = ContextCompat.checkSelfPermission(
                this@MainActivity,
                Manifest.permission.READ_MEDIA_VIDEO,
            ) == PackageManager.PERMISSION_GRANTED

            if (granted) {
                collected += repository.fromMediaStore()
            } else {
                requestMediaPermission.launch(Manifest.permission.READ_MEDIA_VIDEO)
            }

            scopedFolderUri?.let { collected += repository.fromTree(it) }

            publish(collected)
        }
    }

    private fun loadPowerampLibrary() {
        // Without audio access Poweramp's files cannot be opened, so ask before trying.
        val audioGranted = ContextCompat.checkSelfPermission(
            this@MainActivity,
            Manifest.permission.READ_MEDIA_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
        if (!audioGranted) {
            requestAudioPermission.launch(Manifest.permission.READ_MEDIA_AUDIO)
            return
        }

        setScanning(true)
        lifecycleScope.launch {
            val result = poweramp.readLibrary()
            showToast(result.status)
            if (result.entries.isEmpty()) {
                setScanning(false)
            } else {
                // Poweramp entries are merged on top of whatever else is listed.
                val merged = ArrayList<FileEntry>(result.entries)
                merged += entries.filter { it.source != FileEntry.Source.POWERAMP }
                publish(merged)
            }
        }
    }

    /**
     * Wires the library disclosure header.
     *
     * The list starts collapsed: the useful first screen is the status chips and the actions, not a
     * wall of filenames. The choice is remembered, so someone who prefers it open gets it open.
     */
    private fun setUpLibraryDisclosure() {
        binding.libraryHeader.setOnClickListener {
            Haptics.touch(binding.libraryHeader)
            setLibraryExpanded(!libraryExpanded, animate = true)
        }
        setLibraryExpanded(prefs.getBoolean(KEY_LIBRARY_EXPANDED, false), animate = false)
    }

    /**
     * Shows or hides the file list.
     *
     * The body's height comes from `layout_weight`, so expanding animates the weight and the list
     * grows into place instead of appearing at full size. Below a whole number the weighted child
     * gets no height at all, which is why the visibility is flipped once the weight is non-zero
     * rather than up front.
     */
    private fun setLibraryExpanded(expanded: Boolean, animate: Boolean) {
        libraryExpanded = expanded
        prefs.edit().putBoolean(KEY_LIBRARY_EXPANDED, expanded).apply()

        binding.libraryChevron.animate()
            .rotation(if (expanded) 180f else 0f)
            .setDuration(if (animate) MOTION_MS else 0L)
            .start()

        if (!animate) {
            (binding.libraryBody.layoutParams as LinearLayout.LayoutParams).weight =
                if (expanded) 1f else 0f
            binding.libraryBody.visibility = if (expanded) View.VISIBLE else View.GONE
            return
        }

        val params = binding.libraryBody.layoutParams as LinearLayout.LayoutParams
        if (expanded) binding.libraryBody.visibility = View.VISIBLE
        val from = params.weight
        val to = if (expanded) 1f else 0f
        ValueAnimator.ofFloat(from, to).apply {
            duration = MOTION_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener { animator ->
                params.weight = animator.animatedValue as Float
                binding.libraryBody.layoutParams = params
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    // Collapsing to exactly zero removes the body from the layout entirely.
                    if (!expanded) binding.libraryBody.visibility = View.GONE
                }
            })
            start()
        }
    }

    private fun publish(list: List<FileEntry>) {
        entries.clear()
        // De-duplicate on URI so a folder grant does not double up MediaStore rows.
        val seen = HashSet<String>()
        for (entry in list) {
            if (seen.add(entry.uri.toString())) entries += entry
        }
        adapter.submit(entries)
        setScanning(false)
        updateEmptyState()
        updateCapabilityLine()
        updateLibrarySummary()
    }

    /** One line telling the collapsed header what is inside. */
    private fun updateLibrarySummary() {
        val count = entries.size
        val folders = entries.mapNotNull { it.uri.path?.substringBeforeLast('/') }.distinct().size
        binding.librarySummary.text = when {
            count == 0 -> getString(R.string.library_empty)
            folders > 0 -> "$count · $folders folders"
            else -> count.toString()
        }
    }

    private fun setScanning(scanning: Boolean) {
        binding.scanProgress.visibility = if (scanning) View.VISIBLE else View.GONE
    }

    private fun updateEmptyState() {
        val empty = entries.isEmpty()
        binding.emptyState.visibility = if (empty) View.VISIBLE else View.GONE
        binding.fileList.visibility = if (empty) View.GONE else View.VISIBLE
    }

    /**
     * Device and build facts, as chips.
     *
     * These are one-word statuses, so pills keep them scannable; the spatialiser state gets an
     * accent when it is actually engaged, because that is the state worth noticing.
     */
    private fun updateCapabilityLine() {
        val hdr = DeviceCapabilities.hdrSnapshot(this)
        val spatial = DeviceCapabilities.spatialSnapshot(this)
        val ffmpegReady = FfmpegCodecs.isAvailable(MimeTypes.AUDIO_TRUEHD)

        binding.chipStrip.setChips(
            listOf(
                ChipStrip.Chip(
                    "${hdr.widthPx}x${hdr.heightPx}",
                    ChipStrip.Tone.NEUTRAL,
                ),
                ChipStrip.Chip(
                    hdr.supportedHdrTypes.joinToString("+").ifEmpty { "SDR" },
                    if (hdr.supportedHdrTypes.isEmpty()) ChipStrip.Tone.NEUTRAL else ChipStrip.Tone.ACTIVE,
                ),
                ChipStrip.Chip(
                    if (spatial.active) "SPATIAL ON" else "SPATIAL OFF",
                    if (spatial.active) ChipStrip.Tone.ACTIVE else ChipStrip.Tone.NEUTRAL,
                ),
                ChipStrip.Chip(
                    if (spatial.headTrackerAvailable) "HEAD TRACK" else "NO HEAD TRACK",
                    if (spatial.headTrackerAvailable) ChipStrip.Tone.ACTIVE else ChipStrip.Tone.WARN,
                ),
                ChipStrip.Chip(
                    if (ffmpegReady) "FFMPEG" else "NO FFMPEG",
                    if (ffmpegReady) ChipStrip.Tone.ACTIVE else ChipStrip.Tone.WARN,
                ),
            ),
        )
    }

    private fun showSpatialSummary() {
        val spatial = DeviceCapabilities.spatialSnapshot(this)
        val message = buildString {
            append("spatializer available : ").append(spatial.available).append('\n')
            append("enabled               : ").append(spatial.enabled).append('\n')
            append("immersive level       : ").append(spatial.immersiveLevelLabel).append('\n')
            append("head tracker          : ").append(spatial.headTrackerAvailable).append('\n')
            append("output can spatialize : ").append(spatial.outputCanSpatialize).append('\n')
            append("output device         : ").append(spatial.outputDeviceName ?: "—").append('\n')
            append("output type           : ").append(spatial.outputDeviceTypeLabel ?: "—")
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.audio_settings)
            .setMessage(message)
            .setPositiveButton(R.string.close, null)
            .setNeutralButton(R.string.open_settings) { _, _ ->
                runCatching { startActivity(Intent(Settings.ACTION_SOUND_SETTINGS)) }
            }
            .show()
    }

    // ------------------------------------------------------------------ helpers

    private fun openPlayer(entry: FileEntry) {
        startActivity(
            Intent(this, PlayerActivity::class.java)
                .setAction(Intent.ACTION_VIEW)
                .setData(entry.uri)
                .putExtra(PlayerActivity.EXTRA_DISPLAY_NAME, entry.displayName)
                .putExtra(PlayerActivity.EXTRA_MIME_TYPE, entry.mimeType)
                .putExtra(PlayerActivity.EXTRA_SIZE_BYTES, entry.sizeBytes),
        )
    }

    private fun persistAccess(uri: Uri) {
        runCatching {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val PREF_FOLDER_URI = "scoped_folder_uri"

        const val KEY_LIBRARY_EXPANDED = "library_expanded"

        /** Disclosure animation length; a spring would overshoot the weighted height. */
        const val MOTION_MS = 280L
    }
}
