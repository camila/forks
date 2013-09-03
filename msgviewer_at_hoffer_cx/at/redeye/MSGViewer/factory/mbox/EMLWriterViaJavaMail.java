/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package at.redeye.MSGViewer.factory.mbox;

/**
 *
 * @author kingleo
 */
public class EMLWriterViaJavaMail extends MBoxWriterViaJavaMail {
 
    @Override
    public String getExtension()
    {
        return "eml";
    }
}
