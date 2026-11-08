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

import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.turbine.binder.bound.ModuleInfo;
import com.google.turbine.binder.bytecode.BytecodeBinder;
import com.google.turbine.binder.bytecode.BytecodeBoundClass;
import com.google.turbine.binder.env.Env;
import com.google.turbine.binder.env.SimpleEnv;
import com.google.turbine.binder.lookup.LookupKey;
import com.google.turbine.binder.lookup.LookupResult;
import com.google.turbine.binder.lookup.PackageScope;
import com.google.turbine.binder.lookup.Scope;
import com.google.turbine.binder.lookup.TopLevelIndex;
import com.google.turbine.binder.sym.ClassSymbol;
import com.google.turbine.binder.sym.ModuleSymbol;
import com.google.turbine.tree.Tree.Ident;
import com.google.turbine.zip.Zip;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import org.jspecify.annotations.Nullable;

/**
 * Binds a classpath consisting of a single jar whose entries are sorted by name, such as a platform
 * classpath jar.
 *
 * <p>Unlike {@link ClassPathBinder}, this doesn't create symbols, bound classes and index entries
 * for every class in the jar up front. Classes and packages are looked up on demand by binary
 * search over the jar's sorted central directory, so the cost of binding the jar no longer grows
 * with the number of classes in it that the compilation doesn't use.
 *
 * <p>Within a single sorted jar, lookups produce the same results as {@link ClassPathBinder}: a
 * class {@code a/b.class} always sorts before the entries of a package {@code a/b/}, so for a class
 * and a package with the same name the class wins, as it does in the eagerly built index.
 */
public final class LazyClassPathBinder {

  private static final byte[] CLASS_SUFFIX = bytes(".class");
  private static final byte[] MODULE_INFO = bytes("module-info.class");
  private static final byte[] SLASH_MODULE_INFO = bytes("/module-info.class");
  private static final byte[] TRANSITIVE_PREFIX = bytes(ClassPathBinder.TRANSITIVE_PREFIX);
  private static final String MANIFEST = "META-INF/MANIFEST.MF";
  private static final Attributes.Name ORIGINAL_JAR_PATH = new Attributes.Name("Original-Jar-Path");

  /**
   * Returns a lazily bound classpath for the given jars, or {@code null} if lazy binding isn't
   * supported for them, in which case {@link ClassPathBinder} should be used instead.
   */
  public static @Nullable ClassPath bind(ImmutableList<Path> paths) throws IOException {
    if (paths.size() != 1) {
      return null;
    }
    Path path = paths.get(0);
    Zip.ZipIterable zip = new Zip.ZipIterable(path);
    Zip.SortedIndex index = Zip.SortedIndex.create(zip);
    if (index == null || hasTransitiveClasses(index)) {
      zip.close();
      return null;
    }
    return new LazyClassPath(path, index);
  }

  private static boolean hasTransitiveClasses(Zip.SortedIndex index) {
    int i = index.lowerBound(TRANSITIVE_PREFIX);
    return i < index.size() && index.startsWith(i, TRANSITIVE_PREFIX);
  }

  private static byte[] bytes(String s) {
    return s.getBytes(UTF_8);
  }

  private static final class LazyClassPath implements ClassPath {

    private final Path path;
    private final Zip.SortedIndex index;
    private final int rootModuleInfo;
    private final Supplier<String> jarFile;
    private final ConcurrentHashMap<ClassSymbol, BytecodeBoundClass> classes =
        new ConcurrentHashMap<>();
    private final Supplier<Env<ModuleSymbol, ModuleInfo>> moduleEnv;
    private final TopLevelIndex topLevelIndex = new Index();

    private final Env<ClassSymbol, BytecodeBoundClass> env =
        new Env<ClassSymbol, BytecodeBoundClass>() {
          @Override
          public @Nullable BytecodeBoundClass get(ClassSymbol sym) {
            BytecodeBoundClass result = classes.get(sym);
            if (result != null) {
              return result;
            }
            int i = findClass(sym.binaryName());
            if (i == -1) {
              return null;
            }
            BytecodeBoundClass info = new BytecodeBoundClass(sym, index.entry(i), this, jarFile);
            result = classes.putIfAbsent(sym, info);
            return result != null ? result : info;
          }
        };

    LazyClassPath(Path path, Zip.SortedIndex index) {
      this.path = path;
      this.index = index;
      this.rootModuleInfo = index.find(MODULE_INFO);
      this.jarFile = Suppliers.memoize(this::resolveJarFile);
      this.moduleEnv = Suppliers.memoize(this::bindModules);
    }

    /** Returns the index of the class with the given binary name, or {@code -1}. */
    private int findClass(String binaryName) {
      int i = index.find(bytes(binaryName + ".class"));
      return i != -1 && !isModuleInfo(i) ? i : -1;
    }

    private boolean isClass(int i) {
      return index.endsWith(i, CLASS_SUFFIX) && !isModuleInfo(i);
    }

    /** Returns true if the last segment of the {@code i}th entry's name is module-info.class. */
    private boolean isModuleInfo(int i) {
      return i == rootModuleInfo || index.endsWith(i, SLASH_MODULE_INFO);
    }

    /**
     * Returns true if a class exists whose binary name starts with {@code prefix}, which must end
     * with a {@code /}.
     */
    private boolean hasClassWithPrefix(byte[] prefix) {
      for (int i = index.lowerBound(prefix); i < index.size() && index.startsWith(i, prefix); i++) {
        if (isClass(i)) {
          return true;
        }
      }
      return false;
    }

    private String resolveJarFile() {
      int i = index.find(bytes(MANIFEST));
      if (i != -1) {
        try {
          Manifest manifest = new Manifest(new ByteArrayInputStream(index.entry(i).data()));
          String originalJarPath = (String) manifest.getMainAttributes().get(ORIGINAL_JAR_PATH);
          if (originalJarPath != null) {
            return originalJarPath;
          }
        } catch (IOException e) {
          throw new UncheckedIOException("error reading manifest of " + path, e);
        }
      }
      return path.toString();
    }

    private Env<ModuleSymbol, ModuleInfo> bindModules() {
      Map<ModuleSymbol, ModuleInfo> modules = new HashMap<>();
      for (int i = 0; i < index.size(); i++) {
        if (isModuleInfo(i)) {
          ModuleInfo moduleInfo = BytecodeBinder.bindModuleInfo(jarFile, index.entry(i));
          modules.putIfAbsent(new ModuleSymbol(moduleInfo.name()), moduleInfo);
        }
      }
      return new SimpleEnv<>(ImmutableMap.copyOf(modules));
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
      return topLevelIndex;
    }

    @Override
    public @Nullable Supplier<byte[]> resource(String name) {
      if (name.equals(MANIFEST) || name.endsWith(".class")) {
        return null;
      }
      int i = index.find(bytes(name));
      return i != -1 ? index.entry(i) : null;
    }

    private final class Index implements TopLevelIndex {

      private final Scope scope =
          new Scope() {
            @Override
            public @Nullable LookupResult lookup(LookupKey lookupKey) {
              ImmutableList<Ident> names = lookupKey.simpleNames();
              StringBuilder prefix = new StringBuilder();
              for (int i = 0; i < names.size(); i++) {
                prefix.append(names.get(i).value());
                String binaryName = prefix.toString();
                if (findClass(binaryName) != -1) {
                  // the remaining names are the ones after the class's simple name
                  return new LookupResult(
                      new ClassSymbol(binaryName), new LookupKey(names.subList(i, names.size())));
                }
                prefix.append('/');
                if (!hasClassWithPrefix(bytes(prefix.toString()))) {
                  return null;
                }
              }
              return null;
            }
          };

      @Override
      public Scope scope() {
        return scope;
      }

      @Override
      public @Nullable PackageScope lookupPackage(Iterable<String> packageName) {
        StringBuilder prefix = new StringBuilder();
        for (String bit : packageName) {
          if (bit.isEmpty()) {
            throw new IllegalArgumentException("Empty package name");
          }
          prefix.append(bit);
          // A class with the same name as a package shadows it, see the class javadoc.
          if (findClass(prefix.toString()) != -1) {
            return null;
          }
          prefix.append('/');
          if (!hasClassWithPrefix(bytes(prefix.toString()))) {
            return null;
          }
        }
        return new LazyPackageScope(prefix.toString());
      }
    }

    /** A package, identified by its binary name followed by {@code /}, or the empty string. */
    private final class LazyPackageScope implements PackageScope {

      private final String prefix;

      LazyPackageScope(String prefix) {
        this.prefix = prefix;
      }

      @Override
      public @Nullable LookupResult lookup(LookupKey lookupKey) {
        String binaryName = prefix + lookupKey.first().value();
        return findClass(binaryName) != -1
            ? new LookupResult(new ClassSymbol(binaryName), lookupKey)
            : null;
      }

      @Override
      public Iterable<ClassSymbol> classes() {
        byte[] prefixBytes = bytes(prefix);
        ImmutableList.Builder<ClassSymbol> result = ImmutableList.builder();
        for (int i = index.lowerBound(prefixBytes);
            i < index.size() && index.startsWith(i, prefixBytes);
            i++) {
          if (isClass(i) && index.indexOf(i, (byte) '/', prefixBytes.length) == -1) {
            String name = index.name(i);
            result.add(new ClassSymbol(name.substring(0, name.length() - ".class".length())));
          }
        }
        return result.build();
      }
    }
  }

  private LazyClassPathBinder() {}
}
