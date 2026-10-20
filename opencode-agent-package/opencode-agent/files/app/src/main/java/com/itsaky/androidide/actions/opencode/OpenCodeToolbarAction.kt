package com.itsaky.androidide.actions.opencode

import android.content.Context
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.itsaky.androidide.R
import com.itsaky.androidide.actions.ActionData
import com.itsaky.androidide.actions.ActionItem
import com.itsaky.androidide.actions.EditorRelatedAction
import com.itsaky.androidide.actions.requireContext
import com.itsaky.androidide.agent.opencode.ui.OpenCodeAgentStatusDialogFragment
import com.itsaky.androidide.agent.opencode.ui.OpenCodeChatBottomSheet

/**
 * Editor toolbar action for launching the OpenCode AI Assistant with active editor context.
 */
class OpenCodeToolbarAction(
    context: Context,
    override val order: Int
) : EditorRelatedAction() {

    companion object {
        const val ID = "ide.editor.toolbar.opencode"
    }

    override val id: String = ID
    override var location: ActionItem.Location = ActionItem.Location.EDITOR_TOOLBAR

    init {
        label = "OpenCode Assistant"
        icon = ContextCompat.getDrawable(context, R.drawable.ic_opencode)
            ?: ContextCompat.getDrawable(context, R.drawable.ic_ai)
    }

    override suspend fun execAction(data: ActionData): Any {
        val context = data.requireContext()
        val editor = data.getEditor()

        val selectedText = editor?.let { ed ->
            val cursor = ed.text.cursor
            if (cursor.isSelected) {
                ed.text.subSequence(cursor.left(), cursor.right()).toString()
            } else null
        }

        val activeFile = editor?.file
        val activeFilePath = activeFile?.absolutePath
        val activeFileName = activeFile?.name

        if (context is FragmentActivity) {
            val fm = context.supportFragmentManager
            // Like the reference app: tapping OpenCode shows the agent status dialog first;
            // CHAT inside it opens the chat sheet with this editor context.
            if (fm.findFragmentByTag(OpenCodeAgentStatusDialogFragment.TAG) == null &&
                fm.findFragmentByTag(OpenCodeChatBottomSheet.TAG) == null
            ) {
                OpenCodeAgentStatusDialogFragment.newInstance(
                    activeFilePath = activeFilePath,
                    activeFileName = activeFileName,
                    selectedText = selectedText
                ).show(fm, OpenCodeAgentStatusDialogFragment.TAG)
            }
        }
        return true
    }

    override fun retrieveTooltipTag(isReadOnlyContext: Boolean) = "ide.editor.toolbar.opencode"
}
