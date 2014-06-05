package org.jitsi.hammer;


import org.jivesoftware.smack.*;
import org.jivesoftware.smackx.muc.*;
import org.jivesoftware.smackx.packet.*;
import org.jivesoftware.smack.packet.*;
import org.jivesoftware.smack.filter.*;
import org.jivesoftware.smack.provider.*;

import org.ice4j.ice.*;

import org.jitsi.service.neomedia.*;
import org.jitsi.hammer.utils.*;

import net.java.sip.communicator.impl.protocol.jabber.extensions.jingle.*;
import net.java.sip.communicator.impl.protocol.jabber.extensions.jingle.ContentPacketExtension.*;

import java.io.*;
import java.util.*;


/**
 * 
 * @author Thomas Kuntz
 *
 * <tt>JingleSession</tt> represent a Jingle,ICE and RTP/RTCP session with
 * jitsi-videobridge : it simulate a jitmeet user by setting up an 
 * ICE stream and then sending fake audio/video data using RTP
 * to the videobridge.
 *
 */
public class JingleSession implements PacketListener {
    /**
     * The XMPP server info to which this <tt>JingleSession</tt> will
     * communicate
     */
    private HostInfo serverInfo;
    
    /**
     * The username/nickname taken by this <tt>JingleSession</tt> in the
     * MUC chatroom
     */
    private String username;
    
    
    /**
     * The <tt>ConnectionConfiguration</tt> equivalent of <tt>serverInfo</tt>.
     */
    private ConnectionConfiguration config;
    
    /**
     * The object use to connect to and then communicate with the XMPP server.
     */
    private XMPPConnection connection;
    
    /**
     * The object use to connect to and then send message to the MUC chatroom.
     */
    private MultiUserChat muc;
    
        
    /**
     * The IQ message received by the XMPP server to initiate the Jingle session.
     * 
     * It contains a list of <tt>ContentPacketExtension</tt> representing
     * the media and their formats the videobridge is offering to send/receive
     * and their corresponding transport information (IP, port, etc...).
     */
    private JingleIQ sessionInitiate;
    
    /**
     * The IQ message send by this <tt>JingleSession</tt> to the XMPP server
     * to accept the Jingle session.
     * 
     * It contains a list of <tt>ContentPacketExtension</tt> representing
     * the media and format, with their corresponding transport information,
     * that this <tt>JingleSession</tt> accept to receive and send. 
     */
    private JingleIQ sessionAccept;

    /**
     * A Map of the different <tt>MediaStream</tt> this <tt>JingleSession</tt>
     * handles.
     */
    private Map<String,MediaStream> mediaStreamMap;
    
    
    /**
     * Instantiates a <tt>JingleSession</tt> with a default username that
     * will connect to the XMPP server contained in <tt>hostInfo</tt>.
     *  
     * @param hostInfo the XMPP server informations needed for the connection.
     */
    public JingleSession(HostInfo hostInfo)
    {
        this(hostInfo,null);
    }
    
    /**
     * Instantiates a <tt>JingleSession</tt> with a specified <tt>username</tt>
     * that will connect to the XMPP server contained in <tt>hostInfo</tt>.
     * 
     * @param hostInfo the XMPP server informations needed for the connection.
     * @param username the username used by this <tt>JingleSession</tt> in the
     * connection.
     * 
     */
    public JingleSession(HostInfo hostInfo,String username)
    {
        this.serverInfo = hostInfo;
        this.username = (username == null) ? "Anonymous" : username;
        
        
        
        ProviderManager manager = ProviderManager.getInstance();
        manager.addExtensionProvider(
                MediaProvider.ELEMENT_NAME,
                MediaProvider.NAMESPACE,
                new MediaProvider());
        
        manager.addIQProvider(
                JingleIQ.ELEMENT_NAME,
                JingleIQ.NAMESPACE,
                new JingleIQProvider());

        
        
        
        config = new ConnectionConfiguration(
                serverInfo.getHostname(),
                serverInfo.getPort(),
                serverInfo.getDomain());
        
        connection = new XMPPConnection(config);
        connection.addPacketListener(this,new PacketFilter()
            {
                public boolean accept(Packet packet)
                {
                    return (packet instanceof JingleIQ);
                }
            });
        
        
        config.setDebuggerEnabled(true);
    }


    /**
     * Connect to the XMPP server then to the MUC chatroom.
     * @throws XMPPException if the connection to the XMPP server goes wrong
     */
    public void start()
        throws XMPPException
    {
        connection.connect();
        connection.loginAnonymously();

        
        String roomURL = serverInfo.getRoomName()+"@"+serverInfo.getHostname();
        muc = new MultiUserChat(
                connection,
                roomURL);
        muc.join(username);
        muc.sendMessage("Hello World!");
        
        
        /*
         * Send a Presence packet containing a Nick extension so that the
         * nickname is correctly displayed in jitmeet
         */
        Packet nicknamePacket = new Presence(Presence.Type.available);
        String recipient = serverInfo.getRoomName()+"@"+serverInfo.getHostname();
        nicknamePacket.addExtension(new Nick(username));
        nicknamePacket.setTo(recipient);
        connection.sendPacket(nicknamePacket);
        
        
        /*
         * Add a simple message listener that will just display in the terminal
         * received message (and respond back with a "C'est pas faux");
         */
        muc.addMessageListener(
                new MyPacketListener(muc,roomURL +"/" + muc.getNickname()) );
    }

    /**
     * Stop all media stream and disconnect from the MUC and the XMPP server
     */
    public void stop()
    {
        for(MediaStream stream : mediaStreamMap.values())
        {
            stream.stop();
        }
        muc.leave();
        connection.disconnect();
    }
    
    
    
    /**
     * acceptJingleSession create a accept-session Jingle message and
     * send it to the initiator of the session.
     * The initiator is taken from the From attribut 
     * of the initiate-session message.
     * 
     * FIXME : this function is a WIP (more like a battleground) for now.
     * My first implementation was a little naive
     * and I'm not satisfied with how it do things.
     */
    private void acceptJingleSession()
    {
        ArrayList<ContentPacketExtension> contentList = null;
        Map<String,SelectedMedia> selectedMedias = null;
        IceMediaStreamGenerator iceMediaStreamGenerator = null;
        
        Agent agent = null;
        
        
        
        iceMediaStreamGenerator = IceMediaStreamGenerator.getInstance();

        /* Now is the code section where we generate
         * the content list for the session-accept */
        ////////////////////////////////////
        
        contentList = new ArrayList<ContentPacketExtension>();
        
        selectedMedias = 
                HammerUtils.generateAcceptedContentListFromSessionInitiateIQ(
                        contentList,
                        sessionInitiate,
                        SendersEnum.both);
        
        
        try
        {
            agent = iceMediaStreamGenerator.generateIceMediaStream(
                    selectedMedias.keySet(),
                    null,
                    null);
        }
        catch (IOException e)
        {
            System.err.println(e);
        }

        HammerUtils.addRemoteCandidateToAgent(
                agent,
                sessionInitiate.getContentList());
        HammerUtils.addLocalCandidateToContentList(
                agent,
                contentList);

        agent.startConnectivityEstablishment();

        ////////////////////////////////////
        /*
         * End of the code section generating
         * the content list of the accept-session */

        
        //Creation of a session-accept message and its sending
        sessionAccept = JinglePacketFactory.createSessionAccept(
                sessionInitiate.getTo(),
                sessionInitiate.getFrom(),
                sessionInitiate.getSID(),
                contentList);
        connection.sendPacket(sessionAccept);
        System.out.println("Jingle accept-session message sent");
        
        
        while(IceProcessingState.TERMINATED != agent.getState())
        {
            System.out.println("Connectivity Establishment in process");
            try
            {
                Thread.sleep(1500);
            }
            catch (Exception e)
            {
                e.printStackTrace();
            }
        }
        
        mediaStreamMap = HammerUtils.generateMediaStreamFromAgent(
                agent,
                selectedMedias);
        
        HammerUtils.setDtlsEncryptionOnTransport(
                mediaStreamMap,
        //        contentList,
                sessionInitiate.getContentList());
        
        for(MediaStream stream : mediaStreamMap.values())
        {
            stream.start();
        }
    }

    
    /**
     * Callback function used when a JingleIQ is received by the XMPP connector.
     * @param packet the packet received by the <tt>JingleSession</tt> 
     */
    public void processPacket(Packet packet)
    {
        JingleIQ jiq = (JingleIQ)packet;
        System.out.println("Jingle initiate-session message received");
        ackJingleIQ(jiq);
        switch(jiq.getAction())
        {
            case SESSION_INITIATE:
                sessionInitiate = jiq;
                acceptJingleSession();
                break;
            default:
                System.out.println("Unknown Jingle IQ");
                break;
        }
    }

    
    /**
     * This function simply create an ACK packet to acknowledge the Jingle IQ
     * packet <tt>packetToAck</tt>.
     * @param packetToAck the <tt>JingleIQ</tt> that need to be acknowledge.
     */
    private void ackJingleIQ(JingleIQ packetToAck)
    {
        IQ ackPacket = IQ.createResultIQ(packetToAck);
        connection.sendPacket(ackPacket);
        System.out.println("Ack sent for JingleIQ");
    }
}
