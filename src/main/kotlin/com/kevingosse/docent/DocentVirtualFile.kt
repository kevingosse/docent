package com.kevingosse.docent

import com.intellij.testFramework.LightVirtualFile

/**
 * The in-memory "file" the Docent review opens on, so it lives in the main editor area as a normal
 * tab rather than a side tool window. One instance per project (cached in [OpenDocentReviewAction]).
 */
class DocentVirtualFile : LightVirtualFile("Docent Review")
