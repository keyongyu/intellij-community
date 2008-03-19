/*
 * @author max
 */
package com.intellij.psi.stubs;

import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.StubBasedPsiElement;
import com.intellij.psi.StubBuilder;

public class DefaultStubBuilder implements StubBuilder {
  public StubElement buildStubTree(final PsiFile file) {
    return buildStubTreeFor(file, createStubForFile(file));
  }

  protected StubElement createStubForFile(final PsiFile file) {
    return new PsiFileStubImpl(file);
  }

  protected StubElement buildStubTreeFor(PsiElement elt, StubElement parentStub) {
    StubElement stub = parentStub;
    if (elt instanceof StubBasedPsiElement) {
      stub = createStubForElement(elt, parentStub);
    }

    final PsiElement[] psiElements = elt.getChildren();
    for (PsiElement child : psiElements) {
      buildStubTreeFor(child, stub);
    }

    return stub;
  }

  protected StubElement createStubForElement(final PsiElement elt, final StubElement parentStub) {
    final IStubElementType type = ((StubBasedPsiElement)elt).getElementType();

    //noinspection unchecked
    return type.createStub(elt, parentStub);
  }

}