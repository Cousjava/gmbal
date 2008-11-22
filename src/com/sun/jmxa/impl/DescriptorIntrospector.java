/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright 2007-2007 Sun Microsystems, Inc. All rights reserved.
 *
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License. You can obtain
 * a copy of the License at https://glassfish.dev.java.net/public/CDDL+GPL.html
 * or glassfish/bootstrap/legal/LICENSE.txt.  See the License for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at glassfish/bootstrap/legal/LICENSE.txt.
 * Sun designates this particular file as subject to the "Classpath" exception
 * as provided by Sun in the GPL Version 2 section of the License file that
 * accompanied this code.  If applicable, add the following below the License
 * Header, with the fields enclosed by brackets [] replaced by your own
 * identifying information: "Portions Copyrighted [year]
 * [name of copyright owner]"
 *
 * Contributor(s):
 *
 * If you wish your version of this file to be governed by only the CDDL or
 * only the GPL Version 2, indicate your decision by adding "[Contributor]
 * elects to include this software in this distribution under the [CDDL or GPL
 * Version 2] license."  If you don't indicate a single choice of license, a
 * recipient has the option to distribute your version of this file under
 * either the CDDL, the GPL Version 2 or to extend the choice of license to
 * its licensees as provided above.  However, if you add GPL Version 2 code
 * and therefore, elected the GPL Version 2 license, then the option applies
 * only if the new code is made subject to such option by the copyright
 * holder.
 */

package com.sun.jmxa.impl;

import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.lang.reflect.UndeclaredThrowableException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import javax.management.Descriptor;


import com.sun.jmxa.DescriptorKey ;
import com.sun.jmxa.DescriptorFields ;

/**
 * This class contains the methods for performing all the tests needed to verify
 * that a class represents a JMX compliant MBean.
 *
 * @since 1.5
 */
public class DescriptorIntrospector {
    // private constructor defined to "hide" the default public constructor
    private DescriptorIntrospector() {
    }

    /*
     * ------------------------------------------
     *  PUBLIC METHODS
     * ------------------------------------------
     */

    public static Descriptor descriptorForElement(final AnnotatedElement elmt) {
        if (elmt == null)
            return ImmutableDescriptor.EMPTY_DESCRIPTOR;
        final Annotation[] annots = elmt.getAnnotations();
        return descriptorForAnnotations(annots);
    }

    public static Descriptor descriptorForAnnotation(Annotation annot) {
        return descriptorForAnnotations(new Annotation[] {annot});
    }

    public static Descriptor descriptorForAnnotations(Annotation[] annots) {
        if (annots.length == 0) {
            return ImmutableDescriptor.EMPTY_DESCRIPTOR;
        }
        Map<String, Object> descriptorMap = new HashMap<String, Object>();
        for (Annotation a : annots) {
            if (a instanceof DescriptorFields) {
                addDescriptorFieldsToMap(descriptorMap,
                    (DescriptorFields) a);
            }
            addAnnotationFieldsToMap(descriptorMap, a);
        }

        if (descriptorMap.isEmpty()) {
            return ImmutableDescriptor.EMPTY_DESCRIPTOR;
        } else {
            return new ImmutableDescriptor(descriptorMap);
        }
    }

    private static void addDescriptorFieldsToMap(
            Map<String, Object> descriptorMap, DescriptorFields df) {
        for (String field : df.value()) {
            int eq = field.indexOf('=');
            if (eq < 0) {
                throw new IllegalArgumentException(
                        "@DescriptorFields string must contain '=': " +
                        field);
            }
            String name = field.substring(0, eq);
            String value = field.substring(eq + 1);
            addToMap(descriptorMap, name, value);
        }
    }

    private static void addAnnotationFieldsToMap(
            Map<String, Object> descriptorMap, Annotation a) {
        Class<? extends Annotation> c = a.annotationType();
        Method[] elements = c.getMethods();
        for (Method element : elements) {
            DescriptorKey key = element.getAnnotation(DescriptorKey.class);
            if (key != null) {
                String name = key.value();
                Object value;
                try {
                    value = element.invoke(a);
                } catch (RuntimeException e) {
                    // we don't expect this - except for possibly
                    // security exceptions?
                    // RuntimeExceptions shouldn't be "UndeclaredThrowable".
                    // anyway...
                    throw e;
                } catch (Exception e) {
                    // we don't expect this
                    throw new UndeclaredThrowableException(e);
                }
                if (!key.omitIfDefault() ||
                        !equals(value, element.getDefaultValue())) {
                    value = annotationToField(value);
                    addToMap(descriptorMap, name, value);
                }
            }
        }
    }

    private static void addToMap(
            Map<String, Object> descriptorMap, String name, Object value) {
        Object oldValue = descriptorMap.put(name, value);
        if (oldValue != null && !equals(oldValue, value)) {
            final String msg =
                "Inconsistent values for descriptor field " + name +
                " from annotations: " + value + " :: " + oldValue;
            throw new IllegalArgumentException(msg);
        }
    }

    // Convert a value from an annotation element to a descriptor field value
    // E.g. with @interface Foo {class value()} an annotation @Foo(String.class)
    // will produce a Descriptor field value "java.lang.String"
    private static Object annotationToField(Object x) {
        // An annotation element cannot have a null value but never mind
        if (x == null) {
            return null;
        }
        if (x instanceof Number || x instanceof String ||
                x instanceof Character || x instanceof Boolean ||
                x instanceof String[]) {
            return x;
        }
        // Remaining possibilities: array of primitive (e.g. int[]),
        // enum, class, array of enum or class.
        Class<?> c = x.getClass();
        if (c.isArray()) {
            if (c.getComponentType().isPrimitive()) {
                return x;
            }
            Object[] xx = (Object[]) x;
            String[] ss = new String[xx.length];
            for (int i = 0; i < xx.length; i++) {
                ss[i] = (String) annotationToField(xx[i]);
            }
            return ss;
        }
        if (x instanceof Class) {
            return ((Class<?>) x).getName();
        }
        if (x instanceof Enum) {
            return ((Enum) x).name();
        }
        // The only other possibility is that the value is another
        // annotation, or that the language has evolved since this code
        // was written.  We don't allow for either of those currently.
        // If it is indeed another annotation, then x will be a proxy
        // with an unhelpful name like $Proxy2.  So we extract the
        // proxy's interface to use that in the exception message.
        if (Proxy.isProxyClass(c)) {
            c = c.getInterfaces()[0]; // array "can't be empty"
        }  // array "can't be empty"
        throw new IllegalArgumentException("Illegal type for annotation " +
                "element using @DescriptorKey: " + c.getName());
    }

    // This must be consistent with the check for duplicate field values in
    // ImmutableDescriptor.union.  But we don't expect to be called very
    // often so this inefficient check should be enough.
    private static boolean equals(Object x, Object y) {
        return Arrays.deepEquals(new Object[] {x}, new Object[] {y});
    }
}
