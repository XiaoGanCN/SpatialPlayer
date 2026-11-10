package com.gan.spatialplayer

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.View
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
import com.gan.spatialplayer.media.FfmpegCodecs
import com.gan.spatialplayer.media.FileEntry
import com.gan.spatialplayer.media.MediaRepository
import com.gan.spatialplayer.media.PowerampReader
import com.gan.spatialplayer.ui.ChipFade
import com.gan.spatialplayer.ui.ChipStrip
import com.gan.spatialplayer.ui.FileListAdapter
import com.gan.spatialplayer.ui.GlassInstaller
import com.gan.spatialplayer.ui.Haptics
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

    /** Debug builds only: the library probe's hook into this instance. See [onResume]. */
    private var libraryProbeReceiver: BroadcastReceiver? = null

    /**
     * Which library is folded open, if any; persisted so the choice sticks.
     *
     * Poweramp and Library are the same kind of control over the same list - one holds the music,
     * the other everything else - so "open" is a single choice rather than two independent flags.
     * That is also why only one of the two chevrons is ever folded at a time.
     */
    private var folded = Fold.NONE

    /**
     * `internal` rather than `private` only because the debug library probe reads and restores it;
     * see [setFoldForTest].
     */
    internal enum class Fold { NONE, LIBRARY, POWERAMP }

    private val prefs by lazy { getSharedPreferences("spatial_player", MODE_PRIVATE) }
    private val settings by lazy { SettingsStore(this) }

    private val entries = ArrayList<FileEntry>()
    private var scopedFolderUri: Uri? = null

    /**
     * Poweramp's rows, kept aside so a refresh cannot lose them.
     *
     * They used to live only inside the merged [entries], and [refresh] rebuilds that list from
     * MediaStore and the granted folder - so pressing refresh dropped all 140 of them, while
     * [powerampLoaded] stayed `true` and told the chip there was nothing to re-read. The fold then
     * showed its empty state for the rest of the activity's life. Holding them here means "refresh"
     * means "re-read the sources we know about", not "forget the one we are not re-reading".
     */
    private var powerampEntries: List<FileEntry> = emptyList()

    /** True once Poweramp has answered with rows; see [loadPowerampLibrary]. */
    private var powerampLoaded = false

    private val tag = "MainActivity"

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
        applyAmbientSetting()
        ChipFade.attach(binding.chipScroll, binding.chipFade)
        restoreFolderGrant()
        playIntro()
        requestNotificationPermission()
        installGlass()

        // An explicit VIEW intent means the user chose a file elsewhere; honour that first.
        if (!handleViewIntent(intent)) {
            refresh()
        }
    }

    /**
     * Asks once for the notification permission.
     *
     * Background playback is delivered through a media notification, and on API 33+ an app cannot post
     * one without this. Without it the player still runs in the background - verified - but there is
     * no transport control on the lock screen and no entry the user can tap, which is most of the
     * point. Asked on the library screen rather than in the player so the prompt arrives before
     * anything is playing.
     */
    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleViewIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        if (::adapter.isInitialized) adapter.resumePreviews()
        // Debug builds only: lets `tools/verify-poweramp.sh` drive this instance's Poweramp fold and
        // refresh, because the bug it guards lived in the wiring between the two and a probe with a
        // list of its own would test a copy of the code that was wrong. Same shape as the player's
        // panel probe; see PlayerActivity.onResume for the flag notes.
        if (libraryProbeReceiver == null) {
            libraryProbeReceiver = DebugProbes.registerLibrary(this)
        }

        instance = this
    }

    override fun onStop() {
        if (::adapter.isInitialized) adapter.pausePreviews()
        super.onStop()
    }

    override fun onDestroy() {
        // Unregistered here rather than in onPause for the same reason the player's probe is: a
        // probe that briefly covers the library must not lose its hook, and a destroyed activity
        // must not keep its view tree reachable through one.
        libraryProbeReceiver?.let { runCatching { unregisterReceiver(it) } }
        libraryProbeReceiver = null
        if (instance === this) instance = null
        super.onDestroy()
    }

    /**
     * Opens a file handed over by another app.
     *
     * Three different shapes have to be handled, because they are what apps actually send:
     *
     *  * `ACTION_VIEW` with the item as the intent data (a file manager's "open with").
     *  * `ACTION_SEND` with the item on `EXTRA_STREAM` (the share sheet).
     *  * `clipData` with one or more items, which is what a modern picker attaches even for a
     *    single file.
     *
     * Only the first item is played when several arrive: this is a player, not a queue builder, and
     * silently ignoring the rest is better than refusing the whole share.
     */
    private fun handleViewIntent(intent: Intent?): Boolean {
        if (intent == null) return false
        val uri = firstSharedUri(intent) ?: return false

        // The grant is per-URI and lives on the intent, so it has to be taken explicitly; without it
        // the provider throws SecurityException when the player opens the stream.
        runCatching {
            intent.data?.let { grantUriPermission(packageName, it, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }

        lifecycleScope.launch {
            val entry = repository.describe(uri)
            if (entry != null) {
                openPlayer(entry)
            } else {
                showToast(getString(R.string.cannot_open_shared_file))
            }
        }
        return true
    }

    /** Pulls the first readable URI out of whichever shape the intent used. */
    private fun firstSharedUri(intent: Intent): Uri? {
        when (intent.action) {
            Intent.ACTION_VIEW -> intent.data?.let { return it }

            Intent.ACTION_SEND -> {
                @Suppress("DEPRECATION")
                val stream = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                if (stream != null) return stream
            }

            Intent.ACTION_SEND_MULTIPLE -> {
                @Suppress("DEPRECATION")
                val streams = intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
                streams?.firstOrNull()?.let { return it }
            }
        }
        // clipData is populated by most modern pickers regardless of the action.
        val clip = intent.clipData
        if (clip != null && clip.itemCount > 0) return clip.getItemAt(0).uri
        return intent.data
    }

    // ------------------------------------------------------------------ setup

    private fun applyWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            binding.root.setPadding(bars.left, bars.top, bars.right, bars.bottom)
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

    /**
     * Tags every surface that should wear the real material, then installs it.
     *
     * Tagged in code rather than in the layout because `android:tag="@id/..."` does not survive as a
     * reference: the framework stores it as the *string* `"false"` (measured - every tagged
     * ImageButton came back `tag=String:false`), so the installer saw no role and left all nine
     * painted views alone. A tag set here is an `Int`, which is what `GlassInstaller` reads.
     */
    private fun installGlass() {
        binding.buttonSettings.tag = R.id.glass_role_button
        binding.buttonSpatialStatus.tag = R.id.glass_role_button
        binding.buttonRefresh.tag = R.id.glass_role_button
        binding.buttonOpenFolder.tag = R.id.glass_role_button
        binding.buttonOpenFile.tag = R.id.glass_role_button_active
        binding.buttonPoweramp.tag = if (folded == Fold.POWERAMP) R.id.glass_role_button_active else R.id.glass_role_button
        binding.buttonLibrary.tag = if (folded == Fold.LIBRARY) R.id.glass_role_button_active else R.id.glass_role_button
        binding.root.post { GlassInstaller.apply(binding.root) }
    }

    private fun setUpActions() {
        binding.buttonOpenFile.setOnClickListener {
            pickFile.launch(MediaRepository.PICKER_MIME_TYPES)
        }

        binding.buttonOpenFolder.setOnClickListener { pickFolder.launch(null) }

        // Long-press opens a folder instead of a single file.
        binding.buttonOpenFile.setOnLongClickListener {
            pickFolder.launch(null)
            true
        }

        binding.buttonRefresh.setOnClickListener { refresh() }
        binding.buttonSettings.setOnClickListener {
            startActivity(android.content.Intent(this, SettingsActivity::class.java))
        }
        binding.buttonSpatialStatus.setOnClickListener { showSpatialSummary() }
        // buttonPoweramp's click is owned by setUpLibraryDisclosure: it is a fold, not an action.

        // Press and lift feedback for every control in the chip and the header, from one place, so
        // they all feel the same. See Haptics.attachTo.
        listOf(
            binding.buttonOpenFile,
            binding.buttonPoweramp,
            binding.buttonLibrary,
            binding.buttonRefresh,
            binding.buttonSpatialStatus,
            binding.buttonSettings,
        ).forEach { Haptics.attachTo(it) }
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

    /** The library's wash is the same setting as the player's, so it has to be applied here too. */
    private fun applyAmbientSetting() {
        binding.ambientScrim.glowEnabled = settings.ambientEnabled
    }

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
            } else if (folded == Fold.LIBRARY) {
                requestMediaPermission.launch(Manifest.permission.READ_MEDIA_VIDEO)
            }

            scopedFolderUri?.let { collected += repository.fromTree(it) }

            // Re-read Poweramp as well when the fold has already been opened. Without this the
            // refresh silently dropped the music: the list above holds only MediaStore and folder
            // rows, so `entries.clear()` inside `publish` took every Poweramp row with it. Re-reading
            // rather than re-appending the cached rows is deliberate - "refresh" should pick up music
            // added since, and it converges even if a cheap lossless merge would be enough.
            if (powerampLoaded) {
                val result = poweramp.readLibrary()
                powerampEntries = result.entries
                if (result.entries.isEmpty()) {
                    // An empty answer means the read failed, not that the library is empty; see
                    // loadPowerampLibrary. Leaving the flag set would hide the fold's retry path.
                    powerampLoaded = false
                }
            }

            collected += powerampEntries
            publish(collected)
        }
    }

    /**
     * Reads Poweramp's library into [powerampEntries], which is what [refresh] merges in.
     *
     * Deliberately does not `publish` on its own: both entry points into the data - the initial load
     * and a refresh - build one list and publish it once, so there is a single place where the three
     * sources are combined. Two publishers racing was a second way to lose the music.
     */
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
            if (result.entries.isEmpty()) showToast(result.status)
            Log.i(tag, "poweramp read: entries=${result.entries.size} status=${result.status}")
            powerampEntries = result.entries
            // `runCatching` inside the reader turns a provider failure into an empty list, so an empty
            // result is "could not read", not "no music". Latching `true` on it is what made the chip
            // refuse to try again - the fold opened onto a permanent empty state.
            powerampLoaded = result.entries.isNotEmpty()
            if (!powerampLoaded) Log.w(tag, "poweramp returned no rows; the fold will retry")

            val merged = ArrayList<FileEntry>(entries.filter { it.source != FileEntry.Source.POWERAMP })
            merged += powerampEntries
            publish(merged)
        }
    }

    /**
     * Wires the two folding segments of the action chip.
     *
     * Both open the same list, filtered to their own source: Poweramp shows the music, Library shows
     * everything else. Tapping the open one folds it away. The list starts folded so the first screen
     * is the status chips and the actions rather than a wall of filenames; the choice is remembered.
     */
    private fun setUpLibraryDisclosure() {
        binding.buttonLibrary.setOnClickListener {
            setFold(if (folded == Fold.LIBRARY) Fold.NONE else Fold.LIBRARY, animate = true)
            if (folded == Fold.LIBRARY) refresh()
        }

        binding.buttonPoweramp.setOnClickListener {
            // Poweramp is read lazily: its provider costs a query and the audio permission may not
            // have been granted yet, so the fold opens first and fills in when the read lands.
            if (folded == Fold.POWERAMP) {
                setFold(Fold.NONE, animate = true)
            } else {
                setFold(Fold.POWERAMP, animate = true)
                // Re-reads even when it is already loaded. It used to skip the read on the theory
                // that the rows were already in `entries`, which stopped being true the moment
                // anything called `refresh` - the chip then opened onto a permanently empty fold.
                // One provider query on each open is cheap next to a fold that cannot show its
                // contents.
                loadPowerampLibrary()
            }
        }

        val restored = prefs.getString(KEY_FOLDED, null)
        val initialFold = runCatching { Fold.valueOf(restored ?: "") }.getOrDefault(Fold.NONE)
        setFold(initialFold, animate = false)
        // A fold that was left open comes back open, so it also has to come back *filled*: the
        // restore used to call `setFold` alone, so a fresh process opened onto an empty Poweramp
        // fold that only a tap-away-and-back would populate.
        if (initialFold == Fold.POWERAMP) loadPowerampLibrary()


    }

    /**
     * Opens one library and folds the other away, animating both chevrons and the list together.
     *
     * The body's height comes from `layout_weight`, so expanding animates the weight and the list
     * grows into place instead of appearing at full size. Below a whole number the weighted child
     * gets no height at all, which is why the visibility is flipped once the weight is non-zero
     * rather than up front.
     */
    private fun setFold(target: Fold, animate: Boolean) {
        folded = target
        prefs.edit().putString(KEY_FOLDED, folded.name).apply()
        binding.buttonLibrary.tag = if (folded == Fold.LIBRARY) R.id.glass_role_button_active else R.id.glass_role_button
        binding.buttonPoweramp.tag = if (folded == Fold.POWERAMP) R.id.glass_role_button_active else R.id.glass_role_button
        GlassInstaller.apply(binding.actionBar)
        adapter.submit(visibleEntries())
        binding.libraryBody.apply {
            visibility = if (folded == Fold.NONE) View.GONE else View.VISIBLE
            alpha = 1f
            translationY = 0f
            requestLayout()
        }
        if (animate) {
            binding.fileList.animate().cancel()
            binding.fileList.alpha = 0.45f
            binding.fileList.animate().alpha(1f).setDuration(180L).start()
        }
        updateEmptyState()
        updateLibrarySummary()
    }

    private fun publish(list: List<FileEntry>) {
        entries.clear()
        // De-duplicate on URI so a folder grant does not double up MediaStore rows.
        val seen = HashSet<String>()
        for (entry in list) {
            if (seen.add(entry.uri.toString())) entries += entry
        }
        adapter.submit(visibleEntries())
        setScanning(false)
        updateEmptyState()
        updateCapabilityLine()
        updateLibrarySummary()
    }

    /** Count only the selected media source. */
    private fun updateLibrarySummary() {
        val count = visibleEntries().size
        binding.headerSubtitle.visibility = if (folded == Fold.NONE) View.GONE else View.VISIBLE
        binding.headerSubtitle.text = if (count == 0) getString(R.string.library_empty)
            else "$count ${if (folded == Fold.POWERAMP) "songs" else "videos"}"
    }

    /**
     * The entries the open library should show.
     *
     * One list serves both libraries. Poweramp owns the music and the media store owns everything
     * else, so "which library is open" is a filter rather than two adapters to keep in step.
     */
    private fun visibleEntries(): List<FileEntry> = when (folded) {
        Fold.NONE -> emptyList()
        Fold.LIBRARY -> entries.filter { it.source != FileEntry.Source.POWERAMP }
        Fold.POWERAMP -> entries.filter { it.source == FileEntry.Source.POWERAMP }
    }

    private fun setScanning(scanning: Boolean) {
        binding.scanProgress.visibility = if (scanning) View.VISIBLE else View.GONE
    }

    private fun updateEmptyState() {
        val empty = visibleEntries().isEmpty()
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
        binding.chipStrip.setChips(buildList {
            add(ChipStrip.Chip(hdr.supportedHdrTypes.joinToString(" · ").ifEmpty { "SDR display" }, ChipStrip.Tone.NEUTRAL))
            if (!settings.spatialEnabled) add(ChipStrip.Chip("Spatial off", ChipStrip.Tone.NEUTRAL))
            else if (spatial.headTrackerAvailable && spatial.enabled) add(ChipStrip.Chip("Head tracking", ChipStrip.Tone.ACTIVE))
            else if (spatial.active) add(ChipStrip.Chip("Spatial audio", ChipStrip.Tone.ACTIVE))
        })
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
                .putExtra(PlayerActivity.EXTRA_ARTIST, entry.artist)
                .putExtra(PlayerActivity.EXTRA_ALBUM, entry.album)
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

    // ------------------------------------------------------------------ debug library probe
    //
    // Hands `LibraryProbeReceiver` (debug variant only) the same controls a tap would press, so
    // `tools/verify-poweramp.sh` can exercise the *running* library rather than a fresh process.
    // The refresh bug it guards - `refresh()` clearing Poweramp's rows out of the merged list -
    // was invisible to every probe that read the provider for itself, because the provider read
    // was never the thing that was broken.

    /** Opens the Poweramp fold exactly as the chip does, load and all. */
    internal fun openPowerampFoldForTest() {
        if (folded != Fold.POWERAMP) setFold(Fold.POWERAMP, animate = false)
        loadPowerampLibrary()
    }

    /** Puts the fold back where the probe found it, so a diagnostic does not leave the screen moved. */
    internal fun setFoldForTest(fold: Fold) {
        setFold(fold, animate = false)
    }

    /** The fold as it is right now, for the probe to remember and restore. */
    internal val foldForTest: Fold get() = folded

    /** The fold's row count, as the list itself reports it - 0 while the load is still in flight. */
    internal val powerampRowCountForTest: Int
        get() = if (folded == Fold.POWERAMP) adapter.itemCount else -1

    internal val powerampLoadedForTest: Boolean get() = powerampLoaded

    internal val sourceForTest: String get() = folded.name
    internal fun restoreSourceForTest(value: String) {
        val source = runCatching { Fold.valueOf(value) }.getOrNull() ?: return
        setFold(source, animate = false)
        if (source == Fold.POWERAMP && !powerampLoaded) loadPowerampLibrary()
    }

    internal val scanningForTest: Boolean get() = binding.scanProgress.visibility == View.VISIBLE

    internal fun headerForTest(): CharSequence = binding.headerSubtitle.text

    internal fun refreshForTest() = refresh()

    /** The last, and only, instance: a broadcast can only ever reach the resumed one. */
    companion object {
        @JvmStatic
        internal var instance: MainActivity? = null
            private set

        const val REQUEST_NOTIFICATIONS = 1001

        const val PREF_FOLDER_URI = "scoped_folder_uri"

        const val KEY_FOLDED = "folded_library"

    }
}
