/* 
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 * 
 * Copyright 2001-2009 Sun Microsystems, Inc. All rights reserved.
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

package org.glassfish.gmbal.typelib;

import java.lang.reflect.Field;
import org.glassfish.gmbal.generic.Algorithms;
import org.glassfish.gmbal.generic.Display;
import org.glassfish.gmbal.generic.DprintUtil;
import org.glassfish.gmbal.generic.UnaryFunction;
import org.glassfish.gmbal.generic.Pair;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.Arrays;

import static java.lang.reflect.Modifier.* ;

import java.lang.reflect.Type ;
import java.lang.reflect.GenericArrayType ;
import java.lang.reflect.Method;
import java.lang.reflect.WildcardType ;
import java.lang.reflect.ParameterizedType ;
import java.lang.reflect.TypeVariable ;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Date;
import javax.management.ObjectName;

/**
 *
 * @author ken
 */
public class TypeEvaluator {
    // XXX need to use Exceptions for error reporting here.

    private static boolean DEBUG = false ;
    private static boolean DEBUG_EVALUATE = false ;
    private static final DprintUtil dputil = (DEBUG || DEBUG_EVALUATE) ?
        new DprintUtil( TypeEvaluator.class ) :
        null ;

    public static class EvalMapKey extends Pair<Class<?>,List<EvaluatedType>> {
        public EvalMapKey( Class<?> cls, List<EvaluatedType> decls ) {
            super( cls, decls ) ;
        }

        public static EvalMapKey OBJECT_KEY = new EvalMapKey( 
             Object.class, new ArrayList<EvaluatedType>(0) ) ;
    }

    private static EvaluatedClassDeclaration getECD( Class cls ) {
        return DeclarationFactory.ecdecl( PUBLIC,
            cls.getName(), cls ) ;
    }

    // Cache of representations of classes with bound type variables.
    // A class may be in many EvalMapKeys with different tvar bindings.
    // XXX EvaluatedClassDeclaration strongly references Class!
    // XXX Is this part of the problem in Gmbal-10: a value strongly 
    // references its key, trapping the value in the Weak map?
    // XXX We can't have extra attempts to process things like java.lang.Object
    // So what is the best way to handle this?
    // Design sketch: Create a custom Map implementation (or just something
    // with get/put/iterate) that has two maps: a HashMap for system classes,
    // and a WeakHashMap for non-system classes.
    private static Map<EvalMapKey,EvaluatedClassDeclaration> evalClassMap =
        new HashMap<EvalMapKey,EvaluatedClassDeclaration>() ;

    private static List<EvaluatedType> emptyETList =
            new ArrayList<EvaluatedType>(0) ;

    private static void mapPut( EvaluatedClassDeclaration ecd, 
        Class cls ) {
        if (DEBUG) {
            dputil.enter( "mapPut", "ecd", ecd, "cls", cls ) ;
        }

        try {
            EvalMapKey key = new EvalMapKey( cls, emptyETList ) ;
            evalClassMap.put( key, ecd ) ;
        } finally {
            if (DEBUG) {
                dputil.exit() ;
            }
        }
    }

    // Initialize the map with a few key classes that we do NOT want to evaluate
    // (evaluating Object leads to a getClass method, which leads to all of the
    // reflection classes, which leads to, ... (450 classes later).
    // NONE of these classes are interesting for gmbal, so let's just bootstrap
    // evalClassMap with Object, Object.toString, and String (needed for the
    // return type of Object.toString).
    // Kind of like grounding a meta-object protocol...
    static {

        try {
            // Initialize all of the classes in EvaluatedType to just
            // inherit from Object, except for Object, which has no
            // inheritance, and defines a toString() method.
            // We also need to handle String separately because
            // of the toString() method signature.
            final Class[] classes = {
                void.class, Integer.class, Byte.class, Character.class,
                Short.class, Boolean.class, Float.class, Double.class,
                Long.class, BigDecimal.class, BigInteger.class,
                Date.class, ObjectName.class, Class.class,
                Number.class
            } ;

            final Class objectClass = Object.class ;
            final Class stringClass = String.class ;
            final Class voidClass = Void.class ;

            final Method toStringMethod = objectClass.getDeclaredMethod(
                "toString" ) ;

            // Introduce the EvaluatedClassDeclarations we need
            final EvaluatedClassDeclaration objectECD = getECD( objectClass ) ;
            final EvaluatedClassDeclaration voidECD = getECD( voidClass ) ;
            final EvaluatedClassDeclaration stringECD = getECD( stringClass ) ;

            final EvaluatedMethodDeclaration toStringEMD =
                DeclarationFactory.emdecl( objectECD, PUBLIC, stringECD, "toString",
                emptyETList, toStringMethod ) ;
            final List<EvaluatedMethodDeclaration> toStringList =
                Algorithms.list( toStringEMD ) ;

            final List<EvaluatedClassDeclaration> objectList =
                Algorithms.list( objectECD ) ;

            // Now finalize the definitions of the ECDs
            voidECD.inheritance( objectList ) ;
            voidECD.freeze() ;

            objectECD.methods( toStringList ) ;
            objectECD.freeze() ;

            stringECD.inheritance( objectList ) ;
            stringECD.freeze() ;

            // And store them in the evalClassMap.
            mapPut( voidECD, voidClass ) ;
            mapPut( objectECD, objectClass ) ;
            mapPut( stringECD, stringClass ) ;

            // Finally initialize all of the TypeEvaluator classes.
            for (Class cls : classes) {
                EvaluatedClassDeclaration ecd = getECD( cls ) ;
                ecd.inheritance( objectList ) ;
                ecd.freeze() ;
                mapPut( ecd, cls ) ;
            }
        } catch (Exception exc) {
            // If any of this code throws errors, the VM is broken...GIVE UP.
            throw new RuntimeException( exc ) ;
        }
    }

    public static void dumpEvalClassMap() {
        System.out.println( "TypeEvaluator: dumping eval class map") ;
        int numSystem = 0 ;
        int total = 0 ;

        for (Map.Entry<EvalMapKey,EvaluatedClassDeclaration> entry
            : evalClassMap.entrySet() ) {

            System.out.println( "\tKey:" + entry.getKey() + "=>" ) ;
            System.out.println( "\t\t" + entry.getValue() ) ;

            String name = entry.getKey().first().getName() ;
            if (!name.startsWith("org.glassfish.gmbal" )) {
                numSystem++ ;
            }
            total ++ ;
        }

        System.out.printf( 
            "\nEvalClassMap contains %d entries, %d of which are system classes\n",
            total, numSystem ) ;

        // System.out.println( "Complete dump of eval class map") ;
        // System.out.println( ObjectUtility.defaultObjectToString(evalClassMap) ) ;
    }

    /** Given any generic java type, evaluate all of its type bounds and
     * return an evaluated type.
     * 
     * @param jtype The java type to evaluate
     * @return The evaluated type
     */
    public static synchronized EvaluatedType getEvaluatedType( Class cls ) {
        TypeEvaluationVisitor visitor = new TypeEvaluationVisitor() ;
        EvaluatedType etype = visitor.evaluateType( cls ) ;
        return etype ;
    }
    
    // Visits the various java.lang.reflect Types to generate an EvaluatedType
    private static class TypeEvaluationVisitor  {
        private final Display<String,EvaluatedType> display ;
        private final Map<Class<?>,EvaluatedClassDeclaration> partialDefinitions ;
        
        public TypeEvaluationVisitor( ) {
            display = new Display<String,EvaluatedType>() ;

            partialDefinitions = 
                new HashMap<Class<?>,EvaluatedClassDeclaration>() ;
        }

        // External entry point into the Visitor.
	public EvaluatedType evaluateType( Object type ) {
            if (DEBUG||DEBUG_EVALUATE) {
                dputil.enter( "evaluateType", "type=", type ) ;
            }

            try {
                if (type == null) {
                    return null ;
                } else if (type instanceof Class) {
                    Class cls = (Class)type ;
                    return visitClassDeclaration( cls ) ;
                } else if (type instanceof ParameterizedType) {
                    ParameterizedType pt = (ParameterizedType)type ;
                    return visitParameterizedType( pt ) ;
                } else if (type instanceof TypeVariable) {
                    TypeVariable tvar = (TypeVariable)type ;
                    return visitTypeVariable( tvar ) ;
                } else if (type instanceof GenericArrayType) {
                    GenericArrayType gat = (GenericArrayType)type ;
                    return visitGenericArrayType( gat ) ;
                } else if (type instanceof WildcardType) {
                    WildcardType wt = (WildcardType)type ;
                    return visitWildcardType( wt ) ;
                } else if (type instanceof Method) {
                    throw new IllegalArgumentException(
                        "evaluateType should not be called with a Method ("
                        + type + ")" ) ;
                } else {
                    throw new IllegalArgumentException( "Unknown type???" + type ) ;
                }
            } finally {
                if (DEBUG||DEBUG_EVALUATE) {
                    dputil.exit() ;
                }
            }
	}

        // The kind-specific visitXXX methods

        private EvaluatedType visitClassDeclaration( Class decl ) {
            if (DEBUG) {
                dputil.enter( "visitClassDeclaration", "decl=", decl ) ;
            }

            try {
                if (decl.isArray()) {
                    if (DEBUG) {
                        dputil.info( "decl is an array" ) ;
                    }

                    return DeclarationFactory.egat( evaluateType(
                        decl.getComponentType() ) ) ;
                } else {
                    EvaluatedType result = partialDefinitions.get( decl ) ;
                    if (result == null) {
                        // Create the classdecl as early as possible, because it
                        // may be needed on methods or type bounds.
                        EvaluatedClassDeclaration newDecl = DeclarationFactory.ecdecl(
                            decl.getModifiers(), decl.getName(), decl ) ;

                        partialDefinitions.put( decl, newDecl ) ;

                        try {
                            OrderedResult<String,EvaluatedType> bindings =
                                getBindings( decl ) ;

                            result = getCorrectDeclaration( bindings, decl, newDecl ) ;
                        } finally {
                            partialDefinitions.remove( decl ) ;
                        }
                    } else {
                        if (DEBUG) {
                            dputil.info( "found result=" + result ) ;
                        }
                    }

                    return result ;
                }
            } finally {
                if (DEBUG) {
                    dputil.exit() ;
                }
            }
        }

        private EvaluatedType visitParameterizedType( ParameterizedType pt ) {
            if (DEBUG) {
                dputil.enter( "visitParameterizedType", "pt=", pt ) ;
            }

            Class<?> decl = (Class<?>)pt.getRawType() ;

            try {
                EvaluatedType result = partialDefinitions.get( decl ) ;
                if (result == null) {
                    // Create the classdecl as early as possible, because it
                    // may be needed on methods or type bounds.
                    EvaluatedClassDeclaration newDecl = DeclarationFactory.ecdecl(
                        decl.getModifiers(), decl.getName(), decl ) ;

                    partialDefinitions.put( decl, newDecl ) ;

                    try {
                        OrderedResult<String,EvaluatedType> bindings =
                            getBindings( pt ) ;

                        result = getCorrectDeclaration( bindings, decl, newDecl ) ;
                    } finally {
                        partialDefinitions.remove( decl ) ;
                    }
                }

                return result ;
            } finally {
                if (DEBUG) {
                    dputil.exit() ;
                }
            }
        }

        private EvaluatedFieldDeclaration visitFieldDeclaration(
            final EvaluatedClassDeclaration cdecl, final Field fld ) {

            final EvaluatedType ftype = evaluateType( fld.getGenericType() ) ;

            return DeclarationFactory.efdecl(cdecl, fld.getModifiers(),
                ftype, fld.getName(), fld ) ;
        }

        private EvaluatedMethodDeclaration visitMethodDeclaration(
            final EvaluatedClassDeclaration cdecl, final Method mdecl ) {
            if (DEBUG) {
                dputil.enter( "visitMethodDeclaration", "cdecl=", cdecl,
                    "mdecl=", mdecl ) ;
            }

            try {
                final List<EvaluatedType> eptypes =
                    Algorithms.map( Arrays.asList( mdecl.getGenericParameterTypes() ),
                        new UnaryFunction<Type,EvaluatedType>() {
                            public EvaluatedType evaluate( Type type ) {
                                return evaluateType( type ) ;
                            } } ) ;

                if (DEBUG) {
                    dputil.info( "eptypes=" + eptypes ) ;
                }

                // Convenience for the test: all processing is done on a method
                // named getThing, and this is where we need to debug, out of the
                // many hundreds of other method calls.
                if (mdecl.getName().equals( "getThing" )) {
                    if (DEBUG) {
                        dputil.info( "processing getThing method from test" ) ;
                    }
                }

                final EvaluatedMethodDeclaration result = DeclarationFactory.emdecl(
                    cdecl, mdecl.getModifiers(),
                    evaluateType( mdecl.getGenericReturnType() ),
                    mdecl.getName(), eptypes, mdecl ) ;

                if (DEBUG) {
                    dputil.info( "result=" + result ) ;
                }

                return result ;
            } finally {
                if (DEBUG) {
                    dputil.exit() ;
                }
            }
        }

        private EvaluatedType visitTypeVariable( TypeVariable tvar ) {
            if (DEBUG) {
                dputil.enter( "visitTypeVariable" ) ;
            }

            try {
                return lookup( tvar ) ;
            } finally {
                if (DEBUG) {
                    dputil.exit() ;
                }
            }
        }

        private EvaluatedType visitGenericArrayType( GenericArrayType at ) {
            if (DEBUG) {
                dputil.enter( "visitTypeVariable" ) ;
            }

            try {
                return DeclarationFactory.egat(
                    evaluateType( at.getGenericComponentType() ) ) ;
            } finally {
                if (DEBUG) {
                    dputil.exit() ;
                }
            }
        }

        private EvaluatedType visitWildcardType( WildcardType wt ) {
            if (DEBUG) {
                dputil.enter( "visitTypeVariable" ) ;
            }

            try {
                EvaluatedType result ;
                // ignore lower bounds
                // Only support 1 upper bound
                List<Type> ub = Arrays.asList( wt.getUpperBounds() ) ;
                if (ub.size() > 0) {
                    if (ub.size() > 1) {
                        throw new UnsupportedOperationException("Not supported");
                    }

                    result = evaluateType( ub.get(0) ) ;
                } else {
                    result = EvaluatedType.EOBJECT ;
                }

                return result ;
            } finally {
                if (DEBUG) {
                    dputil.exit() ;
                }
            }
        }

        private EvaluatedType lookup( TypeVariable tvar ) {
            if (DEBUG) {
                dputil.enter( "lookup", "tvar=", tvar ) ;
            }
            try {
                EvaluatedType result = display.lookup( tvar.getName() ) ;
                if (result == null) {
                    if (DEBUG) {
                        dputil.info( "tvar not found in display" ) ;
                    }
                    Type[] bounds = tvar.getBounds() ;
                    if (bounds.length > 0) {
                        // XXX We need to create a union of the upper bounds.
                        // For now, only support a single upper bound.
                        if (bounds.length > 1) {
                            throw new UnsupportedOperationException(
                                "Not supported" ) ;
                        }

                        result = evaluateType( bounds[0] ) ;
                    } else {
                        result = EvaluatedType.EOBJECT ;
                    }
                }

                if (DEBUG) {
                    dputil.info( "result=" + result ) ;
                }
                return result ;
            } finally {
                if (DEBUG) {
                    dputil.exit() ;
                }
            }
        }

        private EvaluatedType getCorrectDeclaration( 
            OrderedResult<String,EvaluatedType> bindings,
            Class decl, EvaluatedClassDeclaration newDecl ) {
            if (DEBUG) {
                dputil.enter( "getCorrectDeclaration", "decl=", decl ) ;
            }

            try {
                List<EvaluatedType> blist = bindings.getList() ;
                EvalMapKey key = new EvalMapKey( decl, blist ) ;
                if (blist.size() > 0) {
                    newDecl.instantiations( blist ) ;
                }

                EvaluatedType result = evalClassMap.get( key ) ;
                if (result == null) {
                    if (DEBUG) {
                        dputil.info( "No result in evalClassMap" ) ;
                    }

                    evalClassMap.put( key, newDecl ) ;

                    processClass( newDecl, bindings.getMap(), decl ) ;

                    result = newDecl ;
                } else {
                    if (DEBUG) {
                        dputil.info( "Found result in evalClassMap", 
                        "result=", result ) ;
                    }
                }

                return result ;
            } finally {
                if (DEBUG) {
                    dputil.exit() ;
                }
            }
        }

        private void processClass( final EvaluatedClassDeclaration newDecl,
            final Map<String,EvaluatedType> bindings, final Class decl ) {

            if (DEBUG) {
                dputil.enter( "processClass", "bindings=", bindings,
                    "decl=", decl ) ;
            }

            display.enterScope() ;
            display.bind( bindings ) ;

            try {
                List<EvaluatedClassDeclaration> inheritance =
                    Algorithms.map( getInheritance( decl ),
                    new UnaryFunction<Type,EvaluatedClassDeclaration>() {
                        public EvaluatedClassDeclaration evaluate( Type pt ) {
                            return (EvaluatedClassDeclaration)evaluateType( pt ) ;
                        } } ) ;

                if (DEBUG) {
                    dputil.info( "inheritance=" + inheritance ) ;
                }

                newDecl.inheritance( inheritance ) ;

                if (DEBUG) {
                    dputil.info( "newDecl=" + newDecl ) ;
                }

                List<EvaluatedFieldDeclaration> newFields = Algorithms.map(
                    Arrays.asList( decl.getDeclaredFields() ),
                    new UnaryFunction<Field,EvaluatedFieldDeclaration>() {
                        public EvaluatedFieldDeclaration evaluate( Field fld ) {
                            return visitFieldDeclaration( newDecl, fld ) ;
                        } } ) ;

                newDecl.fields( newFields ) ;

                List<EvaluatedMethodDeclaration> newMethods = Algorithms.map(
                    Arrays.asList( decl.getDeclaredMethods() ),
                    new UnaryFunction<Method,EvaluatedMethodDeclaration>() {
                        public EvaluatedMethodDeclaration evaluate(
                            Method md ) {

                            return visitMethodDeclaration( newDecl, md ) ;
                        } } ) ;

                newDecl.methods( newMethods ) ;
                newDecl.freeze() ;
            } finally {
                display.exitScope() ;
                if (DEBUG) {
                    dputil.exit() ;
                }
            }
        }

        private List<Type> getInheritance( Class cls ) {
            if (DEBUG) {
                dputil.enter( "getInheritance", "cls=", cls ) ;
            }

            try {
                List<Type> result = new ArrayList<Type>(0) ;
                result.add( cls.getGenericSuperclass() ) ;
                result.addAll( Arrays.asList( cls.getGenericInterfaces() ) ) ;

                if (DEBUG) {
                    dputil.info( "result=" + result ) ;
                }

                return result ;
            } finally {
                if (DEBUG) {
                    dputil.exit() ;
                }
            }
        }

        private OrderedResult<String,EvaluatedType> getBindings( Class decl ) {
            OrderedResult<String,EvaluatedType> result = new
                OrderedResult<String,EvaluatedType>() ;

            for (TypeVariable tv : decl.getTypeParameters()) {
                EvaluatedType res = lookup( tv ) ;
                result.add( tv.getName(), res ) ;
            }

            return result ;
        }

        private OrderedResult<String,EvaluatedType> getBindings( ParameterizedType pt ) {
            OrderedResult<String,EvaluatedType> result = new
                OrderedResult<String,EvaluatedType>() ;

            Iterator<Type> types =
                Arrays.asList(pt.getActualTypeArguments()).iterator() ;
            Iterator<TypeVariable> tvars =
                Arrays.asList(((Class)pt.getRawType()).getTypeParameters()).iterator() ;

            while (types.hasNext() && tvars.hasNext()) {
                Type type = types.next() ;
                TypeVariable tvar = tvars.next() ;
                result.add( tvar.getName(), evaluateType( type ) ) ;
            }

            if (types.hasNext() != tvars.hasNext()) {
                throw new IllegalArgumentException(
                    "Type list and TypeVariable list are not the same length");
            }

            return result ;
        }

        public static class OrderedResult<K,V> {
            private List<V> list = new ArrayList<V>(0) ;
            private Map<K,V> map = new HashMap<K,V>() ;

            public List<V> getList() { return list ; }
            public Map<K,V> getMap() { return map ; }

            public void add( K key, V value ) {
                list.add( value ) ;
                map.put( key, value ) ;
            }
        }
    }
}
