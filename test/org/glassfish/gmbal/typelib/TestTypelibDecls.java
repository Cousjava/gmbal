/* 
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 * 
 * Copyright 2007-2009 Sun Microsystems, Inc. All rights reserved.
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
 * file and include the License file at legal/LICENSE.TXT.
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
 * 
 */ 

/*
 * @test
 * @summary Tests com.sun.beans.TypeResolver
 * @author Eamonn McManus
 * @author Ken Cavanaugh
 */

package org.glassfish.gmbal.typelib ;

import java.util.List;

import java.util.Map;

public class TestTypelibDecls {
    public static class Prototypes {
        private Prototypes() {}

        List<Integer> getListInteger() { return null ; }
        public static final EvaluatedType LIST_INTEGER =
            getMethod( "getListInteger" ).returnType() ;

        List<Object> getListObject() { return null ; }
        public static final EvaluatedType LIST_OBJECT =
            getMethod( "getListObject" ).returnType() ;

        List<String> getListString() { return null ; }
        public static final EvaluatedType LIST_STRING =
            getMethod( "getListString" ).returnType() ;

        List<List<String>> getListListString() { return null ; }
        public static final EvaluatedType LIST_LIST_STRING =
            getMethod( "getListListString" ).returnType() ;

        List<List<Object>> getListListObject() { return null ; }
        public static final EvaluatedType LIST_LIST_OBJECT =
            getMethod( "getListListObject" ).returnType() ;

        Map<Object,Object> getMapObjectObject() { return null ; }
        public static final EvaluatedType MAP_OBJECT_OBJECT =
            getMethod( "getMapObjectObject" ).returnType() ;

        Map<String,Integer> getMapStringInteger() { return null ; }
        public static final EvaluatedType MAP_STRING_INTEGER =
            getMethod( "getMapStringInteger" ).returnType() ;

    }

    public static EvaluatedMethodDeclaration getMethod(
        EvaluatedClassDeclaration cdecl, String name )  {

        // First check in cdecl
        for (EvaluatedMethodDeclaration mdecl : cdecl.methods() ) {
            if (mdecl.name().equals( name) ) {
                return mdecl ;
            }
        }

        // If not found, try the inherited EvaluatedClassDeclarations
        for (EvaluatedClassDeclaration ecd : cdecl.inheritance()) {
            EvaluatedMethodDeclaration emd = getMethod( ecd, name ) ;
            if (emd != null) {
                return emd ;
            }
        }

        return null ;
    }

    private static EvaluatedClassDeclaration proto =
        (EvaluatedClassDeclaration) TypeEvaluator.getEvaluatedType(
            Prototypes.class ) ;

    private static EvaluatedMethodDeclaration getMethod( String name ) {
        return getMethod( proto, name ) ;
    }
}