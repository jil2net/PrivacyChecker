package com.jil2.privacychecker

import android.Manifest

import android.accounts.Account
import android.accounts.AccountManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.Telephony
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator

class MainActivity : AppCompatActivity() {

    // Tab positions — single source of truth
    private object Tab {
        const val NETWORK   = 0
        const val WIFI      = 1
        const val BLUETOOTH = 2
        const val ACCOUNTS  = 3
        const val CONTACTS  = 4
        const val SMS       = 5
        const val CALLS     = 6
    }

    // Internal data string buffers to pass into fragments
    var sbNetwork:   String = "N/A"
    var sbWifi:      String = "N/A"
    var sbBluetooth: String = "N/A"
    var sbAccounts:  String = "N/A"
    var sbContacts:  String = "N/A"
    var sbMessages:  String = "N/A"
    var sbCalls:     String = "N/A"

    private lateinit var viewPager: ViewPager2
    private lateinit var tabAdapter: TabAdapter
    private lateinit var btnCheck: Button

    // Permissions
    private val requiredPermissions = arrayOf(
        Manifest.permission.READ_PHONE_STATE,
        Manifest.permission.READ_PHONE_NUMBERS,
        Manifest.permission.READ_CONTACTS,
        Manifest.permission.GET_ACCOUNTS,
        Manifest.permission.READ_SMS,
        Manifest.permission.READ_CALL_LOG,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.ACCESS_WIFI_STATE,
        Manifest.permission.NEARBY_WIFI_DEVICES,
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_CONNECT
    )

    // Permission Requester
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissionsMap ->
        val allGranted = permissionsMap.values.all { it }
        Toast.makeText(
            this,
            if (allGranted) "All permissions granted!" else "Some permissions were denied",
            Toast.LENGTH_SHORT
        ).show()
        refreshData()
    }

    private fun hasAllPermissions(): Boolean =
        requiredPermissions.all { perm ->
            ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED
        }

    fun openAppSettings() {
        try {
            startActivity(
                android.content.Intent(
                    android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS
                ).apply {
                    data = android.net.Uri.fromParts("package", packageName, null)
                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        } catch (e: Exception) {
            Toast.makeText(this, "Unable to open app settings", Toast.LENGTH_SHORT).show()
        }
    }

    // Account Picker Launcher
    private var manuallyPickedAccount: String? = null

    private val accountPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            val name = result.data?.getStringExtra(AccountManager.KEY_ACCOUNT_NAME)
            val type = result.data?.getStringExtra(AccountManager.KEY_ACCOUNT_TYPE)
            if (name != null && type != null) {
                manuallyPickedAccount = "$name ($type)"
                refreshData()
            }
        }
    }

    fun launchExplicitAccountPicker() {
        accountPickerLauncher.launch(
            AccountManager.newChooseAccountIntent(null, null, null, null, null, null, null)
        )
    }

    // Bluetooth discovery
    // Discovered devices accumulated during a single scan session.
    // Only cleared when a NEW scan is explicitly started via toggleBluetoothScan().
    private val discoveredDevices = mutableListOf<String>()
    var btScanningActive  = false

    private val bluetoothReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context, intent: android.content.Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val device: BluetoothDevice? =
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                        }
                    if (device != null) {
                        val hasConnect = ContextCompat.checkSelfPermission(
                            context, Manifest.permission.BLUETOOTH_CONNECT
                        ) == PackageManager.PERMISSION_GRANTED
                        val name    = if (hasConnect) device.name ?: "Unknown" else "Name hidden"
                        val address = device.address ?: "Hidden MAC"
                        val entry   = " • $name [$address]"
                        if (!discoveredDevices.contains(entry)) {
                            discoveredDevices.add(entry)
                            tabAdapter.updateTabData(Tab.BLUETOOTH, checkBluetoothString())
                        }
                    }
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    btScanningActive = false
                    tabAdapter.updateTabData(Tab.BLUETOOTH, checkBluetoothString())
                    tabAdapter.refreshBtButton(false)
                }
            }
        }
    }

    // Called by the Scan/Stop button in the Bluetooth tab.
    // First press: clears old results and starts discovery.
    // Second press (while scanning): cancels discovery immediately.
    fun toggleBluetoothScan() {
        if (ContextCompat.checkSelfPermission(
                this, Manifest.permission.BLUETOOTH_SCAN
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Toast.makeText(this, "BLUETOOTH_SCAN permission not granted", Toast.LENGTH_SHORT).show()
            return
        }
        val ba = (getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        if (ba == null || !ba.isEnabled) {
            Toast.makeText(this, "Bluetooth is not enabled", Toast.LENGTH_SHORT).show()
            return
        }
        if (btScanningActive || ba.isDiscovering) {
            ba.cancelDiscovery()
            btScanningActive = false
        } else {
            discoveredDevices.clear()
            btScanningActive = true
            ba.startDiscovery()
        }
        tabAdapter.updateTabData(Tab.BLUETOOTH, checkBluetoothString())
        tabAdapter.refreshBtButton(btScanningActive)
    }

    // System BT broadcasts (ACTION_FOUND, ACTION_DISCOVERY_FINISHED) come from the OS,
    // so the receiver must be EXPORTED. RECEIVER_NOT_EXPORTED would silently drop them.
    private fun registerRadioReceivers() {
        val filter = android.content.IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        // RECEIVER_EXPORTED is required here — ACTION_FOUND and ACTION_DISCOVERY_FINISHED
        // are system broadcasts sent by the OS (not by this app), so RECEIVER_NOT_EXPORTED
        // would silently drop every incoming device event on API 34+.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            registerReceiver(bluetoothReceiver, filter, RECEIVER_EXPORTED)
        } else {
            registerReceiver(bluetoothReceiver, filter)
        }
    }

    private fun unregisterRadioReceivers() {
        try {
            unregisterReceiver(bluetoothReceiver)
        } catch (e: Exception) {
            // Already unregistered — safe to ignore
        }
        // Cancel BT discovery only if we hold the required permission
        if (ContextCompat.checkSelfPermission(
                this, Manifest.permission.BLUETOOTH_SCAN
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            val bm = getSystemService(BLUETOOTH_SERVICE) as? BluetoothManager
            if (bm?.adapter?.isDiscovering == true) bm.adapter.cancelDiscovery()
        }
    }

    // Lifecycle
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Restore saved state
        manuallyPickedAccount = savedInstanceState?.getString(KEY_PICKED_ACCOUNT)

        val tabLayout = findViewById<TabLayout>(R.id.tabLayout)
        viewPager = findViewById(R.id.viewPager)
        btnCheck = findViewById(R.id.btnCheck)

        // Properly assign directly to class instance
        tabAdapter = TabAdapter(this)
        viewPager.adapter = tabAdapter

        TabLayoutMediator(tabLayout, viewPager) { tab, position ->
            tab.text = when (position) {
                Tab.NETWORK   -> "NW & SIM"
                Tab.WIFI      -> "WiFi"
                Tab.BLUETOOTH -> "Bluetooth"
                Tab.ACCOUNTS  -> "Accounts"
                Tab.CONTACTS  -> "Contacts"
                Tab.SMS       -> "SMS/MMS"
                else          -> "Call Log"
            }
        }.attach()

        tabLayout.setTabTextColors(AppColors.TAB_UNSELECTED, AppColors.TAB_SELECTED)

        refreshData()

        // Button Logic
        btnCheck.setOnClickListener {
            // Check if we need to request permissions
            if (hasAllPermissions()) refreshData()
            // Trigger runtime OS permission prompt
            else permissionLauncher.launch(requiredPermissions)
        }
    }

    override fun onStart() {
        super.onStart()
        registerRadioReceivers()
    }

    override fun onStop() {
        super.onStop()
        unregisterRadioReceivers()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_PICKED_ACCOUNT, manuallyPickedAccount)
    }


    // Refresh
    fun refreshData() {
        btnCheck.isEnabled = false
        btnCheck.text = "Refreshing..."
        btnCheck.setTextColor(AppColors.BTN_TEXT_BUSY)
        btnCheck.setBackgroundColor(AppColors.BTN_BG_BUSY)

        // Fetch data
        sbNetwork   = checkNetworkSimString()
        sbWifi      = checkWifiString()
        sbBluetooth = checkBluetoothString()
        sbAccounts  = checkAccountsString()
        sbContacts  = checkContactsString()
        sbMessages  = checkSmsMmsString()
        sbCalls     = checkCallLogString()

        tabAdapter.updateTabData(Tab.NETWORK,   sbNetwork)
        tabAdapter.updateTabData(Tab.WIFI,      sbWifi)
        tabAdapter.updateTabData(Tab.BLUETOOTH, sbBluetooth)
        tabAdapter.updateTabData(Tab.ACCOUNTS,  sbAccounts)
        tabAdapter.updateTabData(Tab.CONTACTS,  sbContacts)
        tabAdapter.updateTabData(Tab.SMS,       sbMessages)
        tabAdapter.updateTabData(Tab.CALLS,     sbCalls)

        btnCheck.isEnabled = true
        btnCheck.text = "Grant/Refresh"
        btnCheck.setTextColor(AppColors.BTN_TEXT_IDLE)
        btnCheck.setBackgroundColor(AppColors.BTN_BG_IDLE)

        updateStatusLine()
    }

    private fun updateStatusLine() {
        val txt = findViewById<TextView>(R.id.txtQuickStatus) ?: return
        val grantedCount = requiredPermissions.count { perm ->
            ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED
        }
        when {
            grantedCount == requiredPermissions.size -> {
                txt.text = "ALL PERMISSIONS GRANTED"
                txt.setTextColor(AppColors.STATUS_ALL)
            }
            grantedCount > 0 -> {
                txt.text = "SOME PERMISSIONS GRANTED ($grantedCount/${requiredPermissions.size})"
                txt.setTextColor(AppColors.STATUS_SOME)
            }
            else -> {
                txt.text = "NO PERMISSIONS GRANTED"
                txt.setTextColor(AppColors.STATUS_NONE)
            }
        }
    }

    // Mobile Network & SIM Card information
    private fun checkNetworkSimString(): String {
        val sb = StringBuilder()
        sb.append("🛜 MOBILE NETWORK DISCOVERY\n")
        sb.append("━━━━━━━━━━━━━━━━━━━━━\n")
        try {
            val tm = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager

            sb.append("Operator Name: ").append(tm.networkOperatorName).append("\n")
            sb.append("Network Operator ID: ").append(tm.networkOperator).append("\n")
            sb.append("Country ISO: ").append(tm.networkCountryIso).append("\n")
            sb.append("Phone Type: ").append(
                when (tm.phoneType) {
                    TelephonyManager.PHONE_TYPE_GSM -> "GSM"
                    TelephonyManager.PHONE_TYPE_CDMA -> "CDMA"
                    TelephonyManager.PHONE_TYPE_SIP -> "SIP"
                    else -> "NONE"
                }
            ).append("\n")

            // Parse Mobile Country Code (MCC) & Mobile Network Code (MNC)
            val operator = tm.networkOperator
            if (!operator.isNullOrEmpty() && operator.length >= 5) {
                sb.append("MCC: ").append(operator.substring(0, 3)).append("\n")
                sb.append("MNC: ").append(operator.substring(3)).append("\n")
            }

            // Network type (technology generation)
            val networkType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                tm.dataNetworkType
            } else {
                @Suppress("DEPRECATION") tm.networkType
            }
            sb.append("Network Type: ").append(
                when (networkType) {
                    TelephonyManager.NETWORK_TYPE_NR -> "5G NR"
                    TelephonyManager.NETWORK_TYPE_LTE -> "4G LTE"
                    TelephonyManager.NETWORK_TYPE_HSPAP -> "3G HSPA+"
                    TelephonyManager.NETWORK_TYPE_HSPA -> "3G HSPA"
                    TelephonyManager.NETWORK_TYPE_HSDPA -> "3G HSDPA"
                    TelephonyManager.NETWORK_TYPE_UMTS -> "3G UMTS"
                    TelephonyManager.NETWORK_TYPE_EDGE -> "2G EDGE"
                    TelephonyManager.NETWORK_TYPE_GPRS -> "2G GPRS"
                    else -> "Unknown ($networkType)"
                }
            ).append("\n")

            // CHECKING PRIVILEGED PHONE PERMISSION STATE
            if (ContextCompat.checkSelfPermission(
                    this, Manifest.permission.READ_PHONE_STATE
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                // Data state
                sb.append("Data State: ").append(
                    when (tm.dataState) {
                        TelephonyManager.DATA_DISCONNECTED -> "DISCONNECTED"
                        TelephonyManager.DATA_CONNECTING -> "CONNECTING"
                        TelephonyManager.DATA_CONNECTED -> "CONNECTED"
                        TelephonyManager.DATA_SUSPENDED -> "SUSPENDED"
                        TelephonyManager.DATA_DISCONNECTING -> "DISCONNECTING"
                        else -> "UNKNOWN (${tm.dataState})"
                    }
                ).append("\n")

                // Data activity
                sb.append("Data Activity: ").append(
                    when (tm.dataActivity) {
                        TelephonyManager.DATA_ACTIVITY_IN -> "RX (receiving)"
                        TelephonyManager.DATA_ACTIVITY_OUT -> "TX (sending)"
                        TelephonyManager.DATA_ACTIVITY_INOUT -> "TX+RX (both)"
                        TelephonyManager.DATA_ACTIVITY_DORMANT -> "Dormant"
                        TelephonyManager.DATA_ACTIVITY_NONE -> "None"
                        else -> "Unknown"
                    }
                ).append("\n")

                // Roaming
                sb.append("Roaming: ").append(if (tm.isNetworkRoaming) "YES" else "No").append("\n")

                // Signal strength (API 28+)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    val ss = tm.signalStrength
                    sb.append("Signal Level: ").append(ss?.level ?: "N/A").append("/4\n")
                }

                // IMEI
                try {
                    val imei = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        tm.imei
                    } else {
                        @Suppress("DEPRECATION") tm.deviceId
                    }
                    sb.append("IMEI: ").append(imei ?: "⚠️ Restricted (non-system app)").append("\n")
                } catch (e: SecurityException) {
                    sb.append("IMEI: ⚠️ Restricted\n")
                }

                // Voice mail number
                try {
                    sb.append("Voice Mail #: ").append(tm.voiceMailNumber ?: "Not set / Hidden").append("\n")
                } catch (e: SecurityException) {
                    sb.append("⚠️ Voice Mail #: Blocked\n")
                }

                // SIM state
                sb.append("SIM State: ").append(
                    when (tm.simState) {
                        TelephonyManager.SIM_STATE_ABSENT -> "ABSENT"
                        TelephonyManager.SIM_STATE_PIN_REQUIRED -> "PIN REQUIRED"
                        TelephonyManager.SIM_STATE_PUK_REQUIRED -> "PUK REQUIRED"
                        TelephonyManager.SIM_STATE_NETWORK_LOCKED -> "NETWORK LOCKED"
                        TelephonyManager.SIM_STATE_READY -> "READY"
                        TelephonyManager.SIM_STATE_NOT_READY -> "NOT READY"
                        TelephonyManager.SIM_STATE_PERM_DISABLED -> "PERMANENTLY DISABLED"
                        TelephonyManager.SIM_STATE_CARD_IO_ERROR -> "CARD IO ERROR"
                        TelephonyManager.SIM_STATE_CARD_RESTRICTED -> "CARD RESTRICTED"
                        else -> "UNKNOWN (${tm.simState})"
                    }
                ).append("\n")

                // Active subscriptions
                val sm = getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager
                val activeList = sm.activeSubscriptionInfoList
                if (!activeList.isNullOrEmpty()) {
                    sb.append("\n🪪 SIM CONFIGURATION (${activeList.size} active SIM(s))\n")
                    sb.append("━━━━━━━━━━━━━━━━━━━━━\n")
                    for (info in activeList) {
                        // Extracting every available raw field on SubscriptionInfo
                        sb.append("Subscription ID: ").append(info.subscriptionId).append("\n")
                        sb.append(" • Carrier Name: ").append(info.carrierName).append("\n")
                        sb.append(" • Display Name: ").append(info.displayName).append("\n")
                        sb.append(" • Slot Index: ").append(info.simSlotIndex).append("\n")
                        sb.append(" • Data Roaming: ").append(info.dataRoaming).append("\n")
                        sb.append(" • MNC: ").append(info.mncString ?: info.mnc).append("\n")
                        sb.append(" • MCC: ").append(info.mccString ?: info.mcc).append("\n")
                        sb.append(" • ICCID: ").append(info.iccId?.ifEmpty { "⚠️ Restricted" } ?: "⚠️ Restricted").append("\n")
                        sb.append(" • Card ID: ").append(info.cardId).append("\n")

                        // Forbidden PLMN list (API 33+)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            try {
                                val forbidden = info.javaClass
                                    .getMethod("getForbiddenPlmns")
                                    .invoke(info) as? List<*>
                                sb.append(" • Forbidden PLMNs: ")
                                    .append(if (!forbidden.isNullOrEmpty()) forbidden.joinToString() else "None")
                                    .append("\n")
                            } catch (e: Exception) {
                                sb.append(" • Forbidden PLMNs: Unavailable\n")
                            }
                        }

                        // MSISDN (phone number)
                        if (ContextCompat.checkSelfPermission(
                                this, Manifest.permission.READ_PHONE_NUMBERS
                            ) == PackageManager.PERMISSION_GRANTED
                        ) {
                            val phoneNumber = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                sm.getPhoneNumber(info.subscriptionId)
                            } else {
                                @Suppress("DEPRECATION") info.number ?: "Hidden/Null"
                            }
                            sb.append(" • MSISDN: ").append(phoneNumber).append("\n")
                        } else {
                            sb.append(" • MSISDN: 🚫 Blocked (Needs READ_PHONE_NUMBERS)\n")
                        }
                    }
                } else {
                    sb.append("\nNo Active SIM card profiles retrieved.\n")
                }

                // CELLULAR HARDWARE RADIOS & NEIGHBORING CELLS
                // Accessing cell towers requires ACCESS_FINE_LOCATION in addition to telephony
                if (ContextCompat.checkSelfPermission(
                        this, Manifest.permission.ACCESS_FINE_LOCATION
                    ) == PackageManager.PERMISSION_GRANTED
                ) {
                    val cellInfoList = tm.allCellInfo
                    if (!cellInfoList.isNullOrEmpty()) {
                        sb.append("\n📡 RADIO CELLS DISCOVERED: ${cellInfoList.size}\n")

                        // Phase 1: Human-readable visual breakdown
                        for ((index, cellInfo) in cellInfoList.withIndex()) {
                            sb.append("━━━━━━━━━━━━━━━━━━━━━\n")
                            sb.append("[Cell #$index] ${if (cellInfo.isRegistered) "SERVING" else "NEIGHBORING"}\n")
                            when (cellInfo) {
                                is android.telephony.CellInfoLte -> {
                                    val identity = cellInfo.cellIdentity
                                    val signal = cellInfo.cellSignalStrength
                                    sb.append(" • Technology: LTE (4G)\n")
                                    sb.append(" • Cell ID (CI): ${if (identity.ci == Int.MAX_VALUE) "Unknown" else identity.ci}\n")
                                    sb.append(" • Tracking Area (TAC): ${if (identity.tac == Int.MAX_VALUE) "Unknown" else identity.tac}\n")
                                    sb.append(" • Physical ID (PCI): ${if (identity.pci == Int.MAX_VALUE) "Unknown" else identity.pci}\n")
                                    sb.append(" • Signal Strength: ${signal.dbm} dBm (${signal.level}/4)\n")
                                }

                                is android.telephony.CellInfoNr -> {
                                    val identity = cellInfo.cellIdentity as android.telephony.CellIdentityNr
                                    val signal   = cellInfo.cellSignalStrength as android.telephony.CellSignalStrengthNr
                                    sb.append(" • Technology: NR (5G)\n")
                                    sb.append(" • New Radio CI: ${if (identity.nci == Long.MAX_VALUE) "Unknown" else identity.nci}\n")
                                    sb.append(" • Tracking Area (TAC): ${if (identity.tac == Int.MAX_VALUE) "Unknown" else identity.tac}\n")
                                    sb.append(" • Signal Level: ${signal.level}/4\n")
                                }

                                is android.telephony.CellInfoWcdma -> {
                                    val identity = cellInfo.cellIdentity
                                    val signal = cellInfo.cellSignalStrength
                                    sb.append(" • Technology: WCDMA (3G)\n")
                                    sb.append(" • Cell ID (CID): ${if (identity.cid == Int.MAX_VALUE) "Unknown" else identity.cid}\n")
                                    sb.append(" • LAC: ${if (identity.lac == Int.MAX_VALUE) "Unknown" else identity.lac}\n")
                                    sb.append(" • Signal Strength: ${signal.dbm} dBm\n")
                                }

                                is android.telephony.CellInfoGsm -> {
                                    val identity = cellInfo.cellIdentity
                                    val signal = cellInfo.cellSignalStrength
                                    sb.append(" • Technology: GSM (2G)\n")
                                    sb.append(" • Cell ID (CID): ${if (identity.cid == Int.MAX_VALUE) "Unknown" else identity.cid}\n")
                                    sb.append(" • LAC: ${if (identity.lac == Int.MAX_VALUE) "Unknown" else identity.lac}\n")
                                    sb.append(" • Signal Strength: ${signal.dbm} dBm\n")
                                }

                                else -> {
                                    sb.append(" • Unknown cell type: $cellInfo\n")
                                }
                            }
                        }

                        // Phase 2: Unfiltered raw device log
                        sb.append("\n📋 RAW UNFILTERED CELL LOG:\n")
                        sb.append("━━━━━━━━━━━━━━━━━━━━━\n")
                        for ((index, cellInfo) in cellInfoList.withIndex()) {
                            sb.append("[Raw Cell #$index] $cellInfo\n\n")
                        }
                    } else {
                        sb.append("\n⚠️ Ensure Location is enabled.")
                        sb.append("\n • No radio cell info returned\n")
                    }
                } else {
                    sb.append("\n🚫 Blocked: ACCESS_FINE_LOCATION permission not granted.")
                    sb.append("\n • Cell tower profiles are hidden.\n")
                }

            } else {
                sb.append("\n🚫 Blocked: READ_PHONE_STATE permission not granted.\n")
            }

            // APN / SMS Center / MMS Center
            // Both APIs are restricted to carrier/system apps on modern Android:
            //
            // SMSC  — getSmscAddress() is @hide, requires READ_PRIVILEGED_PHONE_STATE
            //         (signature permission). Regular apps always get SecurityException.
            //
            // APN / MMSC — Telephony.Carriers.CONTENT_URI returned data freely up to
            //         Android 10. From API 30+ it requires WRITE_APN_SETTINGS or carrier
            //         privilege — regular apps receive a silent empty cursor (0 rows, no error).
            //
            // We attempt both anyway and report exactly what the OS returns.
            sb.append("\n📶 APN & MESSAGING CENTERS\n")
            sb.append("━━━━━━━━━━━━━━━━━━━━━\n")
            sb.append("⚠️ Both APIs are restricted to carrier/system apps on API 30+\n\n")

            // SMSC via reflection — try multiple method names since Samsung and other
            // OEMs rename or move the @hide API compared to AOSP getSmscAddress.
            try {
                val methodCandidates = listOf(
                    "getSmscAddress",           // AOSP / stock Android
                    "getSmsc",                  // Some Samsung OneUI builds
                    "getSmscAddressFromIcc",    // Samsung Exynos variants
                    "getSmsCenterAddress"       // Occasional MTK-based ROMs
                )
                var smscResult: String? = null
                var lastError  = ""
                for (methodName in methodCandidates) {
                    try {
                        smscResult = TelephonyManager::class.java
                            .getMethod(methodName).invoke(tm) as? String
                        break // found a working method
                    } catch (e: NoSuchMethodException) {
                        lastError = "method not found"
                    } catch (e: SecurityException) {
                        lastError = "blocked (carrier privilege required)"
                        break // no point trying other names — it's a permission issue
                    } catch (e: Exception) {
                        lastError = e.localizedMessage ?: "unknown error"
                    }
                }
                sb.append("SMSC: ${smscResult ?: "Not accessible ($lastError)"}\n")
            } catch (e: Exception) {
                sb.append("SMSC: ⚠️ Error — ${e.localizedMessage}\n")
            }

            // APN + MMSC from Telephony.Carriers content provider
            if (ContextCompat.checkSelfPermission(
                    this, Manifest.permission.READ_PHONE_STATE
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                val apnProjection = arrayOf(
                    Telephony.Carriers.NAME,
                    Telephony.Carriers.APN,
                    Telephony.Carriers.TYPE,
                    Telephony.Carriers.CURRENT,
                    Telephony.Carriers.MMSC,
                    Telephony.Carriers.MMSPROXY,
                    Telephony.Carriers.MMSPORT,
                    Telephony.Carriers.PROXY,
                    Telephony.Carriers.PORT,
                    Telephony.Carriers.PROTOCOL
                )
                try {
                    contentResolver.query(
                        Telephony.Carriers.CONTENT_URI, apnProjection, null, null, null
                    )?.use { cursor ->
                        if (cursor.count == 0) {
                            // Silent empty result is the normal outcome on API 30+ for
                            // non-carrier apps — make this explicit rather than misleading
                            sb.append("APN / MMSC: ⚠️ Blocked by OS — Telephony.Carriers ")
                            sb.append("requires carrier privilege on API 30+\n")
                        } else {
                            sb.append("APN Records Found: ${cursor.count}\n")
                            val nameIdx     = cursor.getColumnIndex(Telephony.Carriers.NAME)
                            val apnIdx      = cursor.getColumnIndex(Telephony.Carriers.APN)
                            val typeIdx     = cursor.getColumnIndex(Telephony.Carriers.TYPE)
                            val currentIdx  = cursor.getColumnIndex(Telephony.Carriers.CURRENT)
                            val mmscIdx     = cursor.getColumnIndex(Telephony.Carriers.MMSC)
                            val mmsProxyIdx = cursor.getColumnIndex(Telephony.Carriers.MMSPROXY)
                            val mmsPortIdx  = cursor.getColumnIndex(Telephony.Carriers.MMSPORT)
                            val proxyIdx    = cursor.getColumnIndex(Telephony.Carriers.PROXY)
                            val portIdx     = cursor.getColumnIndex(Telephony.Carriers.PORT)
                            val protoIdx    = cursor.getColumnIndex(Telephony.Carriers.PROTOCOL)
                            var activeFound = false
                            while (cursor.moveToNext()) {
                                val isCurrent = currentIdx >= 0 && !cursor.isNull(currentIdx)
                                // Show active APN in full; list others briefly
                                if (isCurrent) {
                                    activeFound = true
                                    sb.append("\n🟢 ACTIVE APN\n")
                                    sb.append("━━━━━━━━━━━━━━━━━━━━━\n")
                                    if (nameIdx  >= 0) sb.append(" • Name:     ${cursor.getString(nameIdx)  ?: "—"}\n")
                                    if (apnIdx   >= 0) sb.append(" • APN:      ${cursor.getString(apnIdx)   ?: "—"}\n")
                                    if (typeIdx  >= 0) sb.append(" • Type:     ${cursor.getString(typeIdx)  ?: "—"}\n")
                                    if (proxyIdx >= 0) sb.append(" • Proxy:    ${cursor.getString(proxyIdx)?.ifEmpty { "None" } ?: "None"}\n")
                                    if (portIdx  >= 0) sb.append(" • Port:     ${cursor.getString(portIdx)?.ifEmpty  { "None" } ?: "None"}\n")
                                    if (protoIdx >= 0) sb.append(" • Protocol: ${cursor.getString(protoIdx) ?: "—"}\n")

                                    // MMS center
                                    val mmsc     = if (mmscIdx >= 0)     cursor.getString(mmscIdx)     else null
                                    val mmsProxy = if (mmsProxyIdx >= 0) cursor.getString(mmsProxyIdx) else null
                                    val mmsPort  = if (mmsPortIdx >= 0)  cursor.getString(mmsPortIdx)  else null
                                    sb.append(" • MMSC:      ${mmsc?.ifEmpty     { "Not set" } ?: "Not set"}\n")
                                    sb.append(" • MMS Proxy: ${mmsProxy?.ifEmpty { " None"   } ?: " None"}\n")
                                    sb.append(" • MMS Port:  ${mmsPort?.ifEmpty  { "None"    } ?: "None"}\n")
                                }
                            }
                            if (!activeFound) sb.append("(No active APN row found)\n")
                        }
                    } ?: sb.append("APN provider returned no data.\n")
                } catch (e: SecurityException) {
                    sb.append("APN/MMSC: ⚠️ Restricted\n")
                } catch (e: Exception) {
                    sb.append("APN/MMSC: ⚠️ Error — ${e.localizedMessage}\n")
                }
            } else {
                sb.append("🚫 APN data: Blocked (needs READ_PHONE_STATE)\n")
            }

        } catch (e: Exception) {
            sb.append("\n🚫 Blocked: READ_PHONE_STATE permission not granted.")
            sb.append("\n⚠️ Error reading network data: ${e.localizedMessage}\n")
        }
        return sb.toString()
    }

    // Wi-Fi Discovery
    private fun checkWifiString(): String {
        val sb = StringBuilder()
        sb.append("📶 PRIVACY SCAN: WI-FI\n")
        sb.append("━━━━━━━━━━━━━━━━━━━━━\n")
        try {
            if (ContextCompat.checkSelfPermission(
                    this, Manifest.permission.ACCESS_WIFI_STATE
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                sb.append("\n🚫 Blocked: ACCESS_WIFI_STATE permission not granted.\n")
                return sb.toString()
            }

            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

            // Adapter state
            sb.append("Wi-Fi Enabled: ").append(if (wm.isWifiEnabled) "Yes" else "No").append("\n")
            sb.append("Wi-Fi State: ").append(
                when (wm.wifiState) {
                    WifiManager.WIFI_STATE_ENABLED    -> "Enabled"
                    WifiManager.WIFI_STATE_ENABLING   -> "Enabling…"
                    WifiManager.WIFI_STATE_DISABLED   -> "Disabled"
                    WifiManager.WIFI_STATE_DISABLING  -> "Disabling…"
                    else -> "Unknown (${wm.wifiState})"
                }
            ).append("\n")

            // Connection info
            @Suppress("DEPRECATION")
            val info = wm.connectionInfo
            if (info != null) {
                sb.append("\n📡 CURRENT CONNECTION\n")
                sb.append("━━━━━━━━━━━━━━━━━━━━━\n")

                // On API 29+ SSID requires ACCESS_FINE_LOCATION; show it if available
                val hasFineLocation = ContextCompat.checkSelfPermission(
                    this, Manifest.permission.ACCESS_FINE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
                sb.append("SSID: ").append(
                    if (hasFineLocation) info.ssid?.removeSurrounding("\"") ?: "Unknown"
                    else "🚫 Blocked: ACCESS_FINE_LOCATION permission not granted."
                ).append("\n")
                sb.append("BSSID (AP MAC): ").append(info.bssid ?: "N/A").append("\n")

                // IP address (convert from int)
                val ipInt = info.ipAddress
                val ipStr = if (ipInt != 0) {
                    "%d.%d.%d.%d".format(
                        ipInt and 0xFF,
                        (ipInt shr 8) and 0xFF,
                        (ipInt shr 16) and 0xFF,
                        (ipInt shr 24) and 0xFF
                    )
                } else "Not assigned"
                sb.append("IP Address: ").append(ipStr).append("\n")

                sb.append("Link Speed: ").append(info.linkSpeed).append(" Mbps\n")
                sb.append("Frequency: ").append(info.frequency).append(" MHz (")
                    .append(if (info.frequency in 2400..2500) "2.4 GHz" else "5 GHz").append(")\n")
                sb.append("RSSI: ").append(info.rssi).append(" dBm\n")
                sb.append("Signal Level: ").append(WifiManager.calculateSignalLevel(info.rssi, 5)).append("/4\n")

                // MAC address — on API 23+ the real MAC is hidden; show what the OS exposes
                @Suppress("DEPRECATION")
                sb.append("Device MAC: ").append(info.macAddress ?: "Randomised/Hidden")
                    .append("\n  (Note: randomised per-network on API 29+)\n")

                // Network ID
                sb.append("Network ID: ").append(info.networkId).append("\n")
            } else {
                sb.append("Not connected to any Wi-Fi network.\n")
            }

            // DHCP info
            @Suppress("DEPRECATION")
            val dhcp = wm.dhcpInfo
            if (dhcp != null) {
                fun intToIp(i: Int) = "%d.%d.%d.%d".format(
                    i and 0xFF, (i shr 8) and 0xFF, (i shr 16) and 0xFF, (i shr 24) and 0xFF
                )
                sb.append("\n🌐 DHCP INFO\n")
                sb.append("━━━━━━━━━━━━━━━━━━━━━\n")
                sb.append("Gateway: ").append(intToIp(dhcp.gateway)).append("\n")
                sb.append("Netmask: ").append(intToIp(dhcp.netmask)).append("\n")
                sb.append("DNS 1: ").append(intToIp(dhcp.dns1)).append("\n")
                sb.append("DNS 2: ").append(intToIp(dhcp.dns2)).append("\n")
                sb.append("Server IP: ").append(intToIp(dhcp.serverAddress)).append("\n")
                sb.append("Lease Duration: ").append(dhcp.leaseDuration).append(" s\n")
            }

            // Saved networks count (accessible pre-API 29 only)
            sb.append("\n💾 SAVED NETWORKS\n")
            sb.append("━━━━━━━━━━━━━━━━━━━━━\n")
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                @Suppress("DEPRECATION")
                val saved = wm.configuredNetworks
                if (!saved.isNullOrEmpty()) {
                    sb.append("Count: ").append(saved.size).append("\n")
                    for (n in saved) sb.append("• ").append(n.SSID?.removeSurrounding("\"") ?: "Unknown").append("\n")
                } else {
                    sb.append("(No saved networks returned)\n")
                }
            } else {
                sb.append("⚠️ Restricted on Android 10+ (API 30+)\n")
            }


            // Active Nearby Access Points Discovery (Android 13+)
            val hasFineLocation = ContextCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
            val hasNearby = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                ContextCompat.checkSelfPermission(
                    this, Manifest.permission.NEARBY_WIFI_DEVICES
                ) == PackageManager.PERMISSION_GRANTED
            else true

            sb.append("\n📡 NEARBY ACCESS POINTS\n")
            sb.append("━━━━━━━━━━━━━━━━━━━━━\n")
            if (hasFineLocation && hasNearby) {
                val scanResults = wm.scanResults
                if (!scanResults.isNullOrEmpty()) {
                    sb.append("Found: ${scanResults.size}\n\n")
                    for ((index, scan) in scanResults.withIndex()) {
                        sb.append(" • [#$index] ").append(scan.SSID.ifEmpty { "(Hidden)" }).append(",")
                            .append(("\n").padEnd(10)).append(" [${scan.BSSID}] ${scan.level} dBm\n")
                    }
                } else {
                    sb.append("⚠️ Ensure Location is enabled.\n")
                    sb.append(" • No nearby APs found.\n")
                }
            } else {
                sb.append("🚫 Blocked: ACCESS_FINE_LOCATION or NEARBY DEVICES (NEARBY_WIFI_DEVICES) permissions not granted.\n")
            }

        } catch (e: Exception) {
            sb.append("⚠️ Error reading Wi-Fi state: ").append(e.localizedMessage).append("\n")
        }
        return sb.toString()
    }

    // Bluetooth Discovery
    private fun checkBluetoothString(): String {
        val sb = StringBuilder()
        sb.append("🔵 PRIVACY SCAN: BLUETOOTH\n")
        sb.append("━━━━━━━━━━━━━━━━━━━━━\n")
        try {
            val bm = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            val ba = bm?.adapter
            if (ba == null) {
                sb.append("Bluetooth not supported on this device.\n")
                return sb.toString()
            }

            // Adapter info
            sb.append("Bluetooth Enabled: ").append(if (ba.isEnabled) "Yes" else "No").append("\n")
            sb.append("Adapter State: ").append(
                when (ba.state) {
                    BluetoothAdapter.STATE_ON -> "On"
                    BluetoothAdapter.STATE_OFF -> "Off"
                    BluetoothAdapter.STATE_TURNING_ON  -> "Turning On"
                    BluetoothAdapter.STATE_TURNING_OFF -> "Turning Off"
                    else -> "Unknown (${ba.state})"
                }
            ).append("\n")

            // Device name & MAC — require BLUETOOTH_CONNECT on API 31+
            val hasBtConnect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
            } else {
                // Pre-API 31: legacy BLUETOOTH permission (normal, auto-granted)
                ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH) == PackageManager.PERMISSION_GRANTED
            }

            if (hasBtConnect) {
                sb.append("Device Name: ").append(ba.name ?: "Unknown").append("\n")
                sb.append("Device MAC: ").append(ba.address ?: "Randomised/Hidden")
                    .append("\n  (Note: randomised on most API 26+ devices)\n")

                // Scan mode
                sb.append("Scan Mode: ").append(
                    when (ba.scanMode) {
                        BluetoothAdapter.SCAN_MODE_NONE -> "Not discoverable, not connectable"
                        BluetoothAdapter.SCAN_MODE_CONNECTABLE -> "Connectable, not discoverable"
                        BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE -> "Connectable + Discoverable"
                        else -> "Unknown"
                    }
                ).append("\n")

                // BLE support
                sb.append("\nBLE Supported: ")
                    .append(if (packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) "Yes" else "No")
                    .append("\n")

                // Paired devices
                sb.append("\n📱 PAIRED (BONDED) DEVICES\n")
                sb.append("━━━━━━━━━━━━━━━━━━━━━\n")
                val paired = ba.bondedDevices
                if (paired.isNullOrEmpty()) {
                    sb.append("(No paired devices)\n")
                } else {
                    sb.append("Count: ").append(paired.size).append("\n\n")
                    for (device in paired) {
                        sb.append("• Name: ").append(device.name ?: "Unknown").append("\n")
                        sb.append("   MAC: ").append(device.address).append("\n")
                        sb.append("   Type: ").append(
                            when (device.type) {
                                android.bluetooth.BluetoothDevice.DEVICE_TYPE_CLASSIC -> "Classic"
                                android.bluetooth.BluetoothDevice.DEVICE_TYPE_LE -> "BLE"
                                android.bluetooth.BluetoothDevice.DEVICE_TYPE_DUAL -> "Dual (Classic + BLE)"
                                else -> "Unknown"
                            }
                        ).append("\n")
                        sb.append("   Bond State: ").append(
                            when (device.bondState) {
                                android.bluetooth.BluetoothDevice.BOND_BONDED -> "Bonded"
                                android.bluetooth.BluetoothDevice.BOND_BONDING -> "Bonding…"
                                android.bluetooth.BluetoothDevice.BOND_NONE -> "None"
                                else -> "Unknown"
                            }
                        ).append("\n")
                        device.bluetoothClass?.let { dc ->
                            sb.append("   Device Class: ").append(
                                when (dc.majorDeviceClass) {
                                    android.bluetooth.BluetoothClass.Device.Major.AUDIO_VIDEO    -> "Audio/Video"
                                    android.bluetooth.BluetoothClass.Device.Major.COMPUTER       -> "Computer"
                                    android.bluetooth.BluetoothClass.Device.Major.PHONE          -> "Phone"
                                    android.bluetooth.BluetoothClass.Device.Major.HEALTH         -> "Health"
                                    android.bluetooth.BluetoothClass.Device.Major.PERIPHERAL     -> "Peripheral (HID)"
                                    android.bluetooth.BluetoothClass.Device.Major.WEARABLE       -> "Wearable"
                                    android.bluetooth.BluetoothClass.Device.Major.IMAGING        -> "Imaging"
                                    android.bluetooth.BluetoothClass.Device.Major.NETWORKING     -> "Networking"
                                    android.bluetooth.BluetoothClass.Device.Major.TOY            -> "Toy"
                                    android.bluetooth.BluetoothClass.Device.Major.UNCATEGORIZED  -> "Uncategorized"
                                    else -> "Other (${dc.majorDeviceClass})"
                                }
                            ).append("\n")
                        }
                        sb.append("\n")
                    }
                }

                // B. Discover Active Unpaired Devices (Asynchronous)
                sb.append("\n 📡 DISCOVERED NEARBY DEVICES\n")
                sb.append("━━━━━━━━━━━━━━━━━━━━━\n")
                sb.append("Status: ${if (btScanningActive) "🔴 Scanning in progress…" else "⚪ Idle (use Scan button)"}\n")
                if (discoveredDevices.isNotEmpty()) {
                    sb.append("Found so far: ${discoveredDevices.size}\n\n")
                    for (entry in discoveredDevices) sb.append(entry).append("\n")
                } else {
                    sb.append("(No devices found yet — press Scan to start)\n")
                }

            } else {
                sb.append("\n🚫 Blocked:")
                sb.append(
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                        " NEARBY DEVICES (BLUETOOTH_CONNECT) permission not granted.\n"
                    else
                        " BLUETOOTH permission not granted.\n"
                )
            }
        } catch (e: Exception) {
            sb.append("⚠️ Error reading Bluetooth state: ").append(e.localizedMessage).append("\n")
        }
        return sb.toString()
    }

    // ALL Accounts on the device
    private fun checkAccountsString(): String {
        val sb = StringBuilder()
        sb.append("🔐 PRIVACY SCAN: ACCOUNTS\n")
        sb.append("━━━━━━━━━━━━━━━━━━━━━\n")
        try {
            // GET_ACCOUNTS is required for API < 26. On API 26+, it's restricted without permissions
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                ContextCompat.checkSelfPermission(
                    this, Manifest.permission.GET_ACCOUNTS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                sb.append("🚫 Blocked: CONTACTS (GET_ACCOUNTS) permission not granted.\n")
                return sb.toString()
            }

            val am = AccountManager.get(this)
            val accounts: Array<Account> = am.accounts

            sb.append("System Registered Accounts: ").append(accounts.size).append("\n")
            sb.append("\n📦 AUTHENTICATED SERVICES\n")
            sb.append("━━━━━━━━━━━━━━━━━━━━━\n")
            if (accounts.isNotEmpty()) {
                for (account in accounts) {
                    sb.append("• ").append(account.name).append(" (").append(account.type).append(")\n")
                }
            } else {
                sb.append("(No system accounts exposed due to OS isolation)\n")
            }

            // Append explicitly added account
            sb.append("\n👤 MANUALLY SELECTED ACCOUNTS\n")
            sb.append("━━━━━━━━━━━━━━━━━━━━━\n")
            sb.append(
                if (manuallyPickedAccount != null) "• $manuallyPickedAccount [Granted Access]\n"
                else "(No account shared via system picker yet)\n"
            )

            // Extra fallback: Show all system authenticators available
            sb.append("\n🧩 AUTHENTICATOR TYPES ON DEVICE\n")
            sb.append("━━━━━━━━━━━━━━━━━━━━━\n")
            for (type in am.authenticatorTypes) {
                sb.append("• Type: ").append(type.type).append(" (Package: ").append(type.packageName).append(")\n")
            }

            // Auth tokens (peek – non-blocking, returns null if not cached) ──
            sb.append("\n🔑 CACHED AUTH TOKENS\n")
            sb.append("━━━━━━━━━━━━━━━━━━━━━\n")
            val commonTokenTypes = listOf("oauth2:email", "oauth2", "authtokens", "SID", "LSID")
            var anyToken = false
            for (account in accounts) {
                for (tokenType in commonTokenTypes) {
                    try {
                        val token = am.peekAuthToken(account, tokenType)
                        if (!token.isNullOrEmpty()) {
                            anyToken = true
                            sb.append("• ").append(account.name).append(" [").append(tokenType).append("]: ")
                                .append(token.take(12)).append("…\n")
                        }
                    } catch (e: Exception) { /* token not cached or inaccessible */ }
                }
            }
            if (!anyToken) sb.append("(No cached tokens visible to this app)\n")

            // ── Master sync switch ───────────────────────────────────────
            val masterSync = ContentResolver.getMasterSyncAutomatically()
            sb.append("\n🔄 SYNC ADAPTERS\n")
            sb.append("━━━━━━━━━━━━━━━━━━━━━\n")
            sb.append("Master Auto-Sync: ${if (masterSync) "🔴 ON" else "⚪ OFF"}\n\n")

            val syncAdapters = ContentResolver.getSyncAdapterTypes()
            if (syncAdapters.isEmpty()) {
                sb.append("(No sync adapters found)\n")
            } else {
                sb.append("Total adapters registered: ${syncAdapters.size}\n\n")
                // Collect all accounts once. Adapters with no matching account are
                // still shown — signals will be false but the adapter is listed.
                val allAccounts: Array<Account> = try { am.accounts } catch (e: Exception) { emptyArray() }

                for (sa in syncAdapters) {
                    // Find all accounts that match this adapter's account type
                    val matchingAccounts = allAccounts.filter { it.type == sa.accountType }

                    // Signal 1: auto-sync enabled for any matching account
                    val autoSyncEnabled = matchingAccounts.any { acc ->
                        try { ContentResolver.getSyncAutomatically(acc, sa.authority) } catch (e: Exception) { false }
                    }

                    // Signal 2: periodic sync registered
                    val periodicSyncs = matchingAccounts.flatMap { acc ->
                        try { ContentResolver.getPeriodicSyncs(acc, sa.authority) } catch (e: Exception) { emptyList() }
                    }

                    // Signal 3: currently syncing or pending
                    val isSyncingNow = matchingAccounts.any { acc ->
                        try { ContentResolver.isSyncActive(acc, sa.authority) } catch (e: Exception) { false }
                    }
                    val isSyncPending = matchingAccounts.any { acc ->
                        try { ContentResolver.isSyncPending(acc, sa.authority) } catch (e: Exception) { false }
                    }

                    // Signal 4: historical bytes sent (via SyncStatusInfo) ─
                    var totalBytesSent     = 0L
                    var totalBytesReceived = 0L
                    var totalSyncCount     = 0
                    var lastSuccessTime    = 0L
                    // getSyncStatus() and SyncStatusInfo fields are @hide — not in public SDK.
                    // Access via reflection; fields may vary by ROM so each is caught individually.
                    for (acc in matchingAccounts) {
                        try {
                            val status = ContentResolver::class.java
                                .getMethod("getSyncStatus", Account::class.java, String::class.java)
                                .invoke(null, acc, sa.authority) ?: continue

                            val cls = status.javaClass
                            try { totalBytesSent     += cls.getField("totalBytesSent").getLong(status) }     catch (_: Exception) {}
                            try { totalBytesReceived += cls.getField("totalBytesReceived").getLong(status) } catch (_: Exception) {}
                            try { totalSyncCount     += cls.getField("numSyncs").getInt(status) }            catch (_: Exception) {}
                            try {
                                val t = cls.getField("lastSuccessTime").getLong(status)
                                if (t > lastSuccessTime) lastSuccessTime = t
                            } catch (_: Exception) {}

                        } catch (_: Exception) { /* getSyncStatus not available on this ROM */ }
                    }

                    // Activity indicator: any of the 4 signals firing = active
                    val isActive = autoSyncEnabled || periodicSyncs.isNotEmpty() ||
                            isSyncingNow    || isSyncPending || totalBytesSent > 0

                    val indicator = when {
                        isSyncingNow                -> "🔴 SYNCING NOW"
                        isSyncPending               -> "🟡 PENDING"
                        autoSyncEnabled             -> "🟠 AUTO-SYNC ON"
                        periodicSyncs.isNotEmpty()  -> "🟠 PERIODIC"
                        totalBytesSent > 0          -> "🟡 HAS UPLOADED"
                        else                        -> "⚪ Idle / Unknown"
                    }

                    // Only print full details for adapters showing signs of life;
                    // print a brief line for fully idle ones
                    if (isActive) {
                        val label = resolveSyncLabel(sa.authority, sa.accountType)
                        sb.append("[$indicator] $label\n")
                        sb.append("  Authority:        ${sa.authority}\n")
                        sb.append("  Account type:     ${sa.accountType}\n")
                        sb.append("  User-visible:     ${if (sa.isUserVisible) "Yes" else "No (hidden)"}\n")
                        sb.append("  Supports upload:  ${if (sa.supportsUploading()) "Yes" else "No"}\n")
                        sb.append("  Always syncable:  ${if (sa.isAlwaysSyncable) "Yes" else "No"}\n")
                        if (periodicSyncs.isNotEmpty()) {
                            for (p in periodicSyncs) {
                                val mins = p.period / 60
                                sb.append("  ⏱ Periodic every: ${mins} min\n")
                            }
                        }
                        if (totalSyncCount > 0) {
                            sb.append("  Total syncs run:  $totalSyncCount\n")
                            if (totalBytesSent > 0)
                                sb.append("  ⬆ Uploaded:       ${formatBytes(totalBytesSent)}\n")
                            if (totalBytesReceived > 0)
                                sb.append("  ⬇ Downloaded:     ${formatBytes(totalBytesReceived)}\n")
                            if (lastSuccessTime > 0)
                                sb.append("  Last success:     ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(lastSuccessTime))}\n")
                        }
                        sb.append("\n")
                    } else {
                        val label = resolveSyncLabel(sa.authority, sa.accountType)
                        sb.append("[⚪ Idle] $label\n")
                        sb.append("  ${sa.authority}\n")
                    }
                }
            }
        } catch (e: Exception) {
            sb.append("⚠️ Error reading accounts: ").append(e.localizedMessage).append("\n")
        }
        return sb.toString()
    }

    // ALL Contacts on a device
    private fun checkContactsString(): String {
        val sb = StringBuilder()
        sb.append("👥 PRIVACY SCAN: CONTACTS \n")
        sb.append("━━━━━━━━━━━━━━━━━━━━━\n")
        try {
            if (ContextCompat.checkSelfPermission(
                    this, Manifest.permission.READ_CONTACTS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                sb.append("🚫 Blocked: CONTACTS (READ_CONTACTS) permission not granted.\n")
                return sb.toString()
            }

            contentResolver.query(
                ContactsContract.Contacts.CONTENT_URI, null, null, null,
                "${ContactsContract.Contacts.DISPLAY_NAME_PRIMARY} ASC"
            )?.use { cursor ->
                sb.append("Total Contacts Found: ").append(cursor.count).append("\n")
                sb.append("\n⭐ FAVORITES\n")
                sb.append("━━━━━━━━━━━━━━━━━━━━━\n")

                val nameIdx    = cursor.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY)
                val starredIdx = cursor.getColumnIndex(ContactsContract.Contacts.STARRED)
                var favCount   = 0
                val favNames   = StringBuilder()

                while (cursor.moveToNext()) {
                    if (starredIdx >= 0 && cursor.getInt(starredIdx) == 1) {
                        favCount++
                        if (nameIdx >= 0) favNames.append(" ⭐ ").append(cursor.getString(nameIdx) ?: "Unknown").append("\n")
                    }
                }
                sb.append("Favorite Contacts: ").append(favCount).append("\n")
                if (favCount > 0) sb.append(favNames) else sb.append("(No favorites flagged)\n")
            } ?: sb.append("No contacts available or access returned empty.\n")
        } catch (e: Exception) {
            sb.append("⚠️ Error accessing contacts: ").append(e.localizedMessage).append("\n")
        }
        return sb.toString()
    }

    // SMS / MMS
    private fun checkSmsMmsString(): String {
        val sb = StringBuilder()
        sb.append("📨 PRIVACY SCAN: SMS & MMS\n")
        sb.append("━━━━━━━━━━━━━━━━━━━━━\n")
        try {
            if (ContextCompat.checkSelfPermission(
                    this, Manifest.permission.READ_SMS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                sb.append("🚫 Blocked: READ_SMS permission not granted.\n")
                return sb.toString()
            }

            // SMS
            contentResolver.query(
                Telephony.Sms.CONTENT_URI, null, null, null,
                "${Telephony.Sms.DATE} DESC"
            )?.use { cursor ->
                sb.append("Total SMS:   ").append(cursor.count).append("\n")

                // Column indices fetched once — they don't change between rows
                val addrIdx = cursor.getColumnIndex(Telephony.Sms.ADDRESS)
                val bodyIdx = cursor.getColumnIndex(Telephony.Sms.BODY)
                val typeIdx = cursor.getColumnIndex(Telephony.Sms.TYPE)
                val dateIdx = cursor.getColumnIndex(Telephony.Sms.DATE)
                val readIdx = cursor.getColumnIndex(Telephony.Sms.READ)

                // Count per box and unread in one pass
                val boxCounts  = mutableMapOf<Int, Int>()
                var smsUnreadCount  = 0
                while (cursor.moveToNext()) {
                    if (readIdx >= 0 && cursor.getInt(readIdx) == 0) smsUnreadCount++
                    if (typeIdx >= 0) {
                        val t = cursor.getInt(typeIdx)
                        boxCounts[t] = (boxCounts[t] ?: 0) + 1
                    }
                }
                sb.append("  • Unread:  $smsUnreadCount\n")
                sb.append("  • Inbox:   ${boxCounts[Telephony.Sms.MESSAGE_TYPE_INBOX]   ?: 0}\n")
                sb.append("  • Sent:    ${boxCounts[Telephony.Sms.MESSAGE_TYPE_SENT]    ?: 0}\n")
                sb.append("  • Drafts:  ${boxCounts[Telephony.Sms.MESSAGE_TYPE_DRAFT]   ?: 0}\n")
                sb.append("  • Outbox:  ${boxCounts[Telephony.Sms.MESSAGE_TYPE_OUTBOX]  ?: 0}\n")
                sb.append("  • Failed:  ${boxCounts[Telephony.Sms.MESSAGE_TYPE_FAILED]  ?: 0}\n")

                // Latest 10 — reset cursor to before first row
                sb.append("\n📥 LATEST SMS (up to 10)\n")
                sb.append("━━━━━━━━━━━━━━━━━━━━━\n")
                cursor.moveToPosition(-1)  // reset before loop — moveToFirst() + moveToNext() skips row 0
                var limit = 0
                while (cursor.moveToNext() && limit < 10) {
                    if (addrIdx >= 0) {
                        val address = cursor.getString(addrIdx) ?: "Unknown"
                        val body    = if (bodyIdx >= 0) cursor.getString(bodyIdx)?.take(30) ?: "" else ""
                        val typeStr = when (if (typeIdx >= 0) cursor.getInt(typeIdx) else -1) {
                            Telephony.Sms.MESSAGE_TYPE_INBOX  -> "IN"
                            Telephony.Sms.MESSAGE_TYPE_SENT   -> "OUT"
                            Telephony.Sms.MESSAGE_TYPE_DRAFT  -> "DRAFT"
                            Telephony.Sms.MESSAGE_TYPE_OUTBOX -> "OUTBOX"
                            Telephony.Sms.MESSAGE_TYPE_FAILED -> "FAILED"
                            else -> "UNKNOWN"
                        }
                        val unread  = readIdx >= 0 && cursor.getInt(readIdx) == 0
                        val dateMs  = if (dateIdx >= 0) cursor.getLong(dateIdx) else 0L
                        val dateStr = if (dateMs > 0)
                            java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
                                .format(java.util.Date(dateMs))
                        else "(no date)"
                        sb.append("[$typeStr${if (unread) " NEW" else ""}] $dateStr $address\n")
                        if (body.isNotEmpty()) sb.append("  \"$body\"\n")
                    }
                    limit++
                }
            } ?: sb.append("No local SMS found. (RCS messages are hidden from this provider)\n")

            // MMS
            // MMS lives under Telephony.Mms.CONTENT_URI — a completely separate
            // provider from SMS. Each MMS consists of three sub-tables:
            //   • Main table  — header (date, subject, size, box, read flag)
            //   • addr table  — content://mms/<id>/addr  (From/To/CC/BCC)
            //   • part table  — content://mms/part (text, image, audio, video)
            sb.append("\n🖼 MMS\n")
            sb.append("━━━━━━━━━━━━━━━━━━━━━\n")
            try {
                contentResolver.query(
                    Telephony.Mms.CONTENT_URI, null, null, null,
                    "${Telephony.Mms.DATE} DESC"
                )?.use { mmsCursor ->
                    val totalMms = mmsCursor.count
                    sb.append("Total MMS:   $totalMms\n")

                    if (totalMms > 0) {
                        val idIdx    = mmsCursor.getColumnIndex(Telephony.Mms._ID)
                        val readIdx  = mmsCursor.getColumnIndex(Telephony.Mms.READ)
                        val dateIdx  = mmsCursor.getColumnIndex(Telephony.Mms.DATE)
                        val subjIdx  = mmsCursor.getColumnIndex(Telephony.Mms.SUBJECT)
                        val sizeIdx  = mmsCursor.getColumnIndex(Telephony.Mms.MESSAGE_SIZE)
                        val boxIdx   = mmsCursor.getColumnIndex(Telephony.Mms.MESSAGE_BOX)

                        // Box breakdown + unread in one pass
                        val mmsBoxCounts = mutableMapOf<Int, Int>()
                        var mmsUnreadCount    = 0
                        while (mmsCursor.moveToNext()) {
                            if (readIdx >= 0 && mmsCursor.getInt(readIdx) == 0) mmsUnreadCount++
                            if (boxIdx >= 0) {
                                val b = mmsCursor.getInt(boxIdx)
                                mmsBoxCounts[b] = (mmsBoxCounts[b] ?: 0) + 1
                            }
                        }
                        sb.append("  • Unread:  $mmsUnreadCount\n")
                        sb.append("  • Inbox:   ${mmsBoxCounts[Telephony.Mms.MESSAGE_BOX_INBOX]  ?: 0}\n")
                        sb.append("  • Sent:    ${mmsBoxCounts[Telephony.Mms.MESSAGE_BOX_SENT]   ?: 0}\n")
                        sb.append("  • Drafts:  ${mmsBoxCounts[Telephony.Mms.MESSAGE_BOX_DRAFTS] ?: 0}\n")
                        sb.append("  • Outbox:  ${mmsBoxCounts[Telephony.Mms.MESSAGE_BOX_OUTBOX] ?: 0}\n")
                        sb.append("  • Failed:  ${mmsBoxCounts[Telephony.Mms.MESSAGE_BOX_FAILED] ?: 0}\n")
                        sb.append("\n⚠️ Samsung hides draft/failed MMS from third-party apps\n")

                        // Latest 10 MMS with addresses and parts
                        sb.append("\n📥 LATEST MMS (up to 10)\n")
                        sb.append("━━━━━━━━━━━━━━━━━━━━━\n")
                        mmsCursor.moveToPosition(-1)  // reset before loop — moveToFirst() + moveToNext() skips row 0
                        var mmsLimit = 0
                        while (mmsCursor.moveToNext() && mmsLimit < 10) {
                            val mmsId = if (idIdx   >= 0) mmsCursor.getString(idIdx) else null
                            val dateMs = if (dateIdx >= 0) mmsCursor.getLong(dateIdx) * 1000L else 0L
                            val subject = if (subjIdx >= 0) mmsCursor.getString(subjIdx) else null
                            val sizeB = if (sizeIdx >= 0) mmsCursor.getLong(sizeIdx) else 0L
                            val unread  = readIdx >= 0 && mmsCursor.getInt(readIdx) == 0
                            val boxType = if (boxIdx >= 0) when (mmsCursor.getInt(boxIdx)) {
                                Telephony.Mms.MESSAGE_BOX_INBOX -> "IN"
                                Telephony.Mms.MESSAGE_BOX_SENT -> "OUT"
                                Telephony.Mms.MESSAGE_BOX_DRAFTS -> "DRAFT"
                                Telephony.Mms.MESSAGE_BOX_OUTBOX -> "OUTBOX"
                                Telephony.Mms.MESSAGE_BOX_FAILED -> "FAILED"
                                else -> "BOX(${mmsCursor.getInt(boxIdx)})"
                            } else "UNKNOWN"

                            val dateStr = if (dateMs > 0) {
                                java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
                                    .format(java.util.Date(dateMs))
                            } else "(no date)"

                            sb.append("[$boxType${if (unread) " NEW" else ""}] $dateStr\n")
                            if (!subject.isNullOrEmpty()) sb.append("  Subject: $subject\n")
                            if (sizeB > 0) sb.append("  Size: ${formatBytes(sizeB)}\n")

                            // Fetch Addresses
                            if (mmsId != null) {
                                try {
                                    contentResolver.query(
                                        "content://mms/$mmsId/addr".toUri(),
                                        null, null, null, null
                                    )?.use { addrCursor ->
                                        val addrColIdx = addrCursor.getColumnIndex("address")
                                        val typeColIdx = addrCursor.getColumnIndex("type")
                                        while (addrCursor.moveToNext()) {
                                            val addr = if (addrColIdx >= 0)
                                                addrCursor.getString(addrColIdx) else null
                                            val addrType = if (typeColIdx >= 0) {
                                                when (addrCursor.getInt(typeColIdx)) {
                                                    137 -> "From"
                                                    151 -> "To"
                                                    130 -> "CC"
                                                    129 -> "BCC"
                                                    else -> "Addr"
                                                }
                                            } else "Addr"

                                            if (!addr.isNullOrEmpty() && addr != "insert-address-token") {
                                                sb.append("  $addrType: $addr\n")
                                            }
                                        }
                                    }
                                } catch (e: Exception) { /* Silently catch if unreadable */ }

                                // Fetch Parts
                                try {
                                    contentResolver.query(
                                        "content://mms/part".toUri(),
                                        arrayOf("_id", "mid", "ct", "_data", "text"),
                                        "mid = ?", arrayOf(mmsId), null
                                    )?.use { partCursor ->
                                        val ctIdx = partCursor.getColumnIndex("ct")
                                        val textIdx = partCursor.getColumnIndex("text")
                                        while (partCursor.moveToNext()) {
                                            val ct = if (ctIdx >= 0) partCursor.getString(ctIdx) ?: "" else ""
                                            when {
                                                ct == "text/plain" -> {
                                                    val txt = if (textIdx >= 0) partCursor.getString(textIdx) else null
                                                    if (!txt.isNullOrEmpty())
                                                        sb.append("  Text: \"${txt.take(40)}...\"\n")
                                                }
                                                ct.startsWith("image/") -> sb.append("  Attachment: Image ($ct)\n")
                                                ct.startsWith("audio/") -> sb.append("  Attachment: Audio ($ct)\n")
                                                ct.startsWith("video/") -> sb.append("  Attachment: Video ($ct)\n")
                                                ct == "application/smil" -> { /* Skip layout info */ }
                                                ct.isNotEmpty() -> sb.append("  Attachment: $ct\n")
                                            }
                                        }
                                    }
                                } catch (e: Exception) { /* Silently catch if part inaccessible */ }
                            }
                            sb.append("\n")
                            mmsLimit++
                        }
                    }
                } ?: sb.append("No MMS found or MMS provider inaccessible.\n")

            } catch (e: Exception) {
                sb.append("⚠️ Error reading MMS: ").append(e.localizedMessage ?: "Unknown").append("\n")
            }

        } catch (e: Exception) {
            sb.append("⚠️ Error reading SMS/MMS: ").append(e.localizedMessage).append("\n")
        }
        return sb.toString()
    }

    // ALL Call logs
    private fun checkCallLogString(): String {
        val sb = StringBuilder()
        sb.append("📑 PRIVACY SCAN: CALL LOGS\n")
        sb.append("━━━━━━━━━━━━━━━━━━━━━\n")
        try {
            if (ContextCompat.checkSelfPermission(
                    this, Manifest.permission.READ_CALL_LOG
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                sb.append("🚫 Blocked: READ_CALL_LOG permission not granted.\n")
                return sb.toString()
            }

            contentResolver.query(
                CallLog.Calls.CONTENT_URI, null, null, null, CallLog.Calls.DEFAULT_SORT_ORDER
            )?.use { cursor ->

                sb.append("Total Call Logs: ").append(cursor.count).append("\n")
                sb.append("\n🕒 RECENT DIAL ACTIVITY\n")
                sb.append("━━━━━━━━━━━━━━━━━━━━━\n")

                val numIdx  = cursor.getColumnIndex(CallLog.Calls.NUMBER)
                val typeIdx = cursor.getColumnIndex(CallLog.Calls.TYPE)
                var limit = 0
                while (cursor.moveToNext() && limit < 10) {
                    if (numIdx >= 0 && typeIdx >= 0) {
                        val readableType = when (cursor.getInt(typeIdx)) {
                            CallLog.Calls.INCOMING_TYPE            -> "INCOMING"
                            CallLog.Calls.OUTGOING_TYPE            -> "OUTGOING"
                            CallLog.Calls.MISSED_TYPE              -> "MISSED"
                            CallLog.Calls.VOICEMAIL_TYPE           -> "VOICEMAIL"
                            CallLog.Calls.REJECTED_TYPE            -> "REJECTED"
                            CallLog.Calls.BLOCKED_TYPE             -> "BLOCKED"
                            CallLog.Calls.ANSWERED_EXTERNALLY_TYPE -> "ANSWERED EXTERNALLY"
                            else -> "UNKNOWN"
                        }
                        sb.append("• ")
                            .append((cursor.getString(numIdx) ?: "Unknown").padEnd(13))
                            .append(" (").append(readableType).append(")\n")
                    }
                    limit++
                }
            } ?: sb.append("No call log history found.\n")
        } catch (e: Exception) {
            sb.append("⚠️ Error accessing call logs: ").append(e.localizedMessage).append("\n")
        }
        return sb.toString()
    }

    // Helpers
    // Returns a human-readable label for a sync adapter authority.
    // Strategy 1: well-known authority lookup table.
    // Strategy 2: resolve the package that registered this authority and read its app label.
    private fun resolveSyncLabel(authority: String, accountType: String): String {
        // Well-known authority → readable data type
        KNOWN_SYNC_AUTHORITIES[authority]?.let { return it }

        // Strategy 2: try to resolve the app label from the package that owns this authority
        try {
            val providerInfo = packageManager.resolveContentProvider(authority, 0)
            if (providerInfo != null) {
                val appLabel = packageManager.getApplicationLabel(
                    packageManager.getApplicationInfo(providerInfo.packageName, 0)
                ).toString()
                if (appLabel.isNotEmpty() && appLabel != providerInfo.packageName) {
                    return appLabel
                }
            }
        } catch (e: Exception) { }

        // Strategy 3: extract the last meaningful segment of the authority string
        // e.g. "com.google.android.gm.exchange" → "Exchange"
        val lastSegment = authority.substringAfterLast('.').replaceFirstChar { it.uppercase() }
        return if (lastSegment.length > 2) lastSegment else authority
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1_048_576 -> "%.2f MB".format(bytes / 1_048_576.0)
        bytes >= 1_024     -> "%.1f KB".format(bytes / 1_024.0)
        else               -> "$bytes B"
    }

    companion object {
        private const val KEY_PICKED_ACCOUNT = "key_manually_picked_account"
        val KNOWN_SYNC_AUTHORITIES = mapOf(
            "com.android.contacts"                          to "Contacts",
            "com.android.calendar"                          to "Calendar",
            "com.android.providers.calendar"                to "Calendar",
            "gmail-ls"                                      to "Gmail",
            "com.google.android.gm.exchange"                to "Gmail Exchange",
            "com.google.android.gms.drive.GmsProvider"      to "Google Drive",
            "com.google.android.gms.photos.provider"        to "Google Photos",
            "com.google.android.gms.fitness"                to "Google Fit",
            "com.google.android.gms.people"                 to "Google People / Contacts",
            "com.google.android.gms.auth.accounts"          to "Google Auth",
            "com.google.android.youtube"                    to "YouTube",
            "com.google.android.apps.docs"                  to "Google Docs",
            "com.google.android.apps.photos"                to "Google Photos",
            "com.google.android.apps.messaging"             to "Google Messages",
            "com.google.android.gms.games"                  to "Google Play Games",
            "com.google.android.gms.subscribedfeeds"        to "Google Subscribed Feeds",
            "com.google.android.gms.chromesync"             to "Chrome Sync",
            "subscribedfeeds"                               to "Subscribed Feeds",
            "com.facebook.messenger"                        to "Facebook Messenger",
            "com.facebook.contacts"                         to "Facebook Contacts",
            "com.whatsapp"                                  to "WhatsApp",
            "com.instagram.android"                         to "Instagram",
            "com.linkedin.android"                          to "LinkedIn",
            "com.twitter.android"                           to "X / Twitter",
            "com.dropbox.android"                           to "Dropbox",
            "com.microsoft.office.outlook"                  to "Outlook",
            "com.microsoft.teams"                           to "Microsoft Teams",
            "com.samsung.android.email.provider"            to "Samsung Email",
            "com.samsung.android.calendar"                  to "Samsung Calendar",
            "com.samsung.android.contacts"                  to "Samsung Contacts",
            "com.samsung.android.scloud"                    to "Samsung Cloud",
            "com.samsung.android.app.notes"                 to "Samsung Notes",
            "com.samsung.android.messaging"                 to "Samsung Messages",
            "com.samsung.android.knox.containercore"        to "Samsung Knox",
            "com.sec.android.provider.logsprovider"         to "Samsung Call Log Sync",
            "com.spotify.music"                             to "Spotify",
            "com.slack"                                     to "Slack",
            "com.evernote"                                  to "Evernote",
            "org.telegram.messenger"                        to "Telegram",
            "com.viber.voip"                                to "Viber",
        )
    }
}
