/**
 * Data models for the developer credits section on the home screen.
 *
 * Provides sealed hierarchies for [DeveloperItem] (one per contributor) and
 * [IconLink] (link types: Twitter, GitHub, PayPal, Patreon, Sponsor).
 * Each link is a [RvItem] rendered as an icon row in the credits list.
 */
package pro.magisk.ui.home

import pro.magisk.R
import pro.magisk.core.Const
import pro.magisk.databinding.RvItem
import pro.magisk.core.R as CoreR

/** Interface for objects with a developer handle name. */
interface Dev {
    val name: String
}

private interface MikailImpl : Dev {
    override val name get() = "promikailamin"
}

private interface JohnImpl : Dev {
    override val name get() = "topjohnwu"
}

/** Represents a Magisk contributor with their associated [IconLink]s. */
sealed class DeveloperItem : Dev {

    abstract val items: List<IconLink>
    val handle get() = "@${name}"
    
    object mikail : DeveloperItem(), MikailImpl {
        override val items =
            listOf(
                IconLink.Github.Project
            )
    }

    object john : DeveloperItem(), JohnImpl {
        override val items =
            listOf<IconLink>(
                object : IconLink.Twitter(), JohnImpl {},
                object : IconLink.Github.User(), JohnImpl {}
            )
    }
}

/** A single clickable icon+title link (Twitter, GitHub, etc.) that is also a [RvItem]. */
sealed class IconLink : RvItem() {

    abstract val icon: Int
    abstract val title: Int
    abstract val link: String

    override val layout_res get() = R.layout.item_icon_link

    abstract class PayPal : IconLink(), Dev {
        override val icon get() = CoreR.drawable.ic_paypal
        override val title get() = CoreR.string.paypal
        override val link get() = "https://paypal.me/$name"

        object Project : PayPal() {
            override val name: String get() = "magiskdonate"
        }
    }

    object Patreon : IconLink() {
        override val icon get() = CoreR.drawable.ic_patreon
        override val title get() = CoreR.string.patreon
        override val link get() = Const.Url.PATREON_URL
    }

    abstract class Twitter : IconLink(), Dev {
        override val icon get() = CoreR.drawable.ic_twitter
        override val title get() = CoreR.string.twitter
        override val link get() = "https://twitter.com/$name"
    }

    abstract class Github : IconLink() {
        override val icon get() = CoreR.drawable.ic_github
        override val title get() = CoreR.string.github

        abstract class User : Github(), Dev {
            override val link get() = "https://github.com/$name"
        }

        object Project : Github() {
            override val link get() = Const.Url.SOURCE_CODE_URL
        }
    }

    abstract class Sponsor : IconLink(), Dev {
        override val icon get() = CoreR.drawable.ic_favorite
        override val title get() = CoreR.string.github
        override val link get() = "https://github.com/sponsors/$name"
    }
}
