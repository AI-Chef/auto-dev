package cc.unitmesh.database.actions

import cc.unitmesh.database.DbContextActionProvider
import cc.unitmesh.database.flow.AutoSqlContext
import cc.unitmesh.database.flow.AutoSqlFlow
import cc.unitmesh.database.flow.AutoSqlBackgroundTask
import cc.unitmesh.devti.AutoDevBundle
import cc.unitmesh.devti.gui.sendToChatPanel
import cc.unitmesh.devti.intentions.action.base.ChatBaseIntention
import cc.unitmesh.devti.llms.LlmFactory
import com.intellij.database.model.ObjectKind
import com.intellij.database.psi.DbPsiFacade
import com.intellij.database.util.DasUtil
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.impl.BackgroundableProcessIndicator
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import com.intellij.sql.dialects.sqlite.SqliteDialect


class AutoSqlAction : ChatBaseIntention() {
    override fun priority(): Int = 900
    override fun startInWriteAction(): Boolean = false
    override fun getFamilyName(): String = AutoDevBundle.message("autosql.name")
    override fun getText(): String = AutoDevBundle.message("autosql.generate")

    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?): Boolean {
        DbPsiFacade.getInstance(project).dataSources.firstOrNull() ?: return false
        val hasSelectionText = editor?.selectionModel?.selectedText
        return hasSelectionText != null
    }

    override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
        if (editor == null || file == null) return

        val dbPsiFacade = DbPsiFacade.getInstance(project)
        val dataSource = dbPsiFacade.dataSources.firstOrNull() ?: return

        val selectedText = editor.selectionModel.selectedText

        val rawDataSource = dbPsiFacade.getDataSourceManager(dataSource).dataSources.firstOrNull() ?: return
        val databaseVersion = rawDataSource.databaseVersion
        val schemaName = rawDataSource.name.substringBeforeLast('@')
        val dasTables = rawDataSource.let {
            val tables = DasUtil.getTables(it)
            tables.filter { table -> table.kind == ObjectKind.TABLE &&
                    (table.dasParent?.name == schemaName || (file.language == SqliteDialect.INSTANCE && table.dasParent?.name == "main"))
            }
        }.toList()

        val genSqlContext = AutoSqlContext(
            requirement = selectedText ?: "",
            databaseVersion = databaseVersion.let {
                "name: ${it.name}, version: ${it.version}"
            },
            schemaName = schemaName,
            tableNames = dasTables.map { it.name },
        )

        val actions = DbContextActionProvider(dasTables)

        sendToChatPanel(project) { contentPanel, _ ->
            val llmProvider = LlmFactory().create(project)
            val prompter = AutoSqlFlow(genSqlContext, actions, contentPanel, llmProvider)

            val task = AutoSqlBackgroundTask(project, prompter, editor, file.language)
            ProgressManager.getInstance()
                .runProcessWithProgressAsynchronously(task, BackgroundableProcessIndicator(task))
        }
    }
}

