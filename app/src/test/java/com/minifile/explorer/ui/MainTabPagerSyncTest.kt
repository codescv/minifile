package com.minifile.explorer.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JUnit 4 coverage for [MainTabPagerSync]: tab <-> page mapping, two-way pager / view-model
 * arbitration (no ping-pong, no spurious intermediate selectTab(WIRELESS) on BROWSE <-> TOOLS
 * jumps) and NavigationBar highlight resolution.
 */
class MainTabPagerSyncTest {

    /**
     * Minimal deterministic model of MiniFileRoot's wiring: a fake pager, a fake view model tab
     * and the three effects (VM -> pager, drag interactions, settledPage -> VM).
     */
    private class Harness(initialTab: MainTab = MainTab.BROWSE) {
        var activeTab: MainTab = initialTab
            private set
        var state = MainTabPagerSync.initialState(initialTab)
        var currentPage = MainTabPagerSync.pageOf(initialTab)
        var settledPage = currentPage
        var scrolling = false
        var targetPage = currentPage
        var pagerVisible = true
        val selectTabCalls = mutableListOf<MainTab>()
        val commands = mutableListOf<MainTabPagerCommand>()
        val pagesComposedByProgrammaticScroll = mutableListOf<Int>()

        /** MainViewModel.selectTab / any VM-side tab mutation, followed by the VM -> pager effect. */
        fun vmSetsTab(tab: MainTab, runEffect: Boolean = true) {
            activeTab = tab
            if (runEffect) runActiveTabEffect()
        }

        fun setOverlayVisible(overlayShown: Boolean) {
            pagerVisible = !overlayShown
            runActiveTabEffect()
        }

        fun runActiveTabEffect(completeScroll: Boolean = true) {
            val change = MainTabPagerSync.onActiveTabChanged(state, activeTab, currentPage, scrolling, pagerVisible)
            state = change.state
            commands += change.command
            when (val c = change.command) {
                MainTabPagerCommand.None -> Unit
                is MainTabPagerCommand.AnimateTo -> {
                    // Animation composes every page between current and target.
                    val range = if (c.page >= currentPage) currentPage..c.page else c.page..currentPage
                    pagesComposedByProgrammaticScroll += range.toList()
                    if (completeScroll) finishProgrammatic(c.page)
                }
                is MainTabPagerCommand.SnapTo -> {
                    pagesComposedByProgrammaticScroll += c.page
                    if (completeScroll) finishProgrammatic(c.page)
                }
            }
        }

        private fun finishProgrammatic(page: Int) {
            currentPage = page
            targetPage = page
            scrolling = false
            state = MainTabPagerSync.onProgrammaticScrollFinished(state, page)
            settle(page)
        }

        /** snapshotFlow { isScrollInProgress to settledPage } emission with scrolling == false. */
        fun settle(page: Int) {
            settledPage = page
            val result = MainTabPagerSync.onPageSettled(state, page, activeTab)
            state = result.state
            result.tabToSelect?.let {
                selectTabCalls += it
                vmSetsTab(it)
            }
        }

        fun userDragStart(towardPage: Int) {
            scrolling = true
            state = MainTabPagerSync.onUserDragStarted(state)
            targetPage = towardPage
        }

        fun userRelease(landOn: Int) {
            currentPage = landOn
            targetPage = landOn
            scrolling = false
            settle(landOn)
        }

        fun userSwipe(to: Int) {
            userDragStart(to)
            userRelease(to)
        }

        fun selected(): MainTab = MainTabPagerSync.selectedTab(state, activeTab, scrolling, targetPage, settledPage)
    }

    @Test
    fun mapping_pagesFollowMainTabEntriesOrderAndClamp() {
        assertEquals(3, MainTabPagerSync.pageCount)
        assertEquals(0, MainTabPagerSync.pageOf(MainTab.BROWSE))
        assertEquals(1, MainTabPagerSync.pageOf(MainTab.WIRELESS))
        assertEquals(2, MainTabPagerSync.pageOf(MainTab.TOOLS))
        MainTab.entries.forEach { assertEquals(it, MainTabPagerSync.tabAt(MainTabPagerSync.pageOf(it))) }
        assertEquals(MainTab.BROWSE, MainTabPagerSync.tabAt(-1))
        assertEquals(MainTab.TOOLS, MainTabPagerSync.tabAt(99))
        assertEquals(MainTabPagerSyncState(syncedPage = 2), MainTabPagerSync.initialState(MainTab.TOOLS))
    }

    @Test
    fun userSwipes_commitExactlyOneSelectTabPerSettledGestureWithoutPingPong() {
        val h = Harness()
        h.userSwipe(1)
        assertEquals(listOf(MainTab.WIRELESS), h.selectTabCalls)
        assertEquals(MainTab.WIRELESS, h.activeTab)
        // VM echo did not drive the pager again
        assertEquals(MainTabPagerCommand.None, h.commands.last())

        h.userSwipe(2)
        assertEquals(listOf(MainTab.WIRELESS, MainTab.TOOLS), h.selectTabCalls)
        h.userSwipe(1)
        h.userSwipe(0)
        assertEquals(listOf(MainTab.WIRELESS, MainTab.TOOLS, MainTab.WIRELESS, MainTab.BROWSE), h.selectTabCalls)
        assertTrue(h.commands.all { it == MainTabPagerCommand.None })
        assertNull(h.state.programmaticTarget)
        assertEquals(MainTab.BROWSE, h.selected())
    }

    @Test
    fun abortedSwipe_returningToSamePage_doesNotCallSelectTab() {
        val h = Harness()
        h.userDragStart(1)
        assertEquals(MainTab.WIRELESS, h.selected())
        h.userRelease(0)
        assertTrue(h.selectTabCalls.isEmpty())
        assertEquals(MainTab.BROWSE, h.selected())
    }

    @Test
    fun navigationBarJumpBrowseToTools_snapsWithoutComposingOrSelectingWireless() {
        val h = Harness()
        h.vmSetsTab(MainTab.TOOLS, runEffect = false)
        // First frame after the tap, before the effect ran: indicator already on TOOLS.
        assertEquals(MainTab.TOOLS, h.selected())
        h.runActiveTabEffect()
        assertEquals(listOf<MainTabPagerCommand>(MainTabPagerCommand.SnapTo(2)), h.commands)
        assertEquals(listOf(2), h.pagesComposedByProgrammaticScroll)
        assertTrue(h.selectTabCalls.isEmpty())
        assertEquals(2, h.currentPage)
        assertEquals(MainTab.TOOLS, h.selected())

        // System Back from TOOLS -> BROWSE (handleBack -> selectTab(BROWSE)) also snaps.
        h.vmSetsTab(MainTab.BROWSE)
        assertEquals(MainTabPagerCommand.SnapTo(0), h.commands.last())
        assertFalse(1 in h.pagesComposedByProgrammaticScroll)
        assertTrue(h.selectTabCalls.isEmpty())
        assertEquals(MainTab.BROWSE, h.selected())
    }

    @Test
    fun adjacentProgrammaticTabChange_animates() {
        val h = Harness()
        h.vmSetsTab(MainTab.WIRELESS)
        assertEquals(MainTabPagerCommand.AnimateTo(1), h.commands.last())
        h.vmSetsTab(MainTab.TOOLS)
        assertEquals(MainTabPagerCommand.AnimateTo(2), h.commands.last())
        h.vmSetsTab(MainTab.WIRELESS)
        h.vmSetsTab(MainTab.BROWSE)
        assertEquals(MainTabPagerCommand.AnimateTo(0), h.commands.last())
        assertTrue(h.selectTabCalls.isEmpty())
    }

    @Test
    fun intermediateSettleDuringProgrammaticJump_isIgnored() {
        val h = Harness()
        h.vmSetsTab(MainTab.WIRELESS, runEffect = false)
        h.runActiveTabEffect(completeScroll = false) // animation 0 -> 1 in flight
        h.scrolling = true
        // A second tap re-targets to TOOLS; the cancelled animation briefly settles on page 1.
        h.vmSetsTab(MainTab.TOOLS, runEffect = false)
        assertEquals(MainTab.TOOLS, h.selected())
        h.runActiveTabEffect(completeScroll = false)
        assertEquals(2, h.state.programmaticTarget)
        h.scrolling = false
        h.currentPage = 1
        h.settle(1)
        assertTrue("no spurious selectTab(WIRELESS)", h.selectTabCalls.isEmpty())
        assertEquals(MainTab.TOOLS, h.activeTab)
        assertEquals(MainTab.TOOLS, h.selected())
        // The retried scroll lands on TOOLS and clears the pending target.
        h.currentPage = 2
        h.state = MainTabPagerSync.onProgrammaticScrollFinished(h.state, 2)
        h.settle(2)
        assertNull(h.state.programmaticTarget)
        assertTrue(h.selectTabCalls.isEmpty())
    }

    @Test
    fun userDragDuringProgrammaticJump_takesOverAndCommitsLandingPage() {
        val h = Harness()
        h.vmSetsTab(MainTab.WIRELESS, runEffect = false)
        h.runActiveTabEffect(completeScroll = false)
        assertEquals(1, h.state.programmaticTarget)
        h.userDragStart(0) // finger grabs the pager mid-animation and pulls back
        assertNull(h.state.programmaticTarget)
        assertEquals(MainTab.BROWSE, h.selected())
        h.userRelease(0)
        assertEquals(listOf(MainTab.BROWSE), h.selectTabCalls)
        assertEquals(MainTab.BROWSE, h.activeTab)
    }

    @Test
    fun userDragLandingOnProgrammaticTarget_doesNotDuplicateSelectTab() {
        val h = Harness()
        h.vmSetsTab(MainTab.WIRELESS, runEffect = false)
        h.runActiveTabEffect(completeScroll = false)
        h.userDragStart(1)
        h.userRelease(1)
        assertTrue(h.selectTabCalls.isEmpty())
        assertEquals(MainTab.WIRELESS, h.selected())
    }

    @Test
    fun settledButNotYetCommittedGesture_keepsIndicatorOnNewTab() {
        val state = MainTabPagerSyncState(syncedPage = 0)
        // Pager settled on page 1 this frame; the settledPage collector has not run yet.
        assertEquals(
            MainTab.WIRELESS,
            MainTabPagerSync.selectedTab(state, MainTab.BROWSE, pagerScrolling = false, pagerTargetPage = 1, pagerSettledPage = 1),
        )
        // Mid-swipe the indicator follows the gesture target.
        assertEquals(
            MainTab.TOOLS,
            MainTabPagerSync.selectedTab(
                MainTabPagerSyncState(syncedPage = 1),
                MainTab.WIRELESS,
                pagerScrolling = true,
                pagerTargetPage = 2,
                pagerSettledPage = 1,
            ),
        )
    }

    @Test
    fun tabChangeWhileOverlayShown_snapsHiddenPagerAndOverlayCloseKeepsPage() {
        val h = Harness()
        h.setOverlayVisible(true) // e.g. Favorites overlay
        assertEquals(MainTabPagerCommand.None, h.commands.last())
        // Favorite back navigation restores tab + overlay at once: hidden pager must snap.
        h.vmSetsTab(MainTab.WIRELESS)
        assertEquals(MainTabPagerCommand.SnapTo(1), h.commands.last())
        h.setOverlayVisible(false)
        assertEquals(MainTabPagerCommand.None, h.commands.last())
        assertEquals(1, h.currentPage)
        assertEquals(MainTab.WIRELESS, h.selected())
        assertTrue(h.selectTabCalls.isEmpty())
    }

    @Test
    fun overlayClosedTogetherWithAdjacentTabChange_snapsInsteadOfAnimating() {
        val h = Harness()
        h.setOverlayVisible(true)
        // Snackbar action: closeOverlay() + selectTab(...) in the same frame.
        h.pagerVisible = true
        h.vmSetsTab(MainTab.WIRELESS)
        assertEquals(MainTabPagerCommand.SnapTo(1), h.commands.last())
        // Afterwards, a visible adjacent change animates again.
        h.vmSetsTab(MainTab.BROWSE)
        assertEquals(MainTabPagerCommand.AnimateTo(0), h.commands.last())
    }

    @Test
    fun programmaticChangeWhilePagerAlreadyOnTarget_clearsWithoutCommand() {
        val state = MainTabPagerSyncState(syncedPage = 0)
        val change = MainTabPagerSync.onActiveTabChanged(state, MainTab.WIRELESS, pagerCurrentPage = 1, pagerScrolling = false, pagerVisible = true)
        assertEquals(MainTabPagerCommand.None, change.command)
        assertEquals(MainTabPagerSyncState(syncedPage = 1), change.state)
    }

    @Test
    fun idlePagerDriftedFromSyncedPage_isSnappedBack() {
        val state = MainTabPagerSyncState(syncedPage = 2)
        val change = MainTabPagerSync.onActiveTabChanged(state, MainTab.TOOLS, pagerCurrentPage = 0, pagerScrolling = false, pagerVisible = true)
        assertEquals(MainTabPagerCommand.SnapTo(2), change.command)
        assertEquals(2, change.state.programmaticTarget)
        // While the user is dragging, never fight the gesture.
        val dragging = MainTabPagerSync.onActiveTabChanged(state, MainTab.TOOLS, pagerCurrentPage = 1, pagerScrolling = true, pagerVisible = true)
        assertEquals(MainTabPagerCommand.None, dragging.command)
    }

    @Test
    fun restoredTabAfterProcessDeath_startsSyncedOnRestoredPage() {
        val h = Harness(initialTab = MainTab.TOOLS)
        h.runActiveTabEffect()
        assertEquals(MainTabPagerCommand.None, h.commands.single())
        assertEquals(MainTab.TOOLS, h.selected())
        h.userSwipe(1)
        assertEquals(listOf(MainTab.WIRELESS), h.selectTabCalls)
    }
}
