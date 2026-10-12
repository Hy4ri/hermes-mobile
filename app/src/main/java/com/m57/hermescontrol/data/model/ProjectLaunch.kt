package com.m57.hermescontrol.data.model

/** The folder a chat started in this project runs in: its first folder, or null when it has none. */
val ProjectInfo.launchCwd: String?
    get() = folders.firstOrNull()?.path?.takeIf(String::isNotBlank)

/** Projects a new chat can start in: live (not archived) and with a folder to run in. */
fun List<ProjectInfo>.startable(): List<ProjectInfo> = filter { !it.isArchived && it.launchCwd != null }
