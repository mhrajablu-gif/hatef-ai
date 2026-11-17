package com.example.ui.viewmodel

import com.example.service.ai.ParsedTask
import java.util.ArrayDeque

/**
 * Keeps multi-reminder tasks in order and preserves a task's identity while its
 * missing slots are clarified. A clarification returns a copied ParsedTask, so
 * data-class equality cannot match it to the queued original.
 */
internal class PendingReminderQueue {
    private val tasks = ArrayDeque<ParsedTask>()

    fun addAll(items: Collection<ParsedTask>) = tasks.addAll(items)
    fun clear() = tasks.clear()
    fun isNotEmpty(): Boolean = tasks.isNotEmpty()
    fun firstOrNull(): ParsedTask? = tasks.peekFirst()
    fun removeFirst(): ParsedTask = tasks.removeFirst()

    /** Replace the head only when the clarified copy comes from the same utterance. */
    fun replaceFirst(updated: ParsedTask): Boolean {
        val current = tasks.peekFirst() ?: return false
        if (!sameSource(current, updated)) return false
        tasks.removeFirst()
        tasks.addFirst(updated)
        return true
    }

    fun removeFirstIfSameSource(task: ParsedTask): Boolean {
        val current = tasks.peekFirst() ?: return false
        if (!sameSource(current, task)) return false
        tasks.removeFirst()
        return true
    }

    private fun sameSource(first: ParsedTask, second: ParsedTask): Boolean =
        first.description.isNotBlank() &&
            first.description == second.description &&
            first.rawTimePhrase == second.rawTimePhrase
}
