/*
 *  This file is part of AndroidIDE.
 *
 *  AndroidIDE is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidIDE is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.itsaky.androidide.actions.editor

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.net.Uri
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.itsaky.androidide.R
import com.itsaky.androidide.actions.ActionData
import com.itsaky.androidide.actions.EditorActivityAction
import com.itsaky.androidide.idetooltips.TooltipTag
import java.net.URI
import java.util.Locale
import com.itsaky.androidide.resources.R as StringsR

class OpenCodeAction(
	context: Context,
	override var order: Int,
) : EditorActivityAction() {
	override val id: String = ID

	init {
		label = context.getString(StringsR.string.opencode_toolbar_label)
		icon = ContextCompat.getDrawable(context, R.drawable.ic_ai)
		requiresUIThread = true
	}

	companion object {
		const val ID = "ide.editor.openCode"
		private const val PREFERENCES_NAME = "opencode"
		private const val SERVER_URL_KEY = "server_url"
	}

	override fun retrieveTooltipTag(isReadOnlyContext: Boolean): String = TooltipTag.EDITOR_TOOLBAR_AI

	override suspend fun execAction(data: ActionData): Boolean {
		val activity = data.getActivity() ?: return false
		val preferences = activity.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
		val serverUrl =
			EditText(activity).apply {
				inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
				setSingleLine(true)
				hint = activity.getString(StringsR.string.opencode_server_url_hint)
				setText(preferences.getString(SERVER_URL_KEY, "").orEmpty())
			}
		val instructions =
			TextView(activity).apply {
				text = activity.getString(StringsR.string.opencode_connection_instructions)
			}
		val content =
			LinearLayout(activity).apply {
				orientation = LinearLayout.VERTICAL
				val horizontalPadding = (24 * resources.displayMetrics.density).toInt()
				setPadding(horizontalPadding, 0, horizontalPadding, 0)
				addView(
					instructions,
					LinearLayout.LayoutParams(
						LinearLayout.LayoutParams.MATCH_PARENT,
						LinearLayout.LayoutParams.WRAP_CONTENT,
					),
				)
				addView(
					serverUrl,
					LinearLayout.LayoutParams(
						LinearLayout.LayoutParams.MATCH_PARENT,
						LinearLayout.LayoutParams.WRAP_CONTENT,
					),
				)
			}
		val dialog =
			MaterialAlertDialogBuilder(activity)
				.setTitle(StringsR.string.opencode_connect_title)
				.setView(ScrollView(activity).apply { addView(content) })
				.setNegativeButton(android.R.string.cancel, null)
				.setPositiveButton(StringsR.string.opencode_save_and_open, null)
				.create()

		dialog.setOnShowListener {
			dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
				val address = OpenCodeServerAddress.parse(serverUrl.text.toString())
				if (address == null) {
					serverUrl.error = activity.getString(StringsR.string.opencode_invalid_server_url)
					return@setOnClickListener
				}

				serverUrl.error = null
				preferences.edit().putString(SERVER_URL_KEY, address.toASCIIString()).apply()
				try {
					activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(address.toASCIIString())))
				} catch (_: ActivityNotFoundException) {
					Toast.makeText(activity, StringsR.string.opencode_no_browser, Toast.LENGTH_LONG).show()
				}
				dialog.dismiss()
			}
		}
		dialog.show()
		return true
	}
}

internal object OpenCodeServerAddress {
	fun parse(value: String): URI? {
		val address = runCatching { URI(value.trim()) }.getOrNull() ?: return null
		val scheme = address.scheme?.lowercase(Locale.ROOT)
		if (scheme !in setOf("http", "https")) return null
		if (address.host.isNullOrBlank() || address.rawUserInfo != null) return null
		if (address.rawQuery != null || address.rawFragment != null) return null
		return address
	}
}
