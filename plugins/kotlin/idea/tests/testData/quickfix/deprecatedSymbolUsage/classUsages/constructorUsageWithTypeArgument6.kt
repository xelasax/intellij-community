// "Replace with 'test.New'" "true"
// WITH_STDLIB

package test

abstract class Main<T>

@Deprecated("", ReplaceWith("test.New"))
class Old<T> : Main<T>()

class New<T> : Main<T>()

fun test() {
    val main = <caret>Old<Int>()
}
// FUS_QUICKFIX_NAME: org.jetbrains.kotlin.idea.quickfix.replaceWith.DeprecatedSymbolUsageFix
// FUS_K2_QUICKFIX_NAME: org.jetbrains.kotlin.idea.k2.codeinsight.fixes.replaceWith.DeprecatedSymbolUsageFix