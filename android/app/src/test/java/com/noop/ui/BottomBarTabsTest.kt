package com.noop.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Navigation destinations remain unique, labeled, and disjoint from the More menu. */
class BottomBarTabsTest {

    private val barTabs = barLeadingTabs + barTrailingTabs

    /** The bar and the More sheet must be disjoint, which is the invariant the drawer's own note claims. */
    @Test
    fun noBarTabIsAlsoListedInTheMoreSheet() {
        val inDrawer = drawerGroups.flatMap { it.items }.toSet()
        val both = barTabs.map { it.dest }.filter { it in inDrawer }
        assertTrue("a bar tab must not also appear in the More sheet, found $both", both.isEmpty())
    }

    /** Oura is a primary destination, not hidden in a collapsed More group. */
    @Test
    fun ouraIsDirectlyReachableFromTheMainBar() {
        assertEquals(
            listOf(Destination.Today, Destination.Oura, Destination.Trends, Destination.Sleep),
            barTabs.map { it.dest },
        )
    }

    /** A destination cannot occupy two slots; the More slot's selected state derives from this list. */
    @Test
    fun theBarHasNoDuplicateDestinations() {
        assertEquals(barTabs.map { it.dest }.distinct().size, barTabs.size)
    }

    /** Every tab carries a label, so no slot can render an empty word or an empty a11y description. */
    @Test
    fun everyTabHasALabelResource() {
        assertTrue(barTabs.all { it.labelRes != 0 })
    }
}
