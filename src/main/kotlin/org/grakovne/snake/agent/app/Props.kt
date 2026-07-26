package org.grakovne.snake.agent.app

internal fun prop(name: String, default: String): String = System.getProperty(name) ?: default

internal fun intProp(name: String, default: Int): Int = System.getProperty(name)?.toInt() ?: default

internal fun longProp(name: String, default: Long): Long = System.getProperty(name)?.toLong() ?: default
