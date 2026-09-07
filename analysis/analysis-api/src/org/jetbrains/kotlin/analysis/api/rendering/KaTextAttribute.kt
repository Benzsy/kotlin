/*
 * Copyright 2010-2026 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.analysis.api.rendering

import org.jetbrains.kotlin.analysis.api.KaExperimentalApi
import org.jetbrains.kotlin.analysis.api.KaPlatformInterface
import org.jetbrains.kotlin.analysis.api.KaSpi
import org.jetbrains.kotlin.analysis.api.lifetime.KaLifetimeOwner
import org.jetbrains.kotlin.analysis.api.lifetime.KaLifetimeToken
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbol

/** Describes the semantic role of a fragment of rendered text, e.g. for syntax highlighting. */
@KaSpi
@KaExperimentalApi
public interface KaTextAttribute : KaLifetimeOwner {
    /** A hard keyword or soft/modifier keyword, such as `fun`, `val`, or `suspend`. */
    @KaExperimentalApi
    public object Keyword : KaTextAttribute {
        override val token: KaLifetimeToken get() = KaTextAttributeLifetimeToken
    }

    /** Punctuation, such as `:`, `,`, or `?`. */
    @KaExperimentalApi
    public object Punctuation : KaTextAttribute {
        override val token: KaLifetimeToken get() = KaTextAttributeLifetimeToken
    }

    /** An identifier, such as a declaration or type name. See [Symbol] when the referenced symbol is known. */
    @KaExperimentalApi
    public object Identifier : KaTextAttribute {
        override val token: KaLifetimeToken get() = KaTextAttributeLifetimeToken
    }

    /** Whitespace that is not a line break. */
    @KaExperimentalApi
    public object Whitespace : KaTextAttribute {
        override val token: KaLifetimeToken get() = KaTextAttributeLifetimeToken
    }

    /** A string or character literal, such as `"text"` or `'c'`. */
    @KaExperimentalApi
    public object StringLiteral : KaTextAttribute {
        override val token: KaLifetimeToken get() = KaTextAttributeLifetimeToken
    }

    /** A numeric literal, such as `42`, `2f`, or `3u`. */
    @KaExperimentalApi
    public object NumberLiteral : KaTextAttribute {
        override val token: KaLifetimeToken get() = KaTextAttributeLifetimeToken
    }

    /** A comment, such as `/* = kotlin.String */`. */
    @KaExperimentalApi
    public object Comment : KaTextAttribute {
        override val token: KaLifetimeToken get() = KaTextAttributeLifetimeToken
    }

    /** An identifier that references a known [symbol]. Only attached when [KaRenderingOption.LinkSymbols] is enabled. */
    @KaExperimentalApi
    public class Symbol(public val symbol: KaSymbol) : KaTextAttribute {
        override val token: KaLifetimeToken
            get() = symbol.token

        override fun equals(other: Any?): Boolean = (this === other) || (other is Symbol && symbol == other.symbol)
        override fun hashCode(): Int = symbol.hashCode()
        override fun toString(): String = "KaTextAttribute.Symbol(symbol=$symbol)"
    }
}

@OptIn(KaPlatformInterface::class)
private object KaTextAttributeLifetimeToken : KaLifetimeToken() {
    override fun isValid() = true
    override fun isAccessible() = true

    override fun getInvalidationReason() = error("Getting invalidation reason for a valid token")
    override fun getInaccessibilityReason() = error("Getting inaccessibility reason for a valid token")
}
