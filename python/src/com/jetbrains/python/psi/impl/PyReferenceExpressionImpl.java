package com.jetbrains.python.psi.impl;

import com.intellij.lang.ASTNode;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.extensions.Extensions;
import com.intellij.openapi.util.Ref;
import com.intellij.psi.*;
import com.intellij.psi.util.PsiTreeUtil;
import com.jetbrains.cython.CythonLanguageDialect;
import com.jetbrains.cython.psi.CythonCImportElement;
import com.jetbrains.cython.psi.CythonFromCImportStatement;
import com.jetbrains.cython.psi.CythonImportReference;
import com.jetbrains.python.PyNames;
import com.jetbrains.python.PyTokenTypes;
import com.jetbrains.python.PythonDialectsTokenSetProvider;
import com.jetbrains.python.codeInsight.controlflow.ReadWriteInstruction;
import com.jetbrains.python.codeInsight.controlflow.ScopeOwner;
import com.jetbrains.python.console.PydevConsoleRunner;
import com.jetbrains.python.console.completion.PydevConsoleReference;
import com.jetbrains.python.console.pydev.ConsoleCommunication;
import com.jetbrains.python.psi.*;
import com.jetbrains.python.psi.impl.references.PyImportReference;
import com.jetbrains.python.psi.impl.references.PyQualifiedReference;
import com.jetbrains.python.psi.impl.references.PyReferenceImpl;
import com.jetbrains.python.psi.resolve.*;
import com.jetbrains.python.psi.types.*;
import com.jetbrains.python.refactoring.PyDefUseUtil;
import com.jetbrains.python.toolbox.Maybe;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;

/**
 * Implements reference expression PSI.
 *
 * @author yole
 */
public class PyReferenceExpressionImpl extends PyElementImpl implements PyReferenceExpression {
  private static final Logger LOG = Logger.getInstance("#com.jetbrains.python.psi.impl.PyReferenceExpressionImpl");

  public PyReferenceExpressionImpl(ASTNode astNode) {
    super(astNode);
  }

  @Override
  @NotNull
  public PsiPolyVariantReference getReference() {
    return getReference(PyResolveContext.defaultContext());
  }

  @NotNull
  public PsiPolyVariantReference getReference(PyResolveContext context) {
    final PsiFile file = getContainingFile();
    final PyExpression qualifier = getQualifier();

    // Handle import reference
    if (CythonLanguageDialect.isInsideCythonFile(this)) {
      if (PsiTreeUtil.getParentOfType(this, CythonCImportElement.class, CythonFromCImportStatement.class) != null) {
        return new CythonImportReference(this, context);
      }
    }
    final PsiElement importParent = PsiTreeUtil.getParentOfType(this, PyImportElement.class, PyFromImportStatement.class);
    if (importParent != null) {
      return PyImportReference.forElement(this, importParent, context);
    }

    // Return special reference
    final ConsoleCommunication communication = file.getCopyableUserData(PydevConsoleRunner.CONSOLE_KEY);
    if (communication != null) {
      if (qualifier != null) {
        return new PydevConsoleReference(this, communication, qualifier.getText() + ".");
      }
      return new PydevConsoleReference(this, communication, "");
    }

    if (qualifier != null) {
      return new PyQualifiedReference(this, context);
    }

    return new PyReferenceImpl(this, context);
  }

  @Override
  protected void acceptPyVisitor(PyElementVisitor pyVisitor) {
    pyVisitor.visitPyReferenceExpression(this);
  }

  @Nullable
  public PyExpression getQualifier() {
    final ASTNode[] nodes = getNode().getChildren(PythonDialectsTokenSetProvider.INSTANCE.getExpressionTokens());
    return (PyExpression)(nodes.length == 1 ? nodes[0].getPsi() : null);
  }

  @Nullable
  public String getReferencedName() {
    final ASTNode nameElement = getNameElement();
    return nameElement != null ? nameElement.getText() : null;
  }

  @Nullable
  public ASTNode getNameElement() {
    return getNode().findChildByType(PyTokenTypes.IDENTIFIER);
  }

  @Nullable
  @Override
  public String getName() {
    return getReferencedName();
  }


  private final QualifiedResolveResult EMPTY_RESULT = new QualifiedResolveResultEmpty();

  @NotNull
  public QualifiedResolveResult followAssignmentsChain(PyResolveContext resolveContext) {
    PyReferenceExpression seeker = this;
    QualifiedResolveResult ret = null;
    List<PyExpression> qualifiers = new ArrayList<PyExpression>();
    PyExpression qualifier = seeker.getQualifier();
    if (qualifier != null) {
      qualifiers.add(qualifier);
    }
    Set<PsiElement> visited = new HashSet<PsiElement>();
    visited.add(this);
    SEARCH:
    while (ret == null) {
      ResolveResult[] targets = seeker.getReference(resolveContext).multiResolve(false);
      for (ResolveResult target : targets) {
        PsiElement elt = target.getElement();
        if (elt instanceof PyTargetExpression) {
          PsiElement assigned_from = null;
          final PyTargetExpression expr = (PyTargetExpression)elt;
          if (resolveContext.getTypeEvalContext().maySwitchToAST(expr) || expr.getStub() == null) {
            assigned_from = expr.findAssignedValue();
          }
          // TODO: Maybe findAssignedValueByStub() should become a part of the PyTargetExpression interface
          else if (elt instanceof PyTargetExpressionImpl) {
            assigned_from = ((PyTargetExpressionImpl)elt).findAssignedValueByStub();
          }
          if (assigned_from instanceof PyReferenceExpression) {
            if (visited.contains(assigned_from)) {
              break;
            }
            visited.add(assigned_from);
            seeker = (PyReferenceExpression)assigned_from;
            if (seeker.getQualifier() != null) {
              qualifiers.add(seeker.getQualifier());
            }
            continue SEARCH;
          }
          else if (assigned_from != null) ret = new QualifiedResolveResultImpl(assigned_from, qualifiers, false);
        }
        else if (ret == null && elt instanceof PyElement && target.isValidResult()) {
          // remember this result, but a further reference may be the next resolve result
          ret = new QualifiedResolveResultImpl(elt, qualifiers, target instanceof ImplicitResolveResult);
        }
      }
      // all resolve results checked, reassignment not detected, nothing more to do
      break;
    }
    if (ret == null) ret = EMPTY_RESULT;
    return ret;
  }

  @Nullable
  public PyQualifiedName asQualifiedName() {
    return PyQualifiedName.fromReferenceChain(PyResolveUtil.unwindQualifiers(this));
  }

  @Override
  public String toString() {
    return "PyReferenceExpression: " + getReferencedName();
  }

  public PyType getType(@NotNull TypeEvalContext context, @NotNull TypeEvalContext.Key key) {
    if (!TypeEvalStack.mayEvaluate(this)) {
      return null;
    }
    try {
      final PyExpression qualifier = getQualifier();
      if (qualifier == null) {
        String name = getReferencedName();
        if (PyNames.NONE.equals(name)) {
          return PyNoneType.INSTANCE;
        }
      }
      PyType type = getTypeFromProviders(context);
      if (type != null) {
        return type;
      }
      if (qualifier != null) {
        PyType maybe_type = PyUtil.getSpecialAttributeType(this, context);
        if (maybe_type != null) return maybe_type;
        Ref<PyType> typeOfProperty = getTypeOfProperty(context);
        if (typeOfProperty != null) {
          return typeOfProperty.get();
        }
      }
      ResolveResult[] targets = getReference(PyResolveContext.noImplicits().withTypeEvalContext(context)).multiResolve(false);
      if (targets.length == 0) return null;
      for (ResolveResult resolveResult : targets) {
        PsiElement target = resolveResult.getElement();
        if (target == this || target == null) {
          continue;
        }
        if (!target.isValid()) {
          LOG.error("Reference " + this + " resolved to invalid element " + target + " (text=" + target.getText() + ")");
          continue;
        }
        type = getTypeFromTarget(target, context, this);
        if (type != null) {
          return type;
        }
      }
      return null;
    }
    finally {
      TypeEvalStack.evaluated(this);
    }
  }

  @Nullable
  public Ref<PyType> getTypeOfProperty(@NotNull TypeEvalContext context) {
    final PyExpression qualifier = getQualifier();
    final String name = getName();
    if (name != null && qualifier != null) {
      final PyType qualifierType = context.getType(qualifier);
      return getTypeOfProperty(qualifierType, name, context);
    }
    return null;
  }

  @Nullable
  private Ref<PyType> getTypeOfProperty(@Nullable PyType qualifierType, @NotNull String name, @NotNull TypeEvalContext context) {
    if (qualifierType instanceof PyClassType) {
      final PyClassType classType = (PyClassType)qualifierType;
      PyClass pyClass = classType.getPyClass();
      Property property = pyClass.findProperty(name);
      if (property != null) {
        if (classType.isDefinition()) {
          return Ref.<PyType>create(PyBuiltinCache.getInstance(pyClass).getObjectType(PyNames.PROPERTY));
        }
        final Maybe<Callable> accessor = property.getByDirection(AccessDirection.of(this));
        final Callable callable = accessor.valueOrNull();
        final PyType type = (callable != null) ? callable.getReturnType(context, this) : null;
        return Ref.create(type);
      }
    }
    else if (qualifierType instanceof PyUnionType) {
      final PyUnionType unionType = (PyUnionType)qualifierType;
      for (PyType type : unionType.getMembers()) {
        final Ref<PyType> result = getTypeOfProperty(type, name, context);
        if (result != null) {
          return result;
        }
      }
    }
    return null;
  }

  @Nullable
  private PyType getTypeFromProviders(TypeEvalContext context) {
    for (PyTypeProvider provider : Extensions.getExtensions(PyTypeProvider.EP_NAME)) {
      try {
        final PyType type = provider.getReferenceExpressionType(this, context);
        if (type != null) {
          return type;
        }
      }
      catch (AbstractMethodError e) {
        LOG.info(e);
      }
    }
    return null;
  }

  @Nullable
  public static PyType getTypeFromTarget(@NotNull final PsiElement target,
                                         final TypeEvalContext context,
                                         PyReferenceExpression anchor) {
    if (!(target instanceof PyTargetExpression)) {  // PyTargetExpression will ask about its type itself
      final PyType pyType = getReferenceTypeFromProviders(target, context, anchor);
      if (pyType != null) {
        return pyType;
      }
    }
    if (target instanceof PyTargetExpression) {
      final String name = ((PyTargetExpression)target).getName();
      if (PyNames.NONE.equals(name)) {
        return PyNoneType.INSTANCE;
      }
      if (PyNames.TRUE.equals(name) || PyNames.FALSE.equals(name)) {
        return PyBuiltinCache.getInstance(target).getBoolType();
      }
    }
    if (target instanceof PyFile) {
      return new PyModuleType((PyFile)target);
    }
    if (target instanceof PyImportedModule) {
      return new PyImportedModuleType((PyImportedModule)target);
    }
    if ((target instanceof PyTargetExpression || target instanceof PyNamedParameter) && anchor != null && context.allowDataFlow(anchor)) {
      final ScopeOwner scopeOwner = PsiTreeUtil.getStubOrPsiParentOfType(anchor, ScopeOwner.class);
      if (scopeOwner != null && scopeOwner == PsiTreeUtil.getStubOrPsiParentOfType(target, ScopeOwner.class)) {
        PyAugAssignmentStatement augAssignment = PsiTreeUtil.getParentOfType(anchor, PyAugAssignmentStatement.class);
        try {
          final List<ReadWriteInstruction> defs = PyDefUseUtil.getLatestDefs(scopeOwner,
                                                                             ((PyElement)target).getName(),
                                                                             augAssignment != null ? augAssignment : anchor,
                                                                             true);
          if (!defs.isEmpty()) {
            PyType type = defs.get(0).getType(context, anchor);
            for (int i = 1; i < defs.size(); i++) {
              type = PyUnionType.union(type, defs.get(i).getType(context, anchor));
            }
            return type;
          }
        }
        catch (PyDefUseUtil.InstructionNotFoundException e) {
          // ignore
        }
      }
    }
    if (target instanceof PyFunction) {
      final PyDecoratorList decoratorList = ((PyFunction)target).getDecoratorList();
      if (decoratorList != null) {
        final PyDecorator propertyDecorator = decoratorList.findDecorator(PyNames.PROPERTY);
        if (propertyDecorator != null) {
          return PyBuiltinCache.getInstance(target).getObjectType(PyNames.PROPERTY);
        }
        for (PyDecorator decorator : decoratorList.getDecorators()) {
          final PyQualifiedName qName = decorator.getQualifiedName();
          if (qName != null && (qName.endsWith(PyNames.SETTER) || qName.endsWith(PyNames.DELETER))) {
            return PyBuiltinCache.getInstance(target).getObjectType(PyNames.PROPERTY);
          }
        }
      }
    }
    if (target instanceof PyTypedElement) {
      return context.getType((PyTypedElement)target);
    }
    if (target instanceof PsiDirectory) {
      PsiFile file = ((PsiDirectory)target).findFile(PyNames.INIT_DOT_PY);
      if (file != null) {
        return getTypeFromTarget(file, context, anchor);
      }
    }
    return null;
  }

  @Nullable
  public static PyType getReferenceTypeFromProviders(@NotNull final PsiElement target,
                                                     TypeEvalContext context,
                                                     @Nullable PsiElement anchor) {
    for (PyTypeProvider provider : Extensions.getExtensions(PyTypeProvider.EP_NAME)) {
      final PyType result = provider.getReferenceType(target, context, anchor);
      if (result != null) return result;
    }

    return null;
  }

  private static class QualifiedResolveResultImpl extends RatedResolveResult implements QualifiedResolveResult {
    // a trivial implementation
    private List<PyExpression> myQualifiers;
    private boolean myIsImplicit;

    public boolean isImplicit() {
      return myIsImplicit;
    }

    QualifiedResolveResultImpl(@NotNull PsiElement element, List<PyExpression> qualifiers, boolean isImplicit) {
      super(isImplicit ? RATE_LOW : RATE_NORMAL, element);
      myQualifiers = qualifiers;
      myIsImplicit = isImplicit;
    }

    @Override
    public List<PyExpression> getQualifiers() {
      return myQualifiers;
    }
  }

  public static class QualifiedResolveResultEmpty implements QualifiedResolveResult {
    // a trivial implementation

    public QualifiedResolveResultEmpty() {
    }

    @Override
    public List<PyExpression> getQualifiers() {
      return Collections.emptyList();
    }

    public PsiElement getElement() {
      return null;
    }

    public boolean isValidResult() {
      return false;
    }

    public boolean isImplicit() {
      return false;
    }
  }

}

