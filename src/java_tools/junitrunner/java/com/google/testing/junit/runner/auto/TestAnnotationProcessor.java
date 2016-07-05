// Copyright 2015 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.testing.junit.runner.auto;

import java.io.IOException;
import java.io.Writer;
import java.util.LinkedHashSet;
import java.util.Set;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic.Kind;
import javax.tools.FileObject;
import javax.tools.StandardLocation;

@SupportedAnnotationTypes({
  "org.junit.runner.RunWith",
  "org.junit.Test",
})
@SupportedSourceVersion(SourceVersion.RELEASE_8)
public final class TestAnnotationProcessor extends AbstractProcessor {
  static final String SERVICES_FILENAME =
      "META-INF/services/com.google.testing.junit.runner.auto.TestAnnotationProcessor";

  private Set<String> testClasses = new LinkedHashSet<String>();

  public TestAnnotationProcessor() {
  }

  @Override
  public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
    if (annotations.isEmpty()) {
      try {
        FileObject file = processingEnv.getFiler().createResource(
            StandardLocation.CLASS_OUTPUT, "", SERVICES_FILENAME);
        Writer out = file.openWriter();
        for (String testClass : testClasses) {
          out.append(testClass).append("\n");
        }
        out.close();
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
      return false;
    }
    for (TypeElement e : annotations) {
      log("TypeElement=" + e.toString());
      for (Element element : roundEnv.getElementsAnnotatedWith(e)) {
        log("Element=" + element.toString());
        if (element.getKind() == ElementKind.CLASS) {
          testClasses.add(element.toString());
        } else if (element.getKind() == ElementKind.METHOD) {
          log("EnclosingElement=" + element.getEnclosingElement().toString());
          testClasses.add(element.getEnclosingElement().toString());
        }
      }
    }
    return false;
  }

  @SuppressWarnings("unused")
  private void log(String msg) {
    processingEnv.getMessager().printMessage(Kind.WARNING, msg, null);
  }
}
