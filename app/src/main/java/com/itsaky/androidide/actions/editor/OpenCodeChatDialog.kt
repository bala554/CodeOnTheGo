/*
 *  This file is part of AndroidIDE.
 *
 *  AndroidIDE is free software; you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 */
package com.itsaky.androidide.actions.editor

import android.content.DialogInterface
import android.graphics.Typeface
import android.text.InputType
import android.view.View
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.itsaky.androidide.activities.editor.EditorHandlerActivity
import com.itsaky.androidide.resources.R as StringsR
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal object OpenCodeChatDialog {
	private data class QuestionDraft(
		val selected: MutableList<MutableSet<String>>,
		val custom: MutableList<String>,
	)

	fun show(activity: EditorHandlerActivity) {
		val client = OpenCodeChatClient()
		val padding = (16 * activity.resources.displayMetrics.density).toInt()
		val spacing = (8 * activity.resources.displayMetrics.density).toInt()
		val sessions = mutableListOf<OpenCodeChatSession>()
		val sessionAdapter = ArrayAdapter<String>(activity, android.R.layout.simple_spinner_item).apply {
			setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
		}
		val sessionPicker = Spinner(activity).apply { adapter = sessionAdapter }
		val newSessionButton = Button(activity).apply { text = activity.getString(StringsR.string.opencode_chat_new_session) }
		val modeGroup = RadioGroup(activity).apply { orientation = RadioGroup.VERTICAL }
		val planMode = RadioButton(activity).apply { text = activity.getString(StringsR.string.opencode_chat_planning) }
		val buildMode = RadioButton(activity).apply { text = activity.getString(StringsR.string.opencode_chat_build) }
		modeGroup.addView(planMode)
		modeGroup.addView(buildMode)
		planMode.isChecked = true

		val status = TextView(activity).apply {
			text = activity.getString(StringsR.string.opencode_chat_connecting)
			textSize = 12f
		}
		val sessionRow = LinearLayout(activity).apply {
			orientation = LinearLayout.VERTICAL
			addView(sessionPicker, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
			addView(newSessionButton, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
		}
		val messages = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
		val questionPanel = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
		val conversation = LinearLayout(activity).apply {
			orientation = LinearLayout.VERTICAL
			addView(sessionRow)
			addView(modeGroup, verticalParams(spacing))
			addView(status, verticalParams(spacing))
			addView(messages)
			addView(questionPanel, verticalParams(spacing))
		}
		val conversationScroll = ScrollView(activity).apply {
			isFillViewport = true
			addView(conversation)
		}
		val composer = EditText(activity).apply {
			hint = activity.getString(StringsR.string.opencode_chat_message_hint)
			inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
			minLines = 2
			maxLines = 5
			setPadding(spacing, spacing, spacing, spacing)
		}
		val sendButton = Button(activity).apply { text = activity.getString(StringsR.string.opencode_chat_send) }
		val composerRow = LinearLayout(activity).apply {
			orientation = LinearLayout.HORIZONTAL
			addView(
				composer,
				LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = spacing },
			)
			addView(sendButton)
		}
		val content = LinearLayout(activity).apply {
			orientation = LinearLayout.VERTICAL
			setPadding(padding, 0, padding, padding)
			addView(conversationScroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
			addView(composerRow, verticalParams(spacing))
		}

		val dialog = MaterialAlertDialogBuilder(activity)
			.setTitle(StringsR.string.opencode_chat_title)
			.setView(content)
			.setNegativeButton(android.R.string.cancel, null)
			.create()

		var selectedSession: OpenCodeChatSession? = null
		var activeQuestion: OpenCodeQuestionRequest? = null
		var sending = false
		var answering = false
		var suppressSelection = false
		var pollJob: Job? = null
		val drafts = mutableMapOf<String, QuestionDraft>()
		val uiScope = CoroutineScope(
			activity.lifecycleScope.coroutineContext + SupervisorJob(activity.lifecycleScope.coroutineContext[Job]),
		)

		fun setStatus(text: String) {
			if (dialog.isShowing && !activity.isFinishing && !activity.isDestroyed) status.text = text
		}

		fun setQuestionButtonsEnabled(enabled: Boolean) {
			for (index in 0 until questionPanel.childCount) {
				val child = questionPanel.getChildAt(index)
				if (child is Button) child.isEnabled = enabled
				if (child is LinearLayout) {
					for (inner in 0 until child.childCount) {
						(child.getChildAt(inner) as? Button)?.isEnabled = enabled
					}
				}
			}
		}

		fun scrollToBottom() {
			conversationScroll.post { conversationScroll.fullScroll(View.FOCUS_DOWN) }
		}

		fun renderMessages(items: List<OpenCodeChatMessage>) {
			val scrollHeight = conversationScroll.getChildAt(0)?.height ?: 0
			val wasNearBottom = scrollHeight - (conversationScroll.height + conversationScroll.scrollY) <= spacing * 2
			messages.removeAllViews()
			if (items.isEmpty()) {
				messages.addView(TextView(activity).apply {
					text = activity.getString(StringsR.string.opencode_chat_empty)
					setPadding(0, padding, 0, padding)
				})
			} else {
				items.forEach { item ->
					val label = if (item.role == "user") {
						activity.getString(StringsR.string.opencode_chat_you)
					} else {
						activity.getString(StringsR.string.opencode_chat_assistant)
					}
					messages.addView(
						TextView(activity).apply {
							text = "$label\n${item.text}"
							setPadding(spacing, spacing, spacing, spacing)
							setTypeface(typeface, Typeface.NORMAL)
						},
						verticalParams(spacing),
					)
				}
			}
			if (wasNearBottom) scrollToBottom()
		}

		fun renderQuestion(request: OpenCodeQuestionRequest?) {
			questionPanel.removeAllViews()
			activeQuestion = request
			if (request == null) return

			val draft = drafts.getOrPut(request.id) {
				QuestionDraft(
					selected = request.questions.map { mutableSetOf<String>() }.toMutableList(),
					custom = request.questions.map { "" }.toMutableList(),
				)
			}
			questionPanel.addView(TextView(activity).apply {
				text = activity.getString(StringsR.string.opencode_chat_question_title)
				setTypeface(typeface, Typeface.BOLD)
			}, verticalParams(spacing))

			request.questions.forEachIndexed { questionIndex, question ->
				if (question.header.isNotBlank()) {
					questionPanel.addView(TextView(activity).apply {
						text = question.header
						setTypeface(typeface, Typeface.BOLD)
					})
				}
				questionPanel.addView(TextView(activity).apply {
					text = question.text
					setPadding(0, spacing / 2, 0, spacing / 2)
				})

				if (question.multiple) {
					question.options.forEach { option ->
						val choice = CheckBox(activity).apply {
							text = optionLabel(option.label, option.description)
							isChecked = option.label in draft.selected[questionIndex]
							setOnCheckedChangeListener { _, checked ->
								if (checked) draft.selected[questionIndex].add(option.label)
								else draft.selected[questionIndex].remove(option.label)
							}
						}
						questionPanel.addView(choice)
					}
				} else {
					val group = RadioGroup(activity).apply { orientation = RadioGroup.VERTICAL }
					val radioByLabel = mutableMapOf<String, RadioButton>()
					question.options.forEach { option ->
						val choice = RadioButton(activity).apply {
							id = View.generateViewId()
							text = optionLabel(option.label, option.description)
						}
						radioByLabel[option.label] = choice
						group.addView(choice)
					}
					draft.selected[questionIndex].firstOrNull()?.let { radioByLabel[it]?.isChecked = true }
					group.setOnCheckedChangeListener { checkedGroup, checkedId ->
						val selected = checkedGroup.findViewById<RadioButton>(checkedId)?.text?.toString()
						val label = question.options.firstOrNull { optionLabel(it.label, it.description) == selected }?.label
						draft.selected[questionIndex].clear()
						if (label != null) draft.selected[questionIndex].add(label)
					}
					questionPanel.addView(group)
				}

				val customAnswer = EditText(activity).apply {
					hint = activity.getString(StringsR.string.opencode_chat_other_answer)
					inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
					setSingleLine(true)
					setText(draft.custom[questionIndex])
					addTextChangedListener(object : android.text.TextWatcher {
						override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
						override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
							draft.custom[questionIndex] = s?.toString().orEmpty()
						}
						override fun afterTextChanged(s: android.text.Editable?) = Unit
					})
				}
				questionPanel.addView(customAnswer, verticalParams(spacing))
			}

			val actionRow = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
			val rejectButton = Button(activity).apply {
				text = activity.getString(StringsR.string.opencode_chat_dismiss_question)
				setOnClickListener {
					if (answering) return@setOnClickListener
					answering = true
					setQuestionButtonsEnabled(false)
					uiScope.launch {
						try {
							client.rejectQuestion(request)
							drafts.remove(request.id)
							renderQuestion(null)
							setStatus(activity.getString(StringsR.string.opencode_chat_ready))
						} catch (cancelled: CancellationException) {
							throw cancelled
						} catch (error: Exception) {
							setStatus(error.message ?: error.javaClass.simpleName)
						} finally {
							answering = false
							setQuestionButtonsEnabled(true)
						}
					}
				}
			}
			val answerButton = Button(activity).apply {
				text = activity.getString(StringsR.string.opencode_chat_submit_answer)
				setOnClickListener {
					if (answering) return@setOnClickListener
					val answers = request.questions.mapIndexed { index, prompt ->
						val custom = draft.custom[index].trim()
						val selected = draft.selected[index].toList()
						when {
							custom.isNotBlank() && !prompt.multiple -> listOf(custom)
							custom.isNotBlank() -> selected + custom
							else -> selected
						}
					}
					if (answers.any { it.isEmpty() }) {
						setStatus(activity.getString(StringsR.string.opencode_chat_answer_required))
						return@setOnClickListener
					}
					answering = true
					setQuestionButtonsEnabled(false)
					uiScope.launch {
						try {
							client.replyToQuestion(request, answers)
							drafts.remove(request.id)
							renderQuestion(null)
							setStatus(activity.getString(StringsR.string.opencode_chat_ready))
						} catch (cancelled: CancellationException) {
							throw cancelled
						} catch (error: Exception) {
							setStatus(error.message ?: error.javaClass.simpleName)
						} finally {
							answering = false
							setQuestionButtonsEnabled(true)
						}
					}
				}
			}
			actionRow.addView(rejectButton, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
			actionRow.addView(answerButton, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
			questionPanel.addView(actionRow)
			scrollToBottom()
		}

		suspend fun refreshQuestion(session: OpenCodeChatSession) {
			try {
				val next = client.listQuestions(session.id).firstOrNull()
				if (selectedSession?.id == session.id && next?.id != activeQuestion?.id) renderQuestion(next)
			} catch (cancelled: CancellationException) {
				throw cancelled
			} catch (error: Exception) {
				if (selectedSession?.id == session.id) setStatus(error.message ?: error.javaClass.simpleName)
			}
		}

		fun loadConversation(session: OpenCodeChatSession) {
			setStatus(activity.getString(StringsR.string.opencode_chat_loading))
			activeQuestion = null
			questionPanel.removeAllViews()
			uiScope.launch {
				try {
					val items = client.listMessages(session.id)
					if (selectedSession?.id == session.id) {
						renderMessages(items)
						setStatus(activity.getString(StringsR.string.opencode_chat_ready))
					}
				} catch (cancelled: CancellationException) {
					throw cancelled
				} catch (error: Exception) {
					if (selectedSession?.id == session.id) {
						renderMessages(emptyList())
						setStatus(error.message ?: error.javaClass.simpleName)
					}
				}
				if (selectedSession?.id == session.id) refreshQuestion(session)
			}
		}

		fun replaceSessions(items: List<OpenCodeChatSession>, wantedId: String? = selectedSession?.id) {
			sessions.clear()
			sessions.addAll(items)
			val priorId = selectedSession?.id
			val selectedIndex = sessions.indexOfFirst { it.id == wantedId }.takeIf { it >= 0 }
				?: sessions.indexOfFirst { it.id == priorId }.takeIf { it >= 0 }
				?: 0
			suppressSelection = true
			sessionAdapter.clear()
			if (sessions.isEmpty()) {
				sessionAdapter.add(activity.getString(StringsR.string.opencode_chat_no_sessions))
				sessionPicker.isEnabled = false
				selectedSession = null
			} else {
				sessionPicker.isEnabled = true
				sessionAdapter.addAll(sessions.map { it.title })
				selectedSession = sessions[selectedIndex]
				sessionPicker.setSelection(selectedIndex, false)
			}
			suppressSelection = false
			selectedSession?.let { session ->
				if (session.id != priorId || priorId == null) loadConversation(session)
			}
		}

		sessionPicker.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
			override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
			override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
				if (suppressSelection) return
				val session = sessions.getOrNull(position) ?: return
				if (session.id == selectedSession?.id) return
				selectedSession = session
				loadConversation(session)
			}
		}

		newSessionButton.setOnClickListener {
			newSessionButton.isEnabled = false
			uiScope.launch {
				try {
					val created = client.createSession()
					val updated = client.listSessions()
					replaceSessions(updated, created.id)
					composer.requestFocus()
				} catch (cancelled: CancellationException) {
					throw cancelled
				} catch (error: Exception) {
					setStatus(error.message ?: error.javaClass.simpleName)
				} finally {
					newSessionButton.isEnabled = true
				}
			}
		}

		sendButton.setOnClickListener {
			val session = selectedSession ?: run {
				setStatus(activity.getString(StringsR.string.opencode_chat_create_first))
				return@setOnClickListener
			}
			val text = composer.text.toString().trim()
			if (text.isEmpty() || sending) return@setOnClickListener
			val agent = if (planMode.isChecked) "plan" else "build"
			composer.text.clear()
			sending = true
			sendButton.isEnabled = false
			setStatus(activity.getString(StringsR.string.opencode_chat_sending))
			uiScope.launch {
				try {
					val items = client.sendMessage(session.id, text, agent)
					if (selectedSession?.id == session.id) {
						renderMessages(items)
						setStatus(activity.getString(StringsR.string.opencode_chat_ready))
					}
					val updated = client.listSessions()
					if (selectedSession?.id == session.id) replaceSessions(updated, session.id)
				} catch (cancelled: CancellationException) {
					throw cancelled
				} catch (error: Exception) {
					if (selectedSession?.id == session.id) {
						setStatus(error.message ?: error.javaClass.simpleName)
						if (composer.text.isNullOrBlank()) composer.setText(text)
						runCatching { renderMessages(client.listMessages(session.id)) }
					}
				} finally {
					sending = false
					sendButton.isEnabled = true
				}
			}
		}

		dialog.setOnShowListener {
			dialog.window?.setLayout(
				LinearLayout.LayoutParams.MATCH_PARENT,
				LinearLayout.LayoutParams.MATCH_PARENT,
			)
			dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
			dialog.getButton(DialogInterface.BUTTON_NEGATIVE).text = activity.getString(StringsR.string.opencode_chat_close)
			pollJob = uiScope.launch {
				try {
					val recentSessions = client.listSessions()
					replaceSessions(recentSessions)
					if (recentSessions.isEmpty()) setStatus(activity.getString(StringsR.string.opencode_chat_ready))
					var count = 0
					while (dialog.isShowing && !activity.isFinishing && !activity.isDestroyed) {
						val session = selectedSession
						if (session != null) {
							if (sending) {
								runCatching {
									val items = client.listMessages(session.id)
									if (selectedSession?.id == session.id) renderMessages(items)
								}
							}
							refreshQuestion(session)
						}
						count++
						if (count % 8 == 0) {
							val currentId = selectedSession?.id
							runCatching { replaceSessions(client.listSessions(), currentId) }
						}
						delay(POLL_INTERVAL_MS)
					}
				} catch (cancelled: CancellationException) {
					throw cancelled
				} catch (error: Exception) {
					setStatus(error.message ?: error.javaClass.simpleName)
				}
			}
		}
		dialog.setOnDismissListener {
			pollJob?.cancel()
			uiScope.coroutineContext[Job]?.cancel()
		}
		dialog.show()
	}

	private fun optionLabel(label: String, description: String): String =
		if (description.isBlank()) label else "$label — $description"

	private fun verticalParams(spacing: Int): LinearLayout.LayoutParams =
		LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
			bottomMargin = spacing
		}

	private const val POLL_INTERVAL_MS = 1_500L
}
