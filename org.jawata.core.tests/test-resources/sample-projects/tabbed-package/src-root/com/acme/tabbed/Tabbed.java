package	com.acme.tabbed;

/** A TAB after `package` is legal Java. Requiring the literal "package " sent
 *  this file to the type-declaration check, which declared it the DEFAULT
 *  package — so this directory became its own source root and the class below
 *  landed in the wrong package. */
public class Tabbed {
    /** Returns a marker. */
    public String marker() { return "tabbed"; }
}
