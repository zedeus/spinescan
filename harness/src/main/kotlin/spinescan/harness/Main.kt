package spinescan.harness

import spinescan.core.Thresholds

fun main(args: Array<String>) {
    val thresholds = Thresholds.load()
    println("spinescan harness ready (green threshold: ${thresholds.greenScore})")
}
