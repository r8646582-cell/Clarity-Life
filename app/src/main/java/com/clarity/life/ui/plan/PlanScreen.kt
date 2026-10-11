package com.clarity.life.ui.plan

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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
        Text(
            "Plan.",
            style = MaterialTheme.typography.displaySmall,
            color = clarity.text,
            modifier = Modifier.padding(top = 10.dp, bottom = 10.dp, start = 4.dp),
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(bottom = 6.dp).horizontalScroll(rememberScrollState()),
        ) {
            SECTIONS.forEach { name ->
                val on = name == section
                // A coral dot on Reviews while a letter or a review is unread.
                Text(
                    androidx.compose.ui.text.buildAnnotatedString {
                        append(name)
                        if (name == "Reviews" && reviewState.unread) {
                            pushStyle(androidx.compose.ui.text.SpanStyle(color = clarity.coral))
                            append(" ●")
                            pop()
                        }
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = if (on) clarity.ground else clarity.text,
                    modifier = Modifier
                        .heightIn(min = 44.dp)
                        .background(if (on) clarity.yellow else clarity.ground, RoundedCornerShape(20.dp))
                        .border(1.dp, if (on) clarity.yellow else clarity.controlOutline, RoundedCornerShape(20.dp))
                        .clickable { section = name }
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                )
            }
        }
        if (section == "Promises") {
            PromisesSection(state, model)
        } else if (section == "Today") {
            TodaySection(planState, planner)
        } else if (section == "Goals") {
            GoalsSection(planState, planner) {
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
