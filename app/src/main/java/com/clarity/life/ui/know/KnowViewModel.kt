package com.clarity.life.ui.know

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.clarity.life.core.KnowText
import com.clarity.life.data.entity.ChapterRow
import com.clarity.life.data.entity.FocusRow
import com.clarity.life.data.entity.NoteEvidenceRow
import com.clarity.life.data.entity.NoteRow
import com.clarity.life.data.entity.PersonRow
import com.clarity.life.data.entity.ProfileEntryRow
import com.clarity.life.memory.MemoryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * What I know (spec section 2.3, docs/mockups/Know.dc.html): everything the app has learned about him,
 * each item with its evidence, grade and seen count, and his three powers over it: edit, delete, confirm.
 * It never writes itself; every tap is a command on [MemoryRepository].
 */
@HiltViewModel
class KnowViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val memory: MemoryRepository,
    private val requests: com.clarity.life.chat.TalkRequests,
) : ViewModel() {

    /** The Focus page that is open, read when he opens it. */
    private val focusDetail = MutableStateFlow<MemoryRepository.FocusDetail?>(null)
    val focus: StateFlow<MemoryRepository.FocusDetail?> get() = focusDetail

    fun openFocus(id: Long) = viewModelScope.launch { focusDetail.value = memory.focusDetail(id) }

    fun closeFocus() {
        focusDetail.value = null
    }

    /** Talk about this: a new conversation inside the focus, in Talk. */
    fun talkAboutFocus(id: Long) = requests.startConversation("free", focusId = id)

    sealed class Ref {
        data class Note(val id: Long) : Ref()
        data class Profile(val key: String) : Ref()
        data class Person(val id: Long) : Ref()
        data class Focus(val id: Long) : Ref()
        data class Chapter(val id: Long) : Ref()
    }

    data class Item(
        val ref: Ref,
        /** A small label above the text: a profile key, a person's name, "What you are good at" ... */
        val label: String?,
        val text: String,
        /** Confirmed by you / Likely / Guess, or null where there is no grade (people, chapters). */
        val grade: String?,
        val confidence: String?,
        val seen: String?,
        val evidence: List<String>,
        val canEdit: Boolean,
        val canDelete: Boolean,
        val canConfirm: Boolean,
        /** What the search looks through. */
        val haystack: String,
        /** The values the edit dialog starts from: the text, or a person's relation and notes. */
        val editValues: List<String> = listOf(text),
        /** A focus's status buttons: the words on the button and the status it sets. */
        val statusActions: List<Pair<String, String>> = emptyList(),
    )

    data class Section(val name: String, val items: List<Item>)

    data class State(
        val sections: List<Section> = emptyList(),
        val archive: List<Item> = emptyList(),
        val query: String = "",
        val quietLine: String? = null,
        val loaded: Boolean = false,
    )

    private val query = MutableStateFlow("")
    private val quietLine = MutableStateFlow<String?>(null)

    private data class Memory(
        val notes: List<NoteRow>,
        val evidence: List<NoteEvidenceRow>,
        val profile: List<ProfileEntryRow>,
        val people: List<PersonRow>,
        val focuses: List<FocusRow>,
    )

    private val memoryFlow = combine(
        memory.watchNotes(),
        memory.watchEvidence(),
        memory.watchProfile(),
        memory.watchPeople(),
        memory.watchFocuses(),
    ) { notes, evidence, profile, people, focuses -> Memory(notes, evidence, profile, people, focuses) }

    val state: StateFlow<State> = combine(memoryFlow, memory.watchChapters(), query, quietLine) { m, chapters, q, line ->
        build(m, chapters, q, line)
    }.flowOn(kotlinx.coroutines.Dispatchers.Default) // rebuilt on every keystroke: never on the main thread
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), State())

    fun search(text: String) {
        query.value = text
    }

    fun confirm(ref: Ref) = act {
        when (ref) {
            is Ref.Note -> memory.confirmNote(ref.id)
            is Ref.Profile -> memory.confirmProfile(ref.key)
            else -> Unit
        }
    }

    fun edit(ref: Ref, text: String, second: String = "") = act {
        val now = System.currentTimeMillis()
        when (ref) {
            is Ref.Note -> memory.editNote(ref.id, text)
            is Ref.Profile -> memory.editProfile(ref.key, text, now)
            is Ref.Person -> memory.editPerson(ref.id, text, second, now)
            is Ref.Focus -> memory.editFocusSummary(ref.id, text, now)
            else -> Unit
        }
    }

    /** Pause, Done or Make active, on a focus. */
    fun setStatus(ref: Ref, status: String) = act {
        if (ref is Ref.Focus) memory.setFocusStatus(ref.id, status, System.currentTimeMillis())
    }

    fun delete(ref: Ref) = act {
        when (ref) {
            is Ref.Note -> memory.deleteNote(ref.id)
            is Ref.Profile -> memory.deleteProfile(ref.key)
            is Ref.Person -> memory.deletePerson(ref.id)
            else -> Unit
        }
    }

    /** Runs a tap's command; a refusal or an error becomes one quiet line, never a closed app. */
    private fun act(block: suspend () -> Unit) {
        quietLine.value = null
        viewModelScope.launch {
            try {
                block()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: IllegalArgumentException) {
                quietLine.value = (e.message ?: "that could not be saved").replaceFirstChar { it.uppercase() } + "."
            } catch (e: Throwable) {
                com.clarity.life.CrashLog.record(context, e)
                quietLine.value = "That did not save (${e.javaClass.simpleName}). Details are in Settings, at the bottom."
            }
        }
    }

    // ---------- building the sections ----------

    private fun build(m: Memory, chapters: List<ChapterRow>, q: String, line: String?): State {
        val evidence = m.evidence.groupBy { it.noteId }
        fun noteItem(n: NoteRow, label: String? = null): Item {
            val ev = (evidence[n.id] ?: emptyList()).map { KnowText.evidence(it.kind, it.text, it.day) }
            val active = n.status == "active"
            return Item(
                ref = Ref.Note(n.id),
                label = label,
                text = n.text,
                grade = KnowText.grade(n.confidence),
                confidence = n.confidence,
                seen = KnowText.seen(n.timesSeen, n.firstSeen, n.lastSeen),
                evidence = ev,
                canEdit = active,
                canDelete = true,
                canConfirm = active && n.confidence != "confirmed",
                haystack = (listOf(label.orEmpty(), n.text) + ev).joinToString("\n"),
            )
        }

        // Confirmed first, then likely, then guess; within a grade, the most recently seen first.
        val order = compareByDescending<NoteRow> { listOf("guess", "likely", "confirmed").indexOf(it.confidence) }
            .thenByDescending { it.lastSeen }
            .thenByDescending { it.id }
        val active = m.notes.filter { it.status == "active" }.sortedWith(order)
        fun kind(vararg kinds: String) = active.filter { it.kind in kinds }

        val profile = m.profile.map { e ->
            Item(
                ref = Ref.Profile(e.key),
                label = e.key,
                text = e.value,
                grade = if (e.confirmed == 1) KnowText.grade("confirmed") else KnowText.grade("guess"),
                confidence = if (e.confirmed == 1) "confirmed" else "guess",
                seen = null,
                evidence = emptyList(),
                canEdit = true,
                canDelete = true,
                canConfirm = e.confirmed == 0,
                haystack = e.key + "\n" + e.value,
            )
        } + kind("value").map { noteItem(it, "A value of yours") }

        val people = m.people.map { p ->
            val text = listOf(p.relation, p.notes).filter { it.isNotBlank() }.joinToString(". ")
            Item(
                ref = Ref.Person(p.id),
                label = p.name,
                text = text.ifBlank { "Nothing noted yet." },
                grade = null,
                confidence = null,
                seen = null,
                evidence = emptyList(),
                canEdit = true,
                canDelete = true,
                canConfirm = false,
                haystack = listOf(p.name, p.relation, p.notes).joinToString("\n"),
                editValues = listOf(p.relation, p.notes),
            )
        }

        val focuses = m.focuses.map { f ->
            Item(
                ref = Ref.Focus(f.id),
                label = f.name + if (f.status != "active") " (${f.status})" else "",
                text = f.summary.ifBlank { "No summary yet. It is written after the first conversation in this focus." },
                grade = null,
                confidence = null,
                seen = null,
                evidence = listOf("Why: ${f.why}"),
                // Editable (spec section 3); never deleted, so the timeline of its conversations stays.
                canEdit = true,
                canDelete = false,
                canConfirm = false,
                haystack = listOf(f.name, f.why, f.summary).joinToString("\n"),
                editValues = listOf(f.summary),
                statusActions = when (f.status) {
                    "active" -> listOf("Pause" to "paused", "Done" to "done")
                    else -> listOf("Make active" to "active")
                },
            )
        }

        val chapterItems = chapters.map { c ->
            Item(
                ref = Ref.Chapter(c.id),
                label = "${KnowText.shortDay(c.periodStart)} to ${KnowText.shortDay(c.periodEnd)}: ${c.title}",
                text = c.body,
                grade = null,
                confidence = null,
                seen = null,
                evidence = emptyList(),
                canEdit = false,
                canDelete = false,
                canConfirm = false,
                haystack = c.title + "\n" + c.body,
            )
        }

        val sections = listOf(
            Section("Profile", profile),
            Section("Patterns", kind("pattern").map { noteItem(it) }),
            // Strengths are shown under What helps, as "What you are good at" (spec section 10).
            Section(
                "What helps",
                kind("what_helps", "strength").map { noteItem(it, if (it.kind == "strength") "What you are good at" else null) },
            ),
            Section("What does not help", kind("what_doesnt").map { noteItem(it) }),
            Section("Open threads", kind("thread").map { noteItem(it) }),
            Section("People", people),
            Section("Focuses", focuses),
            Section("Chapters", chapterItems),
        )
        // The archive: retired notes, still searchable. Notes he deleted are never shown again.
        val archive = m.notes.filter { it.status == "retired" }.sortedByDescending { it.lastSeen }.map { noteItem(it) }

        val needle = q.trim().lowercase()
        fun matches(i: Item) = needle.isEmpty() || i.haystack.lowercase().contains(needle)
        return State(
            sections = sections.map { s -> s.copy(items = s.items.filter(::matches)) },
            archive = archive.filter(::matches),
            query = q,
            quietLine = line,
            loaded = true,
        )
    }
}
