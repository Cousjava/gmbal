/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */

package org.glassfish.gmbal.impl;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import javax.management.Attribute;
import javax.management.AttributeList;
import javax.management.ObjectName;
import javax.management.modelmbean.ModelMBeanInfo;
import junit.framework.TestCase;
import org.glassfish.gmbal.AMXMetadata;
import org.glassfish.gmbal.Description;
import org.glassfish.gmbal.GmbalMBean;
import org.glassfish.gmbal.ManagedAttribute;
import org.glassfish.gmbal.ManagedObject;
import org.glassfish.gmbal.ManagedObjectManager;
import org.glassfish.gmbal.ManagedObjectManagerFactory;
import org.glassfish.gmbal.ManagedOperation;
import org.glassfish.gmbal.NameValue;
import org.glassfish.gmbal.generic.Algorithms;
import org.glassfish.gmbal.generic.ObjectUtility;
import org.glassfish.gmbal.generic.Pair;

/**
 *
 * @author ken
 */
public class AMXClientTest extends TestCase {
    private static final String EXTERNAL_ROOT = "test:pp=/,type=FOO" ;

    private ManagedObjectManager standaloneMom ;
    private ManagedObjectManager federatedMom ;
    private ObjectName externalRootName ;

    @ManagedObject
    @Description( "A simple class for generating MBeans" )
    public static class MyManagedClass {
        private int id = 0 ;

        private final String name ;
        private final String attr ;

        public MyManagedClass( int id, String name, String attr ) {
            this.id = id ;
            this.name = name ;
            this.attr = attr ;
        }

        @ManagedAttribute
        @Description( "Get the id" )
        public synchronized int getId() {
            return id ;
        }

        @ManagedAttribute
        @Description( "Set the id" )
        public synchronized void setId( int id ) {
            this.id = id ;
        }

        @ManagedAttribute
        @Description( "Get the attr" )
        public String getAttr() {
            return attr ;
        }

        @NameValue
        public String getName() {
            return name ;
        }

        @ManagedOperation
        @Description( "Increment the value of id by the argument")
        public synchronized int incrementId( int value ) {
            id += value ;
            return id ;
        }
    }

    public static class TestData extends Pair<Integer,Pair<Integer,Pair<String,String>>> {
        public TestData( int parentIndex, int id, String name, String attr ) {
            super( parentIndex, 
                new Pair<Integer,Pair<String,String>>( id,
                    new Pair<String,String>( name, attr ) ) ) ;
        }

        int parentIndex() {
            return first() ;
        }

        int id() {
            return second().first() ;
        }

        String name() {
            return second().second().first() ;
        }

        String attr() {
            return second().second().second() ;
        }
    }

    private TestData[] tdata = new TestData[] {
        new TestData( -1, 42, "Root", "a root" ), // root must be first
        new TestData( 0, 1, "Child", "a child" ),
        new TestData( 1, 27, "GrandChild-1", "a grandchild" ),
        new TestData( 1, 6, "GrandChild-2", "a grandchild" ),
    } ;

    private enum MomType { STANDALONE, FEDERATED } ;

    private Map<MomType,Pair<ManagedObjectManager,
        List<Pair<MyManagedClass,GmbalMBean>>>> moms =
        new HashMap<MomType,Pair<ManagedObjectManager,
        List<Pair<MyManagedClass,GmbalMBean>>>>() ;

    private ManagedObjectManager getMom( MomType mtype ) {
        ManagedObjectManager mom = moms.get(mtype).first() ;
        return mom ;
    }

    private AMXClient getAMX( MomType mtype, int index ) {
        ManagedObjectManager mom = moms.get(mtype).first() ;
        List<Pair<MyManagedClass,GmbalMBean>> data = moms.get(mtype).second() ;

        Object obj = data.get(index).first() ;
        ObjectName oname = mom.getObjectName(obj) ;
        return new AMXClient( mom.getMBeanServer(), oname ) ;
    }

    public AMXClientTest(String testName) {
        super(testName);
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        standaloneMom = ManagedObjectManagerFactory.createStandalone("test") ;
        externalRootName = new ObjectName( EXTERNAL_ROOT ) ;
        federatedMom = ManagedObjectManagerFactory.createFederated(
            externalRootName ) ;

        initializeMom( MomType.STANDALONE, standaloneMom ) ;
        initializeMom( MomType.FEDERATED, federatedMom ) ;
    }

    private void initializeMom( MomType mtype,
        ManagedObjectManager mom ) {
        List<Pair<MyManagedClass,GmbalMBean>> result = new
            ArrayList<Pair<MyManagedClass,GmbalMBean>>() ;

        mom.stripPackagePrefix() ;

        for (TestData td : tdata) {
            MyManagedClass mmc = new MyManagedClass(
                td.id(), td.name(), td.attr() ) ;
            GmbalMBean gmb = null ;
            int parentIndex = td.parentIndex() ;
            if (parentIndex == -1) {
                gmb = mom.createRoot( mmc ) ;
            } else {
                MyManagedClass parent = result.get( parentIndex ).first() ;
                gmb = mom.register( parent, mmc) ;
            }
            Pair<MyManagedClass,GmbalMBean> pair = 
                new Pair<MyManagedClass,GmbalMBean>( mmc, gmb ) ;
            result.add( pair ) ;
        }

        Pair<ManagedObjectManager,
             List<Pair<MyManagedClass,GmbalMBean>>> data =
            new Pair<ManagedObjectManager,
                     List<Pair<MyManagedClass,GmbalMBean>>>(
                mom, result ) ;

        moms.put( mtype, data ) ;
    }

    @Override
    protected void tearDown() throws Exception {
        standaloneMom.close() ;
        federatedMom.close() ;
        super.tearDown();
    }

    /**
     * Test of getName method, of class AMXClient.
     */
    public void testGetNameS( MomType mtype ) {
        testGetName( MomType.STANDALONE ) ;
    }
    public void testGetNameF( MomType mtype ) {
        testGetName( MomType.FEDERATED ) ;
    }
    private void testGetName( MomType mtype ) {
        System.out.println("getName");
        AMXClient instance = getAMX(mtype, 0) ;
        String expResult = "Root";
        String result = instance.getName();
        assertEquals(expResult, result);
    }

    @AMXMetadata
    public interface Dummy {}

    private static final Map<String,Object> AMX_DEFAULTS =
        Algorithms.getAnnotationValues(
            Dummy.class.getAnnotation(AMXMetadata.class), false ) ;

    /**
     * Test of getMeta method, of class AMXClient.
     */
    public void testGetMetaS() {
        testGetMeta( MomType.STANDALONE ) ;
    }
    public void testGetMetaF() {
        testGetMeta( MomType.FEDERATED ) ;
    }
    private void testGetMeta( MomType mtype ) {
        System.out.println("getMeta");
        AMXClient instance = getAMX( mtype, 0) ;
        Map<String,Object> expResult = new HashMap<String,Object>() ;
        expResult.put( "persistPolicy", "never" ) ;
        expResult.put( "visibility", "1" ) ;
        expResult.put( "name", "AMXClientTest$MyManagedClass" ) ;
        expResult.put( "displayName", "AMXClientTest$MyManagedClass" ) ;
        expResult.put( "log", "F" ) ;
        expResult.put( "descriptorType", "mbean" ) ;
        expResult.putAll( AMX_DEFAULTS );

        Map result = instance.getMeta();

        System.out.println( "result = " + result ) ;
        for (Map.Entry<String,Object> entry : expResult.entrySet()) {
            assertEquals( entry.getValue(), result.get( entry.getKey())) ;
        }
        assertEquals(expResult.size(), result.size());
    }

    /**
     * Test of getParent method, of class AMXClient.
     */
    public void testGetParentS( ) {
        testGetParent( MomType.STANDALONE ) ;
    }
    public void testGetParentF( ) {
        testGetParent( MomType.FEDERATED ) ;
    }
    private void testGetParent( MomType mtype ) {
        System.out.println("getParent");
        AMXClient child = getAMX( mtype, 1) ;
        AMXClient root = getAMX( mtype, 0) ;

        // Check that child's parent is root
        AMXMBeanInterface result = child.getParent() ;
        assertEquals( root, result ) ;

        // Check that root's parent is correct
        result = root.getParent();
        if (mtype == MomType.STANDALONE) {
            assertEquals(null, result);
        } else {
            AMXClient rootParent = new AMXClient( getMom( mtype ).getMBeanServer(), 
                externalRootName ) ;
            assertEquals( rootParent, result) ; // What should be here?
        }
    }

    /**
     * Test of getChildren method, of class AMXClient.
     */
    public void testGetChildrenS() {
        testGetChildren(MomType.STANDALONE);
    }
    public void testGetChildrenF() {
        testGetChildren(MomType.FEDERATED);
    }
    private void testGetChildren( MomType mtype ) {
        System.out.println("getChildren");
        AMXClient child = getAMX( mtype, 1) ;
        AMXClient gc1 = getAMX( mtype, 2) ;
        AMXClient gc2 = getAMX( mtype, 3) ;

        AMXMBeanInterface[] expResult = new AMXMBeanInterface[] { gc1, gc2 } ;
        AMXMBeanInterface[] result = child.getChildren();

        assertEquals( Arrays.asList(expResult), Arrays.asList(result) );
    }

    /**
     * Test of getAttribute method, of class AMXClient.
     */
    public void testGetAttributeS() {
        testGetAttribute( MomType.STANDALONE ) ;
    }
    public void testGetAttributeF() {
        testGetAttribute( MomType.FEDERATED ) ;
    }
    private void testGetAttribute( MomType mtype ) {
        System.out.println("getAttribute");
        AMXClient root = getAMX( mtype, 0) ;
        Object result = root.getAttribute("Attr") ;
        assertTrue( result instanceof String) ;
        assertEquals( "a root", result) ;
    }

    /**
     * Test of setAttribute method, of class AMXClient.
     */
    public void testSetAttributeS() {
        testSetAttribute( MomType.STANDALONE ) ;
    }
    public void testSetAttributeF() {
        testSetAttribute( MomType.FEDERATED ) ;
    }
    private void testSetAttribute( MomType mtype ) {
        System.out.println("setAttribute");
        AMXClient root = getAMX( mtype, 0) ;
        Object result = root.getAttribute( "Id" ) ;
        assertTrue( result instanceof Integer ) ;
        assertEquals( Integer.valueOf( 42 ), result ) ;

        Attribute attr = new Attribute( "Id", 84 ) ;
        root.setAttribute( attr ) ;

        result = root.getAttribute( "Id" ) ;
        assertTrue( result instanceof Integer ) ;
        assertEquals( Integer.valueOf( 84 ), result ) ;
    }

    private static String toString( Attribute attr ) {
        return "Attribute[name=" + attr.getName()
            + ",value=" + attr.getValue() + "]" ;
    }

    private static String toString( AttributeList alist ) {
        StringBuilder sb = new StringBuilder() ;
        sb.append( "AttributeList[") ;
        boolean first = true ;
        for (Object obj : alist) {
            Attribute attr = (Attribute)obj ;
            sb.append( toString( attr ) ) ;
            if (first) {
                first = false ;
            } else {
                sb.append( "," ) ;
            }
        }

        sb.append( "]" ) ;
        return sb.toString() ;
    }
    /**
     * Test of getAttributes method, of class AMXClient.
     */
    public void testGetAttributesS() {
        testGetAttributes( MomType.STANDALONE ) ;
    }
    public void testGetAttributesF() {
        testGetAttributes( MomType.FEDERATED ) ;
    }
    private void testGetAttributes( MomType mtype ) {
        System.out.println("getAttributes");
        AMXClient amx = getAMX( mtype, 3) ;

        String[] anames = { "Attr", "Id" } ;
        AttributeList expected = new AttributeList() ;
        expected.add( new Attribute( "Attr", "a grandchild" ) ) ;
        expected.add( new Attribute( "Id", 6 ) ) ;
        System.out.println( "expected = " + toString( expected ) ) ;

        AttributeList result = amx.getAttributes( anames ) ;
        System.out.println( "result = " + toString( result ) ) ;

        // Stupid attributes do not define equals properly!
        // assertEquals( expected, result );
        Iterator riter = result.iterator() ;
        Iterator eiter = expected.iterator() ;
        while (riter.hasNext() && eiter.hasNext()) {
            Attribute ra = (Attribute)riter.next() ;
            Attribute ea = (Attribute)eiter.next() ;
            assertEquals( ra.getName(), ea.getName() ) ;
            assertEquals( ra.getValue(), ea.getValue() ) ;
        }
        assertEquals( riter.hasNext(), eiter.hasNext() ) ;
    }

    /**
     * Test of setAttributes method, of class AMXClient.
     */
    public void testSetAttributesS() {
        testSetAttributes( MomType.STANDALONE ) ;
    }
    public void testSetAttributesF() {
        testSetAttributes( MomType.FEDERATED ) ;
    }
    private void testSetAttributes( MomType mtype ) {
        System.out.println("setAttributes");
        AMXClient root = getAMX( mtype, 0) ;
        Object result = root.getAttribute( "Id" ) ;
        assertTrue( result instanceof Integer ) ;
        assertEquals( Integer.valueOf( 42 ), result ) ;

        Attribute attr = new Attribute( "Id", 84 ) ;
        AttributeList alist = new AttributeList() ;
        alist.add( attr ) ;
        root.setAttributes( alist ) ;

        result = root.getAttribute( "Id" ) ;
        assertTrue( result instanceof Integer ) ;
        assertEquals( Integer.valueOf( 84 ), result ) ;
    }

    /**
     * Test of invoke method, of class AMXClient.
     */
    public void testInvokeS() throws Exception {
        testInvoke( MomType.STANDALONE ) ;
    }
    public void testInvokeF() throws Exception {
        testInvoke( MomType.FEDERATED ) ;
    }
    private void testInvoke( MomType mtype ) throws Exception {
        System.out.println("invoke");
        AMXClient root = getAMX( mtype, 0) ;
        int id = (Integer)root.getAttribute("Id") ;
        Object[] args = { 22 } ;
        String[] sig = { Integer.class.getName() } ;

        int res = (Integer)root.invoke("incrementId", args, sig ) ;
        assertEquals( res, id+22 ) ;
    }

    /**
     * Test of getMBeanInfo method, of class AMXClient.
     */
    public void testGetMBeanInfoS() {
        testGetMBeanInfo( MomType.STANDALONE ) ;
    }
    public void testGetMBeanInfoF() {
        testGetMBeanInfo( MomType.FEDERATED ) ;
    }
    private void testGetMBeanInfo( MomType mtype ) {
        System.out.println("getMBeanInfo");
        AMXClient root = getAMX( mtype, 0) ;
        ModelMBeanInfo mbi = (ModelMBeanInfo)root.getMBeanInfo() ;
        // System.out.println( "MBeanInfo = " + 
            // ObjectUtility.defaultObjectToString(mbi) ) ;
    }
}
