/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */

package com.sun.jmxa.generic;

import java.util.Stack;
import java.util.StringTokenizer;

/**
 *
 * @author ken
 */
public class DprintUtil {
    private Object client ;
    private ThreadLocal<Stack<String>> currentMethod = new ThreadLocal<Stack<String>>() {
        public Stack<String> initialValue() {
            return new Stack<String>() ;
        }
    } ;

    public DprintUtil( Object self ) {
        client = self ;
    }        
    
    private static String compressClassName( String name )
    {
	// Note that this must end in . in order to be renamed correctly.
	String prefix = "com.sun.jmxa." ;
	if (name.startsWith( prefix ) ) {
	    return "(JMXA)." + name.substring( prefix.length() ) ;
	} else
	    return name ;
    }
    
    // Return a compressed representation of the thread name.  This is particularly
    // useful on the server side, where there are many SelectReaderThreads, and
    // we need a short unambiguous name for such threads.
    public static String getThreadName( Thread thr ) 
    {
	if (thr == null)
	    return "null" ;

	// This depends on the formatting in SelectReaderThread and CorbaConnectionImpl.
	// Pattern for SelectReaderThreads:
	// SelectReaderThread CorbaConnectionImpl[ <host> <post> <state>]
	// Any other pattern in the Thread's name is just returned.
	String name = thr.getName() ;
	StringTokenizer st = new StringTokenizer( name ) ;
	int numTokens = st.countTokens() ;
	if (numTokens != 5)
	    return name ;

	String[] tokens = new String[numTokens] ;
	for (int ctr=0; ctr<numTokens; ctr++ ) 
	    tokens[ctr] = st.nextToken() ;

	if( !tokens[0].equals("SelectReaderThread"))
	    return name ;

	return "SelectReaderThread[" + tokens[2] + ":" + tokens[3] + "]" ;
    }
 
    private static synchronized void dprint(java.lang.Object obj, String msg) {
	System.out.println(
	    compressClassName( obj.getClass().getName() ) + "("  +
	    getThreadName( Thread.currentThread() ) + "): " + msg);
    }
    
    private void dprint( String msg ) {
        dprint( client, msg ) ;
    }

    private String makeString( Object... args ) {
        if (args.length == 0)
            return "" ;

        StringBuilder sb = new StringBuilder() ;
        sb.append( '(' ) ;
        boolean first = true ;
        for (Object obj : args) {
            if (first) {
                first = false ;
            } else {
                sb.append( ' ' ) ;
            }
            if (obj == null) {
                sb.append( "<NULL>" ) ;
            } else {
                sb.append( obj.toString() ) ;
            }
        }
        sb.append( ')' ) ;

        return sb.toString() ;
    }

    public void enter( String mname, Object... args ) {
        currentMethod.get().push( mname ) ;
        String str = makeString( args ) ;
        dprint( "." + mname + "->" + str ) ;
    }

    public void info( Object... args ) {
        String mname = currentMethod.get().peek() ;
        String str = makeString( args ) ;
        dprint( "." + mname + "::" + str ) ;
    }
    
    public void exception( String msg, Throwable exc ) {
        info( "Exception: ", msg, exc ) ;
        exc.printStackTrace() ;
    }

    public void exit() {
        String mname = currentMethod.get().pop() ;
        dprint( "." + mname + "<-" ) ;
    }

    public void exit( Object retVal ) {
        String mname = currentMethod.get().pop() ;
        dprint( "." + mname + "<-(" + retVal + ")" ) ;
    }
}