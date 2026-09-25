package com.itsaky.androidide.actions.sidebar

import android.content.Context
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import com.itsaky.androidide.R
import com.itsaky.androidide.actions.ActionData
import com.itsaky.androidide.actions.requireContext
import com.itsaky.androidide.agent.opencode.ui.OpenCodeChatBottomSheet
import kotlin.reflect.KClass

/**
 * Sidebar action for launching the OpenCode AI Coding Agent assistant.
 */
class OpenCodeSidebarAction(
    context: Context,
    override val order: Int
) : AbstractSidebarAction() {

    companion object {
        const val ID = "ide.editor.sidebar.opencode"
    }

    override val id: String = ID
    override val fragmentClass: KClass<out Fragment>? = null

    init {
        label = "OpenCode AI"
        icon = ContextCompat.getDrawable(context, R.drawable.ic_stars)
            ?: ContextCompat.getDrawable(context, R.drawable.ic_bot)
            ?: ContextCompat.getDrawable(context, R.drawable.ic_help)
    }

    override suspend fun execAction(data: ActionData): Any {
        val context = data.requireContext()
        if (context is FragmentActivity) {
            val existing = context.supportFragmentManager.findFragmentByTag(OpenCodeChatBottomSheet.TAG)
            if (existing == null) {
                OpenCodeChatBottomSheet.newInstance().show(context.supportFragmentManager, OpenCodeChatBottomSheet.TAG)
            }
        }
        return true
    }

    override fun retrieveTooltipTag(isAlternateContext: Boolean) = "ide.editor.sidebar.opencode"
}
