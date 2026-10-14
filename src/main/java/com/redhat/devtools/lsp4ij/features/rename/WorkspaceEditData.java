package com.redhat.devtools.lsp4ij.features.rename;

import com.redhat.devtools.lsp4ij.LanguageServerItem;
import com.redhat.devtools.lsp4ij.WorkspaceEditSnapshot;
import org.eclipse.lsp4j.WorkspaceEdit;

public record WorkspaceEditData(WorkspaceEdit edit, LanguageServerItem languageServer, WorkspaceEditSnapshot snapshot) {
    public WorkspaceEditData(WorkspaceEdit edit, LanguageServerItem languageServer) {
        this(edit, languageServer, WorkspaceEditSnapshot.capture(languageServer));
    }
}
