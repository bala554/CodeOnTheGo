package com.itsaky.androidide.opencode

import android.os.Bundle
import android.view.View
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.ViewModelProvider
import com.itsaky.androidide.activities.EdgeToEdgeIDEActivity
import org.koin.androidx.viewmodel.ext.android.viewModel

class OpenCodeChatActivity : EdgeToEdgeIDEActivity() {
	private val viewModel: OpenCodeChatViewModel by viewModel()

	override fun bindLayout(): View =
		ComposeView(this).apply {
			setContent {
				OpenCodeChatScreen(viewModel)
			}
		}

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
	}
}
