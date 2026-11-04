package tech.handsy.phoneagent.agent

import android.content.Context

object Prompts {
    private fun load(context: Context, name: String): String =
        context.assets.open("prompts/$name").bufferedReader().use { it.readText() }.trimEnd()

    fun actor(context: Context): String = load(context, "actor.md")
    fun planner(context: Context): String = load(context, "planner.md")
    fun appRules(context: Context): String = load(context, "app-rules.md")
}
