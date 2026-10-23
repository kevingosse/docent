package com.kevingosse.docent.awb

// Dummy change.

import com.intellij.air.shared.core.identity.SessionWorkspaceId
import com.intellij.air.shared.core.normalizeAirPathOrNull

/**
 * The Air workspace id for a backend project path — what Air's `sessionWorkspaceIdFromBackendPath` computes.
 *
 * Built from its two parts (both in `air.shared.core`, stable across 263 and 271) rather than called, because the
 * helper itself moved: `air.backend.session.api` in 263, `air.shared.session.events` in 271 (IJAI-3261). One
 * binary serves both. Unlike Air's helper, an unnormalizable path falls back to the raw string instead of throwing.
 */
internal fun workspaceIdForPath(path: String): SessionWorkspaceId = SessionWorkspaceId(normalizeAirPathOrNull(path) ?: path)
