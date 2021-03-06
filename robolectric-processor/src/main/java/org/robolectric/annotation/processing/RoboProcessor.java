package org.robolectric.annotation.processing;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Filer;
import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.SourceVersion;
import javax.tools.Diagnostic.Kind;
import javax.tools.JavaFileObject;

/**
 * Annotation processor entry point for Robolectric annotations.
 */
@SupportedAnnotationTypes("org.robolectric.annotation.*")
@SupportedSourceVersion(SourceVersion.RELEASE_6)
public class RoboProcessor extends AbstractProcessor {

  RoboModel model;
  private Messager messager;
  private Map<TypeElement,Validator> elementValidators =
      new HashMap<TypeElement,Validator>(13);
  
  private void addValidator(Validator v) {
    elementValidators.put(v.annotationType, v);
  }
  
  @Override
  public void init(ProcessingEnvironment env) {
    super.init(env);
    model = new RoboModel(env.getElementUtils(),
                          env.getTypeUtils());
    messager = processingEnv.getMessager();
    messager.printMessage(Kind.NOTE, "Initialising RAP");
    addValidator(new ImplementationValidator(model, env));
    addValidator(new ImplementsValidator(model, env));
    addValidator(new RealObjectValidator(model, env));
    addValidator(new ResetterValidator(model, env));
  }

  private boolean generated = false;
  
  @Override
  public boolean process(Set<? extends TypeElement> annotations,
      RoundEnvironment roundEnv) {
    for (TypeElement annotation : annotations) {
      Validator validator = elementValidators.get(annotation);
      if (validator != null) {
        for (Element elem : roundEnv.getElementsAnnotatedWith(annotation)) {
          validator.visit(elem, elem.getEnclosingElement());
        }
      }
    }
    
    if (!generated) {
      model.prepare();
      render();
      generated = true;
    }
    return true;
  }
  
  private static final String GEN_PACKAGE = "org.robolectric";
  private static final String GEN_CLASS   = "Shadows";
  private static final String GEN_FQ      = GEN_PACKAGE + '.' + GEN_CLASS;
  
  private void render() {
    // TODO: Because this was fairly simple to begin with I haven't
    // included a templating engine like Velocity but simply used
    // raw print() statements, in an effort to reduce the number of
    // dependencies that RAP has. However, if it gets too complicated
    // then using Velocity might be a good idea.
    
    messager.printMessage(Kind.NOTE, "Generating output file " + GEN_FQ);
    final Filer filer = processingEnv.getFiler();
    try {
      JavaFileObject jfo = filer.createSourceFile(GEN_FQ);
      PrintWriter writer = new PrintWriter(jfo.openWriter());
      try {
      writer.print("package " + GEN_PACKAGE + ";\n");
      for (String name: model.imports) {
        writer.println("import " + name + ';');
      }
      writer.println();
      writer.println("/**");
      writer.println(" * Main Robolectric entry point. Automatically generated by the Robolectric Annotation Processor.");
      writer.println(" */");
      writer.println("@Generated(\"" + RoboProcessor.class.getCanonicalName() + "\")");
      writer.println("public class " + GEN_CLASS + " {");
      writer.println();
      writer.print  ("  public static final Class<?>[] DEFAULT_SHADOW_CLASSES = {");
      boolean firstIteration = true;
      for (TypeElement shadow : model.shadowTypes.keySet()) {
        if (firstIteration) {
          firstIteration = false;
        } else {
          writer.print(",");
        }
        writer.print("\n    " + model.getReferentFor(shadow) + ".class");
      }
      writer.println("\n  };\n");
      for (Entry<TypeElement,TypeElement> entry: model.getShadowMap().entrySet()) {
        final TypeElement actualType = entry.getValue();
        if (!actualType.getModifiers().contains(Modifier.PUBLIC)) {
          continue;
        }
        // Generics not handled specifically as yet.
//        int paramCount = 0;
//        StringBuilder builder = new StringBuilder("<");
//        for (TypeParameterElement typeParam : entry.getValue().getTypeParameters()) {
//          if (paramCount > 0) {
//            builder.append(',');
//          }
//          builder.append(typeParam).append(" extends ");
//          for (TypeMirror bound : typeParam.getBounds()) {
//            builder.append(bound).append(" & ");
//          }
//          paramCount++;
//          processingEnv.getElementUtils().printElements(writer, typeParam);
//        }
//        final String typeString = paramCount > 0 ? builder.append("> ").toString() : "";
        
        final String actual = model.getReferentFor(actualType);
        final String shadow = model.getReferentFor(entry.getKey());
        writer.println("  public static " + shadow + " shadowOf(" + actual + " actual) {"); 
        writer.println("    return (" + shadow + ") shadowOf_(actual);");
        writer.println("  }");
        writer.println();
      }
      writer.println("  public static void reset() {");
      for (Entry<TypeElement,ExecutableElement> entry: model.resetterMap.entrySet()) {
        writer.println("    " + model.getReferentFor(entry.getKey()) + "." + entry.getValue().getSimpleName() + "();");
      }
      writer.println("  }\n");

      writer.println("  @SuppressWarnings({\"unchecked\"})");
      writer.println("  public static <P, R> P shadowOf_(R instance) {");
      writer.println("    return (P) ShadowExtractor.extract(instance);");
      writer.println("  }");
      
      writer.println('}');
      } finally {
        writer.close();
      }
    } catch (IOException e) {
      // TODO: Better error handling?
      throw new RuntimeException(e);
    }
  }
}
