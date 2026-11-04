/*
 *  This file is part of AndroidIDE.
 *
 *  AndroidIDE is free software; you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.itsaky.androidide.actions.editor

import android.content.DialogInterface
import android.graphics.Typeface
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.itsaky.androidide.activities.editor.EditorHandlerActivity
import com.itsaky.androidide.resources.R as StringsR
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

internal object OpenCodeRuntimeDialog {
	fun show(
		activity: EditorHandlerActivity,
			projectDirectory: File?,
			onConnectRemote: () -> Unit,
		) {
			val installer = OpenCodeRuntimeInstaller(
				cacheDirectory = activity.cacheDir,
				openDependencyAsset = { assetName -> activity.assets.open(assetName) },
			)
			val controller = OpenCodeRuntimeController(installer)
		val padding = (24 * activity.resources.displayMetrics.density).toInt()
		val spacing = (12 * activity.resources.displayMetrics.density).toInt()

		val status = TextView(activity).apply {
			textSize = 18f
			setTypeface(typeface, Typeface.BOLD)
		}
		val endpoint = TextView(activity).apply {
			text = activity.getString(StringsR.string.opencode_local_endpoint, OpenCodeRuntimeController.HEALTH_URL)
		}
		val guidance = TextView(activity).apply {
			text = activity.getString(StringsR.string.opencode_local_install_guidance)
			setPadding(0, spacing, 0, spacing)
		}
		val progress = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
			max = 100
			visibility = View.GONE
		}
		val progressLabel = TextView(activity).apply { visibility = View.GONE }
		val message = TextView(activity).apply { visibility = View.GONE }
		val installButton = Button(activity).apply { text = activity.getString(StringsR.string.opencode_local_install) }
		val startButton = Button(activity).apply { text = activity.getString(StringsR.string.opencode_local_start) }
		val stopButton = Button(activity).apply { text = activity.getString(StringsR.string.opencode_local_stop) }
		val refreshButton = Button(activity).apply { text = activity.getString(StringsR.string.opencode_local_refresh) }
		val chatButton = Button(activity).apply { text = activity.getString(StringsR.string.opencode_chat_open) }

		val content = LinearLayout(activity).apply {
			orientation = LinearLayout.VERTICAL
			setPadding(padding, 0, padding, 0)
			addView(status)
			addView(endpoint, verticalParams(spacing))
			addView(guidance)
			addView(progress, verticalParams(spacing))
			addView(progressLabel)
			addView(message, verticalParams(spacing))
			addView(installButton)
			addView(startButton)
			addView(stopButton)
			addView(refreshButton)
			addView(chatButton)
		}
		val dialog = MaterialAlertDialogBuilder(activity)
			.setTitle(StringsR.string.opencode_local_title)
			.setView(ScrollView(activity).apply { addView(content) })
			.setNegativeButton(android.R.string.cancel, null)
			.setNeutralButton(StringsR.string.opencode_remote_connect, null)
			.create()
		var busy = false

		fun setBusy(label: String) {
			busy = true
			status.text = label
			message.visibility = View.GONE
			progress.visibility = View.VISIBLE
			progress.isIndeterminate = true
			progressLabel.visibility = View.GONE
			installButton.isEnabled = false
			startButton.isEnabled = false
			stopButton.isEnabled = false
			refreshButton.isEnabled = false
			chatButton.isEnabled = false
			dialog.setCancelable(false)
			dialog.setCanceledOnTouchOutside(false)
			dialog.getButton(DialogInterface.BUTTON_NEGATIVE).isEnabled = false
			dialog.getButton(DialogInterface.BUTTON_NEUTRAL).isEnabled = false
		}

		fun render(serverStatus: OpenCodeServerStatus) {
			if (activity.isFinishing || activity.isDestroyed || !dialog.isShowing) return
			busy = false
			dialog.setCancelable(true)
			dialog.setCanceledOnTouchOutside(true)
			dialog.getButton(DialogInterface.BUTTON_NEGATIVE).isEnabled = true
			dialog.getButton(DialogInterface.BUTTON_NEUTRAL).isEnabled = true
			progress.visibility = View.GONE
			progressLabel.visibility = View.GONE
			message.visibility = View.GONE
			when (serverStatus) {
				OpenCodeServerStatus.NotInstalled -> {
					status.text = activity.getString(
						StringsR.string.opencode_local_not_installed,
						OpenCodeRuntimeInstaller.VERSION,
					)
				}
				OpenCodeServerStatus.Stopped -> {
					status.text = activity.getString(
						StringsR.string.opencode_local_stopped,
						OpenCodeRuntimeInstaller.VERSION,
					)
				}
				OpenCodeServerStatus.Starting -> {
					status.text = activity.getString(StringsR.string.opencode_local_starting)
				}
				is OpenCodeServerStatus.Running -> {
					status.text = if (serverStatus.managedByThisApp) {
						activity.getString(StringsR.string.opencode_local_running_managed, serverStatus.version)
					} else {
						activity.getString(StringsR.string.opencode_local_running_unmanaged, serverStatus.version)
					}
				}
				is OpenCodeServerStatus.VersionConflict -> {
					status.text = activity.getString(
						StringsR.string.opencode_local_version_conflict,
						serverStatus.version,
					)
				}
				is OpenCodeServerStatus.Failed -> {
					status.text = activity.getString(StringsR.string.opencode_local_error, serverStatus.message)
				}
			}
			val controls = OpenCodeRuntimeDashboardControls.forStatus(serverStatus, installer.isSupportedDevice)
			installButton.isEnabled = controls.canInstall
			startButton.isEnabled = controls.canStart
			stopButton.isEnabled = controls.canStop
			refreshButton.isEnabled = true
			chatButton.isEnabled = OpenCodeRuntimeDashboardControls.canOpenChat(serverStatus)
		}

		fun inspectStatus() {
			if (busy) return
			setBusy(activity.getString(StringsR.string.opencode_local_checking))
			activity.lifecycleScope.launch {
				try {
					render(controller.status())
				} catch (cancelled: CancellationException) {
					throw cancelled
				} catch (error: Exception) {
					render(OpenCodeServerStatus.Failed(error.message ?: error.javaClass.simpleName))
				}
			}
		}

		fun runServerAction(
			busyLabel: Int,
			action: suspend () -> OpenCodeServerStatus,
		) {
			if (busy) return
			setBusy(activity.getString(busyLabel))
			activity.lifecycleScope.launch {
				try {
					render(action())
				} catch (cancelled: CancellationException) {
					throw cancelled
				} catch (error: Exception) {
					render(OpenCodeServerStatus.Failed(error.message ?: error.javaClass.simpleName))
				}
			}
		}

		fun installRuntime() {
			if (busy) return
			setBusy(activity.getString(StringsR.string.opencode_local_installing))
			activity.lifecycleScope.launch {
				try {
						val installation = controller.install { update ->
						withContext(Dispatchers.Main) {
							if (!dialog.isShowing || activity.isFinishing || activity.isDestroyed) return@withContext
							progress.isIndeterminate = false
						progress.progress = update.percent
						progressLabel.text = activity.getString(
							StringsR.string.opencode_local_download_progress,
							update.assetName.ifBlank { "Runtime files" },
							update.percent,
						)
						progressLabel.visibility = View.VISIBLE
						}
					}
					val currentStatus = controller.status()
					render(currentStatus)
					if (dialog.isShowing && !activity.isFinishing && !activity.isDestroyed) {
						message.text = activity.getString(StringsR.string.opencode_local_installed, installation.version)
						message.visibility = View.VISIBLE
					}
				} catch (cancelled: CancellationException) {
					throw cancelled
				} catch (error: Exception) {
					render(OpenCodeServerStatus.Failed(error.message ?: error.javaClass.simpleName))
				}
			}
		}

		installButton.setOnClickListener { installRuntime() }
		startButton.setOnClickListener {
			runServerAction(StringsR.string.opencode_local_starting) {
				controller.start(activity, projectDirectory)
			}
		}
		stopButton.setOnClickListener {
			runServerAction(StringsR.string.opencode_local_stopping) { controller.stop() }
		}
		refreshButton.setOnClickListener { inspectStatus() }
		chatButton.setOnClickListener { OpenCodeChatDialog.show(activity) }
		dialog.setOnShowListener {
			dialog.getButton(DialogInterface.BUTTON_NEUTRAL).setOnClickListener {
				dialog.dismiss()
				onConnectRemote()
			}
		}
		dialog.show()
		inspectStatus()
	}

	private fun verticalParams(spacing: Int): LinearLayout.LayoutParams =
		LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
			bottomMargin = spacing
		}
}
