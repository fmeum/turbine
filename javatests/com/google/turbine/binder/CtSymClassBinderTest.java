/*
 * Copyright 2020 Google Inc. All Rights Reserved.
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

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;
import static org.junit.Assert.assertThrows;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.turbine.binder.bytecode.BytecodeBoundClass;
import com.google.turbine.binder.lookup.LookupKey;
import com.google.turbine.binder.lookup.LookupResult;
import com.google.turbine.binder.lookup.PackageScope;
import com.google.turbine.binder.sym.ClassSymbol;
import com.google.turbine.binder.sym.ModuleSymbol;
import com.google.turbine.tree.Tree.Ident;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.ZipFile;
import org.jspecify.annotations.Nullable;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class CtSymClassBinderTest {
  @Test
  public void formatReleaseVersion() {
    ImmutableList.of(5, 6, 7, 8, 9)
        .forEach(x ->
            assertThat(Character.toString(CtSymClassBinder.formatReleaseVersion(x)))
                .isEqualTo(String.valueOf(x)));
    ImmutableMap.of(
            10, 'A',
            11, 'B',
            12, 'C',
            35, 'Z')
        .forEach((k, v) -> assertThat(CtSymClassBinder.formatReleaseVersion(k)).isEqualTo(v));
    ImmutableList.of(4, 36)
        .forEach(x ->
            assertThrows(
                Integer.toString(x),
                IllegalArgumentException.class,
                () -> CtSymClassBinder.formatReleaseVersion(x)));
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

  @Test
  public void lazyBindingMatchesEagerBinding() throws Exception {
    Path ctSym = Path.of(System.getProperty("java.home"), "lib", "ct.sym");
    List<String> names = new ArrayList<>();
    try (ZipFile zipFile = new ZipFile(ctSym.toFile())) {
      zipFile.stream().forEach(e -> names.add(e.getName()));
    }
    // Also check a release that isn't in ct.sym.
    int current = Runtime.version().feature();
    for (int release = 8; release <= current; release++) {
      ClassPath eager = CtSymClassBinder.bindEagerly(ctSym, release);
      ClassPath lazy = CtSymClassBinder.bindLazily(ctSym, release);
      assertWithMessage("release %s", release).that(lazy == null).isEqualTo(eager == null);
      if (eager == null || lazy == null) {
        continue;
      }
      char releaseChar = CtSymClassBinder.formatReleaseVersion(release);
      Set<String> packages = new TreeSet<>();
      packages.add("");
      Set<String> modules = new TreeSet<>();
      for (String name : names) {
        int idx = name.indexOf('/');
        if (!name.endsWith(".sig") || idx == -1 || name.indexOf(releaseChar, 0, idx) == -1) {
          continue;
        }
        int moduleEnd = name.indexOf('/', idx + 1);
        if (name.endsWith("/module-info.sig")) {
          modules.add(name.substring(idx + 1, moduleEnd));
          continue;
        }
        String binaryName = name.substring(moduleEnd + 1, name.length() - ".sig".length());
        ClassSymbol sym = new ClassSymbol(binaryName);
        BytecodeBoundClass expected = eager.env().get(sym);
        BytecodeBoundClass actual = lazy.env().get(sym);
        assertWithMessage("%s in release %s", binaryName, release).that(actual).isNotNull();
        assertWithMessage("%s in release %s", binaryName, release)
            .that(actual.jarFile())
            .isEqualTo(expected.jarFile());
        String[] parts = binaryName.split("/", -1);
        String[] withMember = Arrays.copyOf(parts, parts.length + 1);
        withMember[parts.length] = "Member";
        for (String[] lookup : List.of(parts, withMember)) {
          assertWithMessage("%s in release %s", String.join(".", lookup), release)
              .that(describe(lazy.index().scope().lookup(key(lookup))))
              .isEqualTo(describe(eager.index().scope().lookup(key(lookup))));
        }
        for (int i = 1; i < parts.length; i++) {
          packages.add(String.join("/", Arrays.copyOf(parts, i)));
        }
      }
      for (String pkg : packages) {
        List<String> bits = pkg.isEmpty() ? List.of() : List.of(pkg.split("/", -1));
        assertWithMessage("%s in release %s", pkg, release)
            .that(describe(lazy.index().lookupPackage(bits)))
            .isEqualTo(describe(eager.index().lookupPackage(bits)));
      }
      for (String[] missing :
          List.of(new String[] {"nope"}, new String[] {"java", "nope"}, new String[] {"java"})) {
        assertWithMessage("%s in release %s", String.join(".", missing), release)
            .that(describe(lazy.index().scope().lookup(key(missing))))
            .isEqualTo(describe(eager.index().scope().lookup(key(missing))));
      }
      modules.add("missing.module");
      for (String module : modules) {
        ModuleSymbol sym = new ModuleSymbol(module);
        assertWithMessage("%s in release %s", module, release)
            .that(lazy.moduleEnv().get(sym) == null)
            .isEqualTo(eager.moduleEnv().get(sym) == null);
      }
    }
  }
}
