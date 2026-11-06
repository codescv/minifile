package com.minifile.explorer.ui

import kotlin.math.abs

/**
 * Immutable arbitration state shared by the main-screen `HorizontalPager` and
 * [MainViewModel.activeTab].
 *
 * @property syncedPage the page index both the pager and the view model currently agree on.
 * @property programmaticTarget the page the pager is being driven to by a view-model initiated
 *   tab change (NavigationBar tap, Snackbar action, `open_tab` intent, system Back, Favorite back
 *   navigation, ...). While non-null, intermediate settled pages are ignored so a `0 -> 2` jump can
 *   never echo a spurious `selectTab(WIRELESS)` back into the view model.
 * @property lastPagerVisible whether the pager was on screen (no overlay) at the previous sync.
 *   A pager that is (or just was) hidden behind an overlay is always snapped, never animated.
 */
data class MainTabPagerSyncState(
    val syncedPage: Int,
    val programmaticTarget: Int? = null,
    val lastPagerVisible: Boolean = true,
)

/** What the Compose layer must do with the pager after a view-model tab change. */
sealed interface MainTabPagerCommand {
    /** Pager is already where it must be. */
    data object None : MainTabPagerCommand

    /** Smoothly animate to an adjacent page. */
    data class AnimateTo(val page: Int) : MainTabPagerCommand

    /**
     * Jump instantly: used for multi-page jumps (`BROWSE <-> TOOLS`) so the intermediate
     * `WIRELESS` page is never composed (it starts peer discovery) nor flashed on screen, and for
     * pagers hidden behind an overlay.
     */
    data class SnapTo(val page: Int) : MainTabPagerCommand
}

data class MainTabActiveTabChange(
    val state: MainTabPagerSyncState,
    val command: MainTabPagerCommand,
)

data class MainTabPageSettle(
    val state: MainTabPagerSyncState,
    /** Tab to push into [MainViewModel.selectTab], or null when nothing must be committed. */
    val tabToSelect: MainTab?,
)

/**
 * Pure-Kotlin (no Compose / android.* dependencies) tab <-> page mapping and two-way sync
 * arbitration between the main-screen `HorizontalPager` and [MainViewModel.activeTab].
 *
 * Rules:
 * 1. The view model is the source of truth. A view-model change that the arbiter has not produced
 *    itself becomes a [MainTabPagerSyncState.programmaticTarget] and drives the pager.
 * 2. A settled page is committed to the view model only when it comes from a user gesture
 *    (no programmatic target pending) and differs from the synced page; this kills ping-pong loops.
 * 3. A user drag cancels any pending programmatic target: the gesture wins.
 */
object MainTabPagerSync {

    val pageCount: Int get() = MainTab.entries.size

    fun pageOf(tab: MainTab): Int = tab.ordinal

    fun tabAt(page: Int): MainTab = MainTab.entries[page.coerceIn(0, MainTab.entries.lastIndex)]

    fun initialState(tab: MainTab): MainTabPagerSyncState = MainTabPagerSyncState(syncedPage = pageOf(tab))

    /**
     * Called whenever [MainViewModel.activeTab] (or pager visibility) changes.
     *
     * @param pagerCurrentPage `PagerState.currentPage`.
     * @param pagerScrolling `PagerState.isScrollInProgress`.
     * @param pagerVisible true when the pager is composed on screen (`overlay == null`).
     */
    fun onActiveTabChanged(
        state: MainTabPagerSyncState,
        activeTab: MainTab,
        pagerCurrentPage: Int,
        pagerScrolling: Boolean,
        pagerVisible: Boolean,
    ): MainTabActiveTabChange {
        val target = pageOf(activeTab)
        val canAnimate = pagerVisible && state.lastPagerVisible

        if (target == state.syncedPage && state.programmaticTarget == null) {
            // Echo of a committed swipe (or a mere visibility change): nothing to drive, unless an
            // idle pager somehow drifted away from the agreed page.
            val drifted = !pagerScrolling && pagerCurrentPage != target
            return MainTabActiveTabChange(
                state = state.copy(
                    programmaticTarget = if (drifted) target else null,
                    lastPagerVisible = pagerVisible,
                ),
                command = if (drifted) MainTabPagerCommand.SnapTo(target) else MainTabPagerCommand.None,
            )
        }

        if (!pagerScrolling && pagerCurrentPage == target) {
            return MainTabActiveTabChange(
                state = MainTabPagerSyncState(syncedPage = target, programmaticTarget = null, lastPagerVisible = pagerVisible),
                command = MainTabPagerCommand.None,
            )
        }

        val command = if (canAnimate && abs(pagerCurrentPage - target) <= 1) {
            MainTabPagerCommand.AnimateTo(target)
        } else {
            MainTabPagerCommand.SnapTo(target)
        }
        return MainTabActiveTabChange(
            state = MainTabPagerSyncState(syncedPage = target, programmaticTarget = target, lastPagerVisible = pagerVisible),
            command = command,
        )
    }

    /** A programmatic scroll/snap to [page] completed normally. */
    fun onProgrammaticScrollFinished(state: MainTabPagerSyncState, page: Int): MainTabPagerSyncState =
        if (state.programmaticTarget == page) state.copy(programmaticTarget = null) else state

    /** The user started dragging the pager: the gesture takes over any pending programmatic jump. */
    fun onUserDragStarted(state: MainTabPagerSyncState): MainTabPagerSyncState =
        if (state.programmaticTarget == null) state else state.copy(programmaticTarget = null)

    /**
     * A pager scroll ended (`isScrollInProgress == false`) on [settledPage]. Must be called at every
     * scroll end, not only when `settledPage` changes (see the interrupted-animation case).
     */
    fun onPageSettled(
        state: MainTabPagerSyncState,
        settledPage: Int,
        activeTab: MainTab,
    ): MainTabPageSettle {
        val pending = state.programmaticTarget
        if (pending != null) {
            // Intermediate settles while a programmatic jump is in flight are ignored.
            val next = if (settledPage == pending) state.copy(programmaticTarget = null) else state
            return MainTabPageSettle(next, tabToSelect = null)
        }
        if (settledPage == state.syncedPage) return MainTabPageSettle(state, tabToSelect = null)
        val tab = tabAt(settledPage)
        return MainTabPageSettle(
            state = state.copy(syncedPage = pageOf(tab)),
            tabToSelect = tab.takeIf { it != activeTab },
        )
    }

    /**
     * Tab highlighted in the NavigationBar.
     * - View-model initiated change (not yet acknowledged, or in flight): the target tab, at once,
     *   so a `0 -> 2` jump never highlights `WIRELESS` in between.
     * - User swipe in flight: the page the gesture will land on (`PagerState.targetPage`), so the
     *   indicator moves as soon as the swipe passes the threshold.
     * - Gesture just settled but not committed yet (one frame): the settled page, so the
     *   indicator never flicks back to the old tab.
     */
    fun selectedTab(
        state: MainTabPagerSyncState,
        activeTab: MainTab,
        pagerScrolling: Boolean,
        pagerTargetPage: Int,
        pagerSettledPage: Int,
    ): MainTab {
        if (pageOf(activeTab) != state.syncedPage || state.programmaticTarget != null) return activeTab
        if (pagerScrolling) return tabAt(pagerTargetPage)
        return tabAt(pagerSettledPage)
    }
}
