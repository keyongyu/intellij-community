/*
 * Copyright 2000-2007 JetBrains s.r.o.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.plugins.groovy.lang.editor;

import com.intellij.ide.DataManager;
import com.intellij.lang.ASTNode;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.actionSystem.DataKeys;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.*;
import com.intellij.openapi.editor.actionSystem.EditorActionHandler;
import com.intellij.openapi.editor.actionSystem.EditorWriteActionHandler;
import com.intellij.openapi.editor.ex.EditorEx;
import com.intellij.openapi.editor.highlighter.EditorHighlighter;
import com.intellij.openapi.editor.highlighter.HighlighterIterator;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.*;
import com.intellij.psi.impl.source.PostprocessReformattingAspect;
import com.intellij.psi.tree.TokenSet;
import com.intellij.util.IncorrectOperationException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.plugins.grails.lang.gsp.lexer.GspTokenTypesEx;
import org.jetbrains.plugins.groovy.lang.lexer.GroovyTokenTypes;
import org.jetbrains.plugins.groovy.lang.psi.GroovyElementFactory;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.blocks.GrClosableBlock;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.expressions.GrExpression;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.expressions.GrReferenceExpression;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.expressions.literals.GrLiteral;

/**
 * @author ilyas
 */
public class GroovyEnterHandler extends EditorWriteActionHandler {
  private EditorActionHandler myOriginalHandler;

  public static final Logger LOG = Logger.getInstance("org.jetbrains.plugins.groovy.lang.editor.GroovyEnterHandler");

  public GroovyEnterHandler(EditorActionHandler actionHandler) {
    myOriginalHandler = actionHandler;
  }

  public boolean isEnabled(Editor editor, DataContext dataContext) {
    return HandlerUtils.isEnabled(editor, dataContext, myOriginalHandler);
  }

  public void executeWriteAction(final Editor editor, final DataContext dataContext) {
    try {
      executeWriteActionInner(editor, dataContext);
    } catch (IncorrectOperationException e) {
      LOG.error(e);
    }
  }

  private void executeWriteActionInner(Editor editor, DataContext dataContext) throws IncorrectOperationException {
    Project project = DataKeys.PROJECT.getData(dataContext);
    if (project != null) {
      PsiDocumentManager.getInstance(project).commitDocument(editor.getDocument());
    }

    if (project == null || !handleEnter(editor, dataContext, project) &&
        myOriginalHandler != null &&
        myOriginalHandler.isEnabled(editor, dataContext)) {
      myOriginalHandler.execute(editor, dataContext);
    }
  }

  private boolean handleEnter(Editor editor, DataContext dataContext, @NotNull Project project) throws IncorrectOperationException {
    if (!HandlerUtils.canBeInvoked(editor, dataContext)) {
      return false;
    }
    int caretOffset = editor.getCaretModel().getOffset();
    if (caretOffset < 1) return false;

    if (handleJspLikeScriptlet(editor, caretOffset, dataContext)) {
      GroovyEditorActionUtil.insertSpacesByIndent(editor, project);
      return true;
    }
    if (handleInLineComment(editor, caretOffset, dataContext)) {
      return true;
    }
    if (handleInString(editor, caretOffset, dataContext)) {
      return true;
    }
    return false;
  }

  private boolean handleInLineComment(Editor editor, int caret, DataContext dataContext) {
    final EditorHighlighter highlighter = ((EditorEx) editor).getHighlighter();
    HighlighterIterator iterator = highlighter.createIterator(caret - 1);
    if (GroovyTokenTypes.mSL_COMMENT == iterator.getTokenType()) {
      String text = editor.getDocument().getText();
      if (text.length() == caret) return false;
      if (text.length() > caret && (text.charAt(caret) == '\n' || text.charAt(caret) == '\r')) {
        return false;
      }
      myOriginalHandler.execute(editor, dataContext);
      EditorModificationUtil.insertStringAtCaret(editor, "// ");
      editor.getCaretModel().moveCaretRelatively(-3, 0, false, false, true);
      return true;
    }
    return false;
  }

  private static TokenSet AFTER_DOLLAR = TokenSet.create(
      GroovyTokenTypes.mLCURLY,
      GroovyTokenTypes.mIDENT,
      GroovyTokenTypes.mGSTRING_SINGLE_BEGIN,
      GroovyTokenTypes.mGSTRING_SINGLE_CONTENT
  );

  private static TokenSet ALL_STRINGS = TokenSet.create(
      GroovyTokenTypes.mSTRING_LITERAL,
      GroovyTokenTypes.mGSTRING_LITERAL,
      GroovyTokenTypes.mGSTRING_SINGLE_BEGIN,
      GroovyTokenTypes.mGSTRING_SINGLE_END,
      GroovyTokenTypes.mGSTRING_SINGLE_CONTENT,
      GroovyTokenTypes.mRCURLY,
      GroovyTokenTypes.mIDENT
  );

  private static TokenSet BEFORE_DOLLAR = TokenSet.create(
      GroovyTokenTypes.mGSTRING_SINGLE_BEGIN,
      GroovyTokenTypes.mGSTRING_SINGLE_CONTENT
  );

  private static TokenSet EXPR_END = TokenSet.create(
      GroovyTokenTypes.mRCURLY,
      GroovyTokenTypes.mIDENT
  );

  private static TokenSet AFTER_EXPR_END = TokenSet.create(
      GroovyTokenTypes.mGSTRING_SINGLE_END,
      GroovyTokenTypes.mGSTRING_SINGLE_CONTENT
  );

  private static TokenSet STRING_END = TokenSet.create(
      GroovyTokenTypes.mSTRING_LITERAL,
      GroovyTokenTypes.mGSTRING_LITERAL,
      GroovyTokenTypes.mGSTRING_SINGLE_END
  );


  private boolean handleInString(Editor editor, int caretOffset, DataContext dataContext) throws IncorrectOperationException {
    PsiFile file = DataKeys.PSI_FILE.getData(dataContext);
    Project project = DataKeys.PROJECT.getData(dataContext);

    Document document = editor.getDocument();
    String fileText = document.getText();
    if (fileText.length() == caretOffset) return false;

    if (!checkStringApplicable(editor, caretOffset)) return false;
    if (file == null || project == null) return false;

    PsiDocumentManager.getInstance(project).commitDocument(document);
    PsiElement stringElement = file.findElementAt(caretOffset - 1);
    if (stringElement == null) return false;
    ASTNode node = stringElement.getNode();
    if (node == null) return false;

    GroovyElementFactory factory = GroovyElementFactory.getInstance(project);

    // For simple String literals like 'abcdef'
    if (GroovyTokenTypes.mSTRING_LITERAL == node.getElementType()) {
      if (GroovyEditorActionUtil.isPlainStringLiteral(node)) {
        String text = node.getText();
        String innerText = text.equals("''") ? "" : text.substring(1, text.length() - 1);
        PsiElement literal = stringElement.getParent();
        if (!(literal instanceof GrLiteral)) return false;
        TextRange literalRange = literal.getTextRange();
        document.replaceString(literalRange.getStartOffset(), literalRange.getEndOffset(), "'''" + innerText + "'''");
        editor.getCaretModel().moveToOffset(caretOffset + 2);
        EditorModificationUtil.insertStringAtCaret(editor, "\n");
      } else {
        EditorModificationUtil.insertStringAtCaret(editor, "\n");
      }
      return true;
    }

    // For expression injection in GString like "abc ${}<caret>  abc"
    if (!GroovyEditorActionUtil.GSTRING_TOKENS.contains(node.getElementType()) &&
        checkGStringInnerExpression(stringElement)) {
      stringElement = stringElement.getParent().getNextSibling();
      if (stringElement == null) return false;
      node = stringElement.getNode();
      if (node == null) return false;
    }

    if (GroovyEditorActionUtil.GSTRING_TOKENS.contains(node.getElementType())) {
      PsiElement parent = stringElement.getParent();
      while (parent != null && !(parent instanceof GrLiteral)) {
        parent = parent.getParent();
      }
      if (parent == null || parent.getLastChild() instanceof PsiErrorElement) return false;
      if (GroovyEditorActionUtil.isPlainGString(parent.getNode())) {
        PsiElement exprSibling = stringElement.getNextSibling();
        boolean rightFromDollar = exprSibling instanceof GrExpression &&
            exprSibling.getTextRange().getStartOffset() == caretOffset;
        if (rightFromDollar) caretOffset--;
        String text = parent.getText();
        String innerText = text.equals("\"\"") ? "" : text.substring(1, text.length() - 1);
        TextRange parentRange = parent.getTextRange();
        document.replaceString(parentRange.getStartOffset(), parentRange.getEndOffset(), "\"\"\"" + innerText + "\"\"\"");
        editor.getCaretModel().moveToOffset(caretOffset + 2);
        EditorModificationUtil.insertStringAtCaret(editor, "\n");
        if (rightFromDollar) {
          editor.getCaretModel().moveCaretRelatively(1, 0, false, false, true);
        }
      } else {
        EditorModificationUtil.insertStringAtCaret(editor, "\n");
      }
      return true;
    }
    return false;
  }

  private static boolean checkStringApplicable(Editor editor, int caret) {
    final EditorHighlighter highlighter = ((EditorEx) editor).getHighlighter();
    HighlighterIterator iteratorLeft = highlighter.createIterator(caret - 1);
    HighlighterIterator iteratorRight = highlighter.createIterator(caret);

    if (iteratorLeft != null && !(ALL_STRINGS.contains(iteratorLeft.getTokenType()))) {
      return false;
    }
    if (iteratorLeft != null && BEFORE_DOLLAR.contains(iteratorLeft.getTokenType()) &&
        iteratorRight != null && !AFTER_DOLLAR.contains(iteratorRight.getTokenType())) {
      return false;
    }
    if (iteratorLeft != null && EXPR_END.contains(iteratorLeft.getTokenType()) &&
        iteratorRight != null && !AFTER_EXPR_END.contains(iteratorRight.getTokenType())) {
      return false;
    }
    if (iteratorLeft != null && STRING_END.contains(iteratorLeft.getTokenType()) &&
        iteratorRight != null && !STRING_END.contains(iteratorRight.getTokenType())) {
      return false;
    }
    return true;
  }

  private static boolean checkGStringInnerExpression(PsiElement element) {
    if (element != null &&
        (element.getParent() instanceof GrReferenceExpression || element.getParent() instanceof GrClosableBlock)) {
      PsiElement nextSibling = element.getParent().getNextSibling();
      if (nextSibling == null) return false;
      return GroovyEditorActionUtil.GSTRING_TOKENS_INNER.contains(nextSibling.getNode().getElementType());
    }
    return false;
  }

  private boolean handleJspLikeScriptlet(Editor editor, int caret, DataContext dataContext) {

    final EditorHighlighter highlighter = ((EditorEx) editor).getHighlighter();
    HighlighterIterator iterator = highlighter.createIterator(caret - 1);
    if (iterator.getTokenType() != GspTokenTypesEx.JSCRIPT_BEGIN) {
      return false;
    }
    String text = editor.getDocument().getText();
    if (caret < 2 || text.length() < Math.min(caret - 2, 2)) {
      return false;
    }
    if (text.charAt(caret - 1) == '%' && text.charAt(caret - 2) == '<') {
      if (!GroovyEditorActionUtil.areSciptletSeparatorsUnbalanced(iterator)) {
        myOriginalHandler.execute(editor, dataContext);
        return true;
      } else {
        EditorModificationUtil.insertStringAtCaret(editor, "%>");
        editor.getCaretModel().moveCaretRelatively(-2, 0, false, false, true);
        myOriginalHandler.execute(editor, dataContext);
        myOriginalHandler.execute(editor, dataContext);
        editor.getCaretModel().moveCaretRelatively(0, -1, false, false, true);
        return true;
      }
    } else {
      return false;
    }
  }

}
