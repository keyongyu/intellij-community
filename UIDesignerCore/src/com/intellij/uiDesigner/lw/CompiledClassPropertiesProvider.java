/*
 * Copyright 2000-2005 JetBrains s.r.o.
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
package com.intellij.uiDesigner.lw;

import com.intellij.uiDesigner.compiler.Utils;

import javax.swing.*;
import java.awt.*;
import java.beans.BeanInfo;
import java.beans.Introspector;
import java.beans.PropertyDescriptor;
import java.lang.reflect.Method;
import java.util.HashMap;

/**
 * @author Anton Katilin
 * @author Vladimir Kondratyev
 */
public final class CompiledClassPropertiesProvider implements PropertiesProvider {
  private final ClassLoader myLoader;
  private final HashMap myCache;

  public CompiledClassPropertiesProvider(final ClassLoader loader) {
    if (loader == null) {
      throw new IllegalArgumentException("loader cannot be null");
    }
    myLoader = loader;
    myCache = new HashMap();
  }

  public HashMap getLwProperties(final String className) {
    if (myCache.containsKey(className)) {
      return (HashMap)myCache.get(className);
    }

    if (Utils.validateJComponentClass(myLoader, className, false) != null) {
      return null;
    }

    final Class aClass;
    try {
      aClass = Class.forName(className, false, myLoader);
    }
    catch (final ClassNotFoundException exc) {
      throw new RuntimeException(exc.toString()); // should never happen
    }

    final BeanInfo beanInfo;
    try {
      beanInfo = Introspector.getBeanInfo(aClass);
    }
    catch (Throwable e) {
      return null;
    }

    final HashMap result = new HashMap();
    final PropertyDescriptor[] descriptors = beanInfo.getPropertyDescriptors();
    for (int i = 0; i < descriptors.length; i++) {
      final PropertyDescriptor descriptor = descriptors[i];

      final Method readMethod = descriptor.getReadMethod();
      final Method writeMethod = descriptor.getWriteMethod();
      if (writeMethod == null || readMethod == null) {
        continue;
      }

      final String name = descriptor.getName();

      if (
        "preferredSize".equals(name) ||
        "minimumSize".equals(name) ||
        "maximumSize".equals(name)
      ) {
        // our own properties must be used instead
        continue;
      }

      final LwIntrospectedProperty property;

      final Class propertyType = descriptor.getPropertyType();
      if (int.class.equals(propertyType)) { // int
        property = new LwIntroIntProperty(name);
      }
      else if (boolean.class.equals(propertyType)) { // boolean
        property = new LwIntroBooleanProperty(name);
      }
      else if (double.class.equals(propertyType)) { // double
        property = new LwIntroDoubleProperty(name);
      }
      else if (String.class.equals(propertyType)) { // java.lang.String
        property = new LwRbIntroStringProperty(name);
      }
      else if (Insets.class.equals(propertyType)) { // java.awt.Insets
        property = new LwIntroInsetsProperty(name);
      }
      else if (Dimension.class.equals(propertyType)) { // java.awt.Dimension
        property = new LwIntroDimensionProperty(name);
      }
      else if (Rectangle.class.equals(propertyType)) { // java.awt.Rectangle
        property = new LwIntroRectangleProperty(name);
      }
      else if (Color.class.equals(propertyType)) {
        property = new LwIntroColorProperty(name);
      }
      else if (Font.class.equals(propertyType)) {
        property = new LwIntroFontProperty(name);
      }
      else if (Icon.class.equals(propertyType)) {
        property = new LwIntroIconProperty(name);
      }
      else if (propertyType.isAssignableFrom(Component.class)) {
        property = new LwIntroComponentProperty(name);
      }
      else {
        // type is not supported
        continue;
      }

      result.put(name, property);
    }

    myCache.put(className, result);

    return result;
  }
}
