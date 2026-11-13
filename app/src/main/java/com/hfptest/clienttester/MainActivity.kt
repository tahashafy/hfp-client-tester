package com.hfptest.clienttester

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * BluetoothProfile.HEADSET_CLIENT (16) and BluetoothProfile.PBAP_CLIENT (17)
 * exist in the real Android framework but are annotated @SystemApi, so their
 * symbolic constants are stripped from the PUBLIC compileSdk stub jar and
 * will not resolve at compile time against the public SDK. The integer
 * values themselves are stable, documented AOSP constants (see
 * frameworks/base/core/java/android/bluetooth/BluetoothProfile.java), and
 * BluetoothAdapter.getProfileProxy() is a fully public API that accepts any
 * int profile id -- only the symbolic name is hidden, not the capability.
 * We reference them by literal value here, which is itself informative: the
 * fact that the constant is hidden from third-party compilation is part of
 * why this app has to fall back to getProfileProxy()'s runtime behavior
 * (Layer 2/3) rather than any compile-time guarantee (Layer 1).
 */
private const val PROFILE_HEADSET_CLIENT = 16
private const val PROFILE_PBAP_CLIENT = 17

/**
 * HFP Client Tester
 *
 * A read-only diagnostic tool that investigates whether this device exposes
 * the Bluetooth Hands-Free Profile CLIENT role (BluetoothProfile.HEADSET_CLIENT)
 * through public Android APIs.
 *
 * IMPORTANT: this app draws a hard line between four different things, and
 * never collapses one into another:
 *   1. The android.bluetooth.BluetoothHeadsetClient class existing in the
 *      framework jar.
 *   2. A system *service* for that profile actually being registered.
 *   3. That service being reachable by an ordinary third-party app via
 *      BluetoothAdapter.getProfileProxy().
 *   4. The phone's Bluetooth stack actually being able to operate as an
 *      HFP Client against a real headset/AG peer.
 *
 * Only (3) can be observed directly with public, non-root APIs. (1) is checked
 * with a read-only reflection probe purely for informational purposes and is
 * explicitly never used as evidence of (2), (3), or (4). (4) cannot be proven
 * by this app at all -- it would require an actual successful connection to a
 * peer device, which is out of scope for a permission-safe diagnostic tool.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var deviceInfoText: TextView
    private lateinit var hfpStatusText: TextView
    private lateinit var devicesText: TextView
    private lateinit var profilesText: TextView
    private lateinit var permissionsText: TextView

    private var lastReport: String = ""

    // Result of the HEADSET_CLIENT getProfileProxy probe. Null = not yet resolved.
    @Volatile
    private var headsetClientProxyConnected: Boolean? = null
    private var headsetClientProxy: BluetoothProfile? = null
    private var bluetoothAdapterRef: BluetoothAdapter? = null

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            // Whatever the outcome, re-run diagnostics so the report reflects
            // the actual granted/denied state rather than guessing.
            runDiagnostics()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        deviceInfoText = findViewById(R.id.deviceInfoText)
        hfpStatusText = findViewById(R.id.hfpStatusText)
        devicesText = findViewById(R.id.devicesText)
        profilesText = findViewById(R.id.profilesText)
        permissionsText = findViewById(R.id.permissionsText)

        deviceInfoText.text = buildStaticDeviceInfo()

        findViewById<android.widget.Button>(R.id.runDiagnosticsButton).setOnClickListener {
            ensurePermissionsThenRun()
        }
        findViewById<android.widget.Button>(R.id.copyReportButton).setOnClickListener {
            copyReportToClipboard()
        }

        hfpStatusText.text = "Not run yet. Tap \"Run Diagnostics\"."
        devicesText.text = "Not run yet."
        profilesText.text = "Not run yet."
        permissionsText.text = "Not run yet."
    }

    override fun onDestroy() {
        super.onDestroy()
        // Clean up the profile proxy if one was ever obtained.
        try {
            headsetClientProxy?.let { proxy ->
                bluetoothAdapterRef?.closeProfileProxy(PROFILE_HEADSET_CLIENT, proxy)
            }
        } catch (_: Exception) {
            // Best-effort cleanup only.
        }
    }

    // ---------------------------------------------------------------------
    // Permissions
    // ---------------------------------------------------------------------

    private fun requiredBluetoothPermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
        } else {
            // Pre-API 31: BLUETOOTH / BLUETOOTH_ADMIN are install-time (normal)
            // permissions, nothing to request at runtime.
            emptyArray()
        }
    }

    private fun missingPermissions(): List<String> =
        requiredBluetoothPermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

    private fun ensurePermissionsThenRun() {
        val missing = missingPermissions()
        if (missing.isEmpty()) {
            runDiagnostics()
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    // ---------------------------------------------------------------------
    // Diagnostics
    // ---------------------------------------------------------------------

    private fun buildStaticDeviceInfo(): String {
        return buildString {
            append("Manufacturer : ").append(Build.MANUFACTURER).append('\n')
            append("Model        : ").append(Build.MODEL).append('\n')
            append("Device       : ").append(Build.DEVICE).append('\n')
            append("Android      : ").append(Build.VERSION.RELEASE)
            append(" (API ").append(Build.VERSION.SDK_INT).append(')').append('\n')
            append("Build ID     : ").append(Build.DISPLAY)
        }
    }

    private fun runDiagnostics() {
        val sb = StringBuilder()
        val permLines = StringBuilder()
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())

        // --- Bluetooth adapter availability ---
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter: BluetoothAdapter? = bluetoothManager?.adapter
        bluetoothAdapterRef = adapter

        val btPresent = adapter != null
        val btEnabled = adapter?.isEnabled == true

        // --- Permission state ---
        val missing = missingPermissions()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permLines.append("BLUETOOTH_CONNECT granted : ")
                .append(ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED)
                .append('\n')
            permLines.append("BLUETOOTH_SCAN granted    : ")
                .append(ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED)
                .append('\n')
        } else {
            permLines.append("Running on API ").append(Build.VERSION.SDK_INT)
                .append(" -- BLUETOOTH/BLUETOOTH_ADMIN are install-time permissions, no runtime grant needed.\n")
        }
        if (missing.isNotEmpty()) {
            permLines.append("Missing runtime permissions: ").append(missing.joinToString()).append('\n')
        }

        val hasConnectPermission = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

        // --- Paired devices ---
        val devicesSb = StringBuilder()
        if (!btPresent) {
            devicesSb.append("Bluetooth adapter not present on this device.")
        } else if (!hasConnectPermission) {
            devicesSb.append("Cannot list paired devices: BLUETOOTH_CONNECT permission not granted.")
            permLines.append("Paired-device query failed: SecurityException avoided (permission not granted).\n")
        } else {
            try {
                val bonded: Set<BluetoothDevice> = adapter?.bondedDevices ?: emptySet()
                if (bonded.isEmpty()) {
                    devicesSb.append("No paired devices found.")
                } else {
                    bonded.forEachIndexed { index, device ->
                        if (index > 0) devicesSb.append('\n')
                        val name = try {
                            device.name ?: "(unnamed)"
                        } catch (e: SecurityException) {
                            "(name unavailable: ${e.javaClass.simpleName})"
                        }
                        devicesSb.append("- ").append(name)
                            .append("  [").append(device.address).append(']')
                            .append("  bondState=").append(bondStateToString(device.bondState))
                    }
                }
            } catch (e: SecurityException) {
                devicesSb.append("SecurityException reading bonded devices: ").append(e.message)
                permLines.append("Paired-device query threw SecurityException: ").append(e.message).append('\n')
            } catch (e: Exception) {
                devicesSb.append("Unexpected error reading bonded devices: ").append(e.message)
            }
        }
        devicesText.text = devicesSb.toString()

        // --- Profile support inspection via public BluetoothProfile constants ---
        val profilesSb = StringBuilder()
        profilesSb.append(describeKnownProfileConstant("HEADSET", BluetoothProfile.HEADSET))
        profilesSb.append('\n')
        profilesSb.append(describeKnownProfileConstant("A2DP", BluetoothProfile.A2DP))
        profilesSb.append('\n')
        profilesSb.append(describeKnownProfileConstant("HEADSET_CLIENT (HFP Client)", PROFILE_HEADSET_CLIENT))
        profilesSb.append('\n')
        profilesSb.append(describeKnownProfileConstant("PBAP_CLIENT", PROFILE_PBAP_CLIENT))
        profilesText.text = profilesSb.toString()

        // --- HFP Client specific investigation ---
        val hfpSb = StringBuilder()

        // Layer 1: is the class present in the framework at all? Read-only,
        // best-effort reflection. This NEVER counts as evidence the service
        // works -- it only tells us the class symbol exists.
        val classPresent = try {
            Class.forName("android.bluetooth.BluetoothHeadsetClient")
            true
        } catch (e: ClassNotFoundException) {
            false
        } catch (e: Throwable) {
            false
        }
        hfpSb.append("[Layer 1] android.bluetooth.BluetoothHeadsetClient class present: ")
            .append(classPresent)
            .append(" (this says NOTHING about whether the service is implemented, enabled, or reachable)\n\n")

        // Layer 2 & 3: attempt to obtain a profile proxy via the public API.
        // A successful onServiceConnected callback is the only real evidence
        // that (a) a system service for this profile exists AND (b) it is
        // reachable by a normal third-party app.
        if (!btPresent) {
            hfpSb.append("[Layer 2/3] Skipped: no Bluetooth adapter on this device.\n")
        } else if (!hasConnectPermission) {
            hfpSb.append("[Layer 2/3] Skipped: BLUETOOTH_CONNECT permission not granted. Grant it and re-run.\n")
        } else {
            headsetClientProxyConnected = null
            val latch = java.util.concurrent.CountDownLatch(1)
            var connectError: String? = null
            try {
                adapter?.getProfileProxy(
                    this,
                    object : BluetoothProfile.ServiceListener {
                        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                            headsetClientProxyConnected = true
                            headsetClientProxy = proxy
                            latch.countDown()
                        }

                        override fun onServiceDisconnected(profile: Int) {
                            headsetClientProxyConnected = false
                            latch.countDown()
                        }
                    },
                    PROFILE_HEADSET_CLIENT
                )
            } catch (e: SecurityException) {
                connectError = "SecurityException: ${e.message}"
            } catch (e: Exception) {
                connectError = "${e.javaClass.simpleName}: ${e.message}"
            }

            if (connectError != null) {
                hfpSb.append("[Layer 2/3] getProfileProxy(HEADSET_CLIENT) threw ").append(connectError).append('\n')
                permLines.append("getProfileProxy(HEADSET_CLIENT) error: ").append(connectError).append('\n')
            } else {
                // Wait briefly on a background-safe latch for the async callback.
                // This keeps the diagnostic deterministic without blocking the UI
                // thread for long; the callback itself still runs on the main
                // looper per the BluetoothAdapter contract.
                val connected = try {
                    latch.await(1500, java.util.concurrent.TimeUnit.MILLISECONDS)
                } catch (e: InterruptedException) {
                    false
                }
                when {
                    connected && headsetClientProxyConnected == true -> {
                        val devicesViaProxy = try {
                            headsetClientProxy?.connectedDevices?.size ?: 0
                        } catch (e: SecurityException) {
                            -1
                        }
                        hfpSb.append("[Layer 2/3] RESULT: HEADSET_CLIENT service proxy CONNECTED.\n")
                        hfpSb.append("  -> A system component implementing BluetoothProfile.HEADSET_CLIENT exists\n")
                        hfpSb.append("     and is reachable through the public getProfileProxy() API.\n")
                        if (devicesViaProxy >= 0) {
                            hfpSb.append("  -> Currently connected HFP-client-role devices: ").append(devicesViaProxy).append('\n')
                        }
                        hfpSb.append("  -> This does NOT yet prove a successful call can be routed end-to-end;\n")
                        hfpSb.append("     that would require pairing to a phone acting as Audio Gateway and\n")
                        hfpSb.append("     observing an actual call, which this diagnostic does not attempt.\n")
                    }
                    connected && headsetClientProxyConnected == false -> {
                        hfpSb.append("[Layer 2/3] RESULT: Service connected then immediately reported disconnected.\n")
                        hfpSb.append("  -> Inconclusive. The profile service may be present but unstable, or\n")
                        hfpSb.append("     torn down immediately after binding.\n")
                    }
                    else -> {
                        hfpSb.append("[Layer 2/3] RESULT: NOT AVAILABLE. getProfileProxy(HEADSET_CLIENT) never\n")
                        hfpSb.append("  called back onServiceConnected within the timeout.\n")
                        hfpSb.append("  -> Most likely interpretation: this device's Bluetooth stack does not\n")
                        hfpSb.append("     register a HEADSET_CLIENT (HFP Client) profile service, OR it exists\n")
                        hfpSb.append("     but is not exposed to third-party apps on this build.\n")
                        hfpSb.append("  -> This is NOT proof the class or service is entirely absent -- only that\n")
                        hfpSb.append("     this ordinary, non-root app cannot reach it via public API.\n")
                    }
                }
            }
        }
        hfpStatusText.text = hfpSb.toString()
        permissionsText.text = permLines.toString()

        // --- Assemble full shareable report ---
        lastReport = buildString {
            append("HFP Client Tester - Diagnostic Report\n")
            append("Generated: ").append(timestamp).append('\n')
            append("=".repeat(60)).append('\n')
            append(buildStaticDeviceInfo()).append('\n')
            append("Bluetooth adapter present : ").append(btPresent).append('\n')
            append("Bluetooth enabled         : ").append(btEnabled).append('\n')
            append('\n')
            append("--- Permissions and Errors ---\n")
            append(permLines)
            append('\n')
            append("--- Paired Bluetooth Devices ---\n")
            append(devicesSb).append('\n')
            append('\n')
            append("--- Bluetooth Profile Constant Checks ---\n")
            append(profilesSb).append('\n')
            append('\n')
            append("--- HFP Client (HEADSET_CLIENT) Investigation ---\n")
            append(hfpSb)
            append('\n')
            append("=".repeat(60)).append('\n')
            append("NOTE: This report distinguishes framework class presence (Layer 1) from\n")
            append("actual service availability and reachability (Layer 2/3). Only Layer 2/3\n")
            append("evidence should be treated as meaningful for determining whether this\n")
            append("device can function as a Bluetooth HFP Client.\n")
        }

        Toast.makeText(this, "Diagnostics complete", Toast.LENGTH_SHORT).show()
    }

    private fun describeKnownProfileConstant(label: String, profileId: Int): String {
        return "$label = constant value $profileId (public BluetoothProfile API)"
    }

    private fun bondStateToString(state: Int): String = when (state) {
        BluetoothDevice.BOND_BONDED -> "BONDED"
        BluetoothDevice.BOND_BONDING -> "BONDING"
        BluetoothDevice.BOND_NONE -> "NONE"
        else -> "UNKNOWN($state)"
    }

    private fun copyReportToClipboard() {
        if (lastReport.isBlank()) {
            Toast.makeText(this, getString(R.string.no_report_yet), Toast.LENGTH_SHORT).show()
            return
        }
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("HFP Diagnostic Report", lastReport))
        Toast.makeText(this, getString(R.string.report_copied), Toast.LENGTH_SHORT).show()
    }
}
