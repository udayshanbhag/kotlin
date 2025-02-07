/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.konan.blackboxtest.support.group

import kotlin.reflect.KClass

@Target(AnnotationTarget.CLASS)
annotation class NativeBlackBoxTestCaseGroupProvider(val value: KClass<*>)
