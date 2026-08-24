package com.batz.tvlauncher

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.batz.tvlauncher.adapter.CardsAdapter
import com.batz.tvlauncher.data.SearchRepository
import com.batz.tvlauncher.databinding.ActivitySearchBinding
import com.batz.tvlauncher.model.RowItem
import kotlinx.coroutines.launch

class SearchActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_QUERY = "extra_query"
        const val EXTRA_MODE = "extra_mode"

        fun start(context: Context, initialQuery: String = "", mode: String = "search") {
            val intent = Intent(context, SearchActivity::class.java).apply {
                putExtra(EXTRA_QUERY, initialQuery)
                putExtra(EXTRA_MODE, mode)
            }
            context.startActivity(intent)
        }
    }

    private lateinit var binding: ActivitySearchBinding
    private val repository = SearchRepository()
    private var currentMode = "search"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySearchBinding.inflate(layoutInflater)
        setContentView(binding.root)

        currentMode = intent.getStringExtra(EXTRA_MODE) ?: "search"

        setupUI()

        val initialQuery = intent.getStringExtra(EXTRA_QUERY) ?: ""
        if (initialQuery.isNotBlank()) {
            binding.searchInput.setText(initialQuery)
            performSearch(initialQuery, currentMode)
        }
    }

    private fun setupUI() {
        binding.backButton.setOnClickListener { finish() }
        binding.clearButton.setOnClickListener {
            binding.searchInput.setText("")
            binding.clearButton.visibility = View.GONE
            binding.statusText.text = "Type a query and press Enter to search"
            binding.searchResultsRecyclerView.adapter = CardsAdapter(emptyList(), ::onItemClicked)
        }

        binding.searchResultsRecyclerView.layoutManager = GridLayoutManager(this, 5)

        // Show/hide clear button as user types
        binding.searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val query = s?.toString() ?: ""
                binding.clearButton.visibility = if (query.isNotEmpty()) View.VISIBLE else View.GONE
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        // Execute search on Enter / Search key
        binding.searchInput.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH ||
                actionId == EditorInfo.IME_ACTION_DONE ||
                (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            ) {
                val query = binding.searchInput.text.toString().trim()
                performSearch(query, "search")
                true
            } else {
                false
            }
        }

        binding.searchInput.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN) {
                val query = binding.searchInput.text.toString().trim()
                performSearch(query, "search")
                true
            } else {
                false
            }
        }
    }

    private fun performSearch(query: String, mode: String = "search") {
        if (query.isBlank()) {
            binding.statusText.text = "Type a query and press Enter to search"
            binding.searchLoading.visibility = View.GONE
            binding.searchResultsRecyclerView.adapter = CardsAdapter(emptyList(), ::onItemClicked)
            return
        }

        binding.statusText.text = if (mode == "genre") "Loading $query content..." else "Searching for \"$query\"..."
        binding.searchLoading.visibility = View.VISIBLE

        lifecycleScope.launch {
            val results = repository.search(query, mode)
            binding.searchLoading.visibility = View.GONE

            if (results.isEmpty()) {
                binding.statusText.text = "No content found for \"$query\""
                binding.searchResultsRecyclerView.adapter = CardsAdapter(emptyList(), ::onItemClicked)
            } else {
                binding.statusText.text = if (mode == "genre") "$query Category (${results.size} titles)" else "Found ${results.size} results for \"$query\""
                binding.searchResultsRecyclerView.adapter = CardsAdapter(results, ::onItemClicked)
            }
        }
    }

    private fun onItemClicked(item: RowItem) {
        DetailActivity.start(this, itemId = item.id, itemLabel = item.label)
    }
}
