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

package org.glassfish.gmbal.impl ;

import java.util.ResourceBundle ;
import java.util.Map ;
import java.util.HashMap ;
import java.util.WeakHashMap ;
import java.util.List ;
import java.util.ArrayList ;

import java.io.IOException ;

import java.io.Serializable;
import java.lang.annotation.Annotation ;

import java.lang.management.ManagementFactory ;

import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.security.AccessController;
import java.security.PrivilegedAction;
import javax.management.MBeanServer ;
import javax.management.JMException ;
import javax.management.ObjectName ;

import org.glassfish.gmbal.generic.Pair ;
import org.glassfish.gmbal.generic.Algorithms ;

import org.glassfish.gmbal.AMX ;
import org.glassfish.gmbal.GmbalMBean ;
import org.glassfish.gmbal.ManagedObject ;
import org.glassfish.gmbal.Description ;
import org.glassfish.gmbal.IncludeSubclass ;
import org.glassfish.gmbal.InheritedAttribute ;
import org.glassfish.gmbal.InheritedAttributes ;
import org.glassfish.gmbal.AMXMetadata;
import org.glassfish.gmbal.ManagedAttribute;
import org.glassfish.gmbal.ManagedObjectManager;
import org.glassfish.gmbal.generic.MethodMonitor;
import org.glassfish.gmbal.generic.MethodMonitorFactory;
import org.glassfish.gmbal.generic.DumpIgnore;
import org.glassfish.gmbal.generic.ObjectUtility;
import org.glassfish.gmbal.generic.Predicate;
import org.glassfish.gmbal.generic.UnaryFunction;
import org.glassfish.gmbal.generic.FacetAccessor ;
import org.glassfish.gmbal.generic.FacetAccessorImpl;
import java.util.Arrays;
import java.util.Comparator;
import java.util.SortedSet;
import java.util.TreeSet;
import org.glassfish.gmbal.generic.DelayedObjectToString;
import org.glassfish.gmbal.typelib.EvaluatedClassAnalyzer;
import org.glassfish.gmbal.typelib.EvaluatedClassDeclaration;
import org.glassfish.gmbal.typelib.EvaluatedDeclaration;
import org.glassfish.gmbal.typelib.EvaluatedFieldDeclaration;
import org.glassfish.gmbal.typelib.EvaluatedMethodDeclaration;
import org.glassfish.gmbal.typelib.EvaluatedType;
import org.glassfish.gmbal.typelib.TypeEvaluator;

/* Implementation notes:
 * XXX Test attribute change notification.
 */
public class ManagedObjectManagerImpl implements ManagedObjectManagerInternal {
    @AMXMetadata
    private static class DefaultMBeanTypeHolder { }

    private static final AMXMetadata DEFAULT_AMX_METADATA =
	DefaultMBeanTypeHolder.class.getAnnotation(AMXMetadata.class);

    private static ObjectUtility myObjectUtil =
        new ObjectUtility(true, 0, 4)
            .useToString( EvaluatedType.class )
            .useToString( ManagedObjectManager.class ) ;

    private static final class StringComparator implements Serializable,
        Comparator<String> {
        private static final long serialVersionUID = 8274851916877850245L;
        public int compare(String o1, String o2) {
            return - o1.compareTo( o2 ) ;
        }
    } ;
    private static Comparator<String> REV_COMP = new StringComparator() ;

    // All finals should be initialized in this order in the private constructor
    @DumpIgnore
    private final MethodMonitor mm ;
    private final String domain ;
    private final MBeanTree tree ;
    private final Map<EvaluatedClassDeclaration,MBeanSkeleton> skeletonMap ;
    private final Map<EvaluatedType,TypeConverter> typeConverterMap ;
    private final Map<AnnotatedElement, Map<Class, Annotation>> addedAnnotations ;
    private final MBeanSkeleton amxSkeleton ;

    // All non-finals should be initialized in this order in the init() method.
    private boolean rootCreated ;
    private ResourceBundle resourceBundle ;
    private MBeanServer server ;
    private ManagedObjectManager.RegistrationDebugLevel regDebugLevel ;
    private boolean runDebugFlag ;

    // Maintain the list of typePrefixes in reversed sorted order, so that
    // we strip the longest prefix first.
    private final SortedSet<String> typePrefixes = new TreeSet<String>(
        REV_COMP ) ;
    private boolean stripPackagePrefix = false ;

    private ManagedObjectManagerImpl( final String domain, final ObjectName rootParentName ) {
	this.mm = MethodMonitorFactory.makeStandard( getClass() ) ;
        this.domain = domain ;
        this.tree = new MBeanTree( this, domain, rootParentName, "type" ) ;
        this.skeletonMap = 
            new WeakHashMap<EvaluatedClassDeclaration,MBeanSkeleton>() ;
        this.typeConverterMap = new WeakHashMap<EvaluatedType,TypeConverter>() ;
        this.addedAnnotations = 
            new HashMap<AnnotatedElement, Map<Class, Annotation>>() ;

        final EvaluatedClassDeclaration ecd =
            (EvaluatedClassDeclaration)TypeEvaluator.getEvaluatedType(
                AMX.class ) ;
        this.amxSkeleton = getSkeleton( ecd ) ;
    }

    private void init() {
        tree.clear() ;
        skeletonMap.clear() ;
        typeConverterMap.clear() ;
        addedAnnotations.clear() ;
        mm.clear() ;

        rootCreated = false ;
        resourceBundle = null ;
        this.server = ManagementFactory.getPlatformMBeanServer() ;
        regDebugLevel = ManagedObjectManager.RegistrationDebugLevel.NONE ;
        runDebugFlag = false ;
    }
    
    public ManagedObjectManagerImpl( final String domain ) {
        this( domain, null ) ;
        init() ;
    }

    public ManagedObjectManagerImpl( final ObjectName rootParentName ) {
        this( rootParentName.getDomain(), rootParentName ) ;
        init() ;
    }

    public void close() throws IOException {
        // Can be called anytime

        mm.enter( registrationDebug(), "close" ) ;
        
        try {
            init() ;
        } finally {
	    mm.exit( registrationDebug() ) ;
        }
    }

    private synchronized void checkRootNotCreated( String methodName ) {
        if (rootCreated) {
            throw Exceptions.self.createRootCalled(methodName) ;
        }
    }

    private synchronized void checkRootCreated( String methodName ) {
        if (!rootCreated) {
            throw Exceptions.self.createRootNotCalled(methodName) ;
        }
    }

    public synchronized void suspendJMXRegistration() {
        mm.clear() ;
        // Can be called anytime
        tree.suspendRegistration() ;
    }

    public synchronized void resumeJMXRegistration() {
        mm.clear() ;
        // Can be called anytime
        tree.resumeRegistration();
    }

    public synchronized void stripPackagePrefix() {
        mm.clear() ;
        checkRootNotCreated("stripPackagePrefix");
        stripPackagePrefix = true ;
    }

    @Override
    public String toString( ) {
        // Can be called anytime
        return "ManagedObjectManagerImpl[domain=" + domain + "]" ;
    }

    public synchronized ObjectName getRootParentName() {
        checkRootCreated("getRootParentName");
        return tree.getRootParentName() ;
    }

    @ManagedObject
    @AMXMetadata( type="gmbal-root", isSingleton=true)
    @Description( "Dummy class used when no root is specified" ) 
    private static class Root {
        // No methods: will simply implement an AMX container
        @Override
        public String toString() {
            return "GmbalDefaultRoot" ;
        }
    }
    
    public synchronized GmbalMBean createRoot() {
        return createRoot( new Root() ) ;
    }

    public synchronized GmbalMBean createRoot(Object root) {
        return createRoot( root, null ) ;
    }

    public synchronized GmbalMBean createRoot(Object root, String name) {
        mm.clear() ;
        checkRootNotCreated( "createRoot" ) ;

        GmbalMBean result = null ;

        try {
            // Assume successful create, so that AMX checks that
            // back through getRootParentName will succeed.
            rootCreated = true ;
            result = tree.setRoot( root, name ) ;
        } catch (RuntimeException exc) {
            rootCreated = false ;
            throw exc ;
        }

        return result ;
    }

    public synchronized Object getRoot() {
        mm.clear() ;
        // Can be called anytime.
        return tree.getRoot() ;
    }
    
    private synchronized MBeanSkeleton getSkeleton( EvaluatedClassDeclaration cls ) {
        // can be called anytime, otherwise we can't create the root itself!
        mm.enter( registrationDebug(), "getSkeleton", cls ) ;
        
        try {
            MBeanSkeleton result = skeletonMap.get( cls ) ;

            boolean newSkeleton = false ;
            if (result == null) {
                mm.info( registrationDebug(), "Skeleton not found" ) ;
                
                Pair<EvaluatedClassDeclaration,EvaluatedClassAnalyzer> pair = 
                    getClassAnalyzer( cls, ManagedObject.class ) ;
                EvaluatedClassDeclaration annotatedClass = pair.first() ;
                EvaluatedClassAnalyzer ca = pair.second() ;
                mm.info( registrationFineDebug(), "Annotated class for skeleton is",
                    annotatedClass ) ;

                result = skeletonMap.get( annotatedClass ) ;

                if (result == null) {
                    newSkeleton = true ;
                    MBeanSkeleton skel = new MBeanSkeleton( annotatedClass,
                        ca, this ) ;

                    if (amxSkeleton == null) {
                        // Can't compose amxSkeleton with itself!
                        result = skel ;
                    } else {
                        result = amxSkeleton.compose( skel ) ;
                    }
                }

                skeletonMap.put( cls, result ) ;
            }
            
            mm.info(registrationFineDebug() || (registrationDebug() && newSkeleton),
                "Skeleton", new DelayedObjectToString( result, myObjectUtil ) ) ;
            
            return result ;
        } finally {
            mm.exit( registrationDebug() ) ;
        }
    }

    public synchronized TypeConverter getTypeConverter( EvaluatedType type ) {
        // Can be called anytime
        mm.enter( registrationFineDebug(), "getTypeConverter", type ) ;
        
        TypeConverter result = null;
        
        try {
            boolean newTypeConverter = false ;
            result = typeConverterMap.get( type ) ;	
            if (result == null) {
                mm.info( registrationFineDebug(), "Creating new TypeConverter" ) ;
            
                // Store a TypeConverter impl that throws an exception when 
                // acessed.  Used to detect recursive types.
                typeConverterMap.put( type, 
                    new TypeConverterImpl.TypeConverterPlaceHolderImpl( type ) ) ;

                result = TypeConverterImpl.makeTypeConverter( type, this ) ;

                // Replace recursion marker with the constructed implementation
                typeConverterMap.put( type, result ) ;
                newTypeConverter = true ;
            }
            
            mm.info(registrationFineDebug() ||
		(registrationDebug() && newTypeConverter), "result",
		myObjectUtil.objectToString( result ) ) ;
        } finally {
	    mm.exit( registrationFineDebug(), result ) ;
        }
        
        return result ;
    }

    private String getAMXTypeFromField( Class<?> cls, String fieldName ) {
        try {
            final Field fld = cls.getDeclaredField(fieldName);
            if (Modifier.isFinal(fld.getModifiers()) 
                && Modifier.isStatic(fld.getModifiers())
                && fld.getType().equals(String.class)) {

                AccessController.doPrivileged(new PrivilegedAction<Object>() {
                    public Object run() {
                        fld.setAccessible(true);
                        return null;
                    }
                });

                return (String) fld.get(null);
            } else {
                return "";
            }
        } catch (IllegalArgumentException ex) {
            return "" ;
        } catch (IllegalAccessException ex) {
            return "" ;
        } catch (NoSuchFieldException ex) {
            return "" ;
        } catch (SecurityException ex) {
            return "" ;
        }
    }

    private boolean goodResult( String str ) {
        return str!=null && str.length()>0 ;
    }

    // XXX Needs Test for the AMX_TYPE case
    public synchronized String getTypeName( Class<?> cls, String fieldName,
        String nameFromAnnotation ) {
        // Can be called anytime
        String result = getAMXTypeFromField( cls, fieldName ) ;
        if (goodResult( result)) {
            return result ;
        }

	// Next, check for annotations?
        if (goodResult( nameFromAnnotation)) {
            return nameFromAnnotation ;
        }

        String className = cls.getName() ;

	// Next, check stripPrefixes
        for (String str : typePrefixes ) {
            if (className.startsWith( str ) ) {
                return className.substring( str.length() + 1 ) ;
            }
        }
        
        // The result is either the class name, or the class name without
	// package prefix (if any) if stripPackagePrefix has been set.
        if (stripPackagePrefix) {
            int lastDot = className.lastIndexOf( '.' ) ;
            if (lastDot == -1) {
                return className ;
            } else {
                return className.substring( lastDot + 1 ) ;
            }
        } else {
            return className ;
        }
    }
    
    public synchronized MBeanImpl constructMBean( MBeanImpl parentEntity,
        Object obj, String name ) {

        // Can be called anytime
        MBeanImpl result = null ;
        
        mm.enter( registrationDebug(), "constructMean", obj, name ) ;
        
        String objName = name ;
        try {
            final Class<?> cls = obj.getClass() ;
            final EvaluatedClassDeclaration cdecl = 
                (EvaluatedClassDeclaration)TypeEvaluator.getEvaluatedType(cls) ;
            final MBeanSkeleton skel = getSkeleton( cdecl ) ;

            AMXMetadata amd = getFirstAnnotationOnClass( cdecl, AMXMetadata.class ) ;
            if (amd == null) {
                amd = getDefaultAMXMetadata() ;
            }

            String type = skel.getType() ;
	    mm.info( registrationDebug(), "Stripped type", type ) ;

            result = new MBeanImpl( skel, obj, server, type ) ;
            
            if (objName == null) {
                objName = skel.getNameValue( result ) ;
                if (objName == null) {
                    objName = "" ;
                }
            }  

            if (objName.length() == 0) {
                if (!amd.isSingleton()) {
                    throw Exceptions.self.nonSingletonRequiresName( 
                        parentEntity, type ) ;
                }
            } else {
                if (amd.isSingleton()) {
                    throw Exceptions.self.singletonCannotSpecifyName( 
                        parentEntity, type, name ) ;
                }
            }
           
            mm.info( registrationDebug(), "Name value =", objName ) ;
            
            result.name( objName ) ;
        } catch (JMException exc) {
            throw Exceptions.self.errorInConstructingMBean( objName, exc ) ;
        } finally {
            mm.exit( registrationDebug(), result ) ;
        }
        
        return result ;
    }
    
    @SuppressWarnings("unchecked")
    public synchronized GmbalMBean register( final Object parent,
        final Object obj, final String name ) {

        mm.clear() ;
        checkRootCreated("register");
        mm.enter( registrationDebug(), "register", parent, obj, name ) ;
        
        if (obj instanceof String) {
            throw Exceptions.self.objStringWrongRegisterCall( (String)obj ) ;
        }

        // Construct the MBean
        try {
            MBeanImpl parentEntity = tree.getParentEntity(parent) ;

            final MBeanImpl mb = constructMBean( parentEntity, obj, name ) ;
            
            return tree.register( parentEntity, obj, mb) ;
    	} catch (JMException exc) {
            throw Exceptions.self.exceptionInRegister(exc) ;
        } finally {
            mm.exit( registrationDebug() ) ;
        }
    }
    
    public synchronized GmbalMBean register( final Object parent,
        final Object obj ) {

        return register( parent, obj, null ) ;
    }

    
    public synchronized GmbalMBean registerAtRoot(Object obj, String name) {
        return register( tree.getRoot(), obj, name ) ;
    }

    public synchronized GmbalMBean registerAtRoot(Object obj) {
        return register( tree.getRoot(), obj, null ) ;
    }
    
    public synchronized void unregister( Object obj ) {
        mm.clear() ;
        checkRootCreated("unregister");
        mm.enter( registrationDebug(), "unregister", obj ) ;
        
        try {
            tree.unregister( obj ) ;
        } catch (JMException exc) {
            throw Exceptions.self.exceptionInUnregister(exc) ;
        } finally {
            mm.exit( registrationDebug() ) ;
        }
    }

    public synchronized ObjectName getObjectName( Object obj ) {
        mm.clear() ;
        checkRootCreated("getObjectName");
        mm.enter( registrationDebug(), "getObjectName", obj ) ;

        if (obj instanceof ObjectName) {
            return (ObjectName)obj ;
        }

        if (obj instanceof AMXClient) {
            return ((AMXClient)obj).objectName() ;
        }

        ObjectName result = null;
        try {
            result = tree.getObjectName( obj ) ;
        } finally {
            mm.exit( registrationDebug(), result ) ;
        }
        
        return result ;
    }

    public synchronized Object getObject( ObjectName oname ) {
        checkRootCreated("getObject");
        mm.enter( registrationDebug(), "getObject", oname ) ;
        
        Object result = null ;
        try {
            result = tree.getObject( oname ) ;
	} finally {
            mm.exit( registrationDebug(), result ) ;
        }
        
        return result ;
    }
    
    public synchronized FacetAccessor getFacetAccessor( Object obj ) {
        // Can be called anytime
        MBeanImpl mb = tree.getMBeanImpl( obj ) ;
        if (mb != null) {
            return tree.getFacetAccessor( obj ) ;
        } else {
            return new FacetAccessorImpl( obj ) ;
        }
    }   
    
    public synchronized String getDomain() {
        // Can be called anytime
	return domain ;
    }

    public synchronized void setMBeanServer( MBeanServer server ) {
        mm.clear() ;
        checkRootNotCreated("setMBeanServer");
	this.server = server ;
    }

    public synchronized MBeanServer getMBeanServer() {
        // Can be called anytime
	return server ;
    }

    public synchronized void setResourceBundle( ResourceBundle rb ) {
        mm.clear() ;
        checkRootNotCreated("setResourceBundle");
        this.resourceBundle = rb ;
    }

    public synchronized ResourceBundle getResourceBundle() {
        // Can be called anytime
        return resourceBundle ;
    }
    
    public synchronized String getDescription( EvaluatedDeclaration element ) {
        // Can be called anytime
        Description desc ;
        if (element instanceof EvaluatedClassDeclaration) {
            EvaluatedClassDeclaration ecd = (EvaluatedClassDeclaration)element;
            desc = getFirstAnnotationOnClass(ecd, Description.class ) ;
        } else {
            desc = getAnnotation( element, Description.class ) ;
        }

        String result = "" ;
        if (desc != null) {
            result = desc.value() ;
        }

        if (result.length() == 0) {
            result = Exceptions.self.noDescriptionAvailable() ;
        } else {
            if (resourceBundle != null) {
                result = resourceBundle.getString( result ) ;
            }
        }

        return result ;
    }
    
    
    public synchronized void addAnnotation( AnnotatedElement element,
        Annotation annotation ) {
        mm.clear() ;
        checkRootNotCreated("addAnnotation");
        mm.enter( registrationDebug(), "addAnnotation", element, annotation ) ;
        
        try {
            Map<Class, Annotation> map = addedAnnotations.get( element ) ;
            if (map == null) {
	        mm.info( registrationDebug(),
		    "Creating new Map<Class,Annotation>" ) ;
                
                map = new HashMap<Class, Annotation>() ;
                addedAnnotations.put( element, map ) ;
            }

            Class<?> annotationType = annotation.annotationType() ;
            Annotation ann = map.get( annotationType ) ;
            if (ann != null) {
                mm.info( registrationDebug(), "Duplicate annotation") ;
                
                throw Exceptions.self.duplicateAnnotation( element, 
                    annotation.getClass().getName()) ;
            }

            map.put( annotationType, annotation ) ;
        } finally {
            mm.exit( registrationDebug() ) ;
        }
    }

    public <T extends Annotation> T getFirstAnnotationOnClass(
        EvaluatedClassDeclaration element, Class<T> type ) {

        EvaluatedClassAnalyzer eca = new EvaluatedClassAnalyzer( element ) ;
        List<EvaluatedClassDeclaration> ecds = eca.findClasses(
            forAnnotation(type, EvaluatedClassDeclaration.class) ) ;

        if (ecds.size() > 0) {
            return getAnnotation( ecds.get(0), type ) ;
        }  else {
            return null ;
        }
    }

    @SuppressWarnings({"unchecked"})
    public synchronized <T extends Annotation> T getAnnotation( 
        EvaluatedDeclaration element, Class<T> type ) {

        // Can be called anytime
        mm.enter( registrationFineDebug(), "getAnnotation", element,
            type.getName() ) ;
        
        try {
            T result = element.annotation( type ) ;
            if (result == null) {
	        mm.info( registrationFineDebug(),
		    "No annotation on element: trying addedAnnotations map" ) ;

                Map<Class, Annotation> map = addedAnnotations.get( 
                    element.element() );
                if (map != null) {
                    result = (T)map.get( type ) ;
                } 
            }

            mm.info( registrationFineDebug(), "result" + result ) ;
            
            return result ;
        } finally {
            mm.exit( registrationFineDebug() ) ;
        }
    }
    
    public synchronized Pair<EvaluatedClassDeclaration,EvaluatedClassAnalyzer>
        getClassAnalyzer( final EvaluatedClassDeclaration cls,
        final Class<? extends Annotation> annotationClass ) {
        // Can be called anytime
        mm.enter( registrationDebug(), "getClassAnalyzer", cls,
            annotationClass ) ;
        
        try {
            EvaluatedClassAnalyzer ca = new EvaluatedClassAnalyzer( cls ) ;

            final EvaluatedClassDeclaration annotatedClass = Algorithms.getFirst(
                ca.findClasses( forAnnotation( annotationClass, 
                    EvaluatedClassDeclaration.class ) ),
                new Runnable() {
                    public void run() {
                        throw Exceptions.self.noAnnotationFound(
                            annotationClass.getName(), cls.name() ) ;
                    }
                } ) ;

            mm.info( registrationDebug(), "annotatedClass = " + annotatedClass ) ;
    
            final List<EvaluatedClassDeclaration> classes =
                new ArrayList<EvaluatedClassDeclaration>() ;
            classes.add( annotatedClass ) ;
            final IncludeSubclass incsub = getFirstAnnotationOnClass(
                annotatedClass, IncludeSubclass.class ) ;
            if (incsub != null) {
                for (Class<?> klass : incsub.value()) {
                    EvaluatedClassDeclaration ecd = 
                        (EvaluatedClassDeclaration)TypeEvaluator.getEvaluatedType(klass) ;
                    classes.add( ecd ) ;
                    mm.info( registrationDebug(), "included subclass", klass ) ;
                }
            }

            if (classes.size() > 1) {
	        mm.info( registrationDebug(),
                    "Getting new EvaluatedClassAnalyzer for included subclasses" ) ;
                ca = new EvaluatedClassAnalyzer( classes ) ;
            }

            return new Pair<EvaluatedClassDeclaration,
                 EvaluatedClassAnalyzer>( annotatedClass, ca ) ;
        } finally {
            mm.exit( registrationDebug() ) ;
        }
    }
    
    public synchronized List<InheritedAttribute> getInheritedAttributes( 
        final EvaluatedClassAnalyzer ca ) {
        // Can be called anytime
        
        mm.enter( registrationDebug(), "getInheritedAttributes", ca ) ;
        
        try {
            final Predicate<EvaluatedClassDeclaration> pred = Algorithms.or(
                forAnnotation( InheritedAttribute.class, 
                    EvaluatedClassDeclaration.class ),
                forAnnotation( InheritedAttributes.class, 
                    EvaluatedClassDeclaration.class ) ) ;

            // Construct list of classes annotated with InheritedAttribute or
            // InheritedAttributes.
            final List<EvaluatedClassDeclaration> iaClasses =
                ca.findClasses( pred ) ;

            List<InheritedAttribute> isList = Algorithms.flatten( iaClasses,
                new UnaryFunction<EvaluatedClassDeclaration,List<InheritedAttribute>>() {
                    public List<InheritedAttribute> evaluate( EvaluatedClassDeclaration cls ) {
                        final InheritedAttribute ia = getFirstAnnotationOnClass(cls,
                            InheritedAttribute.class);
                        final InheritedAttributes ias = getFirstAnnotationOnClass(cls,
                            InheritedAttributes.class);
                        if ((ia != null) && (ias != null)) {
                            throw Exceptions.self.badInheritedAttributeAnnotation(cls) ;
                        }

                        final List<InheritedAttribute> result = 
                            new ArrayList<InheritedAttribute>() ;

                        if (ia != null) {
                            result.add( ia ) ;
                        } else if (ias != null) {
                            result.addAll( Arrays.asList( ias.value() )) ;
                        }

                        return result ;
                    }
            } ) ;

            return isList ;
        } finally {
	    mm.exit( registrationDebug() ) ;
        }
    }
    
    private class ADHolder implements Predicate<InheritedAttribute> {
        
        private final EvaluatedMethodDeclaration method ;
        private final ManagedObjectManagerInternal.AttributeDescriptorType adt ;
        private AttributeDescriptor content ;

        public ADHolder(  final EvaluatedMethodDeclaration method,
            ManagedObjectManagerInternal.AttributeDescriptorType adt ) {
            this.method = method ;
            this.adt = adt ;
        }
        
        public boolean evaluate( InheritedAttribute ia ) {
            AttributeDescriptor ad = AttributeDescriptor.makeFromInherited( 
                ManagedObjectManagerImpl.this, method,
                ia.id(), ia.methodName(), ia.description(), adt ) ;
            boolean result = ad != null ;
            if (result) {
                content = ad ;
            }
            
            return result ;
        }

        public AttributeDescriptor content() {
            return content ;
        }
    }
    
    private AttributeDescriptor getAttributeDescriptorIfInherited( 
        final EvaluatedMethodDeclaration method, 
        final List<InheritedAttribute> ias,
        final ManagedObjectManagerInternal.AttributeDescriptorType adt ) {
        
        ADHolder adh = new ADHolder( method, adt ) ;
        Algorithms.find( ias, adh ) ;
        return adh.content() ;
    }

    public synchronized <K,V> void putIfNotPresent( final Map<K,V> map,
        final K key, final V value ) {
        // Can be called anytime
        mm.enter( registrationFineDebug(), "putIfNotPresent", key, value ) ;
        
        try {
            if (!map.containsKey( key )) {
                mm.info( registrationFineDebug(), "Adding key, value to map" ) ;
                map.put( key, value ) ;
            } else {
                mm.info( registrationFineDebug(), "Key,value already in map" ) ;
            }
        } finally {
            mm.exit( registrationFineDebug() ) ;
        }
    }

    // Only final fields of immutable type can be used as ManagedAttributes.
    static void checkFieldType( EvaluatedFieldDeclaration field ) {
        if (!Modifier.isFinal( field.modifiers() ) ||
            !field.fieldType().isImmutable()) {
            Exceptions.self.illegalAttributeField(field) ;
        }
    }

    // Returns a pair of maps defining all managed attributes in the ca.  The first map
    // is all setters, and the second is all getters.  Only the most derived version is present.
    public synchronized Pair<Map<String,AttributeDescriptor>,
        Map<String,AttributeDescriptor>>
        getAttributes( 
            final EvaluatedClassAnalyzer ca,
            final ManagedObjectManagerInternal.AttributeDescriptorType adt ) {
        // Can be called anytime
        // mm.enter( registrationDebug(), "getAttributes" ) ;

        try {
            final Map<String,AttributeDescriptor> getters = 
                new HashMap<String,AttributeDescriptor>() ; 
            final Map<String,AttributeDescriptor> setters = 
                new HashMap<String,AttributeDescriptor>() ; 
            final Pair<Map<String,AttributeDescriptor>,
                Map<String,AttributeDescriptor>> result =  
                    new Pair<Map<String,AttributeDescriptor>,
                        Map<String,AttributeDescriptor>>( getters, setters ) ;
            
            final List<InheritedAttribute> ias = getInheritedAttributes( ca ) ;

            ca.findFields( new Predicate<EvaluatedFieldDeclaration>() {
                public boolean evaluate( EvaluatedFieldDeclaration field ) {
                    ManagedAttribute ma = getAnnotation( field,
                        ManagedAttribute.class ) ;
                    if (ma == null) {
                        return false ;
                    } else {
                        checkFieldType( field ) ;

                        Description desc = getAnnotation( field,
                            Description.class ) ;
                        String description ;
                        if (desc == null) {
                            description = "No description available for "
                                + field.name() ;
                        } else {
                            description = desc.value() ;
                        }

                        AttributeDescriptor ad =
                            AttributeDescriptor.makeFromAnnotated(
                                 ManagedObjectManagerImpl.this, field,
                                 ma.id(), description, adt ) ;

                        putIfNotPresent( getters, ad.id(), ad ) ;

                        return true ;
                    }
                } } ) ;

            ca.findMethods( new Predicate<EvaluatedMethodDeclaration>() {
                public boolean evaluate( EvaluatedMethodDeclaration method ) {
                    ManagedAttribute ma = getAnnotation( method,
                        ManagedAttribute.class ) ;
                    AttributeDescriptor ad ;
                    if (ma == null) {
                        ad = getAttributeDescriptorIfInherited( method, ias,
                            adt ) ;
                    } else {
                        Description desc = getAnnotation( method,
                            Description.class ) ;
                        String description ;
                        if (desc == null) {
                            description = "No description available for "
                                + method.name() ;
                        } else {
                            description = desc.value() ;
                        }

                        ad = AttributeDescriptor.makeFromAnnotated(
                            ManagedObjectManagerImpl.this,
                            method, ma.id(), description, adt ) ;
                    }
                    
                    if (ad != null) {
                        if (ad.atype()==AttributeDescriptor.AttributeType.GETTER) {
                            putIfNotPresent( getters, ad.id(), ad ) ;
                        } else {
                            putIfNotPresent( setters, ad.id(), ad ) ;
                        }
                    }
                    
                    return false ;
                } } ) ;
         
            return result ;
        } finally {
	    // mm.exit( registrationDebug() ) ;
        }
    }

    public synchronized void setRegistrationDebug( 
        ManagedObjectManager.RegistrationDebugLevel level ) {
        // can be called anytime
        regDebugLevel = level ;
    }
    
    public synchronized void setRuntimeDebug( boolean flag ) {
        // can be called anytime
        runDebugFlag = flag ;
    }

    public synchronized void setTypelibDebug( int level ) {
        // can be called anytime
        TypeEvaluator.setDebugLevel(level);
    }
    
    public synchronized String dumpSkeleton( Object obj ) {
        // can be called anytime
        MBeanImpl impl = tree.getMBeanImpl( obj ) ;
        if (impl == null) {
            return obj + " is not currently registered with mom " + this ;
        } else {
            MBeanSkeleton skel = impl.skeleton() ;
            String skelString = myObjectUtil.objectToString( skel ) ;
            return "Skeleton for MBean for object " + obj + ":\n"
                + skelString ;
        }
    }
    
    public synchronized boolean registrationDebug() {
        // can be called anytime
        return regDebugLevel == ManagedObjectManager.RegistrationDebugLevel.NORMAL 
            || regDebugLevel == ManagedObjectManager.RegistrationDebugLevel.FINE ;
    }
    
    public synchronized boolean registrationFineDebug() {
        // can be called anytime
        return regDebugLevel == ManagedObjectManager.RegistrationDebugLevel.FINE ;
    }
    
    public synchronized boolean runtimeDebug() {
        // can be called anytime
        return runDebugFlag ;
    }
    
    public synchronized void stripPrefix( String... args ) {
        checkRootNotCreated("stripPrefix" ) ;
        for (String str : args) {
            typePrefixes.add( str ) ;
        }
    }
    
    public synchronized <T extends EvaluatedDeclaration> Predicate<T> forAnnotation(
        final Class<? extends Annotation> annotation,
        final Class<T> cls ) {
        // Can be called anytime

        return new Predicate<T>() {
            public boolean evaluate( T elem ) {
                return getAnnotation( elem, annotation ) != null ;
            }
        } ;
    }

    public AMXMetadata getDefaultAMXMetadata() {
        return DEFAULT_AMX_METADATA ;
    }
}
