/*
 * Copyright 2018 Google Inc. All Rights Reserved.
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

import static com.google.common.base.StandardSystemProperty.JAVA_HOME;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.primitives.ImmutableIntArray;
import com.google.errorprone.annotations.concurrent.LazyInit;
import com.google.turbine.binder.bound.ModuleInfo;
import com.google.turbine.binder.bytecode.BytecodeBinder;
import com.google.turbine.binder.bytecode.BytecodeBoundClass;
import com.google.turbine.binder.env.Env;
import com.google.turbine.binder.env.SimpleEnv;
import com.google.turbine.binder.lookup.SimpleTopLevelIndex;
import com.google.turbine.binder.lookup.TopLevelIndex;
import com.google.turbine.binder.sym.ClassSymbol;
import com.google.turbine.binder.sym.ModuleSymbol;
import com.google.turbine.zip.Zip;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;

/** Constructs a platform {@link ClassPath} from the current JDK's ct.sym file. */
public final class CtSymClassBinder {

  public static @Nullable ClassPath bind(int version) throws IOException {
    return bindLazily(ctSymPath(), version);
  }

  private static Path ctSymPath() {
    Path ctSym;
    String explicitCtSymPath = System.getProperty("turbine.ctSymPath");
    if (explicitCtSymPath == null) {
      String javaHome = JAVA_HOME.value();
      requireNonNull(javaHome, "attempted to use --release, but JAVA_HOME is not set");
      ctSym = Paths.get(javaHome).resolve("lib/ct.sym");
      if (!Files.exists(ctSym)) {
        throw new IllegalStateException("lib/ct.sym does not exist in " + javaHome);
      }
    } else {
      ctSym = Paths.get(explicitCtSymPath);
      if (!Files.exists(ctSym)) {
        throw new IllegalStateException("ct.sym does not exist at " + ctSym);
      }
    }
    return ctSym;
  }

  /**
   * Binds all classes of the given release up front. This is equivalent to {@link #bindLazily}, and
   * is only kept to test that equivalence.
   */
  @VisibleForTesting
  static @Nullable ClassPath bindEagerly(Path ctSym, int version) throws IOException {
    Map<ClassSymbol, BytecodeBoundClass> map = new HashMap<>();
    Map<ModuleSymbol, ModuleInfo> modules = new HashMap<>();
    SimpleTopLevelIndex.Builder indexBuilder = SimpleTopLevelIndex.builder();
    Env<ClassSymbol, BytecodeBoundClass> benv =
        new Env<ClassSymbol, BytecodeBoundClass>() {
          @Override
          public @Nullable BytecodeBoundClass get(ClassSymbol sym) {
            return map.get(sym);
          }
        };
    char releaseChar = formatReleaseVersion(version);
    for (Zip.Entry ze : new Zip.ZipIterable(ctSym)) {
      String name = ze.name();
      if (!name.endsWith(".sig")) {
        continue;
      }
      int idx = name.indexOf('/');
      if (idx == -1) {
        continue;
      }
      // check if the directory matches the desired release
      if (name.indexOf(releaseChar, 0, idx) == -1) {
        continue;
      }
      // JDK >= 12 includes the module name as a prefix
      idx = name.indexOf('/', idx + 1);
      if (name.endsWith("/module-info.sig")) {
        ModuleInfo moduleInfo = BytecodeBinder.bindModuleInfo(() -> ctSym + "!" + name, ze);
        modules.put(new ModuleSymbol(moduleInfo.name()), moduleInfo);
        continue;
      }
      ClassSymbol sym = new ClassSymbol(name.substring(idx + 1, name.length() - ".sig".length()));
      var bound =
          map.putIfAbsent(
              sym, new BytecodeBoundClass(sym, ze, benv, () -> ctSym + "!" + ze.name()));
      if (bound == null) {
        indexBuilder.insert(sym);
      }
    }
    if (map.isEmpty()) {
      // we didn't find any classes for the desired release
      return null;
    }
    Env<ModuleSymbol, ModuleInfo> moduleEnv = new SimpleEnv<>(ImmutableMap.copyOf(modules));
    TopLevelIndex index = indexBuilder.build();
    return new ClassPath() {
      @Override
      public Env<ClassSymbol, BytecodeBoundClass> env() {
        return benv;
      }

      @Override
      public Env<ModuleSymbol, ModuleInfo> moduleEnv() {
        return moduleEnv;
      }

      @Override
      public TopLevelIndex index() {
        return index;
      }

      @Override
      public @Nullable Supplier<byte[]> resource(String input) {
        return null;
      }
    };
  }

  private static final byte[] SIG = ".sig".getBytes(UTF_8);
  private static final Object MISSING = new Object();
  private static final byte[] SLASH_MODULE_INFO_SIG = "/module-info.sig".getBytes(UTF_8);

  /**
   * Binds the classes of the given release on demand.
   *
   * <p>Binding only scans the raw entry names of ct.sym's central directory once, to group the
   * entries for the given release by package. Only one string is decoded for each run of entries in
   * the same package, and the classes of a package are only bound when a lookup first needs them.
   * This avoids creating symbols, bound classes and index entries for the ~20,000 classes of a
   * release when a compilation only uses a few hundred of them.
   */
  @VisibleForTesting
  static @Nullable ClassPath bindLazily(Path ctSym, int version) throws IOException {
    byte releaseChar = (byte) formatReleaseVersion(version);
    Zip.ZipIterable zip = new Zip.ZipIterable(ctSym);
    Zip.Index index = Zip.Index.create(zip);
    Map<String, CtSymPackage> packages = new HashMap<>();
    ImmutableIntArray.Builder moduleInfos = ImmutableIntArray.builder();
    CtSymPackage current = null;
    int previous = -1;
    int previousStart = 0;
    int previousLength = 0;
    for (int i = 0; i < index.size(); i++) {
      if (!index.endsWith(i, SIG)) {
        continue;
      }
      int idx = index.indexOf(i, (byte) '/', 0);
      if (idx == -1) {
        continue;
      }
      // check if the directory matches the desired release
      int release = index.indexOf(i, releaseChar, 0);
      if (release == -1 || release > idx) {
        continue;
      }
      if (index.endsWith(i, SLASH_MODULE_INFO_SIG)) {
        moduleInfos.add(i);
        continue;
      }
      // JDK >= 12 includes the module name as a prefix
      int start = index.indexOf(i, (byte) '/', idx + 1) + 1;
      int packageLength = Math.max(index.lastIndexOf(i, (byte) '/') - start, 0);
      // Entries in the same package are usually adjacent, so only decode the package name if it
      // differs from the previous class's package.
      if (current == null
          || packageLength != previousLength
          || !index.regionEquals(i, start, previous, previousStart, packageLength)) {
        String packageName = index.name(i, start, start + packageLength);
        current = packages.computeIfAbsent(packageName, k -> new CtSymPackage());
      }
      current.entries.add(i);
      previous = i;
      previousStart = start;
      previousLength = packageLength;
    }
    if (packages.isEmpty()) {
      // we didn't find any classes for the desired release
      zip.close();
      return null;
    }
    return new LazyCtSym(ctSym, index, ImmutableMap.copyOf(packages), moduleInfos.build());
  }

  /** The entries of a package in ct.sym for a particular release. */
  private static final class CtSymPackage {
    final ImmutableIntArray.Builder entries = ImmutableIntArray.builder();
    @LazyInit private volatile @Nullable ImmutableMap<String, BytecodeBoundClass> classes;

    /** Returns the classes in this package by simple name, binding them on first use. */
    ImmutableMap<String, BytecodeBoundClass> classes(
        Path ctSym, Zip.Index index, Env<ClassSymbol, BytecodeBoundClass> env) {
      ImmutableMap<String, BytecodeBoundClass> local = classes;
      if (local != null) {
        return local;
      }
      synchronized (this) {
        local = classes;
        if (local == null) {
          Map<String, BytecodeBoundClass> result = new LinkedHashMap<>();
          ImmutableIntArray entries = this.entries.build();
          for (int j = 0; j < entries.length(); j++) {
            int i = entries.get(j);
            String name = index.name(i);
            // the binary name starts after the release directory and module name
            int idx = name.indexOf('/', name.indexOf('/') + 1);
            ClassSymbol sym =
                new ClassSymbol(name.substring(idx + 1, name.length() - ".sig".length()));
            result.putIfAbsent(
                sym.simpleName(),
                new BytecodeBoundClass(sym, index.entry(i), env, () -> ctSym + "!" + name));
          }
          classes = local = ImmutableMap.copyOf(result);
        }
        return local;
      }
    }
  }

  private static final class LazyCtSym extends LazyTopLevelIndex implements ClassPath {

    private final Path ctSym;
    private final Zip.Index index;
    private final ImmutableMap<String, CtSymPackage> packages;
    private final ImmutableSet<String> packagesAndAncestors;
    private final Supplier<Env<ModuleSymbol, ModuleInfo>> moduleEnv;

    /**
     * The classes that have been looked up, including classes that don't exist (mapped to {@link
     * #MISSING}), since the bootclasspath is looked up for every class.
     */
    private final ConcurrentHashMap<ClassSymbol, Object> classes = new ConcurrentHashMap<>(4096);

    private final Env<ClassSymbol, BytecodeBoundClass> env =
        new Env<ClassSymbol, BytecodeBoundClass>() {
          @Override
          public @Nullable BytecodeBoundClass get(ClassSymbol sym) {
            Object result = classes.get(sym);
            if (result == null) {
              CtSymPackage pkg = packages.get(sym.packageName());
              BytecodeBoundClass info =
                  pkg != null ? pkg.classes(ctSym, index, this).get(sym.simpleName()) : null;
              result = info != null ? info : MISSING;
              Object previous = classes.putIfAbsent(sym, result);
              if (previous != null) {
                result = previous;
              }
            }
            return result instanceof BytecodeBoundClass info ? info : null;
          }
        };

    LazyCtSym(
        Path ctSym,
        Zip.Index index,
        ImmutableMap<String, CtSymPackage> packages,
        ImmutableIntArray moduleInfos) {
      this.ctSym = ctSym;
      this.index = index;
      this.packages = packages;
      ImmutableSet.Builder<String> packagesAndAncestors = ImmutableSet.builder();
      for (String packageName : packages.keySet()) {
        for (int i = packageName.indexOf('/'); i != -1; i = packageName.indexOf('/', i + 1)) {
          packagesAndAncestors.add(packageName.substring(0, i));
        }
        packagesAndAncestors.add(packageName);
      }
      this.packagesAndAncestors = packagesAndAncestors.build();
      this.moduleEnv =
          Suppliers.memoize(() -> {
            Map<ModuleSymbol, ModuleInfo> modules = new HashMap<>();
            for (int j = 0; j < moduleInfos.length(); j++) {
              int i = moduleInfos.get(j);
              String name = index.name(i);
              ModuleInfo moduleInfo =
                  BytecodeBinder.bindModuleInfo(() -> ctSym + "!" + name, index.entry(i));
              modules.put(new ModuleSymbol(moduleInfo.name()), moduleInfo);
            }
            return new SimpleEnv<>(ImmutableMap.copyOf(modules));
          });
    }

    @Override
    boolean hasClass(String binaryName) {
      int idx = binaryName.lastIndexOf('/');
      CtSymPackage pkg = packages.get(idx == -1 ? "" : binaryName.substring(0, idx));
      return pkg != null
          && pkg.classes(ctSym, index, env).containsKey(binaryName.substring(idx + 1));
    }

    @Override
    boolean hasPackage(String packageName) {
      return packagesAndAncestors.contains(packageName);
    }

    @Override
    Iterable<ClassSymbol> classes(String packageName) {
      CtSymPackage pkg = packages.get(packageName);
      if (pkg == null) {
        return ImmutableList.of();
      }
      return pkg.classes(ctSym, index, env).values().stream()
          .map(BytecodeBoundClass::sym)
          .collect(toImmutableList());
    }

    @Override
    public Env<ClassSymbol, BytecodeBoundClass> env() {
      return env;
    }

    @Override
    public Env<ModuleSymbol, ModuleInfo> moduleEnv() {
      return sym -> moduleEnv.get().get(sym);
    }

    @Override
    public TopLevelIndex index() {
      return this;
    }

    @Override
    public @Nullable Supplier<byte[]> resource(String input) {
      return null;
    }
  }

  // ct.sym contains directories whose names are the concatenation of a list of target versions
  // formatted as a single character 0-9 or A-Z (e.g. 789A) and which contain interface class
  // files with a .sig extension.
  // This was updated to use 36 as a radix in https://bugs.openjdk.org/browse/JDK-8245544,
  // it's not clear what the plan is for JDK 36.
  @VisibleForTesting
  static char formatReleaseVersion(int n) {
    if (n <= 4 || n >= 36) {
      throw new IllegalArgumentException("invalid release version: " + n);
    }
    if (n < 10) {
      return (char) ('0' + n);
    } else {
      return (char) ('A' + n - 10);
    }
  }

  private CtSymClassBinder() {}
}
