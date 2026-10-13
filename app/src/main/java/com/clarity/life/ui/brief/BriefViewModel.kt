package com.clarity.life.ui.brief

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.clarity.life.data.entity.BriefRow
import com.clarity.life.planner.PlannerRepository
import com.clarity.life.writing.BriefWriter
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn

/**
 * Today's morning brief for Talk home and Plan, Today (spec section 8). Only reads; the brief job writes it.
 * His day is checked once a minute, so after the day boundary yesterday's brief is no longer shown.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class BriefViewModel @Inject constructor(
    private val planner: PlannerRepository,
    private val briefs: BriefWriter,
) : ViewModel() {

    private val today = flow {
        while (true) {
            emit(planner.today(System.currentTimeMillis()))
            delay(60_000)
        }
    }.distinctUntilChanged()

    val brief: StateFlow<BriefRow?> = today.flatMapLatest { day ->
        if (briefs.on()) briefs.watch(day) else flowOf(null)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}
