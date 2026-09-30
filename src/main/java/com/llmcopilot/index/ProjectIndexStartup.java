package com.llmcopilot.index;

import com.intellij.openapi.fileEditor.FileDocumentManagerListener;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.ProjectActivity;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.vfs.VirtualFile;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Builds the project index once the project has finished opening, and keeps it
 * current as files are saved.
 *
 * <p>Nothing waits for it. Completions and error answers get sharper the
 * moment it is ready and work without it until then, which is the only way a
 * whole-project read can be afforded at all: the alternative is either a
 * blocking scan on the first keystroke or no cross-file context.
 *
 * <p>The build is deferred until the IDE's own indices are done, because a
 * project that is still indexing is already spending every core it has, and
 * competing with it would be felt.
 */
public final class ProjectIndexStartup implements ProjectActivity {

    @Nullable
    @Override
    public Object execute(@NotNull Project project, @NotNull Continuation<? super Unit> continuation) {
        DumbService.getInstance(project).runWhenSmart(() -> {
            if (project.isDisposed()) return;
            ProjectIndexService.getInstance(project).buildInBackground();
        });
        return Unit.INSTANCE;
    }

    /**
     * Re-read a file when it is saved.
     *
     * <p>Saves are the honest moment. An index that tracked every keystroke
     * would spend its time re-parsing half-written declarations, and the
     * declarations that matter to another file are the ones that have been
     * committed to disk.
     */
    public static final class SaveListener implements FileDocumentManagerListener {

        private final Project project;

        public SaveListener(Project project) { this.project = project; }

        @Override
        public void beforeDocumentSaving(@NotNull Document document) {
            if (project.isDisposed()) return;
            VirtualFile file = FileDocumentManager.getInstance().getFile(document);
            if (file == null) return;

            ProjectIndexService index = ProjectIndexService.getInstance(project);
            if (index == null || !index.isReady()) return;

            // Off the write thread: the save itself must not wait on a reparse.
            com.intellij.openapi.application.ApplicationManager.getApplication()
                .executeOnPooledThread(() -> {
                    if (!project.isDisposed()) index.refresh(file);
                });
        }
    }
}
