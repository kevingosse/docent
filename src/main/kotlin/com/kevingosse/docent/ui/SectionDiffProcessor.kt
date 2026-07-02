package com.kevingosse.docent.ui

import com.intellij.diff.chains.DiffRequestChain
import com.intellij.diff.impl.CacheDiffRequestChainProcessor
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.project.Project

/**
 * A diff processor whose toolbar is a **hybrid**: it keeps everything the base provides — the
 * side-by-side/unified switcher, "Open in editor", etc. — and adds two layers of our own:
 *
 *  - **Review-step nav** (Overview / Previous / Next), prepended for *every* file. These used to be a
 *    Swing button bar above the diff ([DocentPanel] top bar); moving them into the viewer's own toolbar
 *    matches the native git-diff look ("← N/M files →") instead of an extra strip. Backed by
 *    [DocentReviewController], so they walk the flattened review steps (section summary → each file →
 *    next section), independent of any focus region.
 *  - **Section-scoped change nav + dimming toggle** — only when the active request carries a
 *    [DocentFocusMarker.CONTROLLER]. It also swaps the two built-in *difference* navigation buttons
 *    ("PreviousDiff"/"NextDiff") for our section-scoped Previous/Next.
 *
 * The built-in next/prev come from [com.intellij.diff.impl.DiffRequestProcessor.getNavigationActions]
 * (not the viewer's toolbar), so this is the only place they can be removed. We host this processor
 * ourselves (embedded in the editor pane) because `DiffManager.showDiff` always builds a stock one.
 *
 * When the active request carries no [DocentFocusMarker.CONTROLLER] (no focus region for this file) only
 * the review-step nav is added — the built-in difference nav stays, since there's no section scope.
 *
 * The controller is read **lazily from the active request** (not a constructor field): the toolbar is
 * first built for a placeholder "loading" request during super-construction, then rebuilt once our real
 * request is applied — reading it lazily means we pick up the controller on that real rebuild. The review
 * controller is read from [getProject] (set in the super-constructor before the first toolbar build), so
 * it's safe to use it even during that placeholder build.
 */
class SectionDiffProcessor(
    project: Project?,
    chain: DiffRequestChain,
) : CacheDiffRequestChainProcessor(project, chain) {

    override fun getNavigationActions(): MutableList<AnAction> {
        val base = super.getNavigationActions()
        val am = ActionManager.getInstance()

        // Review-step nav, shown for every file. These are the *registered* actions (DocentStepActions +
        // plugin.xml), so their keyboard shortcuts (Alt+. / Alt+,) show in the toolbar tooltips.
        val review = listOfNotNull(
            am.getAction("Docent.Overview"),
            am.getAction("Docent.PrevStep"),
            am.getAction("Docent.NextStep"),
            Separator.getInstance(),
        )

        val c = getActiveRequest()?.getUserData(DocentFocusMarker.CONTROLLER)
            ?: return (review + base).toMutableList()
        val kept = base.filter { it == null || am.getId(it) !in REMOVED_NAV_IDS }
        val ours = listOf(
            ToggleDimmingAction(c),
            Separator.getInstance(),
            SectionChangeAction(c, forward = false, "Previous Change in Section", AllIcons.Actions.PreviousOccurence),
            SectionChangeAction(c, forward = true, "Next Change in Section", AllIcons.Actions.NextOccurence),
            Separator.getInstance(),
        )
        return (review + ours + kept).toMutableList()
    }

    private companion object {
        // The built-in difference-navigation actions we replace with section-scoped ones.
        val REMOVED_NAV_IDS = setOf("PreviousDiff", "NextDiff")
    }
}
