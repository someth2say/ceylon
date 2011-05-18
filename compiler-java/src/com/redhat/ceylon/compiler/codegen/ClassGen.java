package com.redhat.ceylon.compiler.codegen;

import static com.sun.tools.javac.code.Flags.FINAL;
import static com.sun.tools.javac.code.Flags.INTERFACE;
import static com.sun.tools.javac.code.Flags.PUBLIC;
import static com.sun.tools.javac.code.Flags.STATIC;
import static com.sun.tools.javac.code.TypeTags.VOID;

import java.util.LinkedHashMap;

import com.redhat.ceylon.compiler.codegen.Gen2.Singleton;
import com.redhat.ceylon.compiler.codegen.StatementGen.StatementVisitor;
import com.redhat.ceylon.compiler.typechecker.tree.Tree;
import com.sun.source.tree.Tree.Kind;
import com.sun.tools.javac.code.Flags;
import com.sun.tools.javac.code.TypeTags;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.JCTree.JCAnnotation;
import com.sun.tools.javac.tree.JCTree.JCBlock;
import com.sun.tools.javac.tree.JCTree.JCClassDecl;
import com.sun.tools.javac.tree.JCTree.JCExpression;
import com.sun.tools.javac.tree.JCTree.JCMethodDecl;
import com.sun.tools.javac.tree.JCTree.JCStatement;
import com.sun.tools.javac.tree.JCTree.JCTypeParameter;
import com.sun.tools.javac.tree.JCTree.JCVariableDecl;
import com.sun.tools.javac.util.List;
import com.sun.tools.javac.util.ListBuffer;
import com.sun.tools.javac.util.Name;

public class ClassGen extends GenPart {

	public ClassGen(Gen2 gen) {
		super(gen);
	}

    // FIXME: figure out what insertOverloadedClassConstructors does and port it
    
    public JCClassDecl convert(final Tree.ClassOrInterface cdecl) {
        final ListBuffer<JCVariableDecl> params =
            new ListBuffer<JCVariableDecl>();
        final ListBuffer<JCTree> defs =
            new ListBuffer<JCTree>();
        final ListBuffer<JCStatement> annotations =
            new ListBuffer<JCStatement>();
        final ListBuffer<JCAnnotation> langAnnotations =
            new ListBuffer<JCAnnotation>();
        final ListBuffer<JCStatement> stmts =
            new ListBuffer<JCStatement>();
        final ListBuffer<JCStatement> initStmts =
            new ListBuffer<JCStatement>();
        final ListBuffer<JCTypeParameter> typeParams =
            new ListBuffer<JCTypeParameter>();
        final ListBuffer<JCExpression> satisfies =
            new ListBuffer<JCExpression>();

        class ClassVisitor extends StatementVisitor {
        	Tree.ExtendedType extendedType;
            ClassVisitor(Tree.ClassOrInterface cdecl,
                    ListBuffer<JCStatement> stmts) {
                gen.statementGen.super(cdecl, stmts);
            }
            public void visit(Tree.Parameter param) {
                JCVariableDecl var = at(cdecl).VarDef(make().Modifiers(0),
                        names().fromString(tempName()), gen.convert(param.getType()), null);
                JCVariableDecl localVar = at(cdecl).VarDef(make().Modifiers(0),
                        names().fromString(param.getIdentifier().getText()), gen.convert(param.getType()), null);
                params.append(var);
                defs.append(localVar);
                initStmts.append(at(param).
                        Exec(at(param).Assign(makeSelect("this",
                                localVar.getName().toString()),
                                at(param).Ident(var.getName()))));
            }

            public void visit(Tree.Block b) {
                b.visitChildren(this);
            }

            public void visit(Tree.MethodDefinition meth) {
                defs.appendList(convert(cdecl, meth));
            }
/* FIXME:
            public void visit(Tree.MethodDeclaration meth) {
                defs.appendList(convert(cdecl, meth));
            }
*/
             public void visit(Tree.Annotation ann) {
                // Handled in processAnnotations
            }

            public void visit(Tree.SatisfiedTypes theList) {
                for (Tree.Type t: theList.getTypes()) {
                    satisfies.append(gen.convert(t));
                }
            }

            // FIXME: Here we've simplified CeylonTree.MemberDeclaration to Tree.AttributeDeclaration
            public void visit(Tree.AttributeDeclaration mem) {
                for (JCStatement def: convert(cdecl, mem)) {
                    if (def instanceof JCVariableDecl &&
                            ((JCVariableDecl) def).init != null) {
                        JCVariableDecl decl = (JCVariableDecl)def;
                        Name name = decl.name;
                        JCExpression init = decl.init;
                        decl.init = null;
                        defs.append(decl);
                        stmts.append(at(mem).Exec(at(mem).Assign(at(mem).Ident(name), init)));
                    } else {
                        defs.append(def);
                    }
                }
            }

            public void visit(final Tree.ClassDefinition cdecl) {
                defs.append(convert(cdecl));
            }

            public void visit(final Tree.InterfaceDefinition cdecl) {
                defs.append(convert(cdecl));
            }

            // FIXME: also support Tree.SequencedTypeParameter
            public void visit(Tree.TypeParameterDeclaration param) {
                typeParams.append(convert(param));
            }

            public void visit(Tree.ExtendedType extendedType) {
            	this.extendedType = extendedType;
                if (extendedType.getPositionalArgumentList() != null) {
                    List<JCExpression> args = List.<JCExpression>nil();

                    for (Tree.PositionalArgument arg: extendedType.getPositionalArgumentList().getPositionalArguments())
                        args = args.append(gen.expressionGen.convertArg(arg));

                    stmts.append(at(extendedType).Exec(at(extendedType).Apply(List.<JCExpression>nil(),
                                                          at(extendedType).Ident(names()._super),
                                                          args)));
                }
            }

            // FIXME: implement
            public void visit(Tree.TypeConstraint l) {}
        }

        ClassVisitor visitor = new ClassVisitor (cdecl, stmts);
        cdecl.visitChildren(visitor);

        processAnnotations(cdecl, cdecl.getAnnotationList(), annotations, langAnnotations,
                           cdecl.getIdentifier().getText());
        
        if (cdecl instanceof Tree.AnyClass) {
            JCMethodDecl meth = at(cdecl).MethodDef(make().Modifiers(convertDeclFlags(cdecl)),
                    names().init,
                    at(cdecl).TypeIdent(VOID),
                    List.<JCTypeParameter>nil(),
                    params.toList(),
                    List.<JCExpression>nil(),
                    at(cdecl).Block(0, initStmts.toList().appendList(stmts.toList())), null);

            defs.append(meth);

            //FIXME:
            //insertOverloadedClassConstructors(defs, (CeylonTree.ClassDeclaration) cdecl);
        }

        if (annotations.length() > 0) {
            defs.append(registerAnnotations(annotations.toList()));
        }

        JCTree superclass;
        if (cdecl instanceof Tree.AnyInterface) {
        	// The VM insists that interfaces have java.lang.Object as their superclass
        	superclass = makeIdent(syms().objectType);
        }else{
        	if(visitor.extendedType == null)
                superclass = makeIdent(syms().ceylonObjectType);
        	else {
        		// FIXME: is this typecast normal here?
        		superclass = gen.convert((Tree.Type)visitor.extendedType.getType());
        	}
        }

        if (isExtension(cdecl)) {
            JCAnnotation ann =
                make().Annotation(makeIdent(syms().ceylonExtensionType),
                        List.<JCExpression>nil());
            langAnnotations.append(ann);
        }

        long mods = convertDeclFlags(cdecl);
        if (cdecl instanceof Tree.AnyInterface)
            mods |= INTERFACE;

         JCClassDecl classDef =
            at(cdecl).ClassDef(at(cdecl).Modifiers(mods, langAnnotations.toList()),
                    names().fromString(cdecl.getIdentifier().getText()),
                    processTypeConstraints(cdecl.getTypeConstraintList(), typeParams.toList()),
                    superclass,
                    satisfies.toList(),
                    defs.toList());

        return classDef;
    }

    int convertDeclFlags(Tree.ClassOrInterface cdecl) {
        int result = 0;

        /* Standard Java flags.

        public static final int PUBLIC       = 1<<0;
        public static final int PRIVATE      = 1<<1;
        public static final int PROTECTED    = 1<<2;
        public static final int STATIC       = 1<<3;
        public static final int FINAL        = 1<<4;
        public static final int SYNCHRONIZED = 1<<5;
        public static final int VOLATILE     = 1<<6;
        public static final int TRANSIENT    = 1<<7;
        public static final int NATIVE       = 1<<8;
        public static final int INTERFACE    = 1<<9;
        public static final int ABSTRACT     = 1<<10;
        public static final int STRICTFP     = 1<<11;*/

        /* Standard Ceylon flags

        public static final int PUBLIC = 1 << 0;
        public static final int DEFAULT = 1 << 1;
        public static final int PACKAGE = 1 << 2;
        public static final int ABSTRACT = 1 << 3;
        public static final int MODULE = 1 << 4;
        public static final int OPTIONAL = 1 << 5;
        public static final int MUTABLE = 1 << 6;
        public static final int EXTENSION = 1 << 7;*/

        result |= (isShared(cdecl)) ? PUBLIC : 0;

        return result;
    }

    // Rewrite a list of Ceylon-style type constraints into Java trees.
    //    class TypeWithParameter<X, Y>()
    //    given X satisfies List
    //    given Y satisfies Comparable
    // becomes
    //    class TypeWithParameter<X extends List, Y extends Comparable> extends ceylon.Object {
    private List<JCTypeParameter> processTypeConstraints(
            Tree.TypeConstraintList typeConstraintList, List<JCTypeParameter> typeParams) {
        if (typeConstraintList == null)
            return typeParams;

        LinkedHashMap<String, JCTypeParameter> symtab =
            new LinkedHashMap<String, JCTypeParameter>();
        for (JCTypeParameter item: typeParams) {
            symtab.put(item.getName().toString(), item);
        }

        for (final Tree.TypeConstraint tc: typeConstraintList.getTypeConstraints()) {
        	String name = tc.getIdentifier().getText();
            JCTypeParameter tp = symtab.get(name);
            if (tp == null)
                throw new RuntimeException("Class \"" + name +
                        "\" in satisfies list not found");

            ListBuffer<JCExpression> bounds = new ListBuffer<JCExpression>();
            if (tc.getSatisfiedTypes() != null) {
                for (Tree.Type type: tc.getSatisfiedTypes().getTypes())
                    bounds.add(gen.convert(type));

                if (tp.getBounds() != null) {
                    tp.bounds = tp.getBounds().appendList(bounds.toList());
                } else {
                    JCTypeParameter newTp =
                        at(tc).TypeParameter(names().fromString(name), bounds.toList());
                    symtab.put(name, newTp);
                }
            }

            if (tc.getAbstractedType() != null)
                throw new RuntimeException("\"abstracts\" not supported yet");
        }

        // FIXME: This just converts a map to a List.  There ought to be a
        // better way to do it
        ListBuffer<JCTypeParameter> result = new ListBuffer<JCTypeParameter>();
        for (JCTypeParameter p: symtab.values()) {
            result.add(p);
        }
        return result.toList();
    }

	public List<JCTree> convert(Tree.ClassOrInterface cdecl,
            Tree.MethodDefinition decl) {
        final ListBuffer<JCVariableDecl> params =
            new ListBuffer<JCVariableDecl>();
        final ListBuffer<JCStatement> annotations =
            new ListBuffer<JCStatement>();
        final ListBuffer<JCAnnotation> langAnnotations =
            new ListBuffer<JCAnnotation>();
        final Singleton<JCBlock> body =
            new Singleton<JCBlock>();
        Singleton<JCExpression> restypebuf =
            new Singleton<JCExpression>();
        ListBuffer<JCAnnotation> jcAnnotations = new ListBuffer<JCAnnotation>();
        final ListBuffer<JCTypeParameter> typeParams =
            new ListBuffer<JCTypeParameter>();

        processMethodDeclaration(cdecl, decl, params, body, restypebuf, typeParams,
                annotations, langAnnotations);

        JCExpression restype = restypebuf.thing();

        // FIXME: Handle lots more flags here

        if (isExtension(decl)) {
            JCAnnotation ann =
                make().Annotation(makeIdent(syms().ceylonExtensionType),
                        List.<JCExpression>nil());
            jcAnnotations.append(ann);
        }

        if (gen.isOptional(decl.getType()))
            restype = gen.optionalType(restype);

        JCMethodDecl meth = at(decl).MethodDef(make().Modifiers(PUBLIC, jcAnnotations.toList()),
                names().fromString(decl.getIdentifier().getText()),
                restype,
                processTypeConstraints(decl.getTypeConstraintList(), typeParams.toList()),
                params.toList(),
                List.<JCExpression>nil(), body.thing(), null);;

        if (annotations.length() > 0) {
        	// FIXME: Method annotations.
        	JCBlock b = registerAnnotations(annotations.toList());
        	return List.<JCTree>of(meth, b);
        }

      return List.<JCTree>of(meth);
    }

    public void methodClass(Tree.ClassOrInterface classDecl,
            Tree.MethodDefinition decl, final ListBuffer<JCTree> defs,
            boolean topLevel) {
        // Generate a class with the
        // name of the method and a corresponding run() method.

        final ListBuffer<JCVariableDecl> params =
            new ListBuffer<JCVariableDecl>();
        final ListBuffer<JCStatement> annotations =
            new ListBuffer<JCStatement>();
        final Singleton<JCBlock> body =
            new Singleton<JCBlock>();
        Singleton<JCExpression> restype =
            new Singleton<JCExpression>();
        final ListBuffer<JCAnnotation> langAnnotations =
            new ListBuffer<JCAnnotation>();
        final ListBuffer<JCTypeParameter> typeParams =
            new ListBuffer<JCTypeParameter>();

        processMethodDeclaration(classDecl, decl, params, body, restype, typeParams,
                annotations, langAnnotations);

        JCMethodDecl meth = at(decl).MethodDef(make().Modifiers((topLevel ? PUBLIC|STATIC : 0)),
                names().fromString("run"),
                restype.thing(),
                processTypeConstraints(decl.getTypeConstraintList(), typeParams.toList()),
                params.toList(),
                List.<JCExpression>nil(), body.thing(), null);

        List<JCTree> innerDefs = List.<JCTree>of(meth);

        // FIXME: This is wrong because the annotation registration is done
        // within the scope of the class, but the annotations are lexically
        // outside it.
        if (annotations.length() > 0) {
            innerDefs = innerDefs.append(registerAnnotations(annotations.toList()));
        }

        // Try and find a class to insert this method into
        JCClassDecl classDef = null;
        for (JCTree def : defs) {
            if (def.getKind() == Kind.CLASS) {
                classDef = (JCClassDecl) def;
                break;
            }
        }

        String name;
        if (topLevel)
            name = decl.getIdentifier().getText();
        else
            name = tempName();

        // No class has been made yet so make one
        if (classDef == null) {
            classDef = at(decl).ClassDef(
                at(decl).Modifiers((topLevel ? PUBLIC : 0), List.<JCAnnotation>nil()),
                names().fromString(name),
                List.<JCTypeParameter>nil(),
                makeIdent(syms().ceylonObjectType),
                List.<JCExpression>nil(),
                List.<JCTree>nil());

            defs.append(classDef);
        }

        classDef.defs = classDef.defs.appendList(innerDefs);
    }

    // FIXME: There must be a better way to do this.
    void processMethodDeclaration(final Tree.ClassOrInterface classDecl,
            final Tree.MethodDefinition decl,
            final ListBuffer<JCVariableDecl> params,
            final Singleton<JCBlock> block,
            final Singleton<JCExpression> restype,
            final ListBuffer<JCTypeParameter> typeParams,
            final ListBuffer<JCStatement> annotations,
            final ListBuffer<JCAnnotation> langAnnotations) {

        for (Tree.Parameter param: decl.getParameterLists().get(0).getParameters()) {
            params.append(convert(param));
        }

        if (decl.getTypeParameterList() != null)
            for (Tree.TypeParameterDeclaration t: decl.getTypeParameterList().getTypeParameterDeclarations()) {
                typeParams.append(convert(t));
            }

        block.append(gen.statementGen.convert(classDecl, decl.getBlock()));

        processAnnotations(classDecl, decl.getAnnotationList(), annotations, langAnnotations, decl.getIdentifier().getText());

        restype.append(gen.convert(decl.getType()));
     }

    void processAnnotations(final Tree.ClassOrInterface classDecl,
            Tree.AnnotationList ceylonAnnos,
            final ListBuffer<JCStatement> annotations,
            final ListBuffer<JCAnnotation> langAnnotations,
            final String declName) {
    	/* FIXME: this is probably just wrong
        class V extends Visitor {
            public void visit(Tree.Annotation userAnn) {
                annotations.append(at(userAnn).Exec(convert(userAnn, classDecl, declName)));
            }
        }
        V v = new V();

        if (ceylonAnnos != null)
            for (Tree.Annotation a: ceylonAnnos.getAnnotations())
                a.visit(v);
        */
    }

    JCBlock registerAnnotations(List<JCStatement> annos) {
        JCBlock block = make().Block(Flags.STATIC, annos);
        return block;
    }

    List<JCStatement> convert(Tree.ClassOrInterface classDecl,
            Tree.AttributeDeclaration decl) {
        at(decl);

        JCExpression initialValue = null;
        if (decl.getSpecifierOrInitializerExpression() != null)
            initialValue = gen.expressionGen.convertExpression(decl.getSpecifierOrInitializerExpression().getExpression());

        final ListBuffer<JCAnnotation> langAnnotations =
            new ListBuffer<JCAnnotation>();
        final ListBuffer<JCStatement> annotations =
            new ListBuffer<JCStatement>();
        processAnnotations(classDecl, decl.getAnnotationList(), annotations, langAnnotations, decl.getIdentifier().getText());

        JCExpression type = gen.convert(decl.getType());

        if (isExtension(decl)) {
            JCAnnotation ann =
                make().Annotation(makeIdent(syms().ceylonExtensionType),
                        List.<JCExpression>nil());
            langAnnotations.append(ann);
        }
        if (gen.isOptional(decl.getType())) {
            type = gen.optionalType(type);
        }
        JCExpression innerType = type;
        if (isMutable(decl)) {
            type = gen.mutableType(type);
        }

        if (initialValue == null) {
            if (!isMutable(decl))
                throw new RuntimeException("Member needs a value");
            else {
                initialValue = at(decl).Apply(List.of(innerType),
                        makeSelect(makeIdent(syms().ceylonMutableType), "of"),
                        List.<JCExpression>of(at(decl).Literal(
                                TypeTags.BOT,
                                null)));
            }
        }

        int modifiers = FINAL;
        List<JCStatement> result =
            List.<JCStatement>of(at(decl).VarDef
                    (at(decl).Modifiers(modifiers, langAnnotations.toList()),
                            names().fromString(decl.getIdentifier().getText()),
                            type,
                            initialValue));

        if (annotations.length() > 0) {
            result = result.append(registerAnnotations(annotations.toList()));
        }

        return result;
    }
    
    
    private boolean hasCompilerAnnotation(Tree.Declaration decl, com.sun.tools.javac.code.Type annotationType) {
    	if(decl.getAnnotationList() == null)
    		return false;
    	for(Tree.Annotation a : decl.getAnnotationList().getAnnotations()){
    		if(!(a.getPrimary() instanceof Tree.Member))
    			throw new RuntimeException("Invalid annotation primary: "+a.getPrimary().getNodeType());
    		Tree.Member member = (Tree.Member) a.getPrimary();
    		if(gen.isSameType(member.getIdentifier(), annotationType))
    			return true;
    	}
		return false;
	}

    private boolean isExtension(Tree.Declaration decl) {
    	return hasCompilerAnnotation(decl, syms().ceylonExtensionType);
    }
    
    private boolean isShared(Tree.ClassOrInterface cdecl) {
    	return hasCompilerAnnotation(cdecl, syms().ceylonSharedType);
	}

	private boolean isMutable(Tree.AttributeDeclaration decl) {
		return decl.getSpecifierOrInitializerExpression() instanceof Tree.InitializerExpression;
	}

    JCTypeParameter convert(Tree.TypeParameterDeclaration param) {
    	// FIXME: implement this
        if (param.getTypeVariance() != null)
            throw new RuntimeException("Variance not implemented");
        Tree.Identifier name = param.getIdentifier();
        return at(param).TypeParameter(names().fromString(name.getText()), List.<JCExpression>nil());
    }

    JCVariableDecl convert(Tree.Parameter param) {
        at(param);
        Name name = names().fromString(param.getIdentifier().getText());
        JCExpression type = gen.variableType(param.getType(), param.getAnnotationList());

        if (gen.isOptional(param.getType())) {
            type = gen.optionalType(type);
        }
        /* FIXME: I didn't see anywhere in the spec about mutable parameters
        if ((param.flags & CeylonTree.MUTABLE) != 0) {
            type = mutableType(type);
        }
        */

        JCVariableDecl v = at(param).VarDef(make().Modifiers(FINAL), name,
                type, null);

        return v;
    }
}
