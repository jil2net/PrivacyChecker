package com.jil2.privacychecker

import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter

class TabAdapter(activity: AppCompatActivity) : FragmentStateAdapter(activity) {

    companion object {
        const val TAB_COUNT = 7
    }

    private val activeFragments = HashMap<Int, PrivacyDataFragment>()
    private val currentData = HashMap<Int, String>().apply {
        for (i in 0 until TAB_COUNT) put(i, "No scan performed yet.")
    }

    // FIX #8: was 5 in original — must match actual tab count
    override fun getItemCount(): Int = TAB_COUNT

    // Return a stable ID that changes based on content to force data refresh
    override fun getItemId(position: Int): Long {
        val content = currentData[position] ?: ""
        return (position.toString() + content).hashCode().toLong()
    }

    override fun containsItem(itemId: Long): Boolean =
        (0 until itemCount).any { getItemId(it) == itemId }

    override fun createFragment(position: Int): Fragment {
        val data = currentData[position] ?: "No content"
        // Pass tabPosition so each fragment knows which tab-specific buttons to show:
        //   position 2 = Bluetooth → shows Scan/Stop button
        //   position 3 = Accounts  → shows Pick Account button
        val fragment = PrivacyDataFragment.newInstance(data, position)
        activeFragments[position] = fragment
        return fragment
    }

    // Programmatically update the active fragment text without recreation
    fun updateTabData(position: Int, newData: String) {
        currentData[position] = newData
        activeFragments[position]?.updateContent(newData)
    }

    fun refreshBtButton(scanning: Boolean) {
        activeFragments[2]?.refreshScanButton(scanning) // 2 = Tab.BLUETOOTH
    }
}
