package com.itsaky.androidide.agent.opencode.ui

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.platform.ComposeView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.itsaky.androidide.agent.opencode.model.IdeProjectContext
import com.itsaky.androidide.agent.opencode.viewmodel.OpenCodeChatViewModel
import com.itsaky.androidide.ui.compose.theme.ManagerTheme
import org.koin.androidx.viewmodel.ext.android.viewModel

/**
 * BottomSheetDialogFragment hosting the OpenCode Jetpack Compose chat window.
 */
class OpenCodeChatBottomSheet : BottomSheetDialogFragment() {

    companion object {
        const val TAG = "OpenCodeChatBottomSheet"

        fun newInstance(
            activeFilePath: String? = null,
            activeFileName: String? = null,
            selectedText: String? = null
        ): OpenCodeChatBottomSheet {
            return OpenCodeChatBottomSheet().apply {
                arguments = Bundle().apply {
                    putString("active_file_path", activeFilePath)
                    putString("active_file_name", activeFileName)
                    putString("selected_text", selectedText)
                }
            }
        }
    }

    private val chatViewModel: OpenCodeChatViewModel by viewModel()

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState) as BottomSheetDialog
        dialog.setOnShowListener {
            val bottomSheet = dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            bottomSheet?.let { sheet ->
                val behavior = BottomSheetBehavior.from(sheet)
                behavior.state = BottomSheetBehavior.STATE_EXPANDED
                behavior.skipCollapsed = true
                behavior.isDraggable = true
            }
        }
        return dialog
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val activeFilePath = arguments?.getString("active_file_path")
        val activeFileName = arguments?.getString("active_file_name")
        val selectedText = arguments?.getString("selected_text")

        if (activeFilePath != null || selectedText != null) {
            chatViewModel.setProjectContext(
                IdeProjectContext(
                    activeFilePath = activeFilePath,
                    activeFileName = activeFileName,
                    selectedText = selectedText
                )
            )
        }

        return ComposeView(requireContext()).apply {
            setContent {
                ManagerTheme {
                    OpenCodeChatScreen(
                        viewModel = chatViewModel,
                        onClose = { dismiss() }
                    )
                }
            }
        }
    }
}
