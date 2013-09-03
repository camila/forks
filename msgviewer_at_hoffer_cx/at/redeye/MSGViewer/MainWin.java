/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */

/*
 * MainWin.java
 *
 * Created on 27.11.2010, 18:08:54
 */

package at.redeye.MSGViewer;

import at.redeye.FrameWork.Plugin.AboutPlugins;
import at.redeye.FrameWork.base.*;
import at.redeye.FrameWork.base.desktoplauncher.DesktopLauncher;
import at.redeye.FrameWork.base.desktoplauncher.DesktopLauncher2;
import at.redeye.FrameWork.base.imagestorage.ImageUtils;
import at.redeye.FrameWork.base.prm.impl.gui.LocalConfig;
import at.redeye.FrameWork.utilities.DeleteDir;
import at.redeye.FrameWork.utilities.ReadFile;
import at.redeye.FrameWork.utilities.StringUtils;
import at.redeye.FrameWork.utilities.TempDir;
import at.redeye.MSGViewer.MSGNavigator.MSGNavigator;
import at.redeye.MSGViewer.factory.MessageParserFactory;
import at.redeye.MSGViewer.rtfparser.ParseException;
import at.redeye.Plugins.ShellExec.ShellExec;
import com.auxilii.msgparser.Message;
import com.auxilii.msgparser.attachment.Attachment;
import com.auxilii.msgparser.attachment.FileAttachment;
import com.auxilii.msgparser.attachment.MsgAttachment;
import java.awt.EventQueue;
import java.awt.Graphics2D;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.URL;
import java.net.URLDecoder;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.event.HyperlinkEvent;
import javax.swing.event.HyperlinkEvent.EventType;
import javax.swing.event.HyperlinkListener;
import javax.swing.filechooser.FileFilter;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.text.EditorKit;
import javax.swing.text.html.HTMLEditorKit;
import javax.swing.text.html.StyleSheet;
import net.htmlparser.jericho.Attributes;
import net.htmlparser.jericho.Source;
import net.htmlparser.jericho.StartTag;

/**
 *
 * @author martin
 */
public class MainWin extends BaseDialog implements HyperlinkListener
{
    private Message message;
    private File tmp_dir;
    boolean delete_tmp_dir = false;
    private String file_name;
    private String dialog_id;    
    private MessageParserFactory parser_factory = new MessageParserFactory();
    private String bodyText = null;

    private String MESSAGE_NO_DESKTOP_ICON;

    private static String last_path = null;        
    
    int wating_thread_pool_counter = 0;
    
    /**
     * URL where the mouse cursor is over, for the popup menu
     */
    URL lastUrl = null;

    /** Creates new form MainWin */
    public MainWin(Root root, final String file_name ) {
        super(root, file_name != null ? (root.MlM(root.getAppTitle()) + ": " + file_name) : root.getAppTitle() );

        this.file_name = file_name;

        initComponents();

        MESSAGE_NO_DESKTOP_ICON = MlM("Das Desktopicon konnte leider nicht erzeugt werden.");      
        try {
           tmp_dir = TempDir.getTempDir(null, null);
           delete_tmp_dir = true;
        } catch (IOException ex) {
           tmp_dir = new File(System.getProperty("java.io.tmpdir") + "/" + root.getAppName());
        }
        
        header.addHyperlinkListener(this);
        body.addHyperlinkListener(this);

        last_path = root.getSetup().getLocalConfig("LastPath","");

        if( StringUtils.isYes(root.getSetup().getLocalConfig("RTFFormat", "yes") ) )
        {
            jRRTF.setSelected(true);
            jRText.setSelected(false);
        }
        else
        {
            jRRTF.setSelected(false);
            jRText.setSelected(true);
        }

        JCBfix.setSelected(StringUtils.isYes(root.getSetup().getLocalConfig("FixedFont","no" ) ) );

        JCBfix.setEnabled(jRText.isSelected());               
        
        if( file_name == null )
        {
            header.setText(MlM("Ziehe eine Outlook Datei in das Fenster") );
        } else {
            EventQueue.invokeLater(new Runnable() {

                @Override
                public void run() {
                    parse(file_name);
                }
            });
        }

        new EditorDropTarget(this,header);
        new EditorDropTarget(this,body);


        if (!DesktopLauncher.canCreateDesktopIcon())
        {
            jMCreateDesktopIcon.setVisible(false);
        }
        else
        {
            jMCreateDesktopIcon.setEnabled(false);
        }

        jSplitPane.setDividerLocation(Integer.parseInt(root.getSetup().getLocalConfig("DividerLocation","150")));

        registerActionKeyListener(KeyStroke.getKeyStroke(KeyEvent.VK_V,0), new Runnable() {

            @Override
            public void run() {
                jMDetailActionPerformed(null);
            }
        });
        
        registerActionKeyListener(KeyStroke.getKeyStroke(KeyEvent.VK_N,0), new Runnable() {

            @Override
            public void run() {
                if( jMNav.isEnabled() )
                    jMNavActionPerformed(null);
            }
        });        
    }

    @Override
    public String getUniqueDialogIdentifier(Object requester)
    {    
        /*
         * dadurch können wir später den Titel ändern, ohne das sich dadurch
         * die Dialog ID verändert.
         */
        if( dialog_id == null )
         dialog_id = super.getUniqueDialogIdentifier(requester);

        return dialog_id;
    }


    void cleanUp()
    {
        if( message == null )
            return;
                  
         message = null;         
    }

    @Override
    public void close()
    {
        cleanUp();
        root.getSetup().setLocalConfig("LastPath", last_path);
        root.getSetup().setLocalConfig("RTFFormat",jRRTF.isSelected() ? "yes" : "no");
        root.getSetup().setLocalConfig("FixedFont", JCBfix.isSelected() ? "yes" : "no");
        root.getSetup().setLocalConfig("DividerLocation", String.valueOf(jSplitPane.getDividerLocation()));


        if( delete_tmp_dir && tmp_dir.exists() )
            DeleteDir.deleteDirectory(tmp_dir);

        super.close();
    }

    void parse(final String file_name)
    {
        if( file_name == null )
            return;
        
        this.file_name = file_name;

        setWaitCursor();

        new AutoMBox(MainWin.class.getName()) {

                @Override
                public void do_stuff() throws Exception {
                    parse_int( file_name );
                }
            };

        setNormalCursor();
    }

    static boolean is_image_mime_type( String mime )
    {
        if( mime.equals("image/jpeg") )
            return true;
        else if( mime.equals("image/gif") )
            return true;
        else if( mime.equals("image/png") )
            return true;

        return false;
    }
    
    static boolean is_mail_message( String file_name )
    {
        if( file_name.toLowerCase().endsWith(".mbox") )
            return true;
        
        if( file_name.toLowerCase().endsWith(".msg") )
            return true;        
        
        return false;
    }    
    
    static boolean is_mail_message( String file_name, String mime )
    {
        if( is_mail_message( file_name ) ) 
            return true;
        
        return false;
    }

    void parse_int(final String file_name) throws IOException, FileNotFoundException, Exception
    {
        cleanUp();
        
        final ExecutorService thread_pool = Executors.newCachedThreadPool();        
        wating_thread_pool_counter = 0;

        File file = new File(file_name);

        if( !file.exists() )
            throw new FileNotFoundException( MlM( String.format("Datei %s nicht gefunden",file_name)) );
                
        if( file.getName().toLowerCase().endsWith(".msg") )
        {
            jMNav.setEnabled(true);
        } else {
            jMNav.setEnabled(false);
        }
        
        message = parser_factory.parseMessage(file);        
        
        last_path = file.getParentFile().getPath();

        final StringBuilder sb = new StringBuilder();

        setTitle( MlM(root.getAppTitle()) + ": " + message.getSubject() );
       
        sb.append("<html>");
        sb.append("<body style=\"\">");

        sb.append("<b>");
        if( message.getSubject() != null )
            sb.append(message.getSubject());
        sb.append("</b>");        
        sb.append("<br/>");

        logger.info("Message From:" + message.getFromName() + "\n To:" + message.getToName() + "\n Email: " + message.getFromEmail());

        if (message.getFromEmail() == null && message.getFromName() == null) {
        } else if (message.getFromEmail() == null) {
            sb.append(MlM("From:") + message.getFromName());
        } else if (message.getFromName() == null) {
            sb.append("<a href=\"mailto:");
            sb.append(message.getFromEmail());
            sb.append("\">");
            sb.append(message.getFromEmail());
        } else {

            sb.append("<a href=\"mailto:");
            sb.append(message.getFromEmail());
            sb.append("\">");
            sb.append(message.getFromName());
        }
        
        if( message.getFromEmail() != null && message.getFromEmail().contains("@") )
        {
            sb.append(" [");
            sb.append(message.getFromEmail());
            sb.append("]");
        }
        
        sb.append("</a>");

        sb.append("<br/>");        

        if( message.getDate() != null )
        {
            sb.append(MlM("Datum"));
            sb.append(": ");
            sb.append(DateFormat.getDateTimeInstance().format(message.getDate()));
            sb.append("<br/>");
        }

        if( message.getToEmail() != null || message.getToName() != null )
        {
            sb.append(MlM("An:"));
            sb.append(" ");
        }

        if( message.getToName() != null )
        {
            logger.info("toName: " + message.getToName());
            sb.append(message.getToName());
        }

        if( message.getToEmail() != null && message.getToEmail().contains("@") )
        {
            if( message.getToName() != null )
                sb.append(" [");

            sb.append(message.getToEmail());

            if( message.getToName() != null )
                sb.append("]");
        }

        sb.append("<br>");

        List<Attachment> attachments = message.getAttachments();

        final int max_width = Integer.parseInt(root.getSetup().getLocalConfig(AppConfigDefinitions.IconSize));
        final int max_height = max_width;

        for( Attachment att : attachments )
        {
            //System.out.println(att.getClass().getName());
            if( att instanceof FileAttachment)
            {
                final FileAttachment fatt = (FileAttachment) att;
                
                sb.append("<a href=\"file://");
                sb.append(fatt.toString());
                sb.append("\">");                         

                String mime_type = fatt.getMimeTag();

                logger.info(fatt.toString() + " " + mime_type);


                if( mime_type != null && is_image_mime_type(mime_type) && fatt.getSize() < 1024*1024*2 )
                {
                    File message_dir = tmp_dir;

                    if( !message_dir.isDirectory() && !message_dir.mkdirs() )
                    {
                        logger.error( "Cannot create tmp dir: " + message_dir.getPath() );
                    }
                    else
                    {
                        final File content = new File(message_dir + "/" + fatt.toString());

                        if (!content.exists()) {
                            
                            wating_thread_pool_counter++;
                            
                            thread_pool.execute(new Runnable() {

                                @Override
                                public void run() {
                                    
                                    try {
                                        FileOutputStream fout = new FileOutputStream(content);
                                        fout.write(fatt.getData());
                                        fout.close();                                        
                                        
                                    } catch( IOException ex ) {
                                        logger.error(ex,ex);
                                    }
                                    
                                    wating_thread_pool_counter--;
                                }
                            });
                            
                            wating_thread_pool_counter++;
                                       
                            thread_pool.execute(new Runnable() {

                                @Override
                                public void run() {
                                    
                                    try {
                                        
                                        ImageIcon icon = ImageUtils.loadScaledImageIcon(fatt.getData(), 
                                                                    PrepareImages.getFileName(fatt),
                                                                    max_width, max_height );
                                        
                                        File file_small = new File(content.getAbsolutePath() + "-small.jpg");
                                        
                                        BufferedImage bi = new BufferedImage(icon.getIconWidth(),icon.getIconHeight(),
                                                                BufferedImage.TYPE_INT_ARGB);                                        
                                        
                                        Graphics2D g2 = bi.createGraphics();
                                        g2.drawImage(icon.getImage(), 0, 0, null);
                                        g2.dispose();
                                        
                                        ImageIO.write(bi, "jpg", file_small);
                                        
                                    } catch( IOException ex ) {
                                        logger.error(ex,ex);
                                    }
                                    
                                    wating_thread_pool_counter--;
                                }
                            });                            
                            
                        }
/*
                        ImageIcon icon = ImageUtils.loadImageIcon(fatt.getData(), file_name);
                        Dimension dim = ImageUtils.getScaledSize(icon.getImage(), max_width, max_height, max_height);

                        String extra = "/";

                        if( Setup.is_win_system() )
                            extra = "";

                        sb.append("<img border=0 src=\"file:/" + extra + content.getAbsolutePath() + "\" width=\"" 
                                        + String.valueOf(dim.width) + "\" height=\""
                                        + String.valueOf(dim.height) + "\"  /> ");
                         * 
                         */

                        String extra = "/";

                        if( Setup.is_win_system() )
                            extra = "";
                        
                        System.out.println(extra + content.getAbsolutePath() + "-small.jpg" );
                        sb.append("<img border=0 src=\"file:/" + extra + content.getAbsolutePath() + "-small.jpg\"/> ");
                    }
                }
                
                if( is_mail_message(fatt.getFilename(), fatt.getMimeTag() ) ) {
                    sb.append("<img border=0 align=\"baseline\" src=\"file:");
                    sb.append(getMailIconName(tmp_dir));
                    sb.append("\"/>");
                }
                
                if( !fatt.getFilename().isEmpty() )
                    sb.append(fatt.getFilename());
                else
                    sb.append(fatt.toString());

                sb.append("</a> ");

            } else if( att instanceof MsgAttachment) {
                 
                MsgAttachment msgAtt = (MsgAttachment) att;
                final Message msg = msgAtt.getMessage();                                
                
                File message_dir = tmp_dir;

                if( !message_dir.isDirectory() && !message_dir.mkdirs() )
                {
                        logger.error( "Cannot create tmp dir: " + message_dir.getPath() );
                }
                else
                {                
                    final String sub_file_name = message_dir + "/" + msg.hashCode() + ".mbox";
                    
                    thread_pool.execute(new Runnable() {

                        @Override
                        public void run() {
                            
                            new AutoMBox(file_name) {

                                @Override
                                public void do_stuff() throws Exception {
                                    
                                    MessageParserFactory factory = new MessageParserFactory();                                                                    
                                    factory.saveMessage(msg, new File(  sub_file_name ));
                                    
                                }
                            };                                                        
                        }
                        
                    });
                    
                                    
                    sb.append("<a href=\"file://");
                    sb.append(sub_file_name);
                    sb.append("\">");     
                
                    sb.append("<img border=0 align=\"baseline\" src=\"file:");
                    sb.append(getMailIconName(tmp_dir));
                    sb.append("\"/>");
                    
                    sb.append(msg.getSubject());
                
                    sb.append("</a> &nbsp; ");
                }
                
                
            } else {
                logger.error("unknown Attachment: " + att.toString() + " " + att.getClass().getName() );
            }
        }

        sb.append("</body></html>");
        header.setContentType("text/html");

        if( wating_thread_pool_counter > 0 )
        {
            updateBody(); // do something different now 
            
            new AutoMBox(file_name) {

                @Override
                public void do_stuff() throws Exception {
                    thread_pool.shutdown();
                    
                    if( wating_thread_pool_counter > 0 ) {
                        thread_pool.awaitTermination(1, TimeUnit.DAYS);
                    }
                }
            };
                    
            header.setText(sb.toString());
            header.setCaretPosition(0);

        } else {
                                
            header.setText(sb.toString());
            header.setCaretPosition(0);
        
            updateBody();
        }
    }
    
    private String getMailIconName( File tmp_dir ) throws IOException
    {
        File file = new File(tmp_dir + "/mail.png");
        
        if( file.exists() )
            return file.toString();
        
        byte bytes[] = ReadFile.getBytesResource(this.getClass(), "/at/redeye/MSGViewer/resources/icons/rg1024_yellow_mail.png");
        FileOutputStream writer = new FileOutputStream(file);        
        writer.write(bytes);
        writer.close();
        
        return file.toString();
    }

    private void updateBody()
    {
        if( message == null )
            return;

        if( jRRTF.isSelected() &&  message.getBodyRTF() != null &&  !message.getBodyRTF().isEmpty() )
        {            
            if( message.getBodyRTF() == null )
                return;
            
            if( message.getBodyRTF().contains("\\fromhtml") )
            {
                AutoMBox am = new AutoMBox(MainWin.class.getName()) {

                    @Override
                    public void do_stuff() throws Exception {
                        body.setContentType("text/html");
                        logger.info("extracting HTML data from RTF Code");

                        if( logger.isTraceEnabled() )
                        {
                            logger.trace("\n" + StringUtils.addLineNumbers(message.getBodyRTF()));
                        }

                        bodyText = extractHTMLFromRTF(message.getBodyRTF());
                        
                        logger.trace(bodyText);
                        // System.out.println("\n\n");
                        body.setText(bodyText);
                    }
                };


                if( am.isFailed() )
                {
                    body.setContentType("text/rtf");
                    bodyText = message.getBodyRTF();
                    body.setText(bodyText);
                }
            }
            else if( message.getBodyRTF().contains("\\purehtml") )
            {
                body.setContentType("text/html");
                
                PrepareImages prep_images = new PrepareImages(tmp_dir.getPath(), message);

                String html = prep_images.prepareImages(new StringBuilder(stripMetaTags(message.getBodyRTF()))).toString();                
                bodyText = html;
                body.setText(html);  
            }
            else
            {
                body.setContentType("text/rtf");
                bodyText = message.getBodyRTF();
                body.setText(message.getBodyRTF());
            }

            // System.out.println(message.getBodyRTF());
        }
        else
        {
            /*
            body.setContentType("text/plain");
            body.setText(message.getBodyText());

            if (JCBfix.isSelected()) {
                body.setFont(new java.awt.Font("Courier New", 0, 12));
            } else {
                body.setFont(new java.awt.Font("Dialog", 0, 12));
            }
             */

            StringBuilder sb = new StringBuilder();

            sb.append("<html><body>");

            String font = "Dialog,sans-serif;font-size:10px;";

            if (JCBfix.isSelected()) {
                font = "Courier New;font-size:10px;";
            }

            sb.append("<pre style=\"font-family:" + font + "\">");

            // System.out.println(message.getBodyText());

            String text = message.getBodyText();
/*
            try {
                CharacterSetConversion cs = new CharacterSetConversion(message);
                text = cs.getEncoded(message.getBodyText());
            } catch( UnsupportedEncodingException ex ) {
                logger.error(StringUtils.exceptionToString(ex));
            }
*/
            sb.append(prepareText(text));

            sb.append("</pre>");
            sb.append("</body></html>");

            body.setContentType("text/html");
            bodyText = sb.toString();
            body.setText(bodyText);
        }

        body.setCaretPosition(0);
    }
    
    private String stripMetaTags(String html )
    {
       html =  html.replaceAll("<[Mm][eE][Tt][aA]\\s.*>", "");
       
       StringBuilder body_text = new StringBuilder(html);
       Source source = new Source(html);
       source.fullSequentialParse();
       
       for( StartTag tag : source.getAllStartTags("font") )
       {
            // remove size="x"
            Attributes atts = tag.getAttributes();
           if( atts == null )
               continue;   
           
           net.htmlparser.jericho.Attribute att = atts.get("size");
           
           if( att != null )
           {
               int start = att.getBegin();
               int end = att.getEnd();
               
               for( int i = start; i < end+1; i++ )
               {
                body_text.setCharAt(i, ' ');
               }
           }
       }
       
       System.out.println(body_text.toString());
       
       return body_text.toString();
    }

    // <editor-fold defaultstate="collapsed" desc="Generated Code">//GEN-BEGIN:initComponents
    private void initComponents() {

        jSplitPane = new javax.swing.JSplitPane();
        jScrollPane2 = new javax.swing.JScrollPane();
        header = new javax.swing.JEditorPane();
        jPanel1 = new javax.swing.JPanel();
        jScrollPaneBody = new javax.swing.JScrollPane();
        body = new javax.swing.JEditorPane();
        jPanel2 = new javax.swing.JPanel();
        jRRTF = new javax.swing.JRadioButton();
        jRText = new javax.swing.JRadioButton();
        JCBfix = new javax.swing.JCheckBox();
        jSFontSize = new javax.swing.JSlider();
        jLabel1 = new javax.swing.JLabel();
        menubar = new javax.swing.JMenuBar();
        jMenu1 = new javax.swing.JMenu();
        jMOpenFile = new javax.swing.JMenuItem();
        jMSaveAs = new javax.swing.JMenuItem();
        jMSettings = new javax.swing.JMenuItem();
        jMCreateDesktopIcon = new javax.swing.JMenuItem();
        jMQuit = new javax.swing.JMenuItem();
        jMenu2 = new javax.swing.JMenu();
        jMDetail = new javax.swing.JMenuItem();
        jMNav = new javax.swing.JMenuItem();
        jSeparator1 = new javax.swing.JPopupMenu.Separator();
        jMAbout = new javax.swing.JMenuItem();
        jMChangeLog = new javax.swing.JMenuItem();
        jMPlugin = new javax.swing.JMenuItem();

        setDefaultCloseOperation(javax.swing.WindowConstants.DISPOSE_ON_CLOSE);
        setMinimumSize(new java.awt.Dimension(400, 400));

        jSplitPane.setDividerLocation(150);
        jSplitPane.setOrientation(javax.swing.JSplitPane.VERTICAL_SPLIT);

        header.setEditable(false);
        header.addMouseListener(new java.awt.event.MouseAdapter() {
            public void mouseClicked(java.awt.event.MouseEvent evt) {
                headerMouseClicked(evt);
            }
        });
        jScrollPane2.setViewportView(header);

        jSplitPane.setTopComponent(jScrollPane2);

        body.setEditable(false);
        body.setDoubleBuffered(true);
        body.addMouseListener(new java.awt.event.MouseAdapter() {
            public void mouseClicked(java.awt.event.MouseEvent evt) {
                bodyMouseClicked(evt);
            }
        });
        jScrollPaneBody.setViewportView(body);

        jRRTF.setText("RTF");
        jRRTF.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jRRTFActionPerformed(evt);
            }
        });

        jRText.setText("Text");
        jRText.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jRTextActionPerformed(evt);
            }
        });

        JCBfix.setText("Fixe Schriftart");
        JCBfix.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                JCBfixActionPerformed(evt);
            }
        });

        jSFontSize.setMaximum(30);
        jSFontSize.setMinimum(1);
        jSFontSize.setValue(12);
        jSFontSize.addChangeListener(new javax.swing.event.ChangeListener() {
            public void stateChanged(javax.swing.event.ChangeEvent evt) {
                jSFontSizeStateChanged(evt);
            }
        });
        jSFontSize.addPropertyChangeListener(new java.beans.PropertyChangeListener() {
            public void propertyChange(java.beans.PropertyChangeEvent evt) {
                jSFontSizePropertyChange(evt);
            }
        });

        jLabel1.setText("Schriftgröße");

        javax.swing.GroupLayout jPanel2Layout = new javax.swing.GroupLayout(jPanel2);
        jPanel2.setLayout(jPanel2Layout);
        jPanel2Layout.setHorizontalGroup(
            jPanel2Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(jPanel2Layout.createSequentialGroup()
                .addContainerGap()
                .addComponent(jRRTF)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addComponent(jRText)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.UNRELATED)
                .addComponent(JCBfix)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addComponent(jSFontSize, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addComponent(jLabel1)
                .addContainerGap(371, Short.MAX_VALUE))
        );
        jPanel2Layout.setVerticalGroup(
            jPanel2Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(jPanel2Layout.createSequentialGroup()
                .addGroup(jPanel2Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addGroup(jPanel2Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                        .addComponent(jRText)
                        .addComponent(JCBfix)
                        .addComponent(jRRTF))
                    .addComponent(jSFontSize, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(jLabel1))
                .addContainerGap(javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE))
        );

        javax.swing.GroupLayout jPanel1Layout = new javax.swing.GroupLayout(jPanel1);
        jPanel1.setLayout(jPanel1Layout);
        jPanel1Layout.setHorizontalGroup(
            jPanel1Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addComponent(jScrollPaneBody)
            .addGroup(jPanel1Layout.createSequentialGroup()
                .addComponent(jPanel2, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                .addContainerGap())
        );
        jPanel1Layout.setVerticalGroup(
            jPanel1Layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(javax.swing.GroupLayout.Alignment.TRAILING, jPanel1Layout.createSequentialGroup()
                .addComponent(jScrollPaneBody, javax.swing.GroupLayout.DEFAULT_SIZE, 446, Short.MAX_VALUE)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addComponent(jPanel2, javax.swing.GroupLayout.PREFERRED_SIZE, 25, javax.swing.GroupLayout.PREFERRED_SIZE))
        );

        jSplitPane.setRightComponent(jPanel1);

        jMenu1.setText("Programm");

        jMOpenFile.setText("Datei öffnen");
        jMOpenFile.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jMOpenFileActionPerformed(evt);
            }
        });
        jMenu1.add(jMOpenFile);

        jMSaveAs.setText("Datei speichern unter...");
        jMSaveAs.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jMSaveAsActionPerformed(evt);
            }
        });
        jMenu1.add(jMSaveAs);

        jMSettings.setText("Einstellungen");
        jMSettings.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jMSettingsActionPerformed(evt);
            }
        });
        jMenu1.add(jMSettings);

        jMCreateDesktopIcon.setText("Desktop Icon erstellen");
        jMCreateDesktopIcon.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jMCreateDesktopIconActionPerformed(evt);
            }
        });
        jMenu1.add(jMCreateDesktopIcon);

        jMQuit.setText("Beenden");
        jMQuit.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jMQuitActionPerformed(evt);
            }
        });
        jMenu1.add(jMQuit);

        menubar.add(jMenu1);

        jMenu2.setText("Info");

        jMDetail.setText("Details");
        jMDetail.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jMDetailActionPerformed(evt);
            }
        });
        jMenu2.add(jMDetail);

        jMNav.setText("MSG Navigator");
        jMNav.setEnabled(false);
        jMNav.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jMNavActionPerformed(evt);
            }
        });
        jMenu2.add(jMNav);
        jMenu2.add(jSeparator1);

        jMAbout.setText("Über");
        jMAbout.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jMAboutActionPerformed(evt);
            }
        });
        jMenu2.add(jMAbout);

        jMChangeLog.setText("Änderungsprotokoll");
        jMChangeLog.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jMChangeLogActionPerformed(evt);
            }
        });
        jMenu2.add(jMChangeLog);

        jMPlugin.setText("Plugins");
        jMPlugin.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                jMPluginActionPerformed(evt);
            }
        });
        jMenu2.add(jMPlugin);

        menubar.add(jMenu2);

        setJMenuBar(menubar);

        javax.swing.GroupLayout layout = new javax.swing.GroupLayout(getContentPane());
        getContentPane().setLayout(layout);
        layout.setHorizontalGroup(
            layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addComponent(jSplitPane, javax.swing.GroupLayout.Alignment.TRAILING, javax.swing.GroupLayout.DEFAULT_SIZE, 841, Short.MAX_VALUE)
        );
        layout.setVerticalGroup(
            layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addComponent(jSplitPane, javax.swing.GroupLayout.DEFAULT_SIZE, 633, Short.MAX_VALUE)
        );

        pack();
    }// </editor-fold>//GEN-END:initComponents

    private void jMAboutActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_jMAboutActionPerformed
        invokeDialogUnique(new About(root));
}//GEN-LAST:event_jMAboutActionPerformed

    private void jMChangeLogActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_jMChangeLogActionPerformed

        invokeDialogUnique(new LocalHelpWin(root, "ChangeLog"));
}//GEN-LAST:event_jMChangeLogActionPerformed

    private void jMPluginActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_jMPluginActionPerformed

        invokeDialogUnique(new AboutPlugins(root));
}//GEN-LAST:event_jMPluginActionPerformed

    private void jRRTFActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_jRRTFActionPerformed

        jRText.setSelected(!jRRTF.isSelected());
        JCBfix.setEnabled(jRText.isSelected());
        updateBody();

    }//GEN-LAST:event_jRRTFActionPerformed

    private void jRTextActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_jRTextActionPerformed
         jRRTF.setSelected(!jRText.isSelected());
         JCBfix.setEnabled(jRText.isSelected());
         updateBody();
    }//GEN-LAST:event_jRTextActionPerformed

    private void JCBfixActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_JCBfixActionPerformed

        updateBody();
    }//GEN-LAST:event_JCBfixActionPerformed

    private void jMDetailActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_jMDetailActionPerformed

        if( message != null)
            invokeDialogUnique(new Internals(root, message));

    }//GEN-LAST:event_jMDetailActionPerformed

    private void headerMouseClicked(java.awt.event.MouseEvent evt) {//GEN-FIRST:event_headerMouseClicked

        if( evt.getButton() == MouseEvent.BUTTON3 )
        {
            if( lastUrl == null )
                return;

            JPopupMenu popup = new HeaderActionPopup(this, lastUrl);

            popup.show(evt.getComponent(), evt.getX(), evt.getY());
        }

    }//GEN-LAST:event_headerMouseClicked

    private void bodyMouseClicked(java.awt.event.MouseEvent evt) {//GEN-FIRST:event_bodyMouseClicked
        if( evt.getButton() == MouseEvent.BUTTON3 && message != null )
        {
            JPopupMenu popup = new BodyActionPopup(this);

            popup.show(evt.getComponent(), evt.getX(), evt.getY());
        }

    }//GEN-LAST:event_bodyMouseClicked

    private void jMNavActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_jMNavActionPerformed
        
        invokeDialogUnique(new MSGNavigator(root, new File(file_name)));
        
    }//GEN-LAST:event_jMNavActionPerformed

    private void jSFontSizeStateChanged(javax.swing.event.ChangeEvent evt) {//GEN-FIRST:event_jSFontSizeStateChanged
        
        EditorKit editor = body.getEditorKit();
   
        if (editor instanceof HTMLEditorKit) {                                   
            
            Source source = new Source(body.getText());
            source.fullSequentialParse();

            ArrayList<String> tags = new ArrayList();

            for (StartTag tag : source.getAllStartTags()) {                
                tags.add(tag.getName());
            }
            
            
            HTMLEditorKit html_editor = (HTMLEditorKit) editor;

            System.out.println("Value: " + jSFontSize.getValue());

            StyleSheet sheet = html_editor.getStyleSheet();          
            
            String rule = "{ font-size: " + (jSFontSize.getValue()) + "pt; }";

            StringBuilder sb = new StringBuilder();

            for (String tag : tags) {
                sb.append(tag);
                sb.append(rule);
            }

            sheet.addRule(sb.toString());

            String text = body.getText();
            body.setDocument(html_editor.createDefaultDocument());
            body.setText(text);                       
            body.setCaretPosition(0);

        } else {                                             
           
            bodyText = bodyText.replaceAll("(\\\\fs)([0-9]+)", "$1" + ((jSFontSize.getValue())*2));
            System.out.println(bodyText);
            body.setText(bodyText);             
            body.setCaretPosition(0);
                    
        }
        
    }//GEN-LAST:event_jSFontSizeStateChanged

    private void jSFontSizePropertyChange(java.beans.PropertyChangeEvent evt) {//GEN-FIRST:event_jSFontSizePropertyChange

    }//GEN-LAST:event_jSFontSizePropertyChange

    private void jMQuitActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_jMQuitActionPerformed

         close();     }//GEN-LAST:event_jMQuitActionPerformed

    private void jMCreateDesktopIconActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_jMCreateDesktopIconActionPerformed

         DesktopLauncher launcher = new DesktopLauncher2(root);          if (!launcher.createDesktopIcon()) {              launcher = new DesktopLauncher2(root);              if (!launcher.createDesktopIcon()) {                  JOptionPane.showMessageDialog(this, MESSAGE_NO_DESKTOP_ICON);             }         }     }//GEN-LAST:event_jMCreateDesktopIconActionPerformed

    private void jMSettingsActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_jMSettingsActionPerformed

        invokeDialogUnique(new LocalConfig(root));     }//GEN-LAST:event_jMSettingsActionPerformed

    private void jMSaveAsActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_jMSaveAsActionPerformed

         final JFileChooser fc = new JFileChooser();          fc.setAcceptAllFileFilterUsed(false);          final FileFilter msg_filter = new FileNameExtensionFilter(MlM("Outlook *.msg Datei"), "msg");         final FileFilter mbox_filter = new FileNameExtensionFilter(MlM("Unix *.mbox Datei"), "mbox");         final FileFilter eml_filter = new FileNameExtensionFilter(MlM("Thunderbird *.eml Datei"), "eml");          fc.addChoosableFileFilter(msg_filter);         fc.addChoosableFileFilter(mbox_filter);         fc.addChoosableFileFilter(eml_filter);         fc.setMultiSelectionEnabled(false);          if (last_path != null) {             fc.setCurrentDirectory(new File(last_path));         }          int retval = fc.showSaveDialog(this);          if (retval != 0) {             return;         }          final File file = fc.getSelectedFile();          new AutoMBox(this.getClass().getName()) {              @Override             public void do_stuff() throws Exception {                  File export_file = file;                  if (!file.getName().toLowerCase().endsWith(".msg")                         && !file.getName().toLowerCase().endsWith(".eml")                         && !file.getName().toLowerCase().endsWith(".mbox")) {                     if (fc.getFileFilter() == msg_filter) {                         export_file = new File(file.getAbsolutePath() + ".msg");                     } else if (fc.getFileFilter() == eml_filter) {                         export_file = new File(file.getAbsolutePath() + ".eml");                     } else {                         export_file = new File(file.getAbsolutePath() + ".mbox");                     }                 }                  exportFile(message, export_file);             }         };      }//GEN-LAST:event_jMSaveAsActionPerformed

    private void jMOpenFileActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_jMOpenFileActionPerformed

          JFileChooser fc = new JFileChooser();          fc.setAcceptAllFileFilterUsed(false);         fc.setFileFilter(new MSGFileFilter(root));         fc.setMultiSelectionEnabled(true);          logger.info("last path: " + last_path);          if (last_path != null) {             fc.setCurrentDirectory(new File(last_path));         }          int retval = fc.showOpenDialog(this);          if (retval != 0) {             return;         }          final File[] files = fc.getSelectedFiles();          for (File file : files) {             loadMessage(file.getPath());         }     }//GEN-LAST:event_jMOpenFileActionPerformed



    // Variables declaration - do not modify//GEN-BEGIN:variables
    private javax.swing.JCheckBox JCBfix;
    private javax.swing.JEditorPane body;
    private javax.swing.JEditorPane header;
    private javax.swing.JLabel jLabel1;
    private javax.swing.JMenuItem jMAbout;
    private javax.swing.JMenuItem jMChangeLog;
    private javax.swing.JMenuItem jMCreateDesktopIcon;
    private javax.swing.JMenuItem jMDetail;
    private javax.swing.JMenuItem jMNav;
    private javax.swing.JMenuItem jMOpenFile;
    private javax.swing.JMenuItem jMPlugin;
    private javax.swing.JMenuItem jMQuit;
    private javax.swing.JMenuItem jMSaveAs;
    private javax.swing.JMenuItem jMSettings;
    private javax.swing.JMenu jMenu1;
    private javax.swing.JMenu jMenu2;
    private javax.swing.JPanel jPanel1;
    private javax.swing.JPanel jPanel2;
    private javax.swing.JRadioButton jRRTF;
    private javax.swing.JRadioButton jRText;
    private javax.swing.JSlider jSFontSize;
    private javax.swing.JScrollPane jScrollPane2;
    private javax.swing.JScrollPane jScrollPaneBody;
    private javax.swing.JPopupMenu.Separator jSeparator1;
    private javax.swing.JSplitPane jSplitPane;
    private javax.swing.JMenuBar menubar;
    // End of variables declaration//GEN-END:variables

    @Override
    public void hyperlinkUpdate( final HyperlinkEvent e )
    {
        new AutoMBox(MainWin.class.getName()) {

            @Override
            public void do_stuff() throws Exception {

                if (message == null) {
                    return;
                }

                if (!e.getEventType().equals(EventType.ACTIVATED)) {
                    if (e.getEventType().equals(EventType.ENTERED)) {
                        lastUrl = e.getURL();
                    } else if (e.getEventType().equals(EventType.EXITED)) {
                        lastUrl = null;
                    }

                    return;
                }

                lastUrl = null;
        
                openUrl(e.getURL());
            }      
        };
    }

    public File extractUrl(URL url) throws IOException
    {
        List<Attachment> attachments = message.getAttachments();

        for( Attachment att : attachments )
        {
            //System.out.println(att.getClass().getName());
            if( att instanceof FileAttachment)
            {
                FileAttachment fatt = (FileAttachment) att;

                String att_file_name = "file://" + fatt.toString();

                // logger.info("file " + fatt);

                if( att_file_name.equals(url.toString()) )
                {
                    logger.info("opening " + fatt);

                    File message_dir = tmp_dir;

                    if( !message_dir.isDirectory() && !message_dir.mkdirs() )
                    {
                        throw new RuntimeException( "Cannot create tmp dir: " + message_dir.getPath() );
                    }

                    File content = new File( message_dir + "/" + fatt.toString() );

                    if( !content.exists() )
                    {
                        FileOutputStream fout = new FileOutputStream(content);

                        fout.write(fatt.getData());

                        fout.close();
                    }

                    return content;
                }
            }
        }

        return null;
    }    
   
   public void openUrl(URL url) throws IOException
    {       
        logger.info(url);

        final String protocoll = url.getProtocol();

        if( !protocoll.equals("file") )
        {
            if (Setup.is_win_system()) {
                logger.info("opening: " + url);

                ShellExec shell = new ShellExec();
                int ret = shell.execute(url.toString());
                logger.debug("shell exec returned: " + ret);
            } else {

                String open_command = getOpenCommand();

                String command = open_command + " \"" + url.toString() + "\"";
                logger.info(command);

                String command_array[] = new String[2];

                command_array[0] = open_command;
                command_array[1] = url.toString();

                Process p = Runtime.getRuntime().exec(command_array);
            }
            return;
        }

        File content = extractUrl(url);

        if( content == null )
        {
            // maybe the url points to a local directory
            content = new File(url.getFile());
        }

        if( is_mail_message(content.toString() ) ) {
            MainWin win = new MainWin(root,content.toString());
            
            if( !menubar.isVisible() )
                win.hideMenuBar();
            
            invokeDialog(win);
            
        } else {

            if (Setup.is_win_system() && root.getPlugin("ShellExec") != null ) {
                logger.info("opening: " + content.getPath());

                ShellExec shell = new ShellExec();
                int ret = shell.execute(content.getPath());
                logger.debug("shell exec returned: " + ret);
            } else {
                String open_command = getOpenCommand();

                String command = open_command + " \"" + content.getPath() + "\"";
                logger.info(command);

                String command_array[] = new String[2];

                command_array[0] = open_command;
                command_array[1] = content.getPath();

                Process p = Runtime.getRuntime().exec(command_array);
            }
        }

    }

    private String getOpenCommand()
    {
        if( Setup.is_win_system() )
            return "explorer";

        return root.getSetup().getLocalConfig(FrameWorkConfigDefinitions.OpenCommand);
    }

    void loadMessage(String file_name)
    {
        logger.info("filename: " + file_name);

        if( file_name.startsWith("file://") )
        {
            try
            {
                file_name = URLDecoder.decode(file_name,"UTF-8");
                file_name = file_name.substring(7);

            } catch( UnsupportedEncodingException ex ) {
                logger.error(StringUtils.exceptionToString(ex));
                file_name = file_name.substring(7);
            } 
        }

        if( message == null )
        {
            parse(file_name);
        }
        else
        {
            MainWin win = new MainWin( root, file_name );
            
            if( !menubar.isVisible() )
                win.hideMenuBar();
            
            invokeMainDialog( win );
        }
    }

    public void setCreateDesktopIconEnabled(boolean state) {
        jMCreateDesktopIcon.setEnabled(state);
    }

    String prepareText( String s )
    {
        if( s == null )
            return "";
        
        StringBuilder sb = new StringBuilder();

        s = s.replaceAll("<", "&lt;");
        s = s.replaceAll(">", "&gt;");

        int start = 0;
        int last_start = 0;

        while( true )
        {
            start = s.indexOf("http://", last_start);

            if( start == -1 )
            {
                sb.append(s.substring(last_start));
                break;
            }

            logger.info("last_start: " + last_start + " start: " + start + " length: " + s.length() );

            sb.append(s.substring(last_start, start));
            last_start = start;

            sb.append("<a href=\"");

            int i;

            for( i = start; i < s.length(); i++ )
            {
              if( s.indexOf("&gt;",i ) == i )
                  break;

              char c = s.charAt(i);

              if( StringUtils.is_space(c) )
              {
                  break;
              }
              sb.append(c);
            }

            sb.append("\">");
            sb.append(s.substring(start,i-1));
            sb.append("</a>");

            last_start = i;
        }

        return sb.toString();
    }

    private String extractHTMLFromRTF(String bodyText) throws ParseException
    {
        if( bodyText.contains("\\purehtml") )
            return bodyText;
        
        HtmlFromRtf rtf2html = new HtmlFromRtf(bodyText);

        String html = rtf2html.getHTML();
        
        html = stripMetaTags(html);

        PrepareImages prep_images = new PrepareImages(tmp_dir.getPath(), message);

        return prep_images.prepareImages(new StringBuilder(html)).toString();
    }

    public String getLastOpenPath()
    {
        return last_path;
    }

    public void setLastOpenPath(String path)
    {
        last_path = path;
    }

    public String getHTMLCode() throws ParseException
    {
        return extractHTMLFromRTF(message.getBodyRTF());
    }

    public File getMailDirectory()
    {
        return tmp_dir;
    }

    private void exportFile(Message message, File file) throws FileNotFoundException, Exception 
    {
        parser_factory.saveMessage(message, file);
    }
    
    public String getFileName()
    {
       return file_name;
    }
    
    public void exportFile( File file ) throws FileNotFoundException, Exception 
    {
        parser_factory.saveMessage(message, file);
    }    
    
    public void disableCreateDesktopIcon()
    {
         jMCreateDesktopIcon.setVisible(false);
    }

    void hideMenuBar() {
       menubar.setVisible(false);
    }

}
