/*
 * Copyright 1999-2002 Carnegie Mellon University.  
 * Portions Copyright 2002 Sun Microsystems, Inc.  
 * Portions Copyright 2002 Mitsubishi Electronic Research Laboratories.
 * All Rights Reserved.  Use is subject to license terms.
 * 
 * See the file "license.terms" for information on usage and
 * redistribution of this file, and for a DISCLAIMER OF ALL 
 * WARRANTIES.
 *
 */


package edu.cmu.sphinx.research.distributed.client;

import edu.cmu.sphinx.frontend.AudioSource;
import edu.cmu.sphinx.frontend.Cepstrum;
import edu.cmu.sphinx.frontend.CepstrumExtractor;
import edu.cmu.sphinx.frontend.Signal;

import edu.cmu.sphinx.frontend.util.StreamAudioSource;

import edu.cmu.sphinx.util.SphinxProperties;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;

import java.net.Socket;



/**
 * A FrontEnd that runs on client applications.
 * The main interface between the application and this ClientFrontEnd is
 * the <b>decode(InputStream inputStream, String streamName)</b> method,
 * which returns the decoded result as a String.
 *
 * <p>The method <b>connect()</b> should be called before <b>decode()</b>,
 * and the method <b>close()</b> should be called after all decoding is done.
 * Therefore, the correct sequence of calls is:
 * <code>
 * connect();
 * decode(inputstream1, name1);
 * ...
 * decode(inputstreamN, nameN);
 * close();
 * </code>
 */
public class ClientFrontEnd extends CepstrumExtractor {


    private static final String PROP_PREFIX
         = "edu.cmu.sphinx.research.distributed.client.ClientFrontEnd.";


    /**
     * The SphinxProperty that specifies the decoder server address.
     */
    public static final String PROP_SERVER = PROP_PREFIX + "server";


    /**
     * The default value of PROP_SERVER.
     */
    public static final String PROP_SERVER_DEFAULT = "localhost";


    /**
     * The SphinxProperty that specified the server port number.
     */
    public static final String PROP_PORT = PROP_PREFIX + "port";


    /**
     * The default value of PROP_PORT.
     */
    public static final int PROP_PORT_DEFAULT = 52703;
    

    private BufferedReader reader;
    private DataOutputStream dataWriter;

    private String serverAddress;
    private int serverPort;
    private Socket socket;

    private StreamAudioSource streamAudioSource;

    private boolean inUtterance = false;


    /**
     * Constructs a default ClientFrontEnd.
     *
     * @param name the name of this ClientFrontEnd
     * @param context the context of this ClientFrontEnd
     */
    public void initialize(String name, String context) throws IOException {
        SphinxProperties props = SphinxProperties.getSphinxProperties(context);
        streamAudioSource = new StreamAudioSource
            ("StreamAudioSource", context, null, null);
        super.initialize(name, context, streamAudioSource);

        serverAddress = props.getString(PROP_SERVER, PROP_SERVER_DEFAULT);
        serverPort = props.getInt(PROP_PORT, PROP_PORT_DEFAULT);
    }


    /**
     * Connects this ClientFrontEnd to the back-end server.
     */
    public void connect() {
        try {
            socket = connectToBackEnd();
        } catch (IOException ioe) {
            ioe.printStackTrace();
        }
    }


    /**
     * Closes the connection to the back-end server.
     */
    public void close() {
        try {
            closeConnection(socket);
        } catch (IOException ioe) {
            ioe.printStackTrace();
        }
    }

    
    /**
     * Decodes the data in the given InputStream.
     *
     * @param is the InputStream to decode
     * @param streamName the name of the InputStream
     *
     * @return the result string
     */
    public String decode(InputStream is, String streamName) 
        throws IOException {
        sendRecognition();
        streamAudioSource.setInputStream(is, streamName);
        sendCepstrum();
        String result = readResult();
        return result;
    }


    /**
     * Connects this client to the server.
     */
    private Socket connectToBackEnd() throws IOException {
        Socket socket = new Socket(serverAddress, serverPort);
        reader = new BufferedReader
            (new InputStreamReader(socket.getInputStream()));
        dataWriter = new DataOutputStream
            (new BufferedOutputStream(socket.getOutputStream()));
        return socket;
    }


    /**
     * Sends a single int "1" to the back-end server to
     * signal the cepstra will be sent for recognition.
     */
    private void sendRecognition() throws IOException {
        dataWriter.writeInt(1);
        dataWriter.flush();
    }


    /**
     * Closes the connection to the back-end server.
     */
    private void closeConnection(Socket socket) throws IOException {
        reader.close();
        dataWriter.close();
        socket.close();
    }


    /**
     * Sends all the available cepstra to the back-end server.
     */
    private void sendCepstrum() throws IOException {
        Cepstrum cepstrum = null;
        do {
            cepstrum = getCepstrum();
            if (cepstrum != null) {
                if (!inUtterance) {
                    if (cepstrum.hasSignal(Signal.UTTERANCE_START)) {
                        inUtterance = true;
                        dataWriter.writeFloat(Float.MAX_VALUE);
                        dataWriter.flush();
                    } else {
                        throw new IllegalStateException
                            ("No UTTERANCE_START read");
                    }
                } else {
                    if (cepstrum.hasContent()) {
                        // send the cepstrum data
                        float[] data = cepstrum.getCepstrumData();
                        for (int i = 0; i < data.length; i++) {
                            dataWriter.writeFloat(data[i]);
                        }
                    } else if (cepstrum.hasSignal(Signal.UTTERANCE_END)) {
                        // send an UTTERANCE_END
                        dataWriter.writeFloat(Float.MIN_VALUE);
                        inUtterance = false;
                    } else if (cepstrum.hasSignal(Signal.UTTERANCE_START)) {
                        throw new IllegalStateException
                            ("Too many UTTERANCE_STARTs");
                    }
                    dataWriter.flush();
                }
            }
        } while (cepstrum != null);
    }


    /**
     * Reads the result string from the back-end server.
     *
     * @return the result string
     */
    private String readResult() throws IOException {
        return reader.readLine();
    }

}

