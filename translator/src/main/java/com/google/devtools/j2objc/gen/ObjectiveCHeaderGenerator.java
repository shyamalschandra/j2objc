/*
 * Copyright 2011 Google Inc. All Rights Reserved.
 *
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

package com.google.devtools.j2objc.gen;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.google.devtools.j2objc.J2ObjC;
import com.google.devtools.j2objc.Options;
import com.google.devtools.j2objc.ast.AbstractTypeDeclaration;
import com.google.devtools.j2objc.ast.Annotation;
import com.google.devtools.j2objc.ast.AnnotationTypeDeclaration;
import com.google.devtools.j2objc.ast.AnnotationTypeMemberDeclaration;
import com.google.devtools.j2objc.ast.BodyDeclaration;
import com.google.devtools.j2objc.ast.CompilationUnit;
import com.google.devtools.j2objc.ast.EnumConstantDeclaration;
import com.google.devtools.j2objc.ast.EnumDeclaration;
import com.google.devtools.j2objc.ast.FieldDeclaration;
import com.google.devtools.j2objc.ast.MethodDeclaration;
import com.google.devtools.j2objc.ast.Name;
import com.google.devtools.j2objc.ast.TreeUtil;
import com.google.devtools.j2objc.ast.TreeVisitor;
import com.google.devtools.j2objc.ast.Type;
import com.google.devtools.j2objc.ast.TypeDeclaration;
import com.google.devtools.j2objc.ast.VariableDeclarationFragment;
import com.google.devtools.j2objc.types.HeaderImportCollector;
import com.google.devtools.j2objc.types.IOSMethod;
import com.google.devtools.j2objc.types.Import;
import com.google.devtools.j2objc.types.Types;
import com.google.devtools.j2objc.util.ASTUtil;
import com.google.devtools.j2objc.util.BindingUtil;
import com.google.devtools.j2objc.util.NameTable;
import com.google.devtools.j2objc.util.UnicodeUtils;

import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.IVariableBinding;
import org.eclipse.jdt.core.dom.Modifier;

import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * Generates Objective-C header files from compilation units.
 *
 * @author Tom Ball
 */
public class ObjectiveCHeaderGenerator extends ObjectiveCSourceFileGenerator {

  private static final String DEPRECATED_ATTRIBUTE = "__attribute__((deprecated))";

  protected final String mainTypeName;

  /**
   * Generate an Objective-C header file for each type declared in a specified
   * compilation unit.
   */
  public static void generate(String fileName, String source, CompilationUnit unit) {
    ObjectiveCHeaderGenerator headerGenerator =
        new ObjectiveCHeaderGenerator(fileName, source, unit);
    headerGenerator.generate(unit);
  }

  protected ObjectiveCHeaderGenerator(String fileName, String source, CompilationUnit unit) {
    super(fileName, source, unit, false);
    mainTypeName = NameTable.getMainTypeName(unit.jdtNode(), fileName);
  }

  @Override
  protected String getSuffix() {
    return ".h";
  }

  public void generate(CompilationUnit unit) {
    println(J2ObjC.getFileHeader(getSourceFileName()));

    generateFileHeader();

    for (AbstractTypeDeclaration type : unit.getTypes()) {
      newline();
      generate(type);
    }

    generateFileFooter();
    save(unit);
  }

  private String getSuperTypeName(TypeDeclaration node) {
    Type superType = node.getSuperclassType();
    if (superType == null) {
      return "NSObject";
    }
    return NameTable.getFullName(superType.getTypeBinding());
  }

  @Override
  public void generate(TypeDeclaration node) {
    ITypeBinding binding = node.getTypeBinding();
    String typeName = NameTable.getFullName(binding);
    String superName = getSuperTypeName(node);
    List<MethodDeclaration> methods = TreeUtil.getMethodDeclarationsList(node);
    boolean isInterface = node.isInterface();

    printConstantDefines(node);

    printDocComment(node.getJavadoc());
    if (needsDeprecatedAttribute(node.getAnnotations())) {
      println(DEPRECATED_ATTRIBUTE);
    }

    if (isInterface) {
      printf("@protocol %s", typeName);
    } else {
      printf("@interface %s : %s", typeName, superName);
    }
    List<Type> interfaces = node.getSuperInterfaceTypes();
    if (!interfaces.isEmpty()) {
      print(" < ");
      for (Iterator<Type> iterator = interfaces.iterator(); iterator.hasNext();) {
        print(NameTable.getFullName(iterator.next().getTypeBinding()));
        if (iterator.hasNext()) {
          print(", ");
        }
      }
      print(isInterface ? ", NSObject, JavaObject >" : " >");
    } else if (isInterface) {
      println(" < NSObject, JavaObject >");
    }
    if (!isInterface) {
      println(" {");
      printInstanceVariables(node);
      println("}");
    }
    printMethods(methods);
    println("\n@end");

    if (isInterface) {
      printStaticInterface(node, methods);
    } else {
      printStaticInitFunction(node, methods);
      printFieldSetters(node);
      printStaticFields(node);
    }

    printIncrementAndDecrementFunctions(binding);

    String pkg = binding.getPackage().getName();
    if (NameTable.hasPrefix(pkg) && binding.isTopLevel()) {
      String unprefixedName = NameTable.camelCaseQualifiedName(binding.getQualifiedName());
      if (binding.isInterface()) {
        // Protocols can't be used in typedefs.
        printf("\n#define %s %s\n", unprefixedName, typeName);
      } else {
        printf("\ntypedef %s %s;\n", typeName, unprefixedName);
      }
    }
    printExternalNativeMethodCategory(node, typeName);
  }

  private static final Set<String> NEEDS_INC_AND_DEC = ImmutableSet.of(
      "int", "long", "double", "float", "short", "byte", "char");

  private void printIncrementAndDecrementFunctions(ITypeBinding type) {
    ITypeBinding primitiveType = Types.getPrimitiveType(type);
    if (primitiveType == null || !NEEDS_INC_AND_DEC.contains(primitiveType.getName())) {
      return;
    }
    String primitiveName = primitiveType.getName();
    String valueMethod = primitiveName + "Value";
    if (primitiveName.equals("long")) {
      valueMethod = "longLongValue";
    } else if (primitiveName.equals("byte")) {
      valueMethod = "charValue";
    }
    newline();
    printf("BOXED_INC_AND_DEC(%s, %s, %s)\n", NameTable.capitalize(primitiveName), valueMethod,
           NameTable.getFullName(type));
  }

  @Override
  protected void generate(AnnotationTypeDeclaration node) {
    String typeName = NameTable.getFullName(node.getTypeBinding());
    List<AnnotationTypeMemberDeclaration> members = Lists.newArrayList(
        Iterables.filter(node.getBodyDeclarations(), AnnotationTypeMemberDeclaration.class));

    printConstantDefines(node);

    boolean isRuntime = BindingUtil.isRuntimeAnnotation(node.getTypeBinding());

    // Print annotation as protocol.
    printf("@protocol %s < JavaLangAnnotationAnnotation >\n", typeName);
    if (!members.isEmpty() && isRuntime) {
      newline();
      printAnnotationProperties(members);
    }
    println("\n@end");

    Iterable<IVariableBinding> staticFields = getStaticFieldsNeedingAccessors(node);

    if (isRuntime || !Iterables.isEmpty(staticFields)) {
      // Print annotation implementation interface.
      printf("\n@interface %s : NSObject < %s >", typeName, typeName);
      if (isRuntime) {
        if (members.isEmpty()) {
          newline();
        } else {
          println(" {\n @private");
          printAnnotationVariables(members);
          println("}");
        }
        printAnnotationConstructor(node.getTypeBinding());
        printAnnotationAccessors(members);
      } else {
        newline();
      }
      println("\n@end");
      printStaticInitFunction(node, TreeUtil.getMethodDeclarationsList(node));
      for (IVariableBinding field : staticFields) {
        printStaticField(field);
      }
    }
  }

  private void printExternalNativeMethodCategory(TypeDeclaration node, String typeName) {
    final List<MethodDeclaration> externalMethods = Lists.newArrayList();
    node.accept(new TreeVisitor() {
      @Override
      public void endVisit(MethodDeclaration node) {
        if ((node.getModifiers() & Modifier.NATIVE) > 0 && !hasNativeCode(node)) {
          externalMethods.add(node);
        }
      }
    });
    if (!externalMethods.isEmpty()) {
      printf("\n@interface %s (NativeMethods)\n", typeName);
      for (MethodDeclaration m : externalMethods) {
        print(super.methodDeclaration(m));
        println(";");
      }
      println("@end");
    }
  }

  private void printStaticInterface(TypeDeclaration node, List<MethodDeclaration> methods) {
    // Print @interface for static constants, if any.
    if (hasInitializeMethod(node, methods)) {
      ITypeBinding binding = node.getTypeBinding();
      String typeName = NameTable.getFullName(binding);
      printf("\n@interface %s : NSObject\n", typeName);
      println("\n@end");
    }
    printStaticInitFunction(node, methods);
    for (IVariableBinding field : getStaticFieldsNeedingAccessors(node)) {
      printStaticField(field);
    }
  }

  @Override
  protected void generate(EnumDeclaration node) {
    printConstantDefines(node);
    String typeName = NameTable.getFullName(node.getTypeBinding());
    List<EnumConstantDeclaration> constants = node.getEnumConstants();

    // Strip enum type suffix.
    String bareTypeName = typeName.endsWith("Enum") ?
        typeName.substring(0, typeName.length() - 4) : typeName;

    // C doesn't allow empty enum declarations.  Java does, so we skip the
    // C enum declaration and generate the type declaration.
    if (!constants.isEmpty()) {
      println("typedef enum {");

      // Print C enum typedef.
      indent();
      int ordinal = 0;
      for (EnumConstantDeclaration constant : constants) {
        printIndent();
        printf("%s_%s = %d,\n", bareTypeName, constant.getName().getIdentifier(), ordinal++);
      }
      unindent();
      printf("} %s;\n\n", bareTypeName);
    }

    List<MethodDeclaration> methods = TreeUtil.getMethodDeclarationsList(node);

    if (needsDeprecatedAttribute(node.getAnnotations())) {
      println(DEPRECATED_ATTRIBUTE);
    }

    // Print enum type.
    printf("@interface %s : JavaLangEnum < NSCopying", typeName);
    ITypeBinding enumType = node.getTypeBinding();
    for (ITypeBinding intrface : enumType.getInterfaces()) {
      if (!intrface.getName().equals(("Cloneable"))) { // Cloneable handled below.
        printf(", %s", NameTable.getFullName(intrface));
      }
    }
    println(" > {");
    printInstanceVariables(node);
    println("}");
    println("+ (IOSObjectArray *)values;");
    printf("+ (%s *)valueOfWithNSString:(NSString *)name;\n", typeName);
    println("- (id)copyWithZone:(NSZone *)zone;");
    printMethods(methods);
    println("@end");
    printStaticInitFunction(node, methods);
    printf("\nFOUNDATION_EXPORT %s *%s_values[];\n", typeName, typeName);
    for (EnumConstantDeclaration constant : constants) {
      String varName = NameTable.getStaticVarName(constant.getVariableBinding());
      String valueName = constant.getName().getIdentifier();
      printf("\n#define %s_%s %s_values[%s_%s]\n",
             typeName, varName, typeName, bareTypeName, valueName);
      printf("J2OBJC_STATIC_FIELD_GETTER(%s, %s, %s *)\n", typeName, varName, typeName);
    }
    printStaticFields(node);
    printFieldSetters(node);
  }

  private void printStaticInitFunction(
      AbstractTypeDeclaration node, List<MethodDeclaration> methods) {
    ITypeBinding binding = node.getTypeBinding();
    String typeName = NameTable.getFullName(binding);
    if (hasInitializeMethod(node, methods)) {
      printf("\nFOUNDATION_EXPORT BOOL %s_initialized;\n", typeName);
      printf("J2OBJC_STATIC_INIT(%s)\n", typeName);
    } else {
      printf("\n__attribute__((always_inline)) inline void %s_init() {}\n", typeName);
    }
  }

  private void printStaticFields(AbstractTypeDeclaration node) {
    for (IVariableBinding var : getStaticFieldsNeedingAccessors(node)) {
      printStaticField(var);
    }
  }

  protected void printStaticField(IVariableBinding var) {
    String objcType = NameTable.getObjCType(var.getType());
    String typeWithSpace = objcType + (objcType.endsWith("*") ? "" : " ");
    String name = NameTable.getStaticVarName(var);
    String className = NameTable.getFullName(var.getDeclaringClass());
    boolean isFinal = Modifier.isFinal(var.getModifiers());
    boolean isPrimitive = var.getType().isPrimitive();
    newline();
    if (BindingUtil.isPrimitiveConstant(var)) {
      name = var.getName();
    } else {
      printf("FOUNDATION_EXPORT %s%s_%s;\n", typeWithSpace, className, name);
    }
    printf("J2OBJC_STATIC_FIELD_GETTER(%s, %s, %s)\n", className, name, objcType);
    if (!isFinal) {
      if (isPrimitive) {
        printf("J2OBJC_STATIC_FIELD_REF_GETTER(%s, %s, %s)\n", className, name, objcType);
      } else {
        printf("J2OBJC_STATIC_FIELD_SETTER(%s, %s, %s)\n", className, name, objcType);
      }
    }
  }

  @Override
  protected void printNormalMethod(MethodDeclaration m) {
    IMethodBinding binding = m.getMethodBinding();
    if (binding.isSynthetic()) {
      return;
    }
    if ((m.getModifiers() & Modifier.NATIVE) > 0 && !hasNativeCode(m)) {
      return;
    }
    newline();
    printDocComment(m.getJavadoc());
    print(super.methodDeclaration(m));
    String methodName = NameTable.getName(m.getMethodBinding());
    if (needsObjcMethodFamilyNoneAttribute(methodName)) {
         // Getting around a clang warning.
         // clang assumes that methods with names starting with new, alloc or copy
         // return objects of the same type as the receiving class, regardless of
         // the actual declared return type. This attribute tells clang to not do
         // that, please.
         // See http://clang.llvm.org/docs/AutomaticReferenceCounting.html
         // Sections 5.1 (Explicit method family control)
         // and 5.2.2 (Related result types)
         print(" OBJC_METHOD_FAMILY_NONE");
       }

    if (needsDeprecatedAttribute(m.getAnnotations())) {
      print(" " + DEPRECATED_ATTRIBUTE);
    }
    println(";");
  }

  private boolean needsObjcMethodFamilyNoneAttribute(String name) {
    return name.startsWith("new") || name.startsWith("copy") || name.startsWith("alloc")
        || name.startsWith("init") || name.startsWith("mutableCopy");
  }

  @Override
  protected void printMappedMethodDeclaration(MethodDeclaration m, IOSMethod mappedMethod) {
    newline();
    printDocComment(m.getJavadoc());
    println(super.mappedMethodDeclaration(m, mappedMethod) + ";");
  }

  @Override
  protected void printConstructor(MethodDeclaration m) {
    newline();
    printDocComment(m.getJavadoc());
    println(super.constructorDeclaration(m) + ";");
  }

  @Override
  protected void printStaticConstructorDeclaration(MethodDeclaration m) {
    // Don't do anything.
  }

  @Override
  protected void printMethod(MethodDeclaration m) {
    IMethodBinding binding = m.getMethodBinding();
    if (BindingUtil.isFunction(binding)) {
      return;  // All function declarations are private.
    }
    super.printMethod(m);
  }

  protected void printForwardDeclarations(Set<Import> forwardDecls) {
    Set<String> forwardStmts = Sets.newTreeSet();
    for (Import imp : forwardDecls) {
      forwardStmts.add(createForwardDeclaration(imp.getTypeName(), imp.isInterface()));
    }
    if (!forwardStmts.isEmpty()) {
      for (String stmt : forwardStmts) {
        println(stmt);
      }
      newline();
    }
  }

  protected void generateFileHeader() {
    printf("#ifndef _%s_H_\n", mainTypeName);
    printf("#define _%s_H_\n", mainTypeName);
    pushIgnoreDeprecatedDeclarationsPragma();
    newline();

    HeaderImportCollector collector = new HeaderImportCollector();
    collector.collect(getUnit().jdtNode());

    printForwardDeclarations(collector.getForwardDeclarations());

    println("#import \"JreEmulation.h\"");

    // Print collected includes.
    Set<Import> superTypes = collector.getSuperTypes();
    if (!superTypes.isEmpty()) {
      Set<String> includeStmts = Sets.newTreeSet();
      for (Import imp : superTypes) {
        includeStmts.add(String.format("#include \"%s.h\"", imp.getImportFileName()));
      }
      for (String stmt : includeStmts) {
        println(stmt);
      }
    }
  }

  protected String createForwardDeclaration(String typeName, boolean isInterface) {
    return String.format("@%s %s;", isInterface ? "protocol" : "class", typeName);
  }

  protected void generateFileFooter() {
    newline();
    popIgnoreDeprecatedDeclarationsPragma();
    printf("#endif // _%s_H_\n", mainTypeName);
  }

  private void printInstanceVariables(AbstractTypeDeclaration node) {
    indent();
    boolean first = true;
    for (FieldDeclaration field : TreeUtil.getFieldDeclarations(node)) {
      if (!Modifier.isStatic(field.getModifiers())) {
        List<VariableDeclarationFragment> vars = field.getFragments();
        assert !vars.isEmpty();
        IVariableBinding varBinding = vars.get(0).getVariableBinding();
        ITypeBinding varType = varBinding.getType();
        // Need direct access to fields possibly from inner classes that are
        // promoted to top level classes, so must make all fields public.
        if (first) {
          println(" @public");
          first = false;
        }
        printDocComment(field.getJavadoc());
        printIndent();
        if (BindingUtil.isWeakReference(varBinding)) {
          // We must add this even without -use-arc because the header may be
          // included by a file compiled with ARC.
          print("__weak ");
        }
        String objcType = NameTable.getSpecificObjCType(varType);
        boolean needsAsterisk = !varType.isPrimitive() && !objcType.matches("id|id<.*>|Class");
        if (needsAsterisk && objcType.endsWith(" *")) {
          // Strip pointer from type, as it will be added when appending fragment.
          // This is necessary to create "Foo *one, *two;" declarations.
          objcType = objcType.substring(0, objcType.length() - 2);
        }
        print(objcType);
        print(' ');
        for (Iterator<VariableDeclarationFragment> it = field.getFragments().iterator();
             it.hasNext(); ) {
          VariableDeclarationFragment f = it.next();
          if (needsAsterisk) {
            print('*');
          }
          String name = NameTable.getName(f.getName().getBinding());
          print(NameTable.javaFieldToObjC(name));
          if (it.hasNext()) {
            print(", ");
          }
        }
        println(";");
      }
    }
    unindent();
  }

  private void printAnnotationVariables(List<AnnotationTypeMemberDeclaration> members) {
    indent();
    for (AnnotationTypeMemberDeclaration member : members) {
      printIndent();
      ITypeBinding type = member.getMethodBinding().getReturnType();
      print(NameTable.getObjCType(type));
      if (type.isPrimitive() || type.isInterface()) {
        print(' ');
      }
      print(member.getName().getIdentifier());
      println(";");
    }
    unindent();
  }

  private void printAnnotationConstructor(ITypeBinding annotation) {
    if (annotation.getDeclaredMethods().length > 0) {
      newline();
      print(annotationConstructorDeclaration(annotation));
      println(";");
    }
  }

  private void printFieldSetters(AbstractTypeDeclaration node) {
    ITypeBinding declaringType = node.getTypeBinding();
    boolean newlinePrinted = false;
    for (FieldDeclaration field : TreeUtil.getFieldDeclarations(node)) {
      ITypeBinding type = field.getType().getTypeBinding();
      int modifiers = field.getModifiers();
      if (Modifier.isStatic(modifiers) || type.isPrimitive()) {
        continue;
      }
      String typeStr = NameTable.getObjCType(type);
      String declaringClassName = NameTable.getFullName(declaringType);
      for (VariableDeclarationFragment var : field.getFragments()) {
        if (BindingUtil.isWeakReference(var.getVariableBinding())) {
          continue;
        }
        String fieldName = NameTable.javaFieldToObjC(NameTable.getName(var.getName().getBinding()));
        if (!newlinePrinted) {
          newlinePrinted = true;
          newline();
        }
        println(String.format("J2OBJC_FIELD_SETTER(%s, %s, %s)",
            declaringClassName, fieldName, typeStr));
      }
    }
  }

  private void printAnnotationProperties(List<AnnotationTypeMemberDeclaration> members) {
    int nPrinted = 0;
    for (AnnotationTypeMemberDeclaration member : members) {
      ITypeBinding type = member.getType().getTypeBinding();
      print("@property (readonly) ");
      String typeString = NameTable.getSpecificObjCType(type);
      String propertyName = NameTable.getName(member.getName().getBinding());
      println(String.format("%s%s%s;", typeString, typeString.endsWith("*") ? "" : " ",
          propertyName));
      if (needsObjcMethodFamilyNoneAttribute(propertyName)) {
        println(String.format("- (%s)%s OBJC_METHOD_FAMILY_NONE;", typeString, propertyName));
      }
      nPrinted++;
    }
    if (nPrinted > 0) {
      newline();
    }
  }

  private void printAnnotationAccessors(List<AnnotationTypeMemberDeclaration> members) {
    boolean printedNewline = false;
    for (AnnotationTypeMemberDeclaration member : members) {
      if (member.getDefault() != null) {
        if (!printedNewline) {
          newline();
          printedNewline = true;
        }
        ITypeBinding type = member.getType().getTypeBinding();
        String typeString = NameTable.getSpecificObjCType(type);
        String propertyName = NameTable.getName(member.getName().getBinding());
        printf("+ (%s)%sDefault;\n", typeString, propertyName);
      }
    }
  }

  private void printConstantDefines(AbstractTypeDeclaration node) {
    ITypeBinding type = node.getTypeBinding();
    boolean hadConstant = false;
    for (IVariableBinding field : type.getDeclaredFields()) {
      if (BindingUtil.isPrimitiveConstant(field)) {
        printf("#define %s ", NameTable.getPrimitiveConstantName(field));
        Object value = field.getConstantValue();
        assert value != null;
        if (value instanceof Boolean) {
          println(((Boolean) value).booleanValue() ? "TRUE" : "FALSE");
        } else if (value instanceof Character) {
          println(UnicodeUtils.escapeCharLiteral(((Character) value).charValue()));
        } else if (value instanceof Long) {
          long l = ((Long) value).longValue();
          if (l == Long.MIN_VALUE) {
            println("((long long) 0x8000000000000000LL)");
          } else {
            println(value.toString() + "LL");
          }
        } else if (value instanceof Integer) {
          long l = ((Integer) value).intValue();
          if (l == Integer.MIN_VALUE) {
            println("((int) 0x80000000)");
          } else {
            println(value.toString());
          }
        } else if (value instanceof Float) {
          float f = ((Float) value).floatValue();
          if (Float.isNaN(f)) {
            println("NAN");
          } else if (f == Float.POSITIVE_INFINITY) {
            println("INFINITY");
          } else if (f == Float.NEGATIVE_INFINITY) {
            // FP representations are symmetrical.
            println("-INFINITY");
          } else if (f == Float.MAX_VALUE) {
            println("__FLT_MAX__");
          } else if (f == Float.MIN_NORMAL) {
            println("__FLT_MIN__");
          } else {
            println(value.toString() + "f");
          }
        } else if (value instanceof Double) {
          double d = ((Double) value).doubleValue();
          if (Double.isNaN(d)) {
            println("NAN");
          } else if (d == Double.POSITIVE_INFINITY) {
            println("INFINITY");
          } else if (d == Double.NEGATIVE_INFINITY) {
            // FP representations are symmetrical.
            println("-INFINITY");
          } else if (d == Double.MAX_VALUE) {
            println("__DBL_MAX__");
          } else if (d == Double.MIN_NORMAL) {
            println("__DBL_MIN__");
          } else {
            println(value.toString());
          }
        } else {
          println(value.toString());
        }
        hadConstant = true;
      }
    }
    if (hadConstant) {
      newline();
    }
  }

  private boolean needsDeprecatedAttribute(List<Annotation> annotations) {
    return Options.generateDeprecatedDeclarations() && hasDeprecated(annotations);
  }

  /**
   * Checks if the list of modifiers contains a Deprecated annotation.
   *
   * @param modifiers extended modifiers
   * @return true if the list has {@link Deprecated @Deprecated}, false otherwise
   */
  boolean hasDeprecated(List<Annotation> annotations) {
    for (Annotation annotation : annotations) {
      Name annotationTypeName = annotation.getTypeName();
      String expectedTypeName = annotationTypeName.isQualifiedName() ?
          "java.lang.Deprecated" : "Deprecated";
      if (expectedTypeName.equals(annotationTypeName.getFullyQualifiedName())) {
        return true;
      }
    }

    return false;
  }
}
