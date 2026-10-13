package com.clarity.life.ui.plan

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.clarity.life.ui.theme.clarity

private val SECTIONS = listOf("Today", "Goals", "Promises", "Reviews")

/**
 * Plan (spec section 2.2): one tab with sections. Today is the check-in, Goals holds goals, backlogs and
 * targets with their pace, Promises the one-off commitments, Reviews the weekly letters and the reviews.
 */
@Composable
fun PlanScreen(
    onPlanWithCoach: () -> Unit = {},
    onTalk: () -> Unit = onPlanWithCoach,
    initialSection: String = "Today",
    model: PromisesViewModel = hiltViewModel(),
    planner: PlanViewModel = hiltViewModel(),
    reviews: ReviewsViewModel = hiltViewModel(),
) {
    val state by model.state.collectAsStateWithLifecycle()
    val planState by planner.state.collectAsStateWithLifecycle()
    val reviewState by reviews.state.collectAsStateWithLifecycle()
    var section by rememberSaveable { mutableStateOf(initialSection) }
    var openLetter by rememberSaveable { mutableStateOf<Long?>(null) }
    var openReview by rememberSaveable { mutableStateOf<Long?>(null) }
    // The letter card on Talk asks Plan to open a letter.
    val request by reviews.planRequests.collectAsStateWithLifecycle()
    androidx.compose.runtime.LaunchedEffect(request) {
        reviews.takePlanRequest()?.let { r ->
            section = r.section
            openLetter = r.letterId
            openReview = r.reviewId
        }
    }
    Column(
        Modifier
            .fillMaxSize()
            .background(clarity.ground)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        // The four sections as one segmented control (the 0.1.6 redesign); a coral dot on Reviews while unread.
        Row(
            Modifier
                .padding(top = 12.dp, bottom = 6.dp)
                .fillMaxWidth()
                .background(clarity.surface, RoundedCornerShape(16.dp))
                .border(1.dp, clarity.hairline, RoundedCornerShape(16.dp))
                .padding(4.dp),
        ) {
            SECTIONS.forEach { name ->
                val on = name == section
                Row(
                    Modifier
                        .weight(1f)
                        .heightIn(min = 44.dp)
                        .background(if (on) clarity.raised else clarity.surface, RoundedCornerShape(12.dp))
                        .clickable { section = name },
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        name,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (on) clarity.text else clarity.muted,
                    )
                    if (name == "Reviews" && reviewState.unread) {
                        Box(Modifier.padding(start = 6.dp).size(6.dp).background(clarity.coral, CircleShape))
                    }
                }
            }
        }
        if (section == "Promises") {
            PromisesSection(state, model)
        } else if (section == "Today") {
            TodaySection(planState, planner)
        } else if (section == "Goals") {
            GoalsSection(
                planState,
                planner,
                onToday = { section = "Today" },
                onFix = { draft ->
                    planner.planWithCoach(draft)
                    onPlanWithCoach()
                },
            ) {
                planner.planWithCoach()
                onPlanWithCoach()
            }
        } else {
            ReviewsSection(
                reviewState,
                reviews,
                openLetterId = openLetter,
                openReviewId = openReview,
                onOpenLetter = {
                    openLetter = it
                    openReview = null
                },
                onOpenReview = { openReview = it },
                onTalk = onTalk,
            )
        }
    }
}
