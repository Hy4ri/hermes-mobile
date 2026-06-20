package com.m57.hermescontrol

import org.junit.Test
import kotlin.system.measureNanoTime

class NavigationBenchmarkTest {
    @Test
    fun benchmarkMapToSet() {
        // Simulate ALL_NAV_ITEMS which has 20 items
        val items = (1..20).toList()

        // Warmup
        for (i in 1..10000) {
            val a = items.map { it }.toSet()
            val b = items.mapTo(mutableSetOf()) { it }
        }

        var timeMapToSet: Long = 0
        var timeMapTo: Long = 0

        val iterations = 100000
        for (i in 1..iterations) {
            timeMapToSet +=
                measureNanoTime {
                    items.map { it }.toSet()
                }
            timeMapTo +=
                measureNanoTime {
                    items.mapTo(mutableSetOf()) { it }
                }
        }

        println("============================")
        println("Baseline (.map.toSet): ${timeMapToSet / iterations} ns/op")
        println("Optimized (.mapTo): ${timeMapTo / iterations} ns/op")
        println("============================")
    }
}
