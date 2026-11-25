package dev.dawitsh.noroshi.ui

import androidx.annotation.StringRes
import dev.dawitsh.noroshi.R
import dev.dawitsh.noroshi.core.model.Feature
import dev.dawitsh.noroshi.core.model.ReportKind
import dev.dawitsh.noroshi.core.model.RouterType

sealed interface Node {
    val name: String
    val description: Int

    data class Menu(override val name: String, @StringRes override val description: Int, val children: List<Node>) : Node

    data class Leaf(
        override val name: String,
        @StringRes override val description: Int,
        val target: Route,
        val usage: String = "",
    ) : Node

    data class Action(override val name: String, @StringRes override val description: Int, val action: MenuAction) : Node
}

enum class MenuAction { Reboot }

private fun report(kind: ReportKind) = Node.Leaf(kind.name.lowercase(), kind.description, Route.Report(kind))

val COMMAND_TREE = Node.Menu(
    "noroshi", R.string.app_subtitle, listOf(
        Node.Menu(
            "get", R.string.desc_get, listOf(
                report(ReportKind.Info),
                report(ReportKind.System),
                report(ReportKind.Signal),
                report(ReportKind.Internet),
                report(ReportKind.Apn),
                report(ReportKind.Device),
                report(ReportKind.Wifi),
                report(ReportKind.Devices),
                Node.Menu(
                    "sms", R.string.desc_get_sms, listOf(
                        Node.Leaf("list", R.string.desc_sms_list, Route.SmsInbox),
                        Node.Leaf("show", R.string.desc_sms_show, Route.SmsShow, usage = "<msg_id>"),
                        Node.Leaf("info", R.string.desc_sms_info, Route.Report(ReportKind.SmsInfo)),
                    )
                ),
                report(ReportKind.Syslog),
                report(ReportKind.Airtime),
                report(ReportKind.Power),
            )
        ),
        Node.Menu(
            "post", R.string.desc_post, listOf(
                Node.Action("reboot", R.string.desc_reboot, MenuAction.Reboot),
                Node.Leaf("ussd", R.string.desc_ussd, Route.Ussd(), usage = "<code>"),
                Node.Menu(
                    "sms", R.string.desc_post_sms, listOf(
                        Node.Leaf("send", R.string.desc_sms_send, Route.SendSms(), usage = "<number> <message>"),
                        Node.Leaf("delete", R.string.desc_sms_delete, Route.SmsSelect(SmsAction.Delete), usage = "<msg_id>|all"),
                        Node.Leaf("mark", R.string.desc_sms_mark, Route.SmsSelect(SmsAction.Mark), usage = "<msg_id>|all"),
                    )
                ),
            )
        ),
    )
)

private fun Route.supportedBy(type: RouterType): Boolean = when (this) {
    is Route.Report -> kind in type.reports
    Route.SmsInbox, Route.SmsShow, is Route.SmsMessage, is Route.SendSms, is Route.SmsSelect -> type.has(Feature.Sms)
    is Route.Ussd -> type.has(Feature.Ussd)
    is Route.Menu, Route.Settings -> true
}

private fun MenuAction.supportedBy(type: RouterType): Boolean = when (this) {
    MenuAction.Reboot -> type.has(Feature.Reboot)
}

private fun Node.Menu.only(type: RouterType): Node.Menu = copy(
    children = children.mapNotNull { node ->
        when (node) {
            is Node.Menu -> node.only(type).takeIf { it.children.isNotEmpty() }
            is Node.Leaf -> node.takeIf { it.target.supportedBy(type) }
            is Node.Action -> node.takeIf { it.action.supportedBy(type) }
        }
    }
)

/** The command tree with only the commands [type] can answer. */
fun commandTree(type: RouterType): Node.Menu = COMMAND_TREE.only(type).copy(description = type.subtitle)

fun resolveMenu(type: RouterType, path: List<String>): Node.Menu? {
    var node: Node.Menu = commandTree(type)
    for (segment in path) {
        node = node.children.filterIsInstance<Node.Menu>().firstOrNull { it.name == segment } ?: return null
    }
    return node
}
