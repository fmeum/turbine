/*
 * Copyright 2026 Google Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.turbine.binder;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.collect.ImmutableList;
import com.google.turbine.binder.bytecode.BytecodeBoundClass;
import com.google.turbine.binder.lookup.LookupKey;
import com.google.turbine.binder.lookup.LookupResult;
import com.google.turbine.binder.lookup.PackageScope;
import com.google.turbine.binder.sym.ClassSymbol;
import com.google.turbine.binder.sym.ModuleSymbol;
import com.google.turbine.main.Main;
import com.google.turbine.options.TurbineOptions;
import com.google.turbine.parallel.TurbineExecutor;
import com.google.turbine.tree.Tree.Ident;
import java.io.IOException;
import java.net.URI;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

@RunWith(JUnit4.class)
public class LazyClassPathBinderTest {

  @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

  private static byte[] classFile(String name) {
    ClassWriter cw = new ClassWriter(0);
    cw.visit(
        Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, name, null, "java/lang/Object", null);
    cw.visitEnd();
    return cw.toByteArray();
  }

  private static byte[] moduleInfo(String name) {
    ClassWriter cw = new ClassWriter(0);
    cw.visit(Opcodes.V17, Opcodes.ACC_MODULE, "module-info", null, null, null);
    cw.visitModule(name, 0, null).visitEnd();
    cw.visitEnd();
    return cw.toByteArray();
  }

  /** Writes a jar with the given entries, in iteration order. */
  private Path jar(Map<String, byte[]> entries) throws IOException {
    Path path = temporaryFolder.newFile().toPath();
    try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(path))) {
      for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
        jos.putNextEntry(new JarEntry(entry.getKey()));
        jos.write(entry.getValue());
      }
    }
    return path;
  }

  private static LookupKey key(String... names) {
    ImmutableList.Builder<Ident> idents = ImmutableList.builder();
    for (String name : names) {
      idents.add(new Ident(0, name));
    }
    return new LookupKey(idents.build());
  }

  private static String describe(@Nullable LookupResult result) {
    return result == null
        ? "null"
        : result.sym() + " " + result.remaining().stream().map(Ident::value).toList();
  }

  private static String describe(@Nullable PackageScope scope) {
    if (scope == null) {
      return "null";
    }
    TreeSet<String> classes = new TreeSet<>();
    for (ClassSymbol sym : scope.classes()) {
      classes.add(sym.binaryName());
    }
    return classes.toString();
  }

  /** Asserts that all lookups for the entries of the given jar have the same results. */
  private static void assertSameLookups(Path jar, ClassPath expected, ClassPath actual)
      throws IOException {
    List<String> names = new ArrayList<>();
    try (JarFile jarFile = new JarFile(jar.toFile())) {
      jarFile.stream().forEach(e -> names.add(e.getName()));
    }
    TreeSet<String> packages = new TreeSet<>();
    packages.add("");
    for (String name : names) {
      if (!name.endsWith(".class")) {
        assertWithMessage(name).that(resource(actual, name)).isEqualTo(resource(expected, name));
        continue;
      }
      String binaryName = name.substring(0, name.length() - ".class".length());
      ClassSymbol sym = new ClassSymbol(binaryName);
      BytecodeBoundClass expectedClass = expected.env().get(sym);
      BytecodeBoundClass actualClass = actual.env().get(sym);
      assertWithMessage(binaryName).that(actualClass == null).isEqualTo(expectedClass == null);
      if (expectedClass != null && actualClass != null) {
        assertWithMessage(binaryName)
            .that(actualClass.jarFile())
            .isEqualTo(expectedClass.jarFile());
        assertWithMessage(binaryName).that(actual.env().get(sym)).isSameInstanceAs(actualClass);
      }
      String[] parts = binaryName.split("/", -1);
      String[] withMember = Arrays.copyOf(parts, parts.length + 1);
      withMember[parts.length] = "Member";
      for (String[] lookup : List.of(parts, withMember)) {
        assertWithMessage(String.join(".", lookup))
            .that(describe(actual.index().scope().lookup(key(lookup))))
            .isEqualTo(describe(expected.index().scope().lookup(key(lookup))));
      }
      for (int i = 1; i < parts.length; i++) {
        packages.add(String.join("/", Arrays.copyOf(parts, i)));
      }
    }
    for (String pkg : packages) {
      List<String> bits = pkg.isEmpty() ? List.of() : List.of(pkg.split("/", -1));
      PackageScope expectedPackage = expected.index().lookupPackage(bits);
      PackageScope actualPackage = actual.index().lookupPackage(bits);
      assertWithMessage(pkg).that(describe(actualPackage)).isEqualTo(describe(expectedPackage));
      if (expectedPackage != null && actualPackage != null) {
        for (String simpleName : List.of("A", "B", "Missing", "A$Inner", "y", "module-info")) {
          assertWithMessage("%s/%s", pkg, simpleName)
              .that(describe(actualPackage.lookup(key(simpleName))))
              .isEqualTo(describe(expectedPackage.lookup(key(simpleName))));
        }
      }
    }
  }

  private static byte @Nullable [] resource(ClassPath classPath, String name) {
    var resource = classPath.resource(name);
    return resource == null ? null : resource.get();
  }

  @Test
  public void sameLookupsAsEagerBinding() throws Exception {
    Map<String, byte[]> entries = new TreeMap<>();
    for (String name :
        List.of("Top", "a/A", "a/A$Inner", "a/B", "a/b/C", "a/b/c/D", "x/y", "x/y/Z", "x/z/W")) {
      entries.put(name + ".class", classFile(name));
    }
    entries.put("module-info.class", moduleInfo("root.module"));
    entries.put("m/module-info.class", moduleInfo("nested.module"));
    entries.put("a/resource.txt", "hello".getBytes(UTF_8));
    entries.put("r/only/resources.txt", "world".getBytes(UTF_8));
    entries.put(
        "META-INF/MANIFEST.MF",
        "Manifest-Version: 1.0\r\nOriginal-Jar-Path: original.jar\r\n\r\n".getBytes(UTF_8));
    Path jar = jar(entries);

    ClassPath eager = ClassPathBinder.bindClasspath(TurbineExecutor.direct(), List.of(jar));
    ClassPath lazy = LazyClassPathBinder.bind(ImmutableList.of(jar));
    assertThat(lazy).isNotNull();

    assertSameLookups(jar, eager, lazy);
    // A class shadows a package with the same name.
    assertThat(describe(lazy.index().scope().lookup(key("x", "y", "Z")))).isEqualTo("x.y [Z]");
    assertThat(lazy.index().lookupPackage(List.of("x", "y"))).isNull();
    // Packages that only contain resources don't exist.
    assertThat(lazy.index().lookupPackage(List.of("r"))).isNull();
    for (String module : List.of("root.module", "nested.module", "missing")) {
      ModuleSymbol sym = new ModuleSymbol(module);
      assertWithMessage(module)
          .that(lazy.moduleEnv().get(sym) == null)
          .isEqualTo(eager.moduleEnv().get(sym) == null);
    }
    assertThat(lazy.moduleEnv().get(new ModuleSymbol("root.module"))).isNotNull();
    assertThat(lazy.env().get(new ClassSymbol("a/A")).jarFile()).isEqualTo("original.jar");
  }

  @Test
  public void sameLookupsAsEagerBinding_jdkClasses() throws Exception {
    Path jar = jar(jdkClasses("java/lang", "java/util"));
    ClassPath eager = ClassPathBinder.bindClasspath(TurbineExecutor.direct(), List.of(jar));
    ClassPath lazy = LazyClassPathBinder.bind(ImmutableList.of(jar));
    assertThat(lazy).isNotNull();
    assertSameLookups(jar, eager, lazy);
  }

  @Test
  public void unsupported() throws Exception {
    Map<String, byte[]> unsorted = new LinkedHashMap<>();
    unsorted.put("b/B.class", classFile("b/B"));
    unsorted.put("a/A.class", classFile("a/A"));
    assertThat(LazyClassPathBinder.bind(ImmutableList.of(jar(unsorted)))).isNull();

    Map<String, byte[]> transitive = new TreeMap<>();
    transitive.put("META-INF/TRANSITIVE/a/A.turbine", classFile("a/A"));
    transitive.put("b/B.class", classFile("b/B"));
    assertThat(LazyClassPathBinder.bind(ImmutableList.of(jar(transitive)))).isNull();

    Map<String, byte[]> sorted = new TreeMap<>();
    sorted.put("a/A.class", classFile("a/A"));
    Path jar = jar(sorted);
    assertThat(LazyClassPathBinder.bind(ImmutableList.of())).isNull();
    assertThat(LazyClassPathBinder.bind(ImmutableList.of(jar, jar))).isNull();
    assertThat(LazyClassPathBinder.bind(ImmutableList.of(jar))).isNotNull();
  }

  /** Compiling against a lazily bound bootclasspath produces the same output as eager binding. */
  @Test
  public void compile() throws Exception {
    Map<String, byte[]> jdk =
        jdkClasses("java/lang", "java/lang/annotation", "java/util", "java/io");
    Map<String, byte[]> javaLang = new TreeMap<>();
    Map<String, byte[]> rest = new TreeMap<>();
    jdk.forEach(
        (name, bytes) -> (name.startsWith("java/lang/") ? javaLang : rest).put(name, bytes));
    Path single = jar(jdk);
    // The lazy binding is only used for a single jar, so splitting the same classes across two
    // jars uses the eager binding.
    ImmutableList<Path> split = ImmutableList.of(jar(javaLang), jar(rest));

    Path source = temporaryFolder.newFile("Test.java").toPath();
    Files.writeString(
        source,
        """
        package p;
        import java.util.*;
        import java.lang.annotation.*;
        @Retention(RetentionPolicy.RUNTIME)
        @interface A {
          Thread.State value() default Thread.State.NEW;
        }
        @A(Thread.State.BLOCKED)
        public abstract class Test<T extends Comparable<T>> extends AbstractList<T>
            implements Map.Entry<String, List<T>>, java.io.Serializable {
          public static final int X = Integer.MAX_VALUE - Byte.SIZE;
          public Map.Entry<String, ? extends Iterable<T>> f;
          public abstract Optional<Thread.UncaughtExceptionHandler> g() throws java.io.IOException;
        }
        """);
    assertThat(compile(source, ImmutableList.of(single))).isEqualTo(compile(source, split));
  }

  private byte[] compile(Path source, ImmutableList<Path> bootclasspath) throws IOException {
    Path output = temporaryFolder.newFile().toPath();
    Main.compile(
        TurbineOptions.builder()
            .setSources(ImmutableList.of(source.toString()))
            .setBootClassPath(bootclasspath.stream().map(Path::toString).collect(toImmutableList()))
            .setOutput(output.toString())
            .build());
    try (JarFile jarFile = new JarFile(output.toFile())) {
      return jarFile.getInputStream(jarFile.getJarEntry("p/Test.class")).readAllBytes();
    }
  }

  private static Map<String, byte[]> jdkClasses(String... packages) throws IOException {
    Map<String, byte[]> result = new TreeMap<>();
    Path base = FileSystems.getFileSystem(URI.create("jrt:/")).getPath("/modules/java.base");
    for (String pkg : packages) {
      try (Stream<Path> files = Files.list(base.resolve(pkg))) {
        for (Path file : files.toList()) {
          String name = base.relativize(file).toString();
          if (name.endsWith(".class")) {
            result.put(name, Files.readAllBytes(file));
          }
        }
      }
    }
    return result;
  }
}
