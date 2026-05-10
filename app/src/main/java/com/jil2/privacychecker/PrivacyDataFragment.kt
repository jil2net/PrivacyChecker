package com.jil2.privacychecker

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.fragment.app.Fragment

class PrivacyDataFragment : Fragment() {

    private var contentText: String = ""
    private var tabPosition: Int    = -1

    private var txtContent:    TextView? = null
    private var btnPickAccount: Button? = null
    private var btnRevoke:     Button?  = null
    private var btnBtScan:     Button?  = null

    companion object {
        private const val ARG_CONTENT = "arg_content"
        private const val ARG_TAB_POS = "arg_tab_pos"

        fun newInstance(content: String, tabPosition: Int): PrivacyDataFragment {
            val f = PrivacyDataFragment()
            f.arguments = Bundle().apply {
                putString(ARG_CONTENT, content)
                putInt(ARG_TAB_POS,    tabPosition)
            }
            return f
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        contentText = arguments?.getString(ARG_CONTENT) ?: "No data"
        tabPosition = arguments?.getInt(ARG_TAB_POS, -1) ?: -1
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_privacy_data, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        txtContent     = view.findViewById(R.id.txtContent)
        btnPickAccount = view.findViewById(R.id.btnPickAccount)
        btnRevoke      = view.findViewById(R.id.btnRevokePermissions)
        btnBtScan      = view.findViewById(R.id.btnBtScan)

        // Apply latest content (catches updateContent() calls before view existed)
        txtContent?.text = contentText

        // Revoke button — shown on every tab
        btnRevoke?.setOnClickListener {
            (activity as? MainActivity)?.openAppSettings()
        }

        // Account picker — Accounts tab only (position 3)
        btnPickAccount?.visibility = if (tabPosition == 3) View.VISIBLE else View.GONE
        btnPickAccount?.setOnClickListener {
            (activity as? MainActivity)?.launchExplicitAccountPicker()
        }

        // Bluetooth scan/stop — Bluetooth tab only (position 2)
        btnBtScan?.visibility = if (tabPosition == 2) View.VISIBLE else View.GONE
        if (tabPosition == 2) {
            // Sync label with current state in case scan was already running
            syncScanButton()
            btnBtScan?.setOnClickListener {
                val main = activity as? MainActivity ?: return@setOnClickListener
                main.toggleBluetoothScan()
                // Update label immediately in the click handler —
                // don't wait for the adapter reference chain
                syncScanButton()
            }
        }
    }

    // Called by TabAdapter.updateTabData() — updates text content only
    fun updateContent(newContent: String) {
        contentText = newContent
        txtContent?.text = newContent
        // Re-sync button every time content updates (covers discovery finished event)
        if (tabPosition == 2) syncScanButton()
    }

    // Called by TabAdapter.refreshBtButton() and internally — keeps button
    // label and color in sync with actual MainActivity.btScanningActive state
    fun refreshScanButton(scanning: Boolean) = syncScanButton()

    private fun syncScanButton() {
        val scanning = (activity as? MainActivity)?.btScanningActive ?: false
        btnBtScan?.text = if (scanning) "Stop Scan" else "Scan Nearby Devices"
        btnBtScan?.backgroundTintList = android.content.res.ColorStateList.valueOf(
            if (scanning) android.graphics.Color.parseColor("#B71C1C")
            else          android.graphics.Color.parseColor("#1565C0")
        )
    }
}