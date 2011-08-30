/*
 * Copyright 1998-2009 University Corporation for Atmospheric Research/Unidata
 *
 * Portions of this software were developed by the Unidata Program at the
 * University Corporation for Atmospheric Research.
 *
 * Access and use of this software shall impose the following obligations
 * and understandings on the user. The user is granted the right, without
 * any fee or cost, to use, copy, modify, alter, enhance and distribute
 * this software, and any derivative works thereof, and its supporting
 * documentation for any purpose whatsoever, provided that this entire
 * notice appears in all copies of the software, derivative works and
 * supporting documentation.  Further, UCAR requests that the user credit
 * UCAR/Unidata in any publications that result from the use of this
 * software or in any product that includes this software. The names UCAR
 * and/or Unidata, however, may not be used in any advertising or publicity
 * to endorse or promote any products or commercial entity unless specific
 * written permission is obtained from UCAR/Unidata. The user also
 * understands that UCAR/Unidata is not obligated to provide the user with
 * any support, consulting, training or assistance of any kind with regard
 * to the use, operation and performance of this software nor to provide
 * the user with any updates, revisions, new versions or "bug fixes."
 *
 * THIS SOFTWARE IS PROVIDED BY UCAR/UNIDATA "AS IS" AND ANY EXPRESS OR
 * IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL UCAR/UNIDATA BE LIABLE FOR ANY SPECIAL,
 * INDIRECT OR CONSEQUENTIAL DAMAGES OR ANY DAMAGES WHATSOEVER RESULTING
 * FROM LOSS OF USE, DATA OR PROFITS, WHETHER IN AN ACTION OF CONTRACT,
 * NEGLIGENCE OR OTHER TORTIOUS ACTION, ARISING OUT OF OR IN CONNECTION
 * WITH THE ACCESS, USE OR PERFORMANCE OF THIS SOFTWARE.
 */

package ucar.nc2.util.net;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.*;
import java.io.*;

import org.apache.commons.httpclient.auth.*;
import javax.crypto.*;
import javax.crypto.spec.*;

import static ucar.nc2.util.net.HTTPAuthCreds.*;

/**

HTTPAuthStore stores tuples of authorization information in a thread
safe manner.  It currently supports serial access, but can be extended
to support single writer / multiple reader semantics
using the procedures
{acquire,release}writeaccess
and
{acquire,release}readaccess

 */

//Package local scope
class HTTPAuthStore implements Serializable
{


//////////////////////////////////////////////////
/**

The auth store is (conceptually) a set of tuples (rows) of the form
HTTPAuthScheme(scheme) X String(url) X HTTPAuthCreds(creds).
The creds column specifies the kind of authorization
(e.g. basic, keystore, etc) and the info to support it.
The functional relationship is (scheme,url)=>creds.
*/

static public class Entry implements Serializable, Comparable
{
    public HTTPAuthScheme scheme;
    public String uri;
    public HTTPAuthCreds creds;

    public Entry()
    {
	    this(ANY_ENTRY);
    }

    /**
     * @param entry
     */

    public Entry(Entry entry)
    {
	if(entry == null) entry = ANY_ENTRY;
	constructor(entry.scheme,entry.uri,entry.creds);
    }

    /**
     * @param scheme
     * @param uri
     * @param creds
     */

    public Entry(HTTPAuthScheme scheme, String uri, HTTPAuthCreds creds)
    {
	constructor(scheme, uri, creds);
    }

    /**
     * Shared constructor code
     * @param scheme
     * @param uri
     * @param creds
     */

    protected void constructor(HTTPAuthScheme scheme, String uri, HTTPAuthCreds creds)
    {
	if(uri == null) uri = ANY_URL;
	if(creds != null)
            creds = new HTTPAuthCreds(creds);
	this.scheme = scheme;
	this.uri = uri;
	this.creds = creds;
    }

    public boolean valid()
    {
	return (scheme != null && uri != null);
    }

    public String toString()
    {
	String creds = (this.creds == null ? "null" : this.creds.toString());
        return String.format("%s:%s{%s}",
		scheme.toString(),uri,creds);
    }

    private void writeObject(java.io.ObjectOutputStream oos)
        throws IOException
    {
        oos.writeObject(this.scheme);
        oos.writeObject(this.uri);
        oos.writeObject(this.creds);
    }

    private void readObject(java.io.ObjectInputStream ois)
            throws IOException, ClassNotFoundException
    {
        this.scheme = (HTTPAuthScheme)ois.readObject();
        this.uri = (String)ois.readObject();
        this.creds = (HTTPAuthCreds)ois.readObject();
    }

    /**
      * return 0 if e1 == e2, 1 if e1 > e2
      * and -1 if e1 < e2 using the following tests
      * null, => 0
      * !null,null => -1
      * null,!null => +1
      * e1.scheme :: e2.scheme
      * compareURI(e1.url,e2.url) => e2.url.compare(e1.url) (note reverse order)
      *
      * Assume that the first argument comes from the pattern
      * and the second comes from the AuthStore
      */

    //////////////////////////////////////////////////
    // Comparable interface

    public int compareTo(Object e1)
    {
        if(e1 instanceof Entry) {
            return compare(this,(Entry)e1);
	}
        return +1;
    }

    //////////////////////////////////////////////////
    // Comparator interface

    public boolean equals(Entry e)
    {
        return compare(this,e) == 0;
    }

    static protected int compare(Entry e1, Entry e2)
    {
	if(e1 == null && e2 == null) return 0;
	if(e1 != null && e2 == null) return +1;
	if(e1 == null && e2 != null) return -1;

    int cmp = e1.scheme.compareTo(e2.scheme);
    if(cmp != 0) return cmp;

	if(compatibleURI(e1.uri,e2.uri))
            return e2.uri.compareTo(e1.uri);
        
        return e1.uri.compareTo(e2.uri);
    }

}

//////////////////////////////////////////////////

static public final boolean          SCHEME = true;
static public final boolean          ISLOCAL = false;
static public final String           ANY_URL = "";

static final public Entry            ANY_ENTRY = new Entry(null,ANY_URL,null);

static private Hashtable<HTTPAuthScheme, List<Entry>> rows;


static {
    rows = new Hashtable<HTTPAuthScheme,List<Entry>>();

    // Insert empty lists for all known schemes
    for(HTTPAuthScheme s: HTTPAuthScheme.values())
        rows.put(s,new ArrayList<Entry>());

    // For back compatibility, check some system properties
    // and add appropriate entries
    // 1. ESG keystore support
    String kpath = System.getProperty("keystore");
    if(kpath != null) {
        String tpath = System.getProperty("truststore");
        String kpwd = System.getProperty("keystorepassword");
        String tpwd = System.getProperty("truststorepassword");
        kpath = kpath.trim();
        if(tpath != null) tpath = tpath.trim();
        if(kpwd != null) kpwd = kpwd.trim();
        if(tpwd != null) tpwd = tpwd.trim();
        if(kpath.length() == 0) kpath = null;
        if(tpath.length() == 0) tpath = null;
        if(kpwd.length() == 0) kpwd = null;
        if(tpwd.length() == 0) tpwd = null;

        HTTPAuthCreds creds = new HTTPAuthCreds(HTTPAuthScheme.KEYSTORE);
        creds.setKeyStore(kpath,kpwd,tpath,tpwd);
        try {
            insert(new Entry(HTTPAuthScheme.KEYSTORE,ANY_URL,creds));
        }   catch (HTTPException he) {
            System.err.println("HTTPAuthStore: could not insert default KEYSTORE data");
        }
     }

}

/**
 * Define URI compatibility.
 */
static protected boolean compatibleURI(String u1, String u2)
{   
    if(u1 == u2) return true;
    if(u1 == null) return false;
    if(u2 == null) return false;

    if(u1.equals(u2)
       || u1.startsWith(u2)
       || u2.startsWith(u1)) return true;

    // Check piece by piece
    URI uu1;
    URI uu2;
    try {
        uu1 = new URI(u1);
    } catch (URISyntaxException use) {
        return false;
    }
    try {
        uu2 = new URI(u2);
    } catch (URISyntaxException use) {
        return false;
    }

    // protocols must be same
    String s1 = uu1.getSchemeSpecificPart();
    String s2 = uu2.getSchemeSpecificPart();
    if( ! (s1 != null && s2 != null && s1.equals(s2)))
        return false;

    // Missing uu1 user info will match defined uu2 user info
    s1 = uu1.getUserInfo();
    s2 = uu2.getUserInfo();
    if( ! (s1 == null || (s1 != null && s2 != null && s1.equals(s2))))
        return false;

    // hosts must be same
    s1 = uu1.getHost();
    s2 = uu2.getHost();
    if( ! (s1 != null && s2 != null && s1.equals(s2)))
        return false;

    // ports must be the same
    if(uu1.getPort() != uu2.getPort())
        return false;

    // paths must have prefix relationship
    // and missing is a prefix of anything
    s1 = uu1.getRawPath();
    s2 = uu2.getPath();
    if( ! (s1 == null || s2 == null || (s1.startsWith(s2) || s2.startsWith(s1))))
        return false;

    return true;
}

//////////////////////////////////////////////////
/**
Primary external interface
 */

/**
 * @param entry
 * @return true if entry already existed and was replaced
 */

static synchronized public boolean
insert(Entry entry)
    throws HTTPException
{
    boolean rval = false;
    Entry found = null;

    if(entry == null || !entry.valid())
	throw new HTTPException("HTTPAuthStore.insert: invalid entry: " + entry);

    List<Entry> entries = rows.get(entry.scheme);

    for(Entry e: entries) {
	if(compatibleURI(entry.uri,e.uri)) {
	    found = e;
	    break;
	}
    }
    // If the entry already exists, then overwrite it and return true
    if(found != null) {
        found.creds = new HTTPAuthCreds(entry.creds);
	rval = true;
    } else {
        Entry newentry = new Entry(entry);
        entries.add(newentry);
    }
    return rval;
}

/**
 * @param entry
 * @return true if entry existed and was removed
 */

static synchronized public boolean
remove(Entry entry)
    throws HTTPException
{
    Entry found = null;

    if(entry == null || !entry.valid())
	    throw new HTTPException("HTTPAuthStore.remove: invalid entry: " + entry);

    List<Entry> entries = rows.get(entry.scheme);

    for(Entry e: entries) {
	if(compatibleURI(entry.uri,e.uri)) {
	    found = e;
	    break;
	}
    }
    if(found != null) entries.remove(found);
    return (found != null);
}

/**
 * Remove all auth store entries
 */
static synchronized public void
clear() throws HTTPException
{
   rows.clear(); 
}

/**
 * Return all entries in the auth store
 */
static public List<Entry>
getAllRows()
{
  List<Entry> elist = new ArrayList<Entry>();
  for(HTTPAuthScheme key: rows.keySet()) elist.addAll(rows.get(key));
  return elist;
}

/**
 * Search:
 * 
 * Search match is defined by the compatibleURI function above.
 * The return list is ordered from most restrictive to least restrictive.
 * 
 * @param entry
 * @return list of matching entries
 */
static synchronized public Entry[]
search(Entry entry)
{

    if(entry == null || !entry.valid())
        return new Entry[0];

    List<Entry> entries = rows.get(entry.scheme);
    List<Entry> matches = new ArrayList<Entry>();


    for(Entry e: entries) {
        if(HTTPAuthStore.compatibleURI(entry.uri,e.uri))
            matches.add(e);
    }
    // Sort by scheme then by url, where any_url is last
    Entry[] matchvec = matches.toArray(new Entry[matches.size()]);
    Arrays.sort(matchvec);
    return matchvec;
}

//////////////////////////////////////////////////
// Misc.


static public AuthScope
getAuthScope(Entry entry)
{
    if(entry == null) return null;
    URI uri;
    try {
	uri = new URI(entry.uri);
    } catch(URISyntaxException use) {return null;}

    String host = uri.getHost();
    int port = uri.getPort();
    String realm = uri.getRawPath();
    String scheme = (entry.scheme == null ? null : entry.scheme.getSchemeName());

    if(host == null) host = AuthScope.ANY_HOST;
    if(port <= 0) port = AuthScope.ANY_PORT;
    if(realm == null) realm = AuthScope.ANY_REALM;
    AuthScope as = new AuthScope(host,port,realm);
    return as;
}


//////////////////////////////////////////////////
/**
n-readers/1-writer semantics
Note not synchronized because only called
by other synchronized procedures
 */

static int nwriters = 0;
static int nreaders = 0;
static boolean stop = false;

private void
acquirewriteaccess() throws HTTPException
{
    nwriters++;
    while(nwriters > 1) {
	try { wait(); } catch (InterruptedException e) {
	    if(stop) throw new HTTPException("interrupted");
        }
    }
    while(nreaders > 0) {
	try { wait(); } catch (InterruptedException e) {
	    if(stop) throw new HTTPException("interrupted");
        }
    }
}

private void
releasewriteaccess()
{
    nwriters--;
    notify();
}

private void
acquirereadaccess() throws HTTPException
{
    nreaders++;
    while(nwriters > 0) {
	try { wait(); } catch (InterruptedException e) {
	    if(stop) throw new HTTPException("interrupted");
        }
    }
}

private void
releasereadaccess()
{
    nreaders--;
    if(nreaders == 0) notify(); //only affects writers
}

///////////////////////////////////////////////////
// Print functions

static public void
print(PrintStream p)
	throws IOException
{
    print(new PrintWriter(p,true));
}

static public void
print(PrintWriter p)
	throws IOException
{
    List<Entry> elist = getAllRows();
    for(int i=0;i<elist.size();i++) {
	Entry e = elist.get(i);
	p.printf("[%02d] %s\n",e.toString());
    }
}

///////////////////////////////////////////////////
// Seriablizable interface
// Encrypted (De-)Serialize

static public void
serialize(OutputStream ostream, String password)
	throws HTTPException
{
    try {
        
    // Create Key
    byte key[] = password.getBytes();
    DESKeySpec desKeySpec = new DESKeySpec(key);
    SecretKeyFactory keyFactory = SecretKeyFactory.getInstance("DES");
    SecretKey secretKey = keyFactory.generateSecret(desKeySpec);

    // Create Cipher
    Cipher desCipher = Cipher.getInstance("DES/ECB/PKCS5Padding");
    desCipher.init(Cipher.ENCRYPT_MODE, secretKey);

    // Create crypto stream
    BufferedOutputStream bos = new BufferedOutputStream(ostream);
    CipherOutputStream cos = new CipherOutputStream(bos, desCipher);
    ObjectOutputStream oos = new ObjectOutputStream(cos);

    oos.writeInt(getAllRows().size());

    for(HTTPAuthScheme scheme: HTTPAuthScheme.values()) {
        List<Entry> entries = rows.get(scheme);
        for(Entry e: entries) {
            oos.writeObject(e);
        }
    }

    oos.flush();
    oos.close();

    } catch (Exception e) {throw new HTTPException(e);}

}

static public void
deserialize(InputStream istream, String password)
    throws HTTPException
{
    List<Entry> entries = getDeserializedEntries(istream,password);
    for(Entry e: entries) {
        insert(e);
    }
}

static public List<Entry>
getDeserializedEntries(InputStream istream, String password)
	throws HTTPException
{
    try {

    // Create Key
    byte key[] = password.getBytes();
    DESKeySpec desKeySpec = new DESKeySpec(key);
    SecretKeyFactory keyFactory = SecretKeyFactory.getInstance("DES");
    SecretKey secretKey = keyFactory.generateSecret(desKeySpec);

    // Create Cipher
    Cipher desCipher = Cipher.getInstance("DES/ECB/PKCS5Padding");
    desCipher.init(Cipher.DECRYPT_MODE, secretKey);

    // Create crypto stream
    BufferedInputStream bis = new BufferedInputStream(istream);
    CipherInputStream cis = new CipherInputStream(bis, desCipher);
    ObjectInputStream ois = new ObjectInputStream(cis);

    List<Entry> entries = new ArrayList<Entry>();
    int count = ois.readInt();
    for(int i=0;i<count;i++) {
        Entry e = (Entry)ois.readObject();
        entries.add(e);
    }
    return entries;
    } catch (Exception e) {throw new HTTPException(e);}
}



}
