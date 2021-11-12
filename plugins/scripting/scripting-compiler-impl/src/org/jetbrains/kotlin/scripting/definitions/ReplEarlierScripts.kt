/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.scripting.definitions

import com.intellij.openapi.util.Key
import org.jetbrains.kotlin.psi.KtScript

object ReplEarlierScripts {
    private val HAS_EARLIER_SCRIPTS_KEY = Key.create<Boolean>(KtScript::class.java.name + ".earlierScripts")

    fun hasEarlierScripts(script: KtScript): Boolean {
        return script.getUserData(HAS_EARLIER_SCRIPTS_KEY) ?: false
    }

    fun setHasEarlierScripts(script: KtScript, value: Boolean) {
        script.putUserData(HAS_EARLIER_SCRIPTS_KEY, value)
    }
}
