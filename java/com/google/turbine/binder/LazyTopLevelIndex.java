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

import com.google.common.collect.ImmutableList;
import com.google.turbine.binder.lookup.LookupKey;
import com.google.turbine.binder.lookup.LookupResult;
import com.google.turbine.binder.lookup.PackageScope;
import com.google.turbine.binder.lookup.Scope;
import com.google.turbine.binder.lookup.TopLevelIndex;
import com.google.turbine.binder.sym.ClassSymbol;
import com.google.turbine.tree.Tree.Ident;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;

/**
 * A {@link TopLevelIndex} that looks up classes and packages on demand, instead of indexing all
 * classes up front like {@link com.google.turbine.binder.lookup.SimpleTopLevelIndex}.
 *
 * <p>The index is a trie of packages that is built as lookups visit it: each package memoizes what
 * each simple name that was looked up in it refers to, so repeated lookups of the same names are
 * hash map lookups of the (typically interned) identifier strings.
 *
 * <p>Like {@code SimpleTopLevelIndex} for classes inserted in name order, a class shadows a package
 * with the same name.
 */
abstract class LazyTopLevelIndex implements TopLevelIndex {

  /** Returns true if a class with the given binary name exists. */
  abstract boolean hasClass(String binaryName);

  /**
   * Returns true if a class exists in the package with the given binary name or in one of its
   * subpackages.
   */
  abstract boolean hasPackage(String packageName);

  /** Returns the classes in the package with the given binary name, excluding subpackages. */
  abstract Iterable<ClassSymbol> classes(String packageName);

  private static final Object MISSING = new Object();

  private final Node root = new Node("");

  /** A package in the trie. */
  private final class Node implements PackageScope {

    private final String packageName;

    /** Values are {@link ClassSymbol}s, {@link Node}s or {@link #MISSING}. */
    private final ConcurrentHashMap<String, Object> children = new ConcurrentHashMap<>();

    Node(String packageName) {
      this.packageName = packageName;
    }

    /** Returns the class or package with the given simple name, or {@link #MISSING}. */
    Object child(String simpleName) {
      Object child = children.get(simpleName);
      if (child == null) {
        child = children.computeIfAbsent(simpleName, this::resolve);
      }
      return child;
    }

    private Object resolve(String simpleName) {
      String binaryName = packageName.isEmpty() ? simpleName : packageName + '/' + simpleName;
      if (hasClass(binaryName)) {
        return new ClassSymbol(binaryName);
      }
      if (hasPackage(binaryName)) {
        return new Node(binaryName);
      }
      return MISSING;
    }

    @Override
    public @Nullable LookupResult lookup(LookupKey lookupKey) {
      return child(lookupKey.first().value()) instanceof ClassSymbol sym
          ? new LookupResult(sym, lookupKey)
          : null;
    }

    @Override
    public Iterable<ClassSymbol> classes() {
      return LazyTopLevelIndex.this.classes(packageName);
    }
  }

  private final Scope scope =
      new Scope() {
        @Override
        public @Nullable LookupResult lookup(LookupKey lookupKey) {
          ImmutableList<Ident> names = lookupKey.simpleNames();
          Node node = root;
          for (int i = 0; i < names.size(); i++) {
            Object child = node.child(names.get(i).value());
            if (child instanceof ClassSymbol sym) {
              // the remaining names are the ones after the class's simple name
              return new LookupResult(sym, new LookupKey(names.subList(i, names.size())));
            }
            if (!(child instanceof Node next)) {
              return null;
            }
            node = next;
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
    Node node = root;
    for (String bit : packageName) {
      if (bit.isEmpty()) {
        throw new IllegalArgumentException("Empty package name");
      }
      if (!(node.child(bit) instanceof Node next)) {
        return null;
      }
      node = next;
    }
    return node;
  }
}
