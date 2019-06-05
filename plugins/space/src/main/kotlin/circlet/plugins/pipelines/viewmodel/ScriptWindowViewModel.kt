package circlet.plugins.pipelines.viewmodel

import circlet.pipelines.config.api.*
import circlet.plugins.pipelines.services.*
import circlet.runtime.*
import com.intellij.openapi.project.*
import runtime.async.*
import runtime.reactive.*
import javax.swing.tree.*

class ScriptWindowViewModel(private val lifetime: Lifetime, private val project: Project) {
    val modelBuildIsRunning = mutableProperty(false)
    val script = mutableProperty<ScriptViewModel?>(null)
    val selectedNode = mutableProperty<CircletModelTreeNode?>(null)
    val logData = mutableProperty<LogData?>(null)

    init {
        selectedNode.forEach(lifetime) {
            logData.value = if (it != null && it.isRunnable) LogData(it.userObject.toString()) else null
        }
    }
}


class ScriptViewModel(
    private val lifetime: Lifetime,
    val config: ProjectConfig) {
}

class LogData(val dummy: String)


fun createEmptyScriptViewModel(lifetime: Lifetime) : ScriptViewModel {
    return ScriptViewModel(lifetime, ProjectConfig(emptyList(), emptyList(), emptyList()))

}


class CircletModelTreeNode(text: String? = null, val isRunnable: Boolean = false) : DefaultMutableTreeNode(text) {

}
