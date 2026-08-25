package com.batz.tvlauncher

import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.View
import android.view.Window
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.batz.tvlauncher.adapter.HomeRowsAdapter
import com.batz.tvlauncher.data.JsonRepository
import com.batz.tvlauncher.databinding.ActivityMainBinding
import com.batz.tvlauncher.model.RowItem
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Launch test NanoServer service
        try {
            val serviceIntent = Intent(this, com.batz.tvlauncher.test.ServerService::class.java)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
        } catch (e: Exception) {
            android.util.Log.w("MainActivity", "Could not start test ServerService: ${e.message}")
        }

        setupHeaderNavigation()
        loadHomeData()
    }

    private fun setupHeaderNavigation() {
        binding.profileButton.setOnClickListener {
            showProfileDialog()
        }
    }

    private fun showProfileDialog() {
        val dialog = Dialog(this).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            setContentView(R.layout.dialog_profile)
            window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        }

        dialog.findViewById<Button>(R.id.btnAccountLogin)?.setOnClickListener {
            Toast.makeText(this, "Redirecting to Kinflex Account Login...", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }

        dialog.findViewById<Button>(R.id.btnSettings)?.setOnClickListener {
            Toast.makeText(this, "Kinflex TV Launcher Settings", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }

        dialog.findViewById<Button>(R.id.btnCloseProfile)?.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun loadHomeData() {
        setLoading(true)
        lifecycleScope.launch {
            try {
                val repository = JsonRepository(applicationContext)
                val homeData = repository.load()

                binding.rowsRecyclerView.layoutManager = LinearLayoutManager(this@MainActivity)
                binding.rowsRecyclerView.setHasFixedSize(true)
                binding.rowsRecyclerView.itemAnimator = null // avoids jank on low-end GPUs
                binding.rowsRecyclerView.adapter = HomeRowsAdapter(
                    rows = homeData.rows,
                    searchHint = homeData.searchHint,
                    onItemClick = ::onItemClicked
                )

                setLoading(false)
            } catch (t: Throwable) {
                setError(true)
            }
        }
    }

    private fun onItemClicked(item: RowItem) {
        if (!item.streamUrl.isNullOrBlank()) {
            PlayerActivity.start(this, mediaUrl = item.streamUrl, title = item.label, isLive = item.id.startsWith("m3u"))
        } else if (item.id.startsWith("m3u")) {
            val fallbackStream = "https://amg00862-amg00862c6-amgplt0173.playout.now3.amagi.tv/playlist/amg00862-amg00862c6-amgplt0173/playlist.m3u8"
            PlayerActivity.start(this, mediaUrl = fallbackStream, title = item.label, isLive = true)
        } else {
            DetailActivity.start(this, itemId = item.id, itemLabel = item.label)
        }
    }

    private fun setLoading(isLoading: Boolean) {
        binding.loadingSpinner.visibility = if (isLoading) View.VISIBLE else View.GONE
        binding.rowsRecyclerView.visibility = if (isLoading) View.GONE else View.VISIBLE
        binding.errorText.visibility = View.GONE
    }

    private fun setError(isError: Boolean) {
        binding.loadingSpinner.visibility = View.GONE
        binding.rowsRecyclerView.visibility = View.GONE
        binding.errorText.visibility = if (isError) View.VISIBLE else View.GONE
    }
}
